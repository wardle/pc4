(ns pc4.ui.snomed
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [clojure.string :as str]
    [goog.functions :as gf]))


(defn autocomplete-ident
  "Returns the ident for an autocomplete control. Can be passed a map of props, or a raw ID."
  [id-or-props]
  (if (map? id-or-props)
    [:autocomplete/by-id (:db/id id-or-props)]
    [:autocomplete/by-id id-or-props]))

(defsc CompletionList [this {:keys [values onValueSelect]}]
  (tap> {:completion-list values})
  (dom/select {:size 10}
    (map (fn [v]
           (dom/option {:value (str (:info.snomed.Description/id v))}
             (:info.snomed.Description/term v ))) values)))

(def ui-completion-list (comp/factory CompletionList))

(m/defmutation populate-loaded-suggestions
  "Mutation: Autocomplete suggestions are loaded in a non-visible property to prevent flicker. This is
  used as a post mutation to move them to the active UI field so they appear."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (let [autocomplete-path (autocomplete-ident id)
                source-path       (conj autocomplete-path :autocomplete/loaded-suggestions)
                target-path       (conj autocomplete-path :autocomplete/suggestions)]
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

(defsc Autocomplete [this {:keys [db/id autocomplete/suggestions autocomplete/value] :as props}]
  {:query         [:db/id                                   ; the component's ID
                   :autocomplete/loaded-suggestions         ; A place to do the loading, so we can prevent flicker in the UI
                   :autocomplete/suggestions                ; the current completion suggestions
                   :autocomplete/value]                     ; the current user-entered value
   :ident         (fn [] (autocomplete-ident props))
   :initial-state (fn [{:keys [id]}] {:db/id id :autocomplete/suggestions [] :autocomplete/value ""})}
  (let [field-id             (str "autocomplete-" id)       ; for html label/input association
        ;; server gives us a few, and as the user types we need to filter it further.
        filtered-suggestions suggestions #_(when (vector? suggestions)
                                             (filter #(str/includes? (str/lower-case (:term %)) (str/lower-case value)) suggestions))
        ; We want to not show the list if they've chosen something valid
        exact-match?         (and (= 1 (count filtered-suggestions)) (= value (first filtered-suggestions)))
        ; When they select an item, we place it's value in the input
        onSelect             (fn [v] (m/set-string! this :autocomplete/value :value v))
        _ (tap> {:autocomplete suggestions})]
    (comp/fragment
      (dom/div
        (dom/label {:htmlFor field-id} "Enter search term: ")
        (dom/input {:id       field-id
                    :value    value
                    :onChange (fn [evt]
                                (let [new-value (.. evt -target -value)]
                                  ; we avoid even looking for help until they've typed a couple of letters
                                  (if (>= (.-length new-value) 2)
                                    (get-suggestions this new-value id)
                                    ; if they shrink the value too much, clear suggestions
                                    (m/set-value! this :autocomplete/suggestions []))
                                  ; always update the input itself (controlled)
                                  (m/set-string! this :autocomplete/value :value new-value)))}))
      ; show the completion list when it exists and isn't just exactly what they've chosen
      (dom/div
        (when (and (vector? suggestions) (seq suggestions) (not exact-match?)))
        (ui-completion-list {:values filtered-suggestions :onValueSelect onSelect})))))

(def ui-autocomplete (comp/factory Autocomplete))

(defsc AutocompleteRoot [this {:keys [airport-input]}]
  {:initial-state (fn [p] {:airport-input (comp/get-initial-state Autocomplete {:id :airports})})
   :query         [{:airport-input (comp/get-query Autocomplete)}]}
  (dom/div
    (dom/h4 "Airport Autocomplete")
    (ui-autocomplete airport-input)))