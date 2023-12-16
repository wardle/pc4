(ns pc4.ui.snomed
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [clojure.string :as str]
    [goog.functions :as gf]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [pc4.ui.core :as ui]))

(defn autocomplete-ident
  "Returns the ident for an autocomplete control. Can be passed a map of props, or a raw ID."
  [id-or-props]
  (if (map? id-or-props)
    [:autocomplete/by-id (:db/id id-or-props)]
    [:autocomplete/by-id id-or-props]))

(defsc SelectOption
  [this {:info.snomed.Concept/keys [id preferredDescription]}]
  {:ident :info.snomed.Concept/id
   :query [:info.snomed.Concept/id
           {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
  (dom/option {:value id} (:info.snomed.Description/term preferredDescription)))

(def ui-select-option (comp/factory SelectOption))

(defsc Select
  [this {id :db/id :autocomplete/keys [selected options] :as props} {:keys [onSelect]}]
  {:query         [:db/id
                   :autocomplete/selected
                   {:autocomplete/options (comp/query SelectOption)}]
   :ident         (fn [] (autocomplete-ident props))
   :initial-state (fn [{:keys [id]}] {:db/id id :autocomplete/selected nil :autocomplete/options []})}
  (let [options' (conj (set options) selected)]
    (dom/div :.w-full
      (dom/select
        {:value    selected
         :onChange #(when onSelect (onSelect (evt/target-value %)))}
        (map ui-select-option options')))))

(def ui-select (comp/computed-factory Select))

(defsc CompletionList
  [this {:keys [value values idKey displayPropertyKey size] :or {size 8}}
   {:keys [onValueSelect onDoubleClick]}]
  (let [values# (reduce (fn [acc v] (assoc acc (str (idKey v)) v)) {} values)] ;; generate a lookup map
    (dom/select
      {:className     (when (> size 1) "bg-none")
       :value         (if value (str (idKey value)) "")
       :size          size
       :onChange      #(when onValueSelect (onValueSelect (get values# (evt/target-value %))))
       :onKeyDown     #(when (and onDoubleClick (or (evt/enter-key? %) (evt/is-key? 32 %)) (onDoubleClick)))
       :onDoubleClick #(when onDoubleClick (onDoubleClick))}
      (for [v values]
        (dom/option {:key   (str (idKey v))
                     :value (str (idKey v))}
                    (displayPropertyKey v))))))

(def ui-completion-list (comp/computed-factory CompletionList))

(defsc Synonyms [this {:info.snomed.Concept/keys [id synonyms]}]
  {:ident :info.snomed.Concept/id
   :query (fn [] [:info.snomed.Concept/id
                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/id :info.snomed.Description/term]}
                  {(list :info.snomed.Concept/synonyms {:accept-language "en-GB"}) [:info.snomed.Description/id :info.snomed.Description/term]}])}
  (dom/div :.text-gray-600.text-sm.italic.pl-4
    (dom/ul
      (map (fn [{:info.snomed.Description/keys [id term]}]
             (dom/li {:key id} term)) (sort-by :info.snomed.Description/term synonyms)))))

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

(defn clear-selected* [state path]
  (let [selected-path (conj path :autocomplete/selected)
        selected-synonyms-path (conj path :autocomplete/selected-synonyms)]
    (swap! state assoc-in selected-path nil)
    (swap! state assoc-in selected-synonyms-path nil)))

(m/defmutation clear-selected
  [{:keys [id]}]
  (action [{:keys [state]}]
          (let [autocomplete-path (autocomplete-ident id)]
            (clear-selected* state autocomplete-path))))

(defmutation reset-autocomplete
  [{:keys [id]}]
  (action [{:keys [state]}]
          (let [autocomplete-path (autocomplete-ident id)]
            (clear-selected* state autocomplete-path)
            (swap! state assoc-in (conj autocomplete-path :autocomplete/stringValue) "")
            (swap! state assoc-in (conj autocomplete-path :autocomplete/suggestions) [])
            (swap! state assoc-in (conj autocomplete-path :autocomplete/loaded-suggestions) []))))


(def default-search-params
  {:accept-language "en-GB" :max-hits 500 :remove-duplicates? true :fuzzy 0 :fallback-fuzzy 2})

(def get-suggestions
  "A debounced function that will trigger a load of the server suggestions into a temporary locations and fire
   a post mutation when that is complete to move them into the main UI view."
  (letfn [(load-suggestions [comp id params]
            (df/load! comp :info.snomed.Search/search nil
                      {:params               (merge default-search-params params)
                       :marker               false
                       :post-mutation        `populate-loaded-suggestions
                       :post-mutation-params {:id id}
                       :target               (conj (autocomplete-ident id) :autocomplete/loaded-suggestions)}))]
    (gf/debounce load-suggestions 200)))



(defsc Autocomplete
  [this {id :db/id :autocomplete/keys [suggestions stringValue selected selected-synonyms] :as props}
   {:keys [onSelect onSave autoFocus label placeholder constraint] :or {autoFocus false placeholder ""}}]
  {:query         [:db/id                                   ; the component's ID
                   :autocomplete/loaded-suggestions         ; A place to do the loading, so we can prevent flicker in the UI
                   :autocomplete/suggestions                ; the current completion suggestions
                   :autocomplete/selected                   ; the currently selected option
                   {:autocomplete/selected-synonyms (comp/get-query Synonyms)} ; synonyms are lazily loaded when the user selects an option
                   :autocomplete/stringValue]               ; the current user-entered value
   :ident         (fn [] (autocomplete-ident props))
   :initial-state (fn [{:keys [id]}] {:db/id                    id
                                      :autocomplete/suggestions []
                                      :autocomplete/stringValue ""})}
  (let [field-id (str "autocomplete-" id)                   ; for html label/input association
        onSelect' (fn [v]
                    (m/set-value! this :autocomplete/selected v)
                    (df/load! this [:info.snomed.Concept/id (:info.snomed.Concept/id v)] Synonyms {:target (conj (autocomplete-ident props) :autocomplete/selected-synonyms)})
                    (when onSelect (onSelect v)))
        _ (tap> {:autocomplete suggestions})]
    (comp/fragment
      (dom/div
        (when label (dom/label {:htmlFor field-id} label))
        (dom/input :.w-full.p-1.block.border.rounded-md.border-gray-300.shadow-sm
          {:id          field-id
           :placeholder placeholder
           :value       stringValue
           :autoFocus   autoFocus
           :onKeyDown   (fn [evt] (when (and selected onSave (evt/enter-key? evt))
                                    (onSave selected)))
           :onChange    (fn [evt]
                          (let [new-value (evt/target-value evt)]
                            (if (>= (.-length new-value) 2) ; avoid autocompletion until they've typed a couple of letters
                              (get-suggestions this id {:s new-value :constraint constraint})
                              (m/set-value! this :autocomplete/suggestions [])) ; if they shrink the value too much, clear suggestions
                            (comp/transact! this [(clear-selected {:id id})])
                            (m/set-value! this :autocomplete/selected nil) ;clear selection
                            (m/set-value! this :autocomplete/selected-synonyms nil)
                            (m/set-string! this :autocomplete/stringValue :value new-value)))})) ; always update the input itself (controlled)
      (dom/div :.grid.grid-cols-1
        (ui-completion-list {:value              selected
                             :values             suggestions
                             :idKey              :info.snomed.Description/id
                             :displayPropertyKey (fn [result]
                                                   (let [term (:info.snomed.Description/term result)
                                                         preferred (get-in result [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
                                                     (if (= term preferred) term (str term " (" preferred ")"))))}
                            {:onValueSelect onSelect'
                             :onDoubleClick #(when (and selected onSave) (onSave selected))})
        (when selected
          (dom/div :.p-4.border-2.shadow-inner
            (let [term (:info.snomed.Description/term selected)
                  preferred (get-in selected [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
              (comp/fragment
                (when selected-synonyms
                  (dom/span :.font-bold preferred
                            (ui-synonyms selected-synonyms)))
                (dom/div :.grid.grid-cols-1.mt-4
                  (pc4.ui.core/ui-button {:onClick #(when onSave (onSave selected))} "Save"))))))))))

(def ui-autocomplete (comp/computed-factory Autocomplete))

(defsc AutocompleteRoot [this {:keys [airport-input]}]
  {:initial-state (fn [p] {:airport-input (comp/get-initial-state Autocomplete {:id :airports})})
   :query         [{:airport-input (comp/get-query Autocomplete)}]}
  (dom/div
    (dom/h4 "Airport Autocomplete")
    (ui-autocomplete airport-input)))