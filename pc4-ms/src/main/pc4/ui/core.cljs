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
                  (when no-selection-string (dom/option :.py-1 {:value nil :id "none"} no-selection-string))
                  (println "options:" sorted-options)
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