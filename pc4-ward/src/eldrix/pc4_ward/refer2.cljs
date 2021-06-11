(ns eldrix.pc4-ward.refer2
  (:require [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.rf.users :as users]
            [eldrix.pc4-ward.rf.patients :as patients]
            [reagent.core :as reagent]
            [clojure.string :as str]))

(defn icon-bell []
  [:svg.h-6.w-6 {:xmlns   "http://www.w3.org/2000/svg" :fill "none"
                 :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"}]])

(defn icon-chevron-down []
  [:svg.-mr-1.ml-2.h-5.w-5 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "white" :aria-hidden "true"}
   [:path {:fill-rule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clip-rule "evenodd"}]])

(defn badge-red
  "Display a small badge in red with the text specified."
  [s]
  [:span.text-xs.text-center.font-semibold.inline-block.py-1.px-2.uppercase.rounded-full.text-red-200.bg-red-600.uppercase.last:mr-0.mr-1 s])

(defn nav-bar
  "A navigation bar.
  Parameters:
  - title      : the title to show
  - menu       : the main menu, a sequence of menu items with
                |- :id       : unique identifier
                |- :title    : the menu item title
                |- :on-click : function for when clicked
  - selected   : identifier of the currently selected menu
  - show-user? : should the user and user menu be shown?
  - user-menu  : a sequence of menu items each with keys :id :title :on-click
  - full-name  : full name of user
  - initials   : initials of user (for display on small devices)
  - photo      : photo to show for user  [todo: URL or something else?]
  - on-notify  : function if notify button should appear (and call if clicked)"
  [& {:keys [title menu selected user-menu show-user? full-name initials photo on-notify] :or {show-user? false}}]
  (let [show-menu? (reagent/atom false)
        show-user-menu? (reagent/atom false)]
    (fn []
      [:nav.bg-gray-800
       [:div.max-w-7xl.mx-auto.px-2.sm:px-6.lg:px-8
        [:div.relative.flex.items-center.justify-between.h-16
         (when (seq menu)
           [:div.absolute.inset-y-0.left-0.flex.items-center.sm:hidden
            [:button.inline-flex.items-center.justify-center.p-2.rounded-md.text-gray-400.hover:text-white.hover:bg-gray-700.focus:ring-2.focus:ring-inset.focus:ring-white
             {:type     "button" :aria-controls "mobile-menu" :aria-expanded "false"
              :on-click #(swap! show-menu? not)}
             [:span.sr-only "Open main menu"]
             [:svg.block.h-6.w-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
              [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M4 6h16M4 12h16M4 18h16"}]]
             [:svg.hidden.h-6.w-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
              [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M6 18L18 6M6 6l12 12"}]]]])
         [:div.flex-1.flex.items-center.justify-center.sm:items-stretch.sm:justify-start
          [:div.flex-shrink-0.flex.items-center
           [:span.text-white.rounded-md.text-lg.font-large.font-bold title]]
          (when (seq menu)
            [:div.hidden.sm:block.sm:ml-6
             [:div.flex.space-x-4
              (for [item menu]
                (if (and selected (:id item) (= (:id item) selected))
                  [:a.text-white.px-3.py-2.rounded-md.text-sm.font-medium.font-bold {:key (:id item) :on-click (:on-click item) :aria-current "page"} (:title item)]
                  [:a.text-gray-300.hover:bg-gray-700.hover:text-white.px-3.py-2.rounded-md.text-sm.font-medium {:key (:id item) :on-click (:on-click item)} (:title item)]))]])]
         [:div.absolute.inset-y-0.right-0.flex.items-center.pr-2.sm:static.sm:inset-auto.sm:ml-6.sm:pr-0
          (when on-notify
            [:button.bg-gray-800.p-1.rounded-full.text-gray-400 {:on-click on-notify} [:span.sr-only "View notifications"] [icon-bell]])
          (when show-user?
            [:div.ml-3.relative
             [:div
              [:button#user-menu-button.bg-gray-800.flex.text-sm.rounded-full
               {:type     "button" :aria-expanded "false" :aria-haspopup "true"
                :on-click #(swap! show-user-menu? not)}
               [:span.sr-only "Open user menu"]
               [:span.hidden.sm:block.text-white [:div.flex (or full-name "User") [icon-chevron-down]]]
               [:span.sm:hidden.text-white [:div.flex (when initials initials) [icon-chevron-down]]]
               (when photo [:img.h-8.w-8.rounded-full {:src "https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=facearea&facepad=2&w=256&h=256&q=80" :alt ""}])]]
             (when @show-user-menu?
               [:div.origin-top-right.absolute.right-0.mt-2.w-48.rounded-md.shadow-lg.py-1.bg-white.ring-1.ring-black.ring-opacity-5.focus:outline-none {:role "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabIndex "-1"}
                (for [item user-menu]
                  (if (:on-click item)
                    [:a.block.px-4.py-2.text-sm.text-gray-700.hover:bg-gray-700.hover:text-white
                     {:key (:id item) :on-click #(do (reset! show-user-menu? false) ((:on-click item))) :role "menuitem" :tabIndex "-1"} (:title item)]
                    [:a.block.px-4.py-2.text-sm.text-gray-700.italic {:key (:id item) :role "menuitem" :tabIndex "-1"} (:title item)]))])])]]
        (when (and (seq menu) @show-menu?)
          [:div#mobile-menu.sm:hidden
           [:div.px-2.pt-2.pb-3.space-y-1
            (for [item menu]
              (if (and selected (:id item) (= (:id item) selected))
                [:a.bg-gray-900.text-white.block.px-3.py-2.rounded-md.text-base.font-medium {:key (:id item) :on-click (:on-click item) :aria-current "page"} (:title item)]
                [:a.text-gray-300.hover:bg-gray-700.hover:text-white.block.px-3.py-2.rounded-md.text-base.font-medium {:key (:id item) :on-click (:on-click item)} (:title item)]))]])]])))

(defn patient-banner
  [& {:keys [name nhs-number born hospital-identifier address deceased]}]
  [:div.grid.grid-cols-1.border-2.shadow-lg.p-4.m-2.border-gray-200
   (when deceased
     [:div.grid.grid-cols-1.pb-2
      [badge-red "Deceased"]])
   [:div.grid.grid-cols-2.lg:grid-cols-5
    [:div.font-bold.text-lg.min-w-min name]
    [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min [:span.text-sm.font-thin.hidden.sm:inline "Gender "] "Male"]
    [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min [:span.text-sm.font-thin "Born "] born]
    [:div.lg:hidden.text-right "Male" " " born]
    [:div.lg:text-center.lg:ml-2.min-w-min [:span.text-sm.font-thin "NHS No "] nhs-number]
    [:div.text-right.min-w-min [:span.text-sm.font-thin "CRN "] hospital-identifier]]
   [:div.grid.grid-cols-1 {:class (if-not deceased "bg-gray-100" "bg-red-100")}
    [:div.font-light.text-sm.tracking-tighter.text-gray-500 address]]])


(defn refer-page []
  [:<> [nav-bar
        :title "PatientCare v4"
        :menu [{:id :refer-patient :title "Refer patient"}]
        :selected :refer-patient
        :show-user? true
        :full-name "Dr Mark Wardle"
        :initials "MW"
        :user-menu [{:id :logout :title "Sign out" :on-click #(js/console.log "Menu: logout")}]]
   [patient-banner
    :name "DUMMY, Albert (Mr)"
    :nhs-number "111 111 1111"
    :deceased false
    :born "01-Jun-1985 (36y)"
    :hospital-identifier "A999998"
    :address "University Hospital Wales, Heath Park, Cardiff, CF14 4XW"]])
