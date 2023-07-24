(ns pc4.ui.core
  "Stateless UI components."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [a button div img path span svg nav]]
    [com.fulcrologic.fulcro.dom.inputs]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [taoensso.timbre :as log])
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
  (when date (str (.getDate date) "-" (get months-en (.getMonth date)) "-" (.getYear date))))

(defn format-month-year [^Date date]
  (when date (str (get months-en (.getMonth date)) " " (.getYear date))))

(defn truncate [s length]
  (when s (let [len (count s)]
            (if (> len length) (str (subs s 0 length) "…") s))))


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
  - select-fn   : function to call on select with the item"
  [items & {:keys [selected-id select-fn]}]
  (dom/ul :.flex
    (for [{:keys [id title] :as item} items]
      (dom/li :.mr3 {:key id}
        (if (= selected-id id)
          (dom/a :.inline-block.border.border-blue-500.rounded.py-1.px-3.bg-blue-500.text-white.cursor-not-allowed title)
          (dom/a :.inline-block.border.border-white.rounded.hover:border-gray-200.text-blue-500.hover:bg-gray-200.py-1.px-3.cursor-pointer {:onClick #(when select-fn (select-fn item))} title))))))


(defsc UILoading
  "Display a spinning loading image"
  [this {}]
  (dom/div
    {:role "status"}
    (dom/svg :.mr-2.w-8.h-8.text-gray-200.animate-spin.dark:text-gray-600.fill-blue-600
             {:aria-hidden "true",
              :fill        "none",
              :xmlns       "http://www.w3.org/2000/svg",
              :viewBox     "0 0 100 101"}
             (dom/path
               {:d    "M100 50.5908C100 78.2051 77.6142 100.591 50 100.591C22.3858 100.591 0 78.2051 0 50.5908C0 22.9766 22.3858 0.59082 50 0.59082C77.6142 0.59082 100 22.9766 100 50.5908ZM9.08144 50.5908C9.08144 73.1895 27.4013 91.5094 50 91.5094C72.5987 91.5094 90.9186 73.1895 90.9186 50.5908C90.9186 27.9921 72.5987 9.67226 50 9.67226C27.4013 9.67226 9.08144 27.9921 9.08144 50.5908Z",
                :fill "currentColor"})
             (dom/path
               {:d    "M93.9676 39.0409C96.393 38.4038 97.8624 35.9116 97.0079 33.5539C95.2932 28.8227 92.871 24.3692 89.8167 20.348C85.8452 15.1192 80.8826 10.7238 75.2124 7.41289C69.5422 4.10194 63.2754 1.94025 56.7698 1.05124C51.7666 0.367541 46.6976 0.446843 41.7345 1.27873C39.2613 1.69328 37.813 4.19778 38.4501 6.62326C39.0873 9.04874 41.5694 10.4717 44.0505 10.1071C47.8511 9.54855 51.7191 9.52689 55.5402 10.0491C60.8642 10.7766 65.9928 12.5457 70.6331 15.2552C75.2735 17.9648 79.3347 21.5619 82.5849 25.841C84.9175 28.9121 86.7997 32.2913 88.1811 35.8758C89.083 38.2158 91.5421 39.6781 93.9676 39.0409Z",
                :fill "currentFill"}))
    (dom/span :.sr-only "Loading...")))

(def ui-loading (comp/factory UILoading))

(defsc UILoadingScreen
  [this {:keys [dim?] :or {dim? true}}]
  (div :.flex.justify-center.items-center.h-screen.fixed.top-0.left-0.right-0.bottom-0.w-full.z-50.overflow-hidden
    {:className (when dim? "bg-gray-100 opacity-75")}
    (ui-loading {})))

(def ui-loading-screen (comp/factory UILoadingScreen))

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
    :or   {id-key identity, display-key identity, sort? true}}
   {:keys [onChange onEnterKey sort-fn]}]
  (let [all-options (if (and value (id-key value) (not (some #(= (id-key value) (id-key %)) options)))
                      (conj options value) options)
        sorted-options (vec (if-not sort? all-options (sort-by (or sort-fn display-key) all-options)))
        default-value (or default-value (when (str/blank? no-selection-string) (first sorted-options)))]
    (when (and onChange default-value (nil? value))
      (onChange default-value))
    (div
      (when label (ui-label {:for name :label label}))
      (dom/select :.mt-1.block.pl-3.pr-10.py-2.text-base.border-gray-300.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm.rounded-md
                  {:name      name
                   :disabled  disabled?
                   :value     (str (id-key value))
                   :onKeyDown #(when (and onEnterKey (evt/enter-key? %)) (onEnterKey))
                   :onChange  #(when onChange
                                 (let [idx (-> % .-target .-selectedIndex)]
                                   (if (and no-selection-string (= 0 idx))
                                     (onChange nil)
                                     (onChange (get sorted-options (if no-selection-string (- idx 1) idx))))))}
                  (when no-selection-string (dom/option :.py-1 {:value nil :id "none"} no-selection-string))
                  (for [option sorted-options
                        :let [id (id-key option)]]
                    (dom/option :.py-1 {:key id :value (str id)} (display-key option)))))))

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

(defsc UITitleButton [this {:keys [title]} {:keys [onClick]}]
  (dom/button
    :.inline-flex.items-center.justify-center.rounded-md.border.border-transparent.bg-indigo-600.px-4.py-2.text-sm.font-medium.text-white.shadow-sm.hover:bg-indigo-700.focus:outline-none.focus:ring-2.focus:ring-indigo-500.focus:ring-offset-2.sm:w-auto
    {:type    "button"
     :onClick onClick}
    title))

(def ui-title-button (comp/computed-factory UITitleButton))

(defsc UITitle [this {:keys [key title subtitle]}]
  (let [content (comp/children this)]
    (dom/div
      :.sm:flex.sm:items-center.p-4 {:key key}
      (dom/div
        :.sm:flex-auto
        (dom/h1 :.text-xl.font-semibold.text-gray-900 title)
        (when subtitle
          (dom/p
            :.mt-2.text-sm.text-gray-700
            subtitle)))
      (when content
        (dom/div
          :.mt-4.sm:mt-0.sm:ml-16.sm:flex-none
          content)))))

(def ui-title
  "A simple section title. Properties:
  - title    : title
  - subtitle : subtitle"
  (comp/factory UITitle))

(defsc UITable
  [this props]
  (dom/div
    :.flex.flex-col
    (dom/div
      :.-my-2.-mx-4.overflow-x-auto.sm:-mx-6.lg:-mx-8
      (dom/div
        :.inline-block.min-w-full.py-2.align-middle.md:px-6.lg:px-8
        (dom/div
          :.overflow-hidden.shadow.ring-1.ring-black.ring-opacity-5.md:rounded-lg)
        (dom/table
          :.min-w-full.divide-y.divide-gray-200 (comp/children this))))))

(def ui-table (comp/factory UITable))

(defsc UITableHead [this props]
  (dom/thead :.bg-gray-50 (comp/children this)))

(def ui-table-head (comp/factory UITableHead))

(defsc UITableHeading [this props]
  (dom/th :.px-2.py-3.text-left.text-xs.font-semibold.text-gray-900.uppercase.tracking-wider
          (select-keys props [:title])
          (comp/children this)))

(def ui-table-heading (comp/factory UITableHeading))

(defsc UITableBody [this props]
  (dom/tbody :.bg-white
             (comp/children this)))

(def ui-table-body (comp/factory UITableBody))

(defsc UITableRow [this props]
  (dom/tr (comp/children this)))

(def ui-table-row (comp/factory UITableRow))

(defsc UITableCell
  [this props]
  (dom/td :.px-2.py-4.whitespace-nowrap.text-sm.text-gray-500
          (select-keys props [:title])
          (comp/children this)))

(def ui-table-cell (comp/factory UITableCell))

(defsc UIButton [this {:keys [key disabled? role onClick]}]
  (dom/button :.w-full.inline-flex.justify-center.rounded-md.border.shadow-sm.px-4.py-2.text-base.font-medium.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-red-500.sm:ml-3.sm:w-auto.sm:text-sm
    {:key key
     :type      "button"
     :className (case role :primary "border-transparent text-white bg-red-600" "bg-white")
     :classes   [(when disabled? "opacity-50")]
     :disabled  (or disabled? (not onClick))
     :onClick   #(when (and (not disabled?) onClick) (onClick))}
    (comp/children this)))

(def ui-button (comp/factory UIButton {:keyfn :key}))

(defsc UIButtonWithDropdown [this props]
  (let [choices []
        show-menu? (comp/get-state this :show-menu)]
    (div :.absolute.inset-y-0.right-0.flex.items-center.pr-2.sm:static.sm:inset-auto.sm:ml-6.sm:pr-0
      (div :.ml-3.relative
        (div
          (button :.user-menu-button.bg-gray-800.flex.text-sm.rounded-full
            {:type    "button" :aria-expanded "false" :aria-haspopup "true"
             :onClick #(comp/set-state! this {:show-menu show-menu?})}
            (span :.sr-only "Open menu")
            (span :.text-white (div :.flex "User" (icon-chevron-down)))))
        (when show-menu?
          (div :.origin-top-right.absolute.z-50.right-0.mt-2.w-48.rounded-md.shadow-lg.py-1.bg-white.ring-1.ring-black.ring-opacity-5.focus:outline-none
            {:role "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabIndex "-1"}
            (for [item choices]
              (if (:onClick item)
                (a :.block.px-4.py-2.text-sm.text-gray-700.hover:bg-gray-700.hover:text-white.cursor-pointer
                   {:key (:id item) :onClick #(do (comp/set-state! this {:show-menu (not show-menu?)})
                                                  ((:onClick item))) :role "menuitem" :tabIndex "-1"} (:title item))
                (a :.block.px-4.py-2.text-sm.text-gray-700.italic {:key (:id item) :role "menuitem" :tabIndex "-1"} (:title item))))))))))

(def ui-button-with-dropdown (comp/factory UIButtonWithDropdown))

(defsc UIModal [this {:keys [disabled? title actions onClose]}]
  (div :.fixed.z-10.inset-0.overflow-y-auto
    {:aria-labelledby title :role "dialog" :aria-modal "true"
     :className       (when disabled? "hidden")}
    (div :.flex.items-end.justify-center.min-h-screen.pt-4.px-4.pb-20.text-center.sm:block.sm:p-0
      (div :.fixed.inset-0.bg-gray-500.bg-opacity-75.transition-opacity
        {:aria-hidden "true"
         :onClick     #(when onClose (onClose))})
      (span :.hidden.sm:inline-block.sm:align-middle.sm:h-screen {:aria-hidden "true"} "&#8203;")
      (div :.inline-block.align-bottom.bg-white.rounded-lg.px-4.pt-5.pb-4.text-left.overflow-hidden.shadow-xl.transform.transition-all.sm:my-8.sm:align-middle.sm:max-w-screen-sm.lg:max-w-screen-lg.sm:w-full.sm:p-6
        (div
          (div :.mt-3.text-center.sm:mt-5
            (when title (dom/h3 :.modal-title.text-lg.leading-6.font-medium.text-gray-900 title)))
          (div :.mt-2
            (comp/children this)))
        (when (seq actions)
          (div :.mt-5.sm:mt-4.sm:flex.sm:flex-row-reverse
            (for [action actions
                  :when (not (:hidden? action))]
              (ui-button
                {:key       (:id action)
                 :role      (:role action)
                 :disabled? (:disabled? action)
                 :onClick   #(when-let [f (:onClick action)] (f))}
                (:title action)))))))))

(def ui-modal (comp/factory UIModal))


(defsc UISimpleFormTitle [this {:keys [title]}]
  (div :.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
    (div :.mt-1.sm:mt-0.sm:col-span-2
      (div :.w-full.rounded-md.shadow-sm.space-y-2
        (dom/h3 :.text-lg.font-medium.leading-6.text-gray-900 title)))))

(def ui-simple-form-title (comp/factory UISimpleFormTitle))

(defsc UISimpleFormItem [this {:keys [htmlFor label]}]
  (div :.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
    (dom/label :.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:htmlFor htmlFor} label)
    (div :.mt-1.sm:mt-0.sm:col-span-2
      (comp/children this))))

(def ui-simple-form-item (comp/factory UISimpleFormItem))

(defsc UISimpleForm [this params]
  (dom/form :.space-y-8.divide-y.divide-gray-200 {:onSubmit #(.preventDefault %)}
    (div :.space-y-8.divide-y.divide-gray-200.sm:space-y-5
      (div
        (div :.mt-6.sm:mt-5.space-y-6.sm:space-y-5
          (comp/children this))))))

(def ui-simple-form (comp/factory UISimpleForm))