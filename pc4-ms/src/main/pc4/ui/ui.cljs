(ns pc4.ui.ui
  "Stateless plain components."
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [a button div img path span svg nav]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr])
  (:import [goog.date Date]))

(def months-en
  {0  "Jan"
   1  "Feb"
   2  "Mar"
   3  "Apr"
   4  "May"
   5  "Jun"
   6  "Jul"
   7  "Aug"
   8  "Sep"
   9  "Oct"
   10 "Nov"
   11 "Dec"})

(defn format-date [^Date date]
  (when date (str (.getDate date)
                  "-"
                  (get months-en (.getMonth date))
                  "-"
                  (.getYear date))))

(defn icon-chevron-down []
  (svg :.-mr-1.ml-2.h-5.w-5 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "white" :aria-hidden "true"}
       (path {:fillRule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clipRule "evenodd"})))

(defn box-error-message [& {:keys [title message]}]
  (when message
    (dom/div :.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.relative.shadow-md {:role "alert"}
             (when title (dom/span :.strong.font-bold.mr-4 title))
             (dom/span :.block.sm:inline message))))

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
  - photo      : photo to show for user  [todo: URL or something else?]
  - on-notify  : function if notify button should appear (and call if clicked)"
  [this {:keys [title menu selected user-menu show-user? full-name initials photo on-notify] :or {show-user? false}}]
  (let [show-menu? (or (comp/get-state this :show-menu) false)
        show-user-menu? (or (comp/get-state this :show-user-menu) false)
        _ (println "show user:" show-user?)]
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
                                   (a {:onClick #(dr/change-route! this ["home"])} title)))
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
                          (div :.ml-3.relative
                               (div
                                 (button :.user-menu-button.bg-gray-800.flex.text-sm.rounded-full
                                         {:type    "button" :aria-expanded "false" :aria-haspopup "true"
                                          :onClick #(comp/set-state! this {:show-user-menu (not show-user-menu?)})}
                                         (span :.sr-only "Open user menu")
                                         (span :.hidden.sm:block.text-white (div :.flex (or full-name "User") (icon-chevron-down)))
                                         (span :.sm:hidden.text-white (div :.flex (when initials initials) (icon-chevron-down)))
                                         (when photo (img :.h-8.w-8.rounded-full {:src "https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=facearea&facepad=2&w=256&h=256&q=80" :alt ""}))))
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


(def ui-nav-bar (comp/factory nav-bar))