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

(defsc CompletionList [this {:keys [values onValueSelect idKey displayPropertyKey size] :or {size 10}}]
  (tap> {:completion-list values})
  (let [values' (reduce (fn [acc v] (assoc acc (str (idKey v)) v)) {} values)]
    (dom/select {:size size :onChange #(do (println "Selecting" (evt/target-value %))
                                           (onValueSelect (get values' (evt/target-value %))))}
      (map (fn [v]
             (dom/option {:key (str (idKey v)) :value (str (idKey v))} (displayPropertyKey v))) values))))

(def ui-completion-list (comp/factory CompletionList))

(m/defmutation populate-loaded-suggestions
  "Mutation: Autocomplete suggestions are loaded in a non-visible property to prevent flicker. This is
  used as a post mutation to move them to the active UI field so they appear."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (let [autocomplete-path (autocomplete-ident id)
                source-path (conj autocomplete-path :autocomplete/loaded-suggestions)
                target-path (conj autocomplete-path :autocomplete/suggestions)]
            (swap! state assoc-in target-path (get-in @state source-path)))))

(def get-suggestions
  "A debounced function that will trigger a load of the server suggestions into a temporary locations and fire
   a post mutation when that is complete to move them into the main UI view."
  (letfn [(load-suggestions [comp new-value id]
            (df/load! comp :info.snomed.Search/search nil
                      {:params               {:s new-value :max-hits 250}
                       :marker               false
                       :post-mutation        `populate-loaded-suggestions
                       :post-mutation-params {:id id}
                       :target               (conj (autocomplete-ident id) :autocomplete/loaded-suggestions)}))]
    (gf/debounce load-suggestions 200)))

(defsc Autocomplete [this {:keys [db/id autocomplete/suggestions autocomplete/stringValue autocomplete/selected] :as props} {:keys [onSelect]}]
  {:query         [:db/id                                   ; the component's ID
                   :autocomplete/loaded-suggestions         ; A place to do the loading, so we can prevent flicker in the UI
                   :autocomplete/suggestions                ; the current completion suggestions
                   :autocomplete/selected                   ; the currently selected option
                   :autocomplete/stringValue]               ; the current user-entered value
   :ident         (fn [] (autocomplete-ident props))
   :initial-state (fn [{:keys [id]}] {:db/id id :autocomplete/suggestions [] :autocomplete/stringValue ""})}
  (let [field-id (str "autocomplete-" id)                   ; for html label/input association
        onSelect' (fn [v]
                    (m/set-value! this :autocomplete/selected v)
                    (when onSelect (onSelect v)))
        _ (tap> {:autocomplete suggestions})]
    (comp/fragment
      (dom/div
        (dom/label {:htmlFor field-id} "Enter search term: ")
        (dom/input {:id       field-id
                    :value    stringValue
                    :onChange (fn [evt]
                                (let [new-value (.. evt -target -value)]
                                  (if (>= (.-length new-value) 2) ; avoid autocompletion until they've typed a couple of letters
                                    (get-suggestions this new-value id)
                                    (m/set-value! this :autocomplete/suggestions [])) ; if they shrink the value too much, clear suggestions
                                  (m/set-value! this :autocomplete/selected nil) ;clear selection
                                  (m/set-string! this :autocomplete/stringValue :value new-value)))})) ; always update the input itself (controlled)
      (dom/div
        (when (and (> (count suggestions) 0) (not selected))
          (onSelect' (first suggestions)))
        (ui-completion-list {:values suggestions :onValueSelect onSelect' :idKey :info.snomed.Description/id :displayPropertyKey :info.snomed.Description/term})
        (when selected
          (str (:info.snomed.Description/term selected) " (" (get-in selected [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]) ")"))))))

(def ui-autocomplete (comp/computed-factory Autocomplete))

(defsc AutocompleteRoot [this {:keys [airport-input]}]
  {:initial-state (fn [p] {:airport-input (comp/get-initial-state Autocomplete {:id :airports})})
   :query         [{:airport-input (comp/get-query Autocomplete)}]}
  (dom/div
    (dom/h4 "Airport Autocomplete")
    (ui-autocomplete airport-input)))