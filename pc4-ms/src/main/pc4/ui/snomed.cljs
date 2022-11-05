(ns pc4.ui.snomed
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [clojure.string :as str]
    [goog.functions :as gf]
    [com.fulcrologic.fulcro.dom.events :as evt]))

(defn autocomplete-ident
  "Returns the ident for an autocomplete control. Can be passed a map of props, or a raw ID."
  [id-or-props]
  (if (map? id-or-props)
    [:autocomplete/by-id (:db/id id-or-props)]
    [:autocomplete/by-id id-or-props]))

(defsc CompletionList [this {:keys [value values onValueSelect idKey displayPropertyKey size] :or {size 10}}]
  (tap> {:completion-list values})
  (let [values' (reduce (fn [acc v] (assoc acc (str (idKey v)) v)) {} values)]
    (dom/select :.bg-none
      {:value    (when value (str (idKey value)))
       :size     size
       :onChange #(do (println "Selecting" (evt/target-value %))
                      (onValueSelect (get values' (evt/target-value %))))}
      (map (fn [v]
             (dom/option {:key (str (idKey v)) :value (str (idKey v))} (displayPropertyKey v))) values))))

(def ui-completion-list (comp/factory CompletionList))


(defsc Synonyms [this {:info.snomed.Concept/keys [id synonyms]}]
  {:ident :info.snomed.Concept/id
   :query [:info.snomed.Concept/id
           {:info.snomed.Concept/preferredDescription [:info.snomed.Description/id :info.snomed.Description/term]}
           {:info.snomed.Concept/synonyms [:info.snomed.Description/id :info.snomed.Description/term :info.snomed.Description/typeId]}]}
  (dom/div :.text-gray-600.text-sm.italic.pl-4
    (dom/ul
      (map (fn [{:info.snomed.Description/keys [id term]}]
             (dom/li {:key id} term)) synonyms))))

(def ui-synonyms (comp/factory Synonyms))

(m/defmutation populate-loaded-suggestions
  "Mutation: Autocomplete suggestions are loaded in a non-visible property to prevent flicker. This is
  used as a post mutation to move them to the active UI field so they appear."
  [{:keys [id]}]
  (action [{:keys [app state]}]
          (let [autocomplete-path (autocomplete-ident id)
                source-path (conj autocomplete-path :autocomplete/loaded-suggestions)
                target-path (conj autocomplete-path :autocomplete/suggestions)
                results (get-in @state source-path)]
            (swap! state assoc-in target-path results)
            (when (seq results)
              (swap! state assoc-in (conj autocomplete-path :autocomplete/selected) (first results))
              (df/load! app [:info.snomed.Concept/id (:info.snomed.Concept/id (first results))] Synonyms {:target (conj (autocomplete-ident id) :autocomplete/selected-synonyms)})))))

(m/defmutation clear-selected
  [{:keys [id]}]
  (action [{:keys [state]}]
          (let [autocomplete-path (autocomplete-ident id)
                selected-path (conj autocomplete-path :autocomplete/selected)
                selected-synonyms-path (conj autocomplete-path :autocomplete/selected-synonyms)]
            (swap! state assoc-in selected-path nil)
            (swap! state assoc-in selected-synonyms-path nil))))

(def get-suggestions
  "A debounced function that will trigger a load of the server suggestions into a temporary locations and fire
   a post mutation when that is complete to move them into the main UI view."
  (letfn [(load-suggestions [comp new-value id]
            (df/load! comp :info.snomed.Search/search nil
                      {:params               {:s new-value :max-hits 500 :remove-duplicates? true :fuzzy 0 :fallback-fuzzy 2}
                       :marker               false
                       :post-mutation        `populate-loaded-suggestions
                       :post-mutation-params {:id id}
                       :target               (conj (autocomplete-ident id) :autocomplete/loaded-suggestions)}))]
    (gf/debounce load-suggestions 400)))

(defsc Autocomplete
  [this
   {id :db/id :autocomplete/keys [suggestions stringValue selected selected-synonyms] :as props}
   {:keys [onSelect autoFocus] :or {autoFocus "false"}}]
  {:query         [:db/id                                   ; the component's ID
                   :autocomplete/loaded-suggestions         ; A place to do the loading, so we can prevent flicker in the UI
                   :autocomplete/suggestions                ; the current completion suggestions
                   :autocomplete/selected                   ; the currently selected option
                   {:autocomplete/selected-synonyms (comp/query Synonyms)} ; synonyms are lazily loaded when the user selects an option
                   :autocomplete/stringValue]               ; the current user-entered value
   :ident         (fn [] (autocomplete-ident props))
   :initial-state (fn [{:keys [id]}] {:db/id id :autocomplete/suggestions [] :autocomplete/stringValue ""})}
  (let [field-id (str "autocomplete-" id)                   ; for html label/input association
        onSelect' (fn [v]
                    (m/set-value! this :autocomplete/selected v)
                    (df/load! this [:info.snomed.Concept/id (:info.snomed.Concept/id v)] Synonyms {:target (conj (autocomplete-ident props) :autocomplete/selected-synonyms)})
                    (when onSelect (onSelect v)))
        _ (tap> {:autocomplete suggestions})]
    (comp/fragment
      (dom/div
        (dom/label {:htmlFor field-id} "Enter search term: ")
        (dom/input :.w-full.caret-black.p-1
          {:id        field-id
           :value     stringValue
           :autoFocus autoFocus
           :onChange  (fn [evt]
                        (let [new-value (evt/target-value evt)]
                          (if (>= (.-length new-value) 2)   ; avoid autocompletion until they've typed a couple of letters
                            (get-suggestions this new-value id)
                            (m/set-value! this :autocomplete/suggestions [])) ; if they shrink the value too much, clear suggestions
                          (comp/transact! this [(clear-selected {:id id})])
                          (m/set-value! this :autocomplete/selected nil) ;clear selection
                          #_(m/set-value! this :autocomplete/selected-synonyms nil)
                          (m/set-string! this :autocomplete/stringValue :value new-value)))})) ; always update the input itself (controlled)
      (dom/div :.grid.grid-cols-1
        (ui-completion-list {:value selected :values suggestions :onValueSelect onSelect' :idKey :info.snomed.Description/id :displayPropertyKey :info.snomed.Description/term})
        (when selected
          (dom/div :.p-4.border-2.shadow-inner
            (let [term (:info.snomed.Description/term selected)
                  preferred (get-in selected [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
              (dom/span :.font-bold (:info.snomed.Description/term selected)
                (when-not (= term preferred)
                  (dom/span :.font-bold " (\"" preferred "\")"))
                (ui-synonyms selected-synonyms)))))))))

(def ui-autocomplete (comp/computed-factory Autocomplete))

(defsc AutocompleteRoot [this {:keys [airport-input]}]
  {:initial-state (fn [p] {:airport-input (comp/get-initial-state Autocomplete {:id :airports})})
   :query         [{:airport-input (comp/get-query Autocomplete)}]}
  (dom/div
    (dom/h4 "Airport Autocomplete")
    (ui-autocomplete airport-input)))