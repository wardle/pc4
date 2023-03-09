(ns com.eldrix.pc4.ui.project
  (:require
    [clojure.string :as str]
    [com.eldrix.pc4.ui.misc :as misc]
    [rum.core :as rum]))



(rum/defc project-home [{:t_project/keys [id title type long_description
                                          date_from date_to administrator_user
                                          parent_project count_registered_patients
                                          count_discharged_episodes count_pending_referrals
                                          address1 address2 address3 address4 postcode
                                          inclusion_criteria exclusion_criteria] :as project}]
  (misc/two-column-card {:title      title :subtitle (when long_description [:div {:dangerouslySetInnerHTML {:__html long_description}}])
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


(def role->badge-class
  {:INACTIVE              "bg-black text-white"
   :NORMAL_USER           "bg-green-100 text-green-800"
   :POWER_USER            "bg-red-200 text-red-800"
   :PID_DATA              "bg-yellow-200 text-black"
   :LIMITED_USER          "bg-teal-600 text-teal-600"
   :BIOBANK_ADMINISTRATOR "bg-blue-600 text-blue-600"})

(rum/defc project-users
  [users]
  (let [users* (->> users (sort-by (juxt :t_user/last_name :t_user/first_names)))]
    (misc/grid-list
      (for [{roles' :t_user/roles, photo-url :photo-url, active? :t_user/active?, :t_user/keys [full_name job_title email]} users*]
        (misc/grid-list-item {:title     full_name
                              :subtitle  job_title
                              :image-url photo-url
                              :content   [:div.flex.w-full.items-center.p-6
                                          [:p.space-x-6
                                           (when active?
                                             (for [{role :t_project_user/role} roles'
                                                   :when :t_project_user/active?]
                                               [:span.inline-block.flex-shrink-0.rounded-full.px-2.py-0.5.text-xs.font-medium
                                                {:class (role->badge-class role)}
                                                (str/replace (name role) #"_" " ")]))]]})))))