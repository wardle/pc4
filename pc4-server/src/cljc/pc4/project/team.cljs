(ns pc4.project.team
  (:require [clojure.string :as str]
            [eldrix.pc4-ward.project.views]                 ;; TODO: remove any use of legacy components
            [pc4.project.home :as project]
            [reagent.core :as r]
            [pc4.ui :as ui]
            [re-frame.core :as rf]
            [reitit.frontend.easy :as rfe]))


(def role->badge-class
  {:INACTIVE              "bg-black text-white"
   :NORMAL_USER           "bg-green-100 text-green-800"
   :POWER_USER            "bg-red-200 text-red-800"
   :PID_DATA              "bg-yellow-200 text-black"
   :LIMITED_USER          "bg-teal-600 text-teal-600"
   :BIOBANK_ADMINISTRATOR "bg-blue-600 text-blue-600"})

(defn role-badge [role]
  [:span.inline-block.flex-shrink-0.rounded-full.px-2.py-0.5.text-xs.font-medium
   {:class (role->badge-class role)}
   (str/replace (name role) #"_" " ")])

(defn team-panel
  [users]
  (let [users* (->> users
                    (sort-by (juxt :t_user/last_name :t_user/first_names)))]
    [ui/grid-list
     (for [{id      :t_user/id, roles' :t_user/roles, user-url :user-url, photo-url :photo-url,
            active? :t_user/active?, :t_user/keys [full_name job_title]} users*]
       ^{:key id}
       [ui/grid-list-item
        {:title    [:a.underline.text-blue-600.hover:text-blue-800 {:href user-url} full_name]
         :subtitle job_title
         :image    (if photo-url {:url photo-url} {:content [ui/avatar-14]})
         :content  [:div.flex.w-full.items-center.p-6.space-x-6
                    (when active?
                      (into [:div] (for [{id   :t_project_user/id
                                          role :t_project_user/role} roles'
                                         :when :t_project_user/active?]
                                     ^{:key id} [:<> [role-badge role]])))]}])]))


(def team-page                                              ;; TODO: search should be added to :t_project/users resolver as parameters
  {:tx
   (fn [params] [{[:t_project/id (get-in params [:path :project-id])]
                  [:t_project/id :t_project/title :t_project/pseudonymous :t_project/type
                   {(list :t_project/users {:group-by :user})
                    [:t_user/id :t_user/username :t_user/has_photo :t_user/email :t_user/full_name :t_user/active?
                     :t_user/first_names :t_user/last_name :t_user/job_title
                     {:t_user/roles [:t_project_user/id :t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?]}]}]}])
   :target (constantly [:current-project :team])
   :view
   (fn [ctx _]
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
           (project/layout project
                           {:selected-id :team
                            :sub-menu    {:title "Team"
                                          :items [{:id      :filter
                                                   :content [:div
                                                             [:select.w-full.p-2.border
                                                              {:name "active" :on-change #(reset! user-filter (-> % .-target .-value))}
                                                              [:option {:value "active"} "Active"]
                                                              [:option {:value "inactive"} "Inactive"]
                                                              [:option {:value "all"} "All users"]]
                                                             [:input.border.p-2.w-full
                                                              {:type      "search" :name "search" :placeholder "Search..." :auto-complete "off"
                                                               :on-change #(reset! search-filter (-> % .-target .-value))}]
                                                             #_[:button.w-full.border.bg-gray-100.hover:bg-gray-400.text-gray-800.font-bold.py-2.px-4.rounded-l "Add user"]]}]}}
                           [team-panel (map #(assoc % :user-url (rfe/href :project/user {:project-id (:t_project/id project)
                                                                                         :user-id    (:t_user/id %)}
                                                                          {:return (:path ctx)})) users)])))))})

(def user-page
  {:tx   (fn [params]
           [{[:t_user/id (get-in params [:path :user-id])]
             [:t_user/id :t_user/username :t_user/full_name :t_user/first_names :t_user/last_name :t_user/email :t_user/send_email_for_messages
              :t_user/authentication_method :t_professional_registration_authority/name :t_professional_registration_authority/abbreviation :t_user/professional_registration :t_user/professional_registration_url :t_user/pr
              :t_user/job_title :t_user/custom_job_title
              {:t_user/active_projects [:t_project/id :t_project/title :t_project/long_description]}]}])
   :view (fn [ctx [{:t_user/keys [id username authentication_method full_name title first_names last_name
                                  professional_registration professional_registration_url
                                  email send_email_for_messages job_title custom_job_title active_projects] :as user}]]
           (let [return-url (get-in ctx [:query-params :return])]
             (tap> {:user-page  {:ctx ctx}
                    :return-url return-url})
             [:<>
              (ui/ui-button {:on-click #(do (.pushState js/window.history nil "" return-url)
                                            (reitit.frontend.history/-on-navigate rfe/history return-url))} "BACK")
              (ui/two-column-card
                {:title       full_name
                 :title-attrs {:class (case type :NHS ["bg-yellow-200"] :RESEARCH ["bg-pink-200"] nil)}
                 :subtitle    (or custom_job_title job_title)
                 :items       [{:title "First names" :content first_names}
                               {:title "Last name" :content last_name}
                               {:title "Username" :content username}
                               {:title "Authentication" :content authentication_method}
                               {:title [:div.inline-flex
                                        "Professional registration"
                                        (when professional_registration_url
                                          [:a.pl-2 {:href professional_registration_url :target "_blank" :title (str "View record at " (:t_professional_registration_authority/name user))}
                                           [ui/icon-arrow-top-right-on-square]])]
                                :content (str (:t_professional_registration_authority/abbreviation user) " " professional_registration)}
                               {:title "Email" :content email}
                               {:title "Email used for messages?" :content (if send_email_for_messages "Yes" "No")}]
                 :long-items  [{:title   (str "Active project registrations: " (count active_projects))
                                :content [ui/ui-table
                                          [ui/ui-table-body
                                           (for [{:t_project/keys [id title long_description]} (sort-by :t_project/title active_projects)]
                                             [ui/ui-table-row {:key id}
                                              [ui/ui-table-cell {} (ui/ui-table-link {:href (rfe/href :project/home {:project-id id})} title)]
                                              [ui/ui-table-cell {} [:div {:dangerouslySetInnerHTML {:__html long_description}}]]])]]}]})]))})
