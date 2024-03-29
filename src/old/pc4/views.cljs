(ns pc4.views
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [pc4.events :as events]
            [pc4.subs :as subs]
            [pc4.snomed.views :as snomed]
            [reagent.core :as reagent]
            [eldrix.pc4-ward.ui :as ui]
            [reitit.frontend.easy :as rfe]))

(defn nav-bar
  "A navigation bar.
  Parameters:
  - title      : the title to show
  - menu       : the main menu, a sequence of menu items with
                |- :id       : unique identifier
                |- :title    : the menu item title
                |- :on-click : function for when clicked
                |- :href     : href for when clicked
  - selected   : identifier of the currently selected menu
  - show-user? : should the user and user menu be shown?
  - user-menu  : a sequence of menu items each with keys :id :title :on-click
  - full-name  : full name of user
  - initials   : initials of user (for display on small devices)
  - photo      : photo to show for user  [todo: URL or something else?]
  - on-notify  : function if notify button should appear (and call if clicked)"
  [& opts]
  (let [show-menu? (reagent/atom false)
        show-user-menu? (reagent/atom false)]
    (fn [& {:keys [title menu selected user-menu show-user? full-name initials photo on-notify] :or {show-user? false}}]
      [:nav.bg-gray-800
       [:div.mx-auto.px-2.sm:px-6.lg:px-8
        [:div.relative.flex.items-center.justify-between.h-12
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
           [:span.text-white.rounded-md.text-lg.font-large.font-bold [:a {:href (rfe/href :home)} title]]]
          (when (seq menu)
            [:div.hidden.sm:block.sm:ml-6
             [:div.flex.space-x-4
              (for [item menu]
                (if (and selected (:id item) (= (:id item) selected))
                  [:a.text-white.px-3.py-2.rounded-md.text-sm.font-medium.font-bold {:key (:id item) :on-click (:on-click item) :aria-current "page"} (:title item)]
                  [:a.text-gray-300.hover:bg-gray-700.hover:text-white.px-3.py-2.rounded-md.text-sm.font-medium
                   (cond-> {:key (:id item)}
                           (:href item) (assoc :href (:href item))
                           (:on-click item) (assoc :on-click (:on-click item)))
                   (:title item)]))]])]
         [:div.absolute.inset-y-0.right-0.flex.items-center.pr-2.sm:static.sm:inset-auto.sm:ml-6.sm:pr-0
          (when on-notify
            [:button.bg-gray-800.p-1.rounded-full.text-gray-400 {:on-click on-notify} [:span.sr-only "View notifications"] [ui/icon-bell]])
          (when show-user?
            [:div.ml-3.relative
             [:div
              [:button#user-menu-button.bg-gray-800.flex.text-sm.rounded-full
               {:type     "button" :aria-expanded "false" :aria-haspopup "true"
                :on-click #(swap! show-user-menu? not)}
               [:span.sr-only "Open user menu"]
               [:span.hidden.sm:block.text-white [:div.flex (or full-name "User") [ui/icon-chevron-down]]]
               [:span.sm:hidden.text-white [:div.flex (when initials initials) [ui/icon-chevron-down]]]
               (when photo [:img.h-8.w-8.rounded-full {:src "https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=facearea&facepad=2&w=256&h=256&q=80" :alt ""}])]]
             (when @show-user-menu?
               [:div.origin-top-right.absolute.z-50.right-0.mt-2.w-48.rounded-md.shadow-lg.py-1.bg-white.ring-1.ring-black.ring-opacity-5.focus:outline-none
                {:role "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabIndex "-1"}
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
                [:a.bg-gray-900.text-white.block.px-3.py-2.rounded-md.text-base.font-medium
                 {:key (:id item) :on-click (:on-click item) :aria-current "page"} (:title item)]
                [:a.text-gray-300.hover:bg-gray-700.hover:text-white.block.px-3.py-2.rounded-md.text-base.font-medium
                 {:key (:id item) :on-click (:on-click item)} (:title item)]))]])]])))

(defn home-panel []
  (let [latest-news @(rf/subscribe [::subs/latest-news])
        _ (tap> latest-news)]
    [:div.grid.grid-cols-1.md:grid-cols-4.md:gap-4.m-4
     [:div.md:mr-2
      (when pc4.config/debug? [pc4.patient.home/patient-search-by-id])
      [pc4.user.views/project-panel
       :on-choose #(rfe/push-state :project/home {:project-id (:t_project/id %)})]]
     [:div.col-span-3
      [:div.mb-4.bg-white.overflow-hidden.sm:rounded-md
       [:ul.divide-y.divide-gray-200 {:role "list"}
        (for [article latest-news]
          [:li {:key (:t_news/id article)}
           [:div.px-4.py-4.sm:px-6.border.rounded.shadow.mb-4
            [:div.flex.items-center.justify-between
             [:p.text-lg.font-medium.text-indigo-600.truncate (:t_news/title article)]
             [:div.ml-2.flex-shrink-0.flex
              [:p.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-blue-100.text-green-800 [:time {:date-time (:t_news/date_time article)} (pc4.dates/format-date (:t_news/date_time article))]]]]
            [:div.sm:flex.sm:justify-between
             [:div.mb-2.flex.items-center.text-sm.text-gray-500.sm:mt-0
              [:p "by " (:t_user/first_names article) " " (:t_user/last_name article)]]]
            [:p.text-sm.prose.p-2 {:dangerouslySetInnerHTML {:__html (:t_news/body article)}}]]])]]]]))



(defn test-snomed []
  (let [selected-diagnosis (reagent/atom nil)]
    [:<>
     [snomed/select-snomed
      :id :example
      :label "Enter diagnosis"
      :constraint "<404684003"
      ;  :common-choices  @(rf/subscribe [::subs/common-diagnoses])
      :max-hits 100
      :value @selected-diagnosis
      :select-fn #(reset! selected-diagnosis %)]]))

(defn main-page []
  (let [must-change-password? @(rf/subscribe [::subs/must-change-password?])]
    [:<>
     (if must-change-password?
       [pc4.user.views/change-password]
       [home-panel])]))


(defn login-page []
  [ui/main-login-panel
   :on-login (fn [username password]
               (rf/dispatch [::events/do-login {:username username, :password password}]))
   :error @(rf/subscribe [::subs/login-error])
   :disabled @(rf/subscribe [::subs/loading :login])])