(ns pc4.project.home
  (:require [clojure.string :as str]
            [eldrix.pc4-ward.project.views]                 ;; TODO: remove any use of legacy components
            [pc4.dates :as dates]
            [reitit.frontend.easy :as rfe]
            [pc4.ui :as ui.misc]))

(defn menu
  [{:t_project/keys [id title pseudonymous]}
   {:keys [selected-id sub-menu]}]
  (let [content (fn [s] (vector :span.truncate s))]
    [:<>
     [:div.px-2.pt-1.pb-8.font-bold title]
     [ui.misc/vertical-navigation
      {:selected-id selected-id
       :items       [{:id      :home
                      :icon    (ui.misc/icon-home)
                      :content (content "Home")
                      :attrs   {:href (rfe/href :project/home {:project-id id})}}
                     (when pseudonymous
                       {:id      :find-pseudonymous-patient
                        :icon    (ui.misc/icon-magnifying-glass)
                        :content (content "Find patient")
                        :attrs   {:href (rfe/href :project/find-pseudonymous-patient {:project-id id})}})
                     (if pseudonymous
                       {:id      :register-pseudonymous-patient
                        :icon    (ui.misc/icon-plus-circle)
                        :content (content "Register patient")
                        :attrs   {:href (rfe/href :project/register-pseudonymous-patient {:project-id id})}}
                       {:id      :register-patient
                        :icon    (ui.misc/icon-plus-circle)
                        :content (content "Register patient")
                        :attrs   {:href (rfe/href :project/register-patient {:project-id id})}})
                     {:id      :team
                      :icon    (ui.misc/icon-team)
                      :content (content "Team")
                      :attrs   {:href (rfe/href :project/team {:project-id id})}}
                     {:id      :reports
                      :icon    (ui.misc/icon-reports)
                      :content (content "Downloads")
                      :attrs   {:href (rfe/href :project/downloads {:project-id id})}}]
       :sub-menu    sub-menu}]]))

(defn layout
  [project menu-options content]
  (when project
    [:div.grid.grid-cols-1.md:grid-cols-6 {:class (case (:t_project/type project) :NHS "bg-amber-50" :RESEARCH "bg-purple-50" nil)}
     [:div.col-span-1.pt-6
      (menu project menu-options)]
     [:div.col-span-5.p-6
      content]]))

(defn home-panel
  "Project home panel"
  [{:t_project/keys [title type pseudonymous long_description date_from date_to administrator_user
                     parent_project count_registered_patients
                     count_discharged_episodes count_pending_referrals
                     address1 address2 address3 address4 postcode
                     inclusion_criteria exclusion_criteria] :as project}]
  (ui.misc/two-column-card
    {:title       title
     :title-attrs {:class (case type :NHS ["bg-yellow-200"] :RESEARCH ["bg-pink-200"] nil)}
     :subtitle    (when long_description [:div {:dangerouslySetInnerHTML {:__html long_description}}])
     :items       [{:title "Date from" :content (dates/format-date date_from)}
                   {:title "Date to" :content (dates/format-date date_to)}
                   {:title "Administrator" :content (or (:t_user/full_name administrator_user) "None recorded")}
                   {:title "Registered patients" :content count_registered_patients}
                   {:title "Pending referrals" :content count_pending_referrals}
                   {:title "Discharged episodes" :content count_discharged_episodes}
                   {:title "Type" :content (str/join " / " (remove nil? [(when type (name type))
                                                                         (when pseudonymous "PSEUDONYMOUS")]))}
                   {:title "Specialty" :content (get-in project [:t_project/specialty :info.snomed.Concept/preferredDescription :info.snomed.Description/term])}
                   {:title "Parent" :content [:a {:href (or (:t_project/url parent_project) "#")} (:t_project/title parent_project)]}]
     :long-items  [{:title   "Address"
                    :content (str/join ", " (remove str/blank? [address1 address2 address3 address4 postcode]))}
                   {:title   "Inclusion criteria"
                    :content (when inclusion_criteria [:div {:dangerouslySetInnerHTML {:__html inclusion_criteria}}])}
                   {:title   "Exclusion criteria"
                    :content (when exclusion_criteria [:div {:dangerouslySetInnerHTML {:__html exclusion_criteria}}])}]}))


(def home-page
  {:tx   (fn [params]
           [{[:t_project/id (get-in params [:path :project-id])]
             [:t_project/id :t_project/title :t_project/date_from :t_project/date_to
              :t_project/count_pending_referrals :t_project/count_registered_patients :t_project/count_discharged_episodes
              :t_project/type {:t_project/parent [:t_project/title]}
              {:t_project/administrator_user [:t_user/full_name]}
              :t_project/pseudonymous :t_project/inclusion_criteria :t_project/exclusion_criteria
              {:t_project/specialty [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
              :t_project/address1 :t_project/address2 :t_project/address3 :t_project/address4]}])
   :view (fn [_ [project]]
           [layout project {:selected-id :home}
            (home-panel project)])})
