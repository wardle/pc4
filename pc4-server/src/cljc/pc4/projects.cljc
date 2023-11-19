(ns pc4.projects
  (:require [clojure.string :as str]
            [eldrix.pc4-ward.project.views :as legacy.views]
            [com.eldrix.pc4.commons.dates :as dates]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [reitit.frontend.easy :as rfe]
            [pc4.ui.misc :as ui.misc]
            [pc4.ui.user :as ui.user]))

(defn menu
  [{:t_project/keys [id title pseudonymous]}
   {:keys [selected-id sub-menu]}]
  (let [content (fn [s] (vector :span.truncate s))]
    [:<>
     [:div.px-2.py-1.font-bold title]
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
                     (when pseudonymous
                       {:id      :register-pseudonymous-patient
                        :icon    (ui.misc/icon-plus-circle)
                        :content (content "Register patient")
                        :attrs   {:href (rfe/href :project/register-pseudonymous-patient {:project-id id})}})
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
    [:div.grid.grid-cols-1.md:grid-cols-6
     [:div.col-span-1.pt-6
      (menu project menu-options)]
     [:div.col-span-5.p-6
      content]]))

(defn home-panel
  "Project home panel"
  [{:t_project/keys [title type long_description date_from date_to administrator_user
                     parent_project count_registered_patients
                     count_discharged_episodes count_pending_referrals
                     address1 address2 address3 address4 postcode
                     inclusion_criteria exclusion_criteria] :as project}]
  (ui.misc/two-column-card
    {:title      title
     :subtitle   (when long_description [:div {:dangerouslySetInnerHTML {:__html long_description}}])
     :items      [{:title "Date from" :content (dates/format-date date_from)}
                  {:title "Date to" :content (dates/format-date date_to)}
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


(defn team-panel
  [users]
  (let [users* (->> users
                    (sort-by (juxt :t_user/last_name :t_user/first_names)))]
    [ui.misc/grid-list
     (for [{id      :t_user/id, roles' :t_user/roles, user-url :user-url, photo-url :photo-url,
            active? :t_user/active?, :t_user/keys [full_name job_title]} users*]
       ^{:key id}
       [ui.misc/grid-list-item
        {:title    [:a.underline.text-blue-600.hover:text-blue-800 {:href user-url} full_name]
         :subtitle job_title
         :image    (if photo-url {:url photo-url} {:content [ui.misc/avatar-14]})
         :content  [:div.flex.w-full.items-center.p-6.space-x-6
                    (when active?
                      (into [:div] (for [{id   :t_project_user/id
                                          role :t_project_user/role} roles'
                                         :when :t_project_user/active?]
                                     ^{:key id} [:<> [ui.user/role-badge role]])))]}])]))


(def home-page
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])]
              [:t_project/id :t_project/title :t_project/date_from :t_project/date_to
               :t_project/count_pending_referrals :t_project/count_registered_patients :t_project/count_discharged_episodes
               :t_project/type {:t_project/parent [:t_project/title]}
               {:t_project/administrator_user [:t_user/full_name]}
               :t_project/pseudonymous :t_project/inclusion_criteria :t_project/exclusion_criteria
               {:t_project/specialty [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
               :t_project/address1 :t_project/address2 :t_project/address3 :t_project/address4]}])
   :view  (fn [_ [project]]
            [layout project {:selected-id :home}
             (home-panel project)])})

(def find-pseudonymous-patient
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])]
              [:t_project/id :t_project/title :t_project/pseudonymous]}])
   :view  (fn [_ [project]]
            (layout project {:selected-id :find-pseudonymous-patient}
                    [eldrix.pc4-ward.project.views/search-by-pseudonym-panel (:t_project/id project)]))})

(def register-pseudonymous-patient
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])]
              [:t_project/id :t_project/title :t_project/pseudonymous]}])
   :view  (fn [_ [project]]
            (layout project {:selected-id :register-pseudonymous-patient}
                    [eldrix.pc4-ward.project.views/register-pseudonymous-patient (:t_project/id project)]))})

(def downloads
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])]
              [:t_project/id :t_project/title :t_project/pseudonymous]}])
   :view  (fn [_ [project]]
            (layout project {:selected-id :reports}
                    [:div.overflow-hidden.bg-white.shadow.sm:rounded-lg
                     [:div.px-4.py-5.sm:px-6
                      [:p "No downloads currently available for this project"]]]))})


(def team-page  ;; TODO: search should be added to :t_project/users resolver as parameters
  {:query
   (fn [params] [{[:t_project/id (get-in params [:path :project-id])]
                  [:t_project/id :t_project/title :t_project/pseudonymous
                   {(list :t_project/users {:group-by :user})
                    [:t_user/id :t_user/username :t_user/has_photo :t_user/email :t_user/full_name :t_user/active?
                     :t_user/first_names :t_user/last_name :t_user/job_title
                     {:t_user/roles [:t_project_user/id :t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?]}]}]}])
   :target (constantly [:current-project :team])
   :view   (fn [_ _]
             (let [user-filter (r/atom "active")
                   search-filter (r/atom "")]
               (fn [params [project]]
                 (let [users (cond->> (:t_project/users project)
                                      (not (str/blank? @search-filter))
                                      (filter #(or (str/starts-with? (str/lower-case (:t_user/first_names %)) (str/lower-case @search-filter))
                                                   (str/starts-with? (str/lower-case (:t_user/last_name %)) (str/lower-case @search-filter))))
                                      (= "active" @user-filter)
                                      (filter :t_user/active?)
                                      (= "inactive" @user-filter)
                                      (remove :t_user/active?))]
                   (layout project {:selected-id :team
                                    :sub-menu {:title "Team"
                                               :items [{:id      :filter
                                                        :content [:div
                                                                  [:select.w-full.p-2.border
                                                                   {:name "active" :onChange #(reset! user-filter (-> % .-target .-value))}
                                                                   [:option {:value "active"} "Active"]
                                                                   [:option {:value "inactive"} "Inactive"]
                                                                   [:option {:value "all"} "All users"]]
                                                                  [:input.border.p-2.w-full
                                                                   {:type     "search" :name "search" :placeholder "Search..."
                                                                    :onChange #(reset! search-filter (-> % .-target .-value))}]
                                                                  #_[:button.w-full.border.bg-gray-100.hover:bg-gray-400.text-gray-800.font-bold.py-2.px-4.rounded-l "Add user"]]}]}}
                           [team-panel users])))))})
