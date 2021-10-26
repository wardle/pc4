(ns eldrix.pc4-ward.project.views
  (:require [reitit.frontend.easy :as rfe]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.project.subs :as project-subs]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.ui :as ui]
            [clojure.string :as str]
            [com.eldrix.pc4.commons.dates :as dates]))

(defn inspect-project [project]
  [:div.bg-white.shadow.overflow-hidden.sm:rounded-lg
   [:div.border-t.border-gray-200
    [:dl
     [:div.bg-gray-50.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Status"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (if (:t_project/active? project) "Active" "Inactive")]]
     [:div.bg-white.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Type"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (str/upper-case (name (:t_project/type project)))]]
     [:div.bg-white.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Date from"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (dates/format-date (:t_project/date_from project))]]
     [:div.bg-white.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Date to"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (dates/format-date (:t_project/date_to project))]]
     [:div.bg-gray-50.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Registered patients"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (:t_project/count_registered_patients project)]]
     [:div.bg-gray-50.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Discharged episodes"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (:t_project/count_discharged_episodes project)]]
     [:div.bg-gray-50.px-4.py-2.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
      [:dt.text-sm.font-medium.text-gray-500 "Pending referrals"]
      [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 (:t_project/count_pending_referrals project)]]
     ]]])



(defn project-home-page []
  (let [selected-page (reagent.core/atom :home)]
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
                            {:title "Search" :id :search-patient}
                            {:title "Register" :id :register-patient}]
              :selected-id @selected-page
              :select-fn #(reset! selected-page %)]]]
           [inspect-project current-project]])))))