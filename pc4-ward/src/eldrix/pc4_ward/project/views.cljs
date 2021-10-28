(ns eldrix.pc4-ward.project.views
  (:require [reitit.frontend.easy :as rfe]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.patient.events :as patient-events]
            [eldrix.pc4-ward.patient.subs :as patient-subs]
            [eldrix.pc4-ward.project.subs :as project-subs]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.ui :as ui]
            [clojure.string :as str]
            [com.eldrix.pc4.commons.dates :as dates]))

(defn inspect-project [project]                             ;;TODO: create from data instead - this is egregious
  [:div.bg-white.shadow.overflow-hidden.sm:rounded-lg
   [:div.border-t.border-gray-200
    [:dl
     [:div.bg-gray-50.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Status"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (if (:t_project/active? project) "Active" "Inactive")]]
     [:div.bg-white.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Type"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (str/upper-case (name (:t_project/type project)))]]
     [:div.bg-gray-50.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Date from"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (dates/format-date (:t_project/date_from project))]]
     [:div.bg-white.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Date to"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (dates/format-date (:t_project/date_to project))]]
     [:div.bg-gray-50.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Registered patients"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (:t_project/count_registered_patients project)]]
     [:div.bg-white.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Discharged episodes"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (:t_project/count_discharged_episodes project)]]
     [:div.bg-gray-50.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Pending referrals"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (:t_project/count_pending_referrals project)]]
     ]]])

(defn search-by-pseudonym-panel
  [project-id]
  (let [patient @(rf/subscribe [::patient-subs/search-by-legacy-pseudonym-result])]
    [:div.bg-white.overflow-hidden.shadow.sm:rounded-lg
     [:div.px-4.py-6.sm:p-6
      [:form.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
       [:div.divide-y.divide-gray-200.sm:space-y-5
        [:div
         [:div
          [:h3.text-lg.leading-6.font-medium.text-gray-900 "Search by pseudonymous identifier"]
          [:p.max-w-2xl.text-sm.text-gray-500 "Enter a project-specific pseudonym, or choose register to search by patient identifiable information."]]
         [:div.mt-4
          [:label.sr-only {:for "pseudonym"} "Pseudonym"]
          [:input.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md.pl-5.py-2
           {:type        "text" :name "pseudonym" :placeholder "Start typing pseudonym" :auto-focus true
            :on-key-down #(when (and patient (= 13 (.-which %)))
                            (rfe/push-state :patient-by-project-pseudonym {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)}))
            :on-change   #(let [s (-> % .-target .-value)]
                            (rf/dispatch [::patient-events/search-legacy-pseudonym project-id s]))}]]
         (when patient
           [:div.bg-white.shadow.sm:rounded-lg.mt-4
            [:div.px-4.py-5.sm:p-6
             [:h3.text-lg.leading-6.font-medium.text-gray-900
              (str (name (:t_patient/sex patient))
                   " "
                   "born: " (.getYear (:t_patient/date_birth patient)))]
             [:div.mt-2.sm:flex.sm:items-start.sm:justify-between
              [:div.max-w-xl.text-sm.text-gray-500
               [:p (:t_episode/stored_pseudonym patient)]]
              [:div.mt-5.sm:mt-0.sm:ml-6.sm:flex-shrink-0.sm:flex.sm:items-center
               [:button.inline-flex.items-center.px-4.py-2.border.border-transparent.shadow-sm.font-medium.rounded-md.text-white.bg-indigo-600.hover:bg-indigo-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500.sm:text-sm
                {:type     "button"
                 :on-click #(rfe/push-state :patient-by-project-pseudonym {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)})}
                "View patient record"]]]]])]]]]]))


(defn view-pseudonymous-patient []
  (let [patient @(rf/subscribe [::patient-subs/current])
        authenticated-user @(rf/subscribe [::user-subs/authenticated-user])
        _ (tap> {:patient patient :user authenticated-user})]
    [:div
     [ui/patient-banner
      :name (:t_patient/sex patient)
      :born (when-let [dob (:t_patient/date_birth patient)] (.getYear dob))
      :address (:t_episode/stored_pseudonym patient)
      :on-close #(when-let [project-id (:t_episode/project_fk patient)]
                   (println "opening project page for project" project-id)
                   (rfe/push-state :projects {:project-id project-id :slug "home"}))]]))

(defn list-users [users]
  [:div.flex.flex-col
   [:div.-my-2.overflow-x-auto.sm:-mx-6.lg:-mx-8
    [:div.py-2.align-middle.inline-block.min-w-full.sm:px-6.lg:px-8
     [:div.shadow.overflow-hidden.border-b.border-gray-200.sm:rounded-lg
      [:table.min-w-full.divide-y.divide-gray-200
       [:thead.bg-gray-50
        [:tr
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Name"]
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Title"]
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Email"]]]
       [:tbody.bg-white.divide-y.divide-gray-200
        (for [user (sort-by (juxt :t_user/last_name :t_user/first_names) (reduce-kv (fn [acc k v] (conj acc (first v))) [] (group-by :t_user/id users)))
              :let [id (:t_user/id user)]]
          [:tr {:key id}
           [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900 (str/join " " [(:t_user/title user) (:t_user/first_names user) (:t_user/last_name user)])]
           [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (or (:t_user/custom_job_title user) (:t_job_title/name user))]
           [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (:t_user/email user)]])]]]]]])

(defn project-home-page []
  (let [selected-page (reagent.core/atom :home)]
    (rf/dispatch [::patient-events/search-legacy-pseudonym nil ""])
    (fn []
      (let [route @(rf/subscribe [:eldrix.pc4-ward.subs/current-route])
            authenticated-user @(rf/subscribe [::user-subs/authenticated-user])
            current-project @(rf/subscribe [::project-subs/current])]
        (when current-project
          [:<>
           [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200


            [:ul.flex
             [:div.font-bold.text-lg.min-w-min.mr-6.py-1 (:t_project/title current-project)]
             [ui/flat-menu [{:title "Home" :id :home}
                            {:title "Search" :id :search}
                            {:title "Register" :id :register}
                            {:title "Users" :id :users}]
              :selected-id @selected-page
              :select-fn #(do (reset! selected-page %)
                              (rf/dispatch [::patient-events/search-legacy-pseudonym (:t_project/id current-project) ""]))]]]
           (case @selected-page
             :home [inspect-project current-project]
             :search [search-by-pseudonym-panel (:t_project/id current-project)]
             :register nil
             :users [list-users (:t_project/users current-project)])])))))