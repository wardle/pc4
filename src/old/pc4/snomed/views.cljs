(ns pc4.snomed.views
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [pc4.snomed.events :as events]
            [pc4.snomed.subs :as subs]
            [pc4.server :as server]
            [pc4.ui :as ui]))


(defn term-for-description
  [{term      :info.snomed.Description/term
    preferred :info.snomed.Concept/preferredDescription}]
  (let [preferred-term (:info.snomed.Description/term preferred)]
    (if (str/blank? preferred-term)
      term
      (if (or (= term preferred-term) (str/includes? term preferred-term))
        term
        (str term " (" preferred-term ")")))))

(defn select-or-autocomplete
  "A flexible select/autocompletion control.
  Parameters:
  - id             : identifier to use
  - label          : label to show
  - value          : currently selected value, if any
  - id-key         : function to get id from a value (e.g. could be a keyword)
  - display-key    : function to get display from value
  - select-display-key : function to get display from value in the select
  - common-choices : collection of common choices to show
  - autocomplete-fn: autocompletion function that takes one parameter
  - clear-fn       : function to run to clear autocompletion, if required
  - select-fn      : function to be called with a selected id
  - minimum-chars  : minimum number of characters needed to run autocompletion
  - autocomplete-results - results of autocompletion
  - placeholder    : placeholder text for autocompletion
  - no-selection-string : label for select when nothing selected
  - size           : size of the select box, default 5
  - disabled?      : if disabled"
  [{:keys [id clear-fn]}]
  (when clear-fn (clear-fn))
  (let [mode (r/atom nil)]
    (fn [& {:keys [label value id-key display-key select-display-key common-choices autocomplete-fn
                   clear-fn autocomplete-results select-fn placeholder
                   minimum-chars no-selection-string default-value size disabled?]
            :or   {minimum-chars 3 id-key identity display-key identity size 5}}]
      [:<>
       (when label [ui/ui-label :label label])
       (cond
         (and (= :select (or @mode :select)) (or value (seq common-choices)))
         (let [value-in-choices? (some #(= % value) common-choices)
               all-choices (if (and value (not value-in-choices?)) (conj common-choices value) common-choices)
               choices (zipmap (map id-key all-choices) all-choices)
               sorted-choices (sort-by display-key (vals choices))]
           (when (and default-value (str/blank? value))
             (select-fn default-value))
           [:<>
            [:select.block.border.p-3.bg-white.rounded.outline-none.w-full
             {:disabled  disabled?, :value     (str (id-key value))
              :on-change #(when select-fn
                            (let [idx (-> % .-target .-selectedIndex)]
                              (if (and no-selection-string (= 0 idx))
                                (select-fn nil)
                                (select-fn (nth sorted-choices (if no-selection-string (dec idx) idx))))))}
             (when no-selection-string [:option.py-1 {:value nil :id nil} no-selection-string])
             (for [choice sorted-choices]
               (let [id (id-key choice)]
                 [:option.py-1 {:value (str id) :key id} (if select-display-key (select-display-key choice) (display-key choice))]))]
            [:button.bg-blue-400.text-white.text-xs.mt-1.py-1.px-2.rounded-full
             {:disabled disabled? :class (if disabled? "opacity-50" "hover:bg-blue-500")
              :on-click #(reset! mode :autocomplete)} "..."]])
         :else
         [:<>
          [:input.w-full.p-1.block.w-full.px-4.py-1.border.border-gray-300.rounded-md.dark:bg-gray-800.dark:text-gray-300.dark:border-gray-600.focus:border-blue-500.dark:focus:border-blue-500.focus:outline-none.focus:ring
           {:id            id, :type "text"
            :placeholder   placeholder :required true
            :class         ["text-gray-700" "bg-white" "shadow"]
            :default-value nil, :disabled disabled?, :auto-focus true
            :on-change     #(let [s (-> % .-target .-value)]
                              (if (>= (count s) minimum-chars)
                                (autocomplete-fn s)
                                (when clear-fn (clear-fn))))}]
          [:button.bg-blue-400.hover:bg-blue-500.text-white.text-xs.my-1.py-1.px-2.rounded-full
           {:disabled disabled? :on-click #(reset! mode :select)} "Close"]

          [:div.w-full
           [:select.w-full.border.border-gray-300.rounded-md
            {:multiple        false
             :size            size
             :disabled        disabled?
             :on-change       #(when select-fn (tap> autocomplete-results) (select-fn (nth autocomplete-results (-> % .-target .-selectedIndex))))
             :on-double-click #(reset! mode :select)}
            (for [result autocomplete-results]
              (let [id (id-key result)]
                [:option {:value result :key id}
                 (display-key result)]))]]])])))

(defn select-snomed
  "A general purpose SNOMED select and autocomplete control."
  [& {:keys [id label value max-hits constraint select-fn common-choices placeholder size]
      :or   {max-hits 200 common-choices [] size 5} :as params}]
  (when-not (and id constraint select-fn)
    (throw (ex-info "missing parameter(s)" {:id id :constraint constraint :select-fn select-fn})))
  [:<>
   [select-or-autocomplete
    {:label label
     :id id
     :value value
     :id-key :info.snomed.Description/id
     :display-key term-for-description
     :select-display-key #(get-in % [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])
     :no-selection-string "-Choose-"
     :common-choices common-choices
     :autocomplete-fn (server/debounce #(rf/dispatch [::events/search id {:s                  %
                                                                          :constraint         constraint
                                                                          :max-hits           max-hits
                                                                          :remove-duplicates? true
                                                                          :fallback-fuzzy     2}]) 200)
     :autocomplete-results @(rf/subscribe [::subs/search-results id])
     :clear-fn #(rf/dispatch [::events/clear-search-results id])
     :select-fn select-fn
     :minimum-chars 2
     :placeholder placeholder
     :size size}]])