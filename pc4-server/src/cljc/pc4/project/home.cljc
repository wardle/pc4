(ns pc4.project.home
  (:require [clojure.string :as str]
            [pc4.ui.misc :as ui.misc]))

(defn project-home
  {:query [:t_project/id
           :t_project/title :t_project/type
           :t_project/long_description]}
  [{:t_project/keys [title type long_description
                     date_from date_to administrator_user
                     parent_project count_registered_patients
                     count_discharged_episodes count_pending_referrals
                     address1 address2 address3 address4 postcode
                     inclusion_criteria exclusion_criteria] :as project}]
  (ui.misc/two-column-card
    {:title      title :subtitle (when long_description [:div {:dangerouslySetInnerHTML {:__html long_description}}])
     :items      [{:title "Date from" :content date_from}
                  {:title "Date to" :content date_to}
                  {:title "Administrator" :content (or (:t_user/full_name administrator_user) "None recorded")}
                  {:title "Registered patients" :content count_registered_patients}
                  {:title "Pending referrals" :content count_pending_referrals}
                  {:title "Discharged episodes" :content count_discharged_episodes}
                  {:title "Type" :content (when type (name type))}
                  {:title "Specialty" :content (get-in project [:t_project/specialty :info.snomed.Concept/preferredDescription :info.snomed.Description/term])}
                  {:title "Parent" :content [:a {:href (or (:t_project/url parent_project) "#")} (:t_project/title parent_project)]}]
     :long-items [{:title   "Address"
                   :content (str/join ", " (remove str/blank? [address1 address2 address3 address4 postcode]))}
                  {:title   "Inclusion criteria"
                   :content (when inclusion_criteria [:div {:dangerouslySetInnerHTML {:__html inclusion_criteria}}])}
                  {:title   "Exclusion criteria"
                   :content (when exclusion_criteria [:div {:dangerouslySetInnerHTML {:__html exclusion_criteria}}])}]}))