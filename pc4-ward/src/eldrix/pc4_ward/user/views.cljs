(ns eldrix.pc4-ward.user.views
  (:require [clojure.string :as str]
            [reagent.core :as reagent]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.ui :as ui]))

(defn login-panel
  []
  (let [error (rf/subscribe [::user-subs/login-error])
        ping-error (rf/subscribe [::user-subs/ping-error])
        username (reagent/atom "")
        password (reagent/atom "")
        submitting false                                    ;; @(rf/subscribe [:show-foreground-spinner])
        do-login #(rf/dispatch [::user-events/do-login "wales.nhs.uk" (str/trim @username) @password])]
    (fn []
      [:<>
       [:section.p-2.mx-auto.bg-white.rounded-md.shadow-md.dark:bg-gray-800
        [:h2.text-lg.font-semibold.text-gray-700.capitalize.dark:text-white "Please login"]
        [:div.grid.grid-cols-1.gap-6.mt-4
         [:div
          [:label.text-gray-700.dark:text-gray-200 {:for "login-username"} "Username"]
          [:input#username.block.px-4.py-2.mt-2.text-gray-700.bg-white.border.border-gray-300.rounded-md.dark:bg-gray-800.dark:text-gray-300.dark:border-gray-600.focus:border-blue-500.dark:focus:border-blue-500.focus:outline-none.focus:ring
           {:id          "login-username" :type "text" :placeholder "e.g. ma090906" :required true
            :disabled    submitting
            :auto-focus  true
            :on-key-down #(if (= 13 (.-which %)) (do (.focus (.getElementById js/document "login-pw"))))
            :on-change   #(reset! username (-> % .-target .-value))}]]
         [:div
          [:label.text-gray-700.dark:text-gray-200 {:for "password"} "Password"]
          [:input#password.block.px-4.py-2.mt-2.text-gray-700.bg-white.border.border-gray-300.rounded-md.dark:bg-gray-800.dark:text-gray-300.dark:border-gray-600.focus:border-blue-500.dark:focus:border-blue-500.focus:outline-none.focus:ring
           {:id          "login-pw" :type "password" :placeholder "Enter password" :required true
            :disabled    submitting
            :on-key-down #(if (= 13 (.-which %))
                            (do (reset! password (-> % .-target .-value)) (do-login)))
            :on-change   #(reset! password (-> % .-target .-value))}]]]
        [:div.flex.mt-6
         [:button.px-6.py-2.leading-5.text-white.transition-colors.duration-200.transform.bg-gray-700.rounded-md.hover:bg-gray-600.focus:outline-none.focus:bg-gray-600
          {:disabled submitting
           :on-click do-login} "Login"]]]
       (when-not (str/blank? @error) [ui/box-error-message :message @error])
       (when @ping-error [ui/box-error-message :message "Warning: connection error; unable to connect to server. Will retry automatically."])])))