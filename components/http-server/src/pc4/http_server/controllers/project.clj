(ns pc4.http-server.controllers.project
  (:require
    [clojure.string :as str]
    [com.eldrix.hermes.core :as hermes]
    [io.pedestal.http.route :as route]
    [pc4.http-server.html :as html]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]
    [selmer.parser :as selmer]))


(defn project->display-type
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
          "view-project.html"
          {:project {:title       title
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
                                   {:title "Parent"
                                    :content (str "<a href=\"" (route/url-for :project/home :path-params {:project-id parent_project_fk}) "\">" (:t_project/title parent-project) "</a>")}]
                     :long-items  [{:title "Address"
                                    :body  (str/join ", " (remove str/blank? [address1 address2 address3 address4 postcode]))}
                                   {:title   "Inclusion criteria"
                                    :content inclusion_criteria}
                                   {:title   "Exclusion criteria"
                                    :content exclusion_criteria}]}})))))