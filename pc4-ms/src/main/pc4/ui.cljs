(ns pc4.ui
  "Stateless plain components."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [a button div img path span svg nav]]
    [com.fulcrologic.fulcro.dom.inputs]
    [com.fulcrologic.fulcro.dom.events :as evt]
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


(defsc PlaceholderImage
  "Generates an SVG image placeholder of the given size and with the given label
  (defaults to showing 'w x h'.

  ```
  (ui-placeholder {:w 50 :h 50 :label \"avatar\"})
  ```
  "
  [this {:keys [w h label]}]
  (let [label (or label (str w "x" h))]
    (dom/svg #js {:width w :height h}
             (dom/rect #js {:width w :height h :style #js {:fill        "rgb(200,200,200)"
                                                           :strokeWidth 2
                                                           :stroke      "black"}})
             (dom/text #js {:textAnchor "middle" :x (/ w 2) :y (/ h 2)} label))))

(def ui-placeholder (comp/factory PlaceholderImage))


(defn icon-chevron-down []
  (svg :.-mr-1.ml-2.h-5.w-5 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "white" :aria-hidden "true"}
       (path {:fillRule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clipRule "evenodd"})))

(defn box-error-message [& {:keys [title message]}]
  (when message
    (dom/div :.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.relative.shadow-md {:role "alert"}
             (when title (dom/span :.strong.font-bold.mr-4 title))
             (dom/span :.block.sm:inline message))))


(defn flat-menu
  "A simple flat menu.
  Parameters:
  - items       : a collection of items, each with :id and :title
  - selected-id : identifier of the selected item
  - select-fn   : function to call on select with the identifier"
  [items & {:keys [selected-id select-fn]}]
  (dom/ul :.flex
          (for [item items
                :let [id (:id item) title (:title item)]]
            (dom/li :.mr3 {:key id}
                    (if (= selected-id id)
                      (dom/a :.inline-block.border.border-blue-500.rounded.py-1.px-3.bg-blue-500.text-white.cursor-not-allowed title)
                      (dom/a :.inline-block.border.border-white.rounded.hover:border-gray-200.text-blue-500.hover:bg-gray-200.py-1.px-3.cursor-pointer {:onClick #(when select-fn (select-fn id))} title))))))






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
                                   (a {:onClick #(comp/transact! this
                                                                 [(route/route-to {:path ["home"]})])}
                                      #_{:onClick #(dr/change-route! this ["home"])} title)))
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

(defsc UIBadge
  "Display a small badge with the text specified."
  [this {:keys [label text-color bg-color uppercase?] :or {text-color "text-red-200" bg-color "bg-red-500" uppercase? true}}]
  (span :.text-xs.text-center.font-semibold.inline-block.py-1.px-2.uppercase.rounded-full.ml-1.last:mr-0.mr-1
        {:className (str/join " " [text-color bg-color (when uppercase? "uppercase")])} label))

(def ui-badge (comp/factory UIBadge))

(defsc UILabel
  [this {:keys [for label]}]
  (dom/label :.block.text-sm.font-medium.text-gray-600 {:htmlFor for} label))

(def ui-label (comp/factory UILabel))

(defsc UITextField
  "A styled textfield control."
  [this
   {:keys [id value type label placeholder required auto-focus disabled help-text] :or {type "text"}}
   {:keys [onChange onBlur onEnterKey]}]
  (div
    (when label (ui-label {:for id :label label}))
    (div :.mt-1
         (dom/input :.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md
                    {:name      id :type type :placeholder placeholder
                     :required  required :className (if-not disabled ["text-gray-700" "bg-white" "shadow"] ["text-gray-600" "bg-gray-50" "italic"])
                     :disabled  disabled :value (or value "")
                     :autoFocus auto-focus
                     :onChange  #(when onChange (let [v (evt/target-value %)] (onChange v)))
                     :onBlur    #(when onBlur (onBlur))
                     :onKeyDown #(when (and onEnterKey (evt/enter-key? %)) (onEnterKey))
                     :onWheel   #(when (= type "number") (-> % .-target .blur))})
         (when help-text (dom/p :.text-sm.text-gray-500.italic help-text)))))

(def ui-textfield (comp/computed-factory UITextField))


(defn unparse-local-date [^Date d]
  (when d (.toIsoString d true)))

(defn parse-local-date [s]
  (Date/fromIsoString s))


(def ui-local-date-input
  "A goog.Date input. Can be used like `dom/input` but onChange and onBlur handlers will be passed a Date instead
  of a raw react event, and you should supply a goog.Date for `:value` instead of a string.
  All other attributes passed in props are passed through to the contained `dom/input`."
  (comp/factory (com.fulcrologic.fulcro.dom.inputs/StringBufferedInput ::DateInput {:model->string #(or (unparse-local-date %) "")
                                                                                    :string->model #(or (parse-local-date %) nil)})))

(defsc UILocalDate
  [this {:keys [id label value min-date max-date]} {:keys [onBlur onChange onEnterKey]}]
  (println "UILocalDate value: " value)
  (div
    (when label (ui-label {:for id :label label}))
    (div :.mt-1
         (ui-local-date-input (cond-> {:type "date" :value value}
                                      id (assoc :name id)
                                      min-date (assoc :min (unparse-local-date min-date))
                                      max-date (assoc :max (unparse-local-date max-date))
                                      onBlur (assoc :onBlur onBlur)
                                      onEnterKey (assoc :onKeyDown #(when (evt/enter-key? %) (onEnterKey)))
                                      onChange (assoc :onChange onChange))))))

(def ui-local-date
  "A UI control to edit a date."
  (comp/computed-factory UILocalDate))


(defsc UISelectPopupButton
  "See [[ui-select-popup-button]] for documentation."
  [this
   {:keys [name label value options id-key display-key default-value no-selection-string disabled? sort?]
    :or   {id-key identity display-key identity sort? true}}
   {:keys [onChange onEnterKey sort-fn]}]
  (let [all-options (if (and value (id-key value) (not (some #(= (id-key value) (id-key %)) options)))
                      (conj options value) options)
        sorted-options (vec (if-not sort? all-options (sort-by (or sort-fn display-key) all-options)))
        default-value (or default-value (when (str/blank? no-selection-string) (first sorted-options)))]
    (when (and onChange default-value (nil? value))
      (onChange default-value))
    (div
      (when label (ui-label {:for name :label label}))
      (dom/select :#location.mt-1.block.pl-3.pr-10.py-2.text-base.border-gray-300.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm.rounded-md
                  {:name      name
                   :disabled  disabled?
                   :value     (str (id-key value))
                   :onKeyDown #(when (and onEnterKey (evt/enter-key? %)) (onEnterKey))
                   :onChange  #(when onChange
                                 (let [idx (-> % .-target .-selectedIndex)]
                                   (if (and no-selection-string (= 0 idx))
                                     (onChange nil)
                                     (onChange (get sorted-options (if no-selection-string (- idx 1) idx))))))}
                  (when no-selection-string [:option.py-1 {:value nil :id "none"} no-selection-string])
                  (println "options:" sorted-options)
                  (for [option sorted-options
                        :let [id (id-key option)]]
                    ^{:key id} (dom/option :.py-1 {:value (str id)} (display-key option)))))))

(def ui-select-popup-button
  "A select control that appears as a pop-up.
    Callbacks are:
    onChange  : called with the value selected
    onEnter   : called when enter key pressed"
  (comp/computed-factory UISelectPopupButton))

(defsc UISubmitButton
  [this {:keys [label disabled?]} {:keys [onClick]}]
  (dom/button :.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600
              {:type      "submit"
               :className (if disabled? "opacity-50 pointer-events-none" "hover:bg-blue-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-blue-500")
               :onClick   #(when (and onClick (not disabled?)) (onClick))}
              label))

(def ui-submit-button (comp/computed-factory UISubmitButton))