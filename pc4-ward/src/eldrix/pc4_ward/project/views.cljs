(ns eldrix.pc4-ward.project.views
  (:require [reitit.frontend.easy :as rfe]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.project.subs :as project-subs]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.ui :as ui]
            [clojure.string :as str]))


(defn project-home-page []
  (let [selected-page (reagent.core/atom :home)]
    (fn []
      (let [route @(rf/subscribe [:eldrix.pc4-ward.subs/current-route])
            authenticated-user @(rf/subscribe [::user-subs/authenticated-user])
            current-project @(rf/subscribe [::project-subs/current])]
        (when current-project
          [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200

           [:div.font-bold.text-lg.min-w-min (:t_project/title current-project)]
           [:ul.flex
            [ui/flat-menu [{:title "Home" :id :home}
                           {:title "Register patient" :id :register-patient}
                           {:title "Patients" :id :list-patients}]
             :selected-id @selected-page
             :select-fn #(do (println "selecting page " %) (reset! selected-page %))]]])))))