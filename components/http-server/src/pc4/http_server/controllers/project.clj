(ns pc4.http-server.controllers.project
  (:require
    [clojure.string :as str]
    [com.eldrix.hermes.core :as hermes]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.http-server.controllers.user :as user]
    [pc4.http-server.html :as html]
    [pc4.http-server.ui :as ui]
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
                {:id   :find-patient
                 :url  (route/url-for :project/find-patient :path-params {:project-id project-id})
                 :text "Find patient"}
                {:id   :register-patient
                 :url  (route/url-for :project/register-patient :path-params {:project-id project-id})
                 :text "Register patient"}
                {:id   :today
                 :url  (route/url-for :project/today :path-params {:project-id project-id})
                 :text "Today"}
                {:id   :patients
                 :url  (route/url-for :project/patients :path-params {:project-id project-id})
                 :text "Patients"}
                {:id   :team
                 :url  (route/url-for :project/team :path-params {:project-id project-id})
                 :text "Team"}]
     :submenu  (case selected
                 :home
                 {:items [{:text "Edit project"}]}
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


(defn find-patient
  [request]
  (when-let [project-id (some-> (get-in request [:path-params :project-id]) parse-long)]
    (let [rsdb (get-in request [:env :rsdb])
          patient-identifier (some-> (get-in request [:params "patient-identifier"]) parse-long)
          found-patient (when patient-identifier (rsdb/fetch-patient rsdb {:t_patient/patient_identifier patient-identifier}))
          project (rsdb/project-by-id rsdb project-id)
          pseudonymous? (:t_project/pseudonymous project)]
      (println "search for patient" {:patient-identifier patient-identifier})

      (cond
        found-patient
        {:status  303
         :headers {"Location" (route/url-for :patient/home :params {:patient-identifier patient-identifier})}}
        :else
        (html/ok
          (selmer/render-file
            "project/find-patient-page.html"
            (assoc (user/session-env request)
              :menu (project-menu-env {:project project :selected :find-patient})
              :title "Find patient"
              :content (rum/render-static-markup
                         [:div
                          (ui/active-panel {:title    "Search by patient identifier"
                                            :subtitle "For pseudonymous projects, this functionality is only available to system administrators"
                                            :content  [:form {:method "post" :action (route/url-for :project/do-find-patient {:params {:project-id project-id}})}
                                                       [:input {:type "hidden" :name "__anti-forgery-token" :value (csrf/existing-token request)}]
                                                       [:div.space-y-2
                                                        (ui/ui-textfield {:id "patient-identifier" :placeholder "Enter patient identifier"})
                                                        (ui/ui-submit-button {:label "Search »"})]]})])
              :patient {:name         "Mr SMITH, John"
                        :date-birth   (java.time.LocalDate/now)
                        :age          41
                        :deceased     true
                        :date-death   (java.time.LocalDate/of 1990 1 1)
                        :pseudonymous true
                        :address      "1 Station Rd, Heath, Cardiff CF14 4XW"})))))))


(comment
  (rum/render-static-markup (ui/active-panel {:title "hi there" :class "wibble woobble"})))
(defn register-patient
  [request])

(defn today-wizard [request])

(defn patients [request])

(defn user->team-item [{:t_user/keys [id username photo_fk roles] :as user}]
  (let [user' (rsdb/user->display-names user)]
    {:title     (:t_user/full_name user')
     :url       (route/url-for :user/profile {:params {:user-id id}})
     :badges    (into #{} (comp
                            (filter :t_project_user/active?)
                            (map (comp str/upper-case name :t_project_user/role))) roles)
     :subtitle  (rsdb/user->job-title user)
     :image-url (when photo_fk (route/url-for :user/photo {:params {:user-id id}}))}))

(defn team
  [request]
  (when-let [project-id (some-> (get-in request [:path-params :project-id]) parse-long)]
    (let [rsdb (get-in request [:env :rsdb])
          target (get-in request [:headers "hx-target"])
          filter (str/lower-case (get-in request [:params :user-filter] "active"))
          project (rsdb/project-by-id rsdb project-id)
          users (->> (rsdb/project->users rsdb project-id {:active (case filter "active" true "inactive" false "all" nil true), :group-by :user})
                     (sort-by (juxt :t_user/last_name :t_user/first_names))
                     (map user->team-item))]
      (case target
        ;; if we are only updating team list, just render that fragment
        "team-list"
        (html/ok
          (selmer/render-file "project/team-list.html" {:team {:items users}}))
        ;; otherwise, render whole page...
        (html/ok
          (selmer/render-file
            "project/team-page.html"
            (assoc (user/session-env request)
              :menu (project-menu-env {:project project :selected :team})
              :team {:items users})))))))

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
          "project/home-page.html"
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


