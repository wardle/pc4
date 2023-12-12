(ns pc4.user.views
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [pc4.subs :as subs]
            [pc4.events :as events]
            [eldrix.pc4-ward.ui :as ui]))

(defn change-password [_]
  (let [data (reagent.core/atom {})]
    (fn [_]
      (let [authenticated-user @(rf/subscribe [::subs/authenticated-user])
            forced-change? @(rf/subscribe [::subs/must-change-password?])
            data' @data
            passwords-match? (= (:new-password1 data') (:new-password2 data'))
            both-touched? (clojure.set/subset? #{:password1 :password2} (:touched data'))
            server-error @(rf/subscribe [::subs/change-password-error])
            has-error (or server-error
                          (not passwords-match?)
                          (and (not forced-change?) (str/blank? (:password data')))
                          (str/blank? (:new-password1 data')))
            reset-errors #(rf/dispatch [::events/clear-change-password-error])
            _ (tap> {:user           authenticated-user
                     :forced-change? forced-change?})]
        [:div.min-h-full.flex.items-center.justify-center.py-12.px-4.sm:px-6.lg:px-8
         [:div.max-w-md.w-full.space-y-8
          [:div
           [:h2.mt-6.text-center.text-2xl.font-extrabold.text-gray-900 "Change your password"]]
          [:form.mt-8.space-y-6
           {:on-submit #(do (.preventDefault %)
                            (when-not has-error
                              (tap> {:change-password  data'
                                     :passwords-match? passwords-match?})
                              (rf/dispatch [::events/change-password
                                            (merge (when (:password data') {:t_user/password (:password data')})
                                                   {:t_user/username     (:t_user/username authenticated-user)
                                                    :t_user/new_password (:new-password1 data')})])
                              (reset! data {})))}
           [:div.rounded-md.shadow-sm
            (when-not forced-change?                        ;; only force to enter old password if they've chosen to change password
              [:div                                         ;; otherwise, we have asked them to change after login, so just allow them to enter new password
               [:label.block.text-sm.font-medium.text-gray-500 {:for "password"} "Enter your current password:"]
               [:input.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
                {:name      "password" :type "password" :auto-complete "current-password" :required true :placeholder "Current password"
                 :on-change #(do (swap! data assoc :password (-> % .-target .-value))
                                 (reset-errors))}]])
            [:div.mt-8
             [:label.block.text-sm.font-medium.text-gray-500 {:for "password1"} "Enter your new password:"]
             [:input.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
              {:name      "password1" :type "password" :auto-complete "new-password" :required true :placeholder "New password"
               :on-change #(do (swap! data assoc :new-password1 (-> % .-target .-value))
                               (reset-errors))
               :on-blur   #(swap! data assoc :touched (set (conj (:touched data') :password1)))}]]
            [:div
             [:input.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
              {:name      "password2" :type "password" :auto-complete "new-password" :required true :placeholder "New password again"
               :on-change #(do (swap! data assoc :new-password2 (-> % .-target .-value))
                               (reset-errors))
               :on-blur   #(swap! data assoc :touched (set (conj (:touched data') :password2)))}]]]
           [:div
            [:button.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.bg-blue-600.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500
             {:type  "submit"
              :class (if has-error ["opacity-50" "cursor-not-allowed"] ["hover:bg-blue-700"])}
             [:span.absolute.left-0.inset-y-0.flex.items-center.pl-3] "Change password"]]]
          (when server-error [ui/box-error-message :message server-error])
          (when (and (not passwords-match?) (clojure.set/subset? #{:password1 :password2} (:touched data')))
            [ui/box-error-message :message "New passwords do not match"])]]))))


(defn login-panel
  []
  (let [error (rf/subscribe [::subs/login-error])
        ping-error (rf/subscribe [::subs/ping-error])
        username (r/atom "")
        password (r/atom "")
        submitting @(rf/subscribe [::subs/loading :login])
        do-login #(rf/dispatch [::events/do-login {:username @username :password @password}])]
    (fn []
      [:form {:on-submit #(.preventDefault %)}
       [:section.p-2.mx-auto.bg-white.rounded-md.shadow-md.dark:bg-gray-800
        [:h2.text-lg.font-semibold.text-gray-700.capitalize.dark:text-white "Please login"]
        [:div.grid.grid-cols-1.gap-6.mt-4
         [:div
          [:label.text-gray-700.dark:text-gray-200 {:for "login-username"} "Username"]
          [:input#username.block.px-4.py-2.mt-2.text-gray-700.bg-white.border.border-gray-300.rounded-md.dark:bg-gray-800.dark:text-gray-300.dark:border-gray-600.focus:border-blue-500.dark:focus:border-blue-500.focus:outline-none.focus:ring
           {:id            "login-username" :type "text" :placeholder "e.g. ma090906" :required true
            :disabled      submitting
            :auto-complete "username"
            :auto-focus    true
            :on-key-down   #(if (= 13 (.-which %)) (do (.focus (.getElementById js/document "login-pw"))))
            :on-change     #(reset! username (-> % .-target .-value))}]]
         [:div
          [:label.text-gray-700.dark:text-gray-200 {:for "password"} "Password"]
          [:input#password.block.px-4.py-2.mt-2.text-gray-700.bg-white.border.border-gray-300.rounded-md.dark:bg-gray-800.dark:text-gray-300.dark:border-gray-600.focus:border-blue-500.dark:focus:border-blue-500.focus:outline-none.focus:ring
           {:id            "login-pw" :type "password" :placeholder "Enter password" :required true
            :disabled      submitting
            :auto-complete "current-password"
            :on-key-down   #(if (= 13 (.-which %))
                              (do (reset! password (-> % .-target .-value)) (do-login)))
            :on-change     #(reset! password (-> % .-target .-value))}]]]
        [:div.flex.mt-6
         [:button.px-6.py-2.leading-5.text-white.transition-colors.duration-200.transform.bg-gray-700.rounded-md.hover:bg-gray-600.focus:outline-none.focus:bg-gray-600
          {:class (when submitting ["text-gray" :bg])
           :disabled submitting
           :on-click do-login} "Login"]]]
       (when-not (str/blank? @error) [ui/box-error-message :message @error])
       (when @ping-error [ui/box-error-message :message "Warning: connection error; unable to connect to server. Will retry automatically."])])))


(defn project-panel
  "A simple panel to show the user's own projects."
  [& {:keys [on-choose]}]
  (let [active-projects @(rf/subscribe [::subs/active-projects])
        grouped (group-by :t_project/type active-projects)
        has-clinical (seq (:NHS grouped))
        has-research (seq (:RESEARCH grouped))]
    [:div.border-solid.border-gray-800.bg-gray-50.border.rounded.shadow-lg
     [:div.bg-gray-800.text-white.px-2.py-2.border-solid.border-grey-800 "My projects / services"]
     (when has-clinical
       [:<>
        [:div
         [:span.mt-2.text-xs.inline-block.py-1.px-2.uppercase.bg-yellow-200.uppercase.last:mr-0.mr-1 "clinical"]]
        (for [project (sort-by :t_project/title (:NHS grouped))]
          [:a.cursor-default {:key      (:t_project/id project)
                              :on-click #(when on-choose (on-choose project))}
           [:div.px-3.py-1.text-sm.bg-yellow-50.hover:bg-yellow-100.border
            (:t_project/title project)]])])
     (when (and has-clinical has-research)
       [:hr])
     (when has-research
       [:<>
        [:div
         [:span.mt-2.text-xs.inline-block.py-1.px-2.uppercase.bg-pink-200.uppercase.last:mr-0.mr-1 "research"]]
        (for [project (sort-by :t_project/title (:RESEARCH grouped))]
          [:a.cursor-default {:key      (:t_project/id project)
                              :on-click #(when on-choose (on-choose project))}
           [:div.px-3.py-1.text-sm.bg-pink-50.hover:bg-pink-100.border
            (:t_project/title project)]])])]))