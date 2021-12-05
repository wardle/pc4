(ns eldrix.pc4-ward.snomed.views
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.snomed.events :as events]
            [eldrix.pc4-ward.snomed.subs :as subs]
            [com.eldrix.pc4.commons.debounce :as debounce]
            [eldrix.pc4-ward.refer.views]
            [clojure.string :as str]
            [eldrix.pc4-ward.ui :as ui]))


(defn term-for-description
  [{term      :info.snomed.Description/term
    preferred :info.snomed.Concept/preferredDescription}]
  (let [preferred-term (:info.snomed.Description/term preferred)]
    (if (str/blank? preferred-term)
      term
      (if (or (= term preferred-term) (str/includes? term preferred-term))
        term
        (str term " (" preferred-term ")")))))

(defn select-snomed
  "A general purpose SNOMED select and autocomplete control."
  [& {:keys [id label value max-hits constraint select-fn common-choices placeholder size]
      :or   {max-hits 200 common-choices [] size 5} :as params}]
  (when-not (and id constraint select-fn)
    (throw (ex-info "missing parameter(s)" {:id id :constraint constraint :select-fn select-fn})))
  [:<>
   [ui/select-or-autocomplete
    :label                label
     :id                   id
     :value                value
     :id-key               :info.snomed.Description/id
     :display-key          term-for-description
     :select-display-key   #(get-in % [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])
     :no-selection-string  "-Choose-"
     :common-choices       common-choices
     :autocomplete-fn      (debounce/debounce #(rf/dispatch [::events/search id {:s              %
                                                                                 :constraint     constraint
                                                                                 :max-hits       max-hits
                                                                                 :fallback-fuzzy 2}]) 200)
     :autocomplete-results @(rf/subscribe [::subs/search-results id])
     :clear-fn             #(rf/dispatch [::events/clear-search-results id])
     :select-fn            select-fn
     :minimum-chars        2
     :placeholder          placeholder
     :size                 size]])