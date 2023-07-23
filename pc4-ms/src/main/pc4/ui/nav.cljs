(ns pc4.ui.nav
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [a button div img path span svg nav]]
    [com.fulcrologic.fulcro.dom.inputs]
    [pc4.ui.core :as ui]
    [taoensso.timbre :as log]))

(defsc nav-bar
  "A stateless navigation bar.
  Parameters:
  - title      : the title to show
  - menu       : the main menu, a sequence of menu items with
                |- :id       : unique identifier
                |- :title    : the menu item title
                |- :onClick : function for when clicked
                |- :href     : href for when clicked
  - selected   : identifier of the currently selected menu
  - show-user? : should the user and user menu be shown?
  - user-menu  : a sequence of menu items each with keys :id :title :onClick
  - full-name  : full name of user
  - initials   : initials of user (for display on small devices)
  - photo      : URL of photo to show for user
  - on-home    : function if home button clicked
  - on-notify  : function if notify button should appear (and call if clicked)"
  [this
   {:keys [title menu selected user-menu show-user? full-name initials photo] :or {show-user? false}}
   {:keys [on-home on-notify]}]
  (let [show-menu? (or (comp/get-state this :show-menu) false)
        show-user-menu? (or (comp/get-state this :show-user-menu) false)
        _ (println "show user:" show-user?)]
    (when-not on-home
      (log/warn "Missing 'on-home' handler for navigation bar"))
    (nav :.bg-gray-800
      (div :.mx-auto.px-2.sm:px-6.lg:px-8
        (div :.relative.flex.items-center.justify-between.h-16
          (when (seq menu)
            (div :.absolute.inset-y-0.left-0.flex.items-center.sm:hidden
              (button :.inline-flex.items-center.justify-center.p-2.rounded-md.text-gray-400.hover:text-white.hover:bg-gray-700.focus:ring-2.focus:ring-inset.focus:ring-white
                {:type    "button" :aria-controls "mobile-menu" :aria-expanded "false"
                 :onClick #(comp/set-state! this {:show-menu (not show-menu?)})}
                (span :.sr-only "Open main menu")
                (svg :.block.h-6.w-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
                     (path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M4 6h16M4 12h16M4 18h16"}))
                (svg :.hidden.h-6.w-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
                     (path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M6 18L18 6M6 6l12 12"})))))
          (div :.flex-1.flex.items-center.justify-center.sm:items-stretch.sm:justify-start
            (div :.flex-shrink-0.flex.items-center
              (span :.text-white.rounded-md.text-lg.font-large.font-bold.cursor-pointer
                    (a {:onClick #(when on-home (on-home))} title)))
            (when (seq menu)
              (div :.hidden.sm:block.sm:ml-6
                (div :.flex.space-x-4
                  (for [item menu]
                    (if (and selected (:id item) (= (:id item) selected))
                      (a :.text-white.px-3.py-2.rounded-md.text-sm.font-medium.font-bold {:key (:id item) :onClick (:onClick item) :aria-current "page"} (:title item))
                      (a :.text-gray-300.hover:bg-gray-700.hover:text-white.px-3.py-2.rounded-md.text-sm.font-medium
                         (cond-> {:key (:id item)}
                                 (:href item) (assoc :href (:href item))
                                 (:onClick item) (assoc :onClick (:onClick item)))
                         (:title item))))))))
          (div :.absolute.inset-y-0.right-0.flex.items-center.pr-2.sm:static.sm:inset-auto.sm:ml-6.sm:pr-0
            (when show-user?
              (when photo
                (div (img :.inline-block.h-10.w-10.rounded-md {:src photo :alt ""})))
              (div :.ml-3.relative
                (div
                  (button :.user-menu-button.bg-gray-800.flex.text-sm.rounded-full
                    {:type    "button" :aria-expanded "false" :aria-haspopup "true"
                     :onClick #(comp/set-state! this {:show-user-menu (not show-user-menu?)})}
                    (span :.sr-only "Open user menu")
                    (span :.hidden.sm:block.text-white (div :.flex (or full-name "User") (ui/icon-chevron-down)))
                    (span :.sm:hidden.text-white (div :.flex (when initials initials) (ui/icon-chevron-down)))))
                (when show-user-menu?
                  (div :.origin-top-right.absolute.z-50.right-0.mt-2.w-48.rounded-md.shadow-lg.py-1.bg-white.ring-1.ring-black.ring-opacity-5.focus:outline-none
                    {:role "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabIndex "-1"}
                    (for [item user-menu]
                      (if (:onClick item)
                        (a :.block.px-4.py-2.text-sm.text-gray-700.hover:bg-gray-700.hover:text-white.cursor-pointer
                           {:key (:id item) :onClick #(do (comp/set-state! this {:show-user-menu (not show-user-menu?)})
                                                          ((:onClick item))) :role "menuitem" :tabIndex "-1"} (:title item))
                        (a :.block.px-4.py-2.text-sm.text-gray-700.italic {:key (:id item) :role "menuitem" :tabIndex "-1"} (:title item))))))))))
        (when (and (seq menu) show-menu?)
          (div :.mobile-menu.sm:hidden
            (div :.px-2.pt-2.pb-3.space-y-1
              (for [item menu]
                (if (and selected (:id item) (= (:id item) selected))
                  (a :.bg-gray-900.text-white.block.px-3.py-2.rounded-md.text-base.font-medium
                     {:key (:id item) :onClick (:onClick item) :aria-current "page"} (:title item))
                  (a :.text-gray-300.hover:bg-gray-700.hover:text-white.block.px-3.py-2.rounded-md.text-base.font-medium
                     {:key (:id item) :onClick (:onClick item)} (:title item)))))))))))


(def ui-nav-bar (comp/computed-factory nav-bar))
