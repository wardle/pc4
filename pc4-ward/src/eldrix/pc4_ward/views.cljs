(ns eldrix.pc4-ward.views
  (:require
    [clojure.string :as str]
    [re-frame.core :as rf]
    [eldrix.pc4-ward.events :as events]
    [eldrix.pc4-ward.users :as users]

    [reagent.core :as reagent]))

(defn nav-bar []
  (let [show-nav-menu? (reagent/atom false)
        authenticated-user-full-name (rf/subscribe [::users/authenticated-user-full-name])]
    (fn []
      [:nav.navbar.is-black.is-fixed-top {:role "navigation" :aria-label "main navigation"}
       [:div.navbar-brand
        [:a.navbar-item {:href "#/"} [:h1 "PatientCare v4: " [:strong "Ward"]]]
        [:a.navbar-burger.burger {:role     "button" :aria-label "menu" :aria-expanded @show-nav-menu?
                                  :class    (if @show-nav-menu? :is-active "")
                                  :on-click #(swap! show-nav-menu? not)}
         [:span {:aria-hidden "true"}]
         [:span {:aria-hidden "true"}]
         [:span {:aria-hidden "true"}]]]
       [:div.navbar-menu (when @show-nav-menu? {:class :is-active})
        [:div.navbar-start
         [:a.navbar-item {:href "#/"} "Home"]
         (comment
           [:div.navbar-item.has-dropdown.is-hoverable
            [:a.navbar-link [:span "Messages\u00A0"] [:span.tag.is-danger.is-rounded 123]]
            [:div.navbar-dropdown
             [:a.navbar-item "Unread messages"]
             [:a.navbar-item "Archive"]
             [:hr.navbar-divider]
             [:a.navbar-item "Send a message..."]]]
           [:a.navbar-item "Teams"])

         [:div.navbar-item.has-dropdown.is-hoverable
          [:a.navbar-link [:span "Links"]]
          [:div.navbar-dropdown
           [:a.navbar-item "Cardiff and Vale homepage"]
           [:a.navbar-item "Cardiff Clinical Portal"]
           [:a.navbar-item "Welsh Clinical Portal (Cardiff)"]
           [:a.navbar-item "Welsh Clinical Portal (Cwm Taf Morgannwg)"]
           [:hr.navbar-divider]
           [:a.navbar-item "View radiology images"]]]]

        [:div.navbar-end
         [:div.navbar-item.has-dropdown.is-hoverable
          [:a.navbar-link @authenticated-user-full-name]
          [:div.navbar-dropdown
           [:a.navbar-item "Profile"]
           [:a.navbar-item "Teams"]
           [:hr.navbar-divider]
           [:a.navbar-item {:disabled true} "Report an issue"]]]
         [:a.navbar-item {:on-click #(rf/dispatch [::users/do-logout])} "Logout"]]]])))

(defn home-panel []
  [:div
   [:h1 "This is the main page."]])

;; about
(defn about-panel []
  [:div
   [:h1 " This is the About Page. "]

   [:div
    [:a {:href " #/"}
     " go to Home Page "]]])

(defn login-panel []
  (let [error (rf/subscribe [::users/login-error])
        username (reagent/atom "")
        password (reagent/atom "")
        submitting false                                    ;; @(rf/subscribe [:show-foreground-spinner])
        do-login #(rf/dispatch [::users/do-login "wales.nhs.uk" (str/trim @username) @password])]
    (fn []
      [:<>
       [:section.hero.is-full-height
        [:div.hero-body
         [:div.container
          [:div.columns.is-centered
           [:div.column.is-5-tablet.is-4-desktop.is-3-widescreen
            [:div.box
             ;; username field - if user presses enter, automatically switch to password field
             [:div.field [:label.label {:for "login-un"} "Username"]
              [:div.control
               [:input.input {:id          "login-un" :type "text" :placeholder "e.g. ma090906" :required true
                              :disabled    submitting
                              :auto-focus  true
                              :on-key-down #(if (= 13 (.-which %)) (do (.focus (.getElementById js/document "login-pw"))))
                              :value       @username
                              :on-change   #(reset! username (-> % .-target .-value))}]]]

             ;; password field - if user presses enter, automatically submit
             [:div.field [:label.label {:for "login-pw"} "Password"]
              [:div.control
               [:input.input {:id          "login-pw" :type "password" :placeholder "Enter password" :required true
                              :disabled    submitting
                              :on-key-down #(if (= 13 (.-which %))
                                              (do (reset! password (-> % .-target .-value)) (do-login)))
                              :value       @password
                              :on-change   #(reset! password (-> % .-target .-value))}]]]

             [:button.button {:class    ["is-primary" (when submitting "is-loading")]
                              :disabled submitting
                              :on-click do-login} " Login "]]

            (if-not (str/blank? @error) [:div.notification.is-danger [:p @error]])]]]]]])))

  (defn- panels [panel-name]
    (case panel-name
      :home-panel [home-panel]
      :login-panel [login-panel]
      :about-panel [about-panel]
      [:div]))

  (defn show-panel [panel-name]
    [panels panel-name])

  (defn main-page []
    (let [authenticated-user (rf/subscribe [::users/authenticated-user])
          active-panel (rf/subscribe [::events/active-panel])]
      (fn []
        (if @authenticated-user
          [:div
           [nav-bar]
           [show-panel @active-panel]]
          [login-panel]))))

