(ns pc4.http-server.controllers.project
  (:require
    [clojure.string :as str]
    [com.eldrix.hermes.core :as hermes]
    [io.pedestal.http.route :as route]
    [pc4.http-server.controllers.user :as user]
    [pc4.http-server.html :as html]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]
    [rum.core :as rum]
    [selmer.parser :as selmer]))


(defn project-menu-env
  [{:keys [project selected]}]
  (let [project-id (:t_project/id project)]
    {:selected selected
     :title    (:t_project/title project)
     :items    [{:id   :home
                 :url  (route/url-for :project/home :path-params {:project-id project-id})
                 :text "Home"}
                {:id   :find-pt
                 :text "Find patient"}
                {:id   :team
                 :url  (route/url-for :project/team :path-params {:project-id project-id})
                 :text "Team"}]
     :submenu  (case selected
                 :home
                 {:items [{:text "Wibble"}]}
                 :team
                 {:items [{:content (selmer/render-file "ui/select-button.html"
                                                        {:id        "user-filter"
                                                         :selected  "active"
                                                         :hx-get    (route/url-for :project/team)
                                                         :hx-target "#team-list"
                                                         :hx-swap   "outerHTML"
                                                         :options   [{:id "active" :text "Active"}
                                                                     {:id "inactive" :text "Inactive"}
                                                                     {:id "all" :text "All users"}]})}]}
                 nil)}))

(defn user->team-item [{:t_user/keys [id username photo_fk roles] :as user}]
  (let [user' (rsdb/user->display-names user)]
    {:title     (:t_user/full_name user')
     :badges    (into #{} (map (comp str/upper-case name :t_project_user/role)) roles)
     :subtitle  (rsdb/user->job-title user)
     :image-url (when photo_fk (route/url-for :user/photo {:params {:system "patientcare.app" :value username}}))}))

(defn team
  [request]
  (when-let [project-id (some-> (get-in request [:path-params :project-id]) parse-long)]
    (let [rsdb (get-in request [:env :rsdb])
          target (get-in request [:headers "hx-target"])
          filter (str/lower-case (get-in request [:params :user-filter] "active"))
          project (rsdb/project-by-id rsdb project-id)
          users (rsdb/project->users rsdb project-id {:active (case filter "active" true "inactive" false "all" nil true), :group-by :user})]
      (clojure.pprint/pprint (get-in request [:params]))
      (case target
        ;; if we are only updating team list, just render that fragment
        "team-list"
        (html/ok
          (selmer/render-file "project/team-list.html" {:team {:items (map user->team-item users)}}))
        ;; otherwise, render whole page...
        (html/ok
          (selmer/render-file
            "project/team.html"
            (assoc (user/session-env request)
              :menu (project-menu-env {:project project :selected :team})
              :team {:items (map user->team-item users)})))))))

(defn ^:private project->display-type
  [{:t_project/keys [type pseudonymous]}]
  (str (case type
         :NHS "Clinical"
         :RESEARCH "Research"
         :ALL_PATIENTS "All patients")
       (when pseudonymous
         " (pseudonymous)")))

(defn home
  [request]
  (when-let [project-id (some-> (get-in request [:path-params :project-id]) parse-long)]
    (let [rsdb (get-in request [:env :rsdb])
          hermes (get-in request [:env :hermes])
          project-ids #{project-id}
          {:t_project/keys [title long_description administrator_user_fk date_from date_to specialty_concept_fk parent_project_fk
                            address1 address2 address3 address4 postcode inclusion_criteria exclusion_criteria] :as project}
          (rsdb/project-by-id rsdb project-id)
          parent-project (when parent_project_fk (rsdb/project-by-id rsdb parent_project_fk))
          admin-user (rsdb/user->display-names (rsdb/user-by-id rsdb administrator_user_fk))
          n-patients (rsdb/projects->count-registered-patients rsdb project-ids)
          n-pending (rsdb/projects->count-pending-referrals rsdb project-ids)
          n-discharged (rsdb/projects->count-discharged-episodes rsdb project-ids)]
      (log/debug :project/home {:project-id project-id :type (:t_project/type project)})
      (html/ok
        (selmer/render-file
          "project/home.html"
          (assoc (user/session-env request)
            :menu (project-menu-env {:project project :selected :home})
            :project {:title       title
                      :tint-class  (case (:t_project/type project) :NHS "bg-yellow-100" :RESEARCH "bg-pink-100" "bg-gray-100")
                      :description long_description
                      :items       [{:title "Date from"
                                     :body  date_from}
                                    {:title "Date to"
                                     :body  date_to}
                                    {:title "Administrator"
                                     :body  (:t_user/full_name admin-user)}
                                    {:title "Registered patients"
                                     :body  n-patients}
                                    {:title "Pending referrals"
                                     :body  n-pending}
                                    {:title "Discharged (closed) episodes"
                                     :body  n-discharged}
                                    {:title "Type"
                                     :body  (project->display-type project)}
                                    {:title "Specialty"
                                     :body  (when specialty_concept_fk (:term (hermes/preferred-synonym hermes specialty_concept_fk)))}
                                    {:title   "Parent"
                                     :content (str "<a href=\"" (route/url-for :project/home :path-params {:project-id parent_project_fk}) "\">" (:t_project/title parent-project) "</a>")}]
                      :long-items  [{:title "Address"
                                     :body  (str/join ", " (remove str/blank? [address1 address2 address3 address4 postcode]))}
                                    {:title   "Inclusion criteria"
                                     :content inclusion_criteria}
                                    {:title   "Exclusion criteria"
                                     :content exclusion_criteria}]}))))))


