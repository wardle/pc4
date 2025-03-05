(ns pc4.http-server.controllers.snomed
  (:require
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.http-server.ui :as ui]
    [pc4.http-server.web :as web]
    [pc4.log.interface :as log]
    [pc4.snomedct.interface :as sct]
    [rum.core :as rum]))

(rum/defc ui-select*
  "HTML SELECT for SNOMED CT concept.
   - selected-concept : a map representing currently selected concept
   - common-concepts  : a collection of maps representing common concepts.
   Each 'item' should be a map of the form:
   { :info.snomed.Concept/id xxx
     :info.snomed.Concept/preferredDescription
          {:info.snomed.Description/term \"xxx\"}}"
  [{:keys [id disabled name selected-concept common-concepts other]}]
  (let [id# (or id name)
        select-id (str id# "-select")
        selected-concept-id (:info.snomed.Concept/id selected-concept)
        concept-ids (into #{} (map :info.snomed.Concept/id) common-concepts) ;; need to find out whether selected is already in our common concept list
        options (if (or (some? selected-concept) (concept-ids selected-concept-id)) common-concepts (conj common-concepts selected-concept))]
    [:div.mt-2 {:id select-id}
     [:div.grid.grid-cols-1
      [:select.col-start-1.row-start-1.w-full.appearance-none.rounded-md.bg-white.py-1.5.pl-3.pr-8.text-base.outline.outline-1.-outline-offset-1.outline-gray-300.focus:outline.focus:outline-2.focus:-outline-offset-2.focus:outline-indigo-600
       (cond-> {:name  (or name id)
                :class (if disabled ["bg-gray-100" "text-gray-600"] ["bg-white" "text-gray-800"])}
         disabled (assoc :disabled "disabled"))
       (for [{:info.snomed.Concept/keys [id preferredDescription] :as option} (sort-by (comp :info.snomed.Description/term :info.snomed.Concept/preferredDescription) options)
             :let [preferredTerm (:info.snomed.Description/term preferredDescription)]]
         (if (and selected-concept-id (= id selected-concept-id))
           [:option {:value id :selected "selected"} preferredTerm]
           [:option {:value id} preferredTerm]))]
      [:svg.pointer-events-none.col-start-1.row-start-1.mr-2.size-5.self-center.justify-self-end.text-gray-500.sm:size-4 {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true" :data-slot "icon"}
       [:path {:fill-rule "evenodd" :d "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z" :clip-rule "evenodd"}]]]
     (when (and other (not disabled))
       [:button.w-full.inline-flex.justify-center.rounded-md.border.shadow-sm.px-4.py-2.text-base.font-medium.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-red-500.sm:w-auto.sm:text-sm
        (merge {:type "button"} other)
        "Other..."])]))

(rum/defc ui-autocomplete*
  "A SNOMED autocomplete control based on a textfield.
  The chosen result will be 'selected' using the 'name' parameter.
  During the lifecycle, 'name'-search will be used as the 'name' of the search
  box, and 'name'-results will be used as the 'name' of the results select box."
  [{:keys [id name ecl max-hits size] :or {max-hits 512 size 5}}]
  (let [id# (or id name)
        autocomplete-id (str id# "-autocomplete")
        search-id (str id# "-search")
        results-id (str id# "-results")
        selected-id (str id# "-selected")]
    [:div.mt-2 {:id autocomplete-id}
     (ui/ui-textfield* {:name       search-id
                        :hx-post    (route/url-for :snomed/autocomplete-results)
                        :hx-target  (str "#" results-id)
                        :hx-trigger "input changed delay:500ms, keyup[key=='Enter'], load"
                        :hx-vals    (json/write-str {:data (prn-str {:name search-id :ecl ecl :max-hits max-hits :size size :selected-id selected-id})})})
     [:div {:id results-id}]]))

(rum/defc ui-select-autocomplete
  "A combined HTML SELECT and autocomplete for SNOMED CT."
  [{:keys [id autocompleting disabled name selected-concept common-concepts ecl size] :as params}]
  (let [id# (or id name)]
    [:div {:id id#}                                         ;; this can get replaced dynamically
     (if-not autocompleting
       (ui-select*
         (assoc params
           :other {:hx-post   (route/url-for :snomed/autocomplete)
                   :hx-target (str "#" id#)
                   :hx-vals   (json/write-str {"data" (prn-str {:autocompleting   true
                                                                :name             name
                                                                :id               id
                                                                :disabled         disabled
                                                                :selected-concept selected-concept
                                                                :common-concepts  common-concepts
                                                                :ecl              ecl
                                                                :size             size})})}))
       (ui-autocomplete* params))]))


(defn autocomplete
  [{:keys [params env]}]
  (let [data (edn/read-string (get params "data"))]
    (log/debug "autocomplete" params)
    (log/debug "autocomplete" data)
    (web/ok
      (web/render
        (ui-select-autocomplete data)))))

(defn autocomplete-results
  [{:keys [params env]}]
  (log/debug "autocomplete-results" params)
  (let [hermes (:hermes env)
        {:keys [name ecl max-hits selected-id size]} (edn/read-string (get params "data"))
        s (get params name)                                 ;; our data tells us the name of the form value to use
        results (when-not (str/blank? s) (sct/search hermes {:s s :constraint ecl :max-hits max-hits :fuzzy 0 :fallback-fuzzy 2}))]
    (log/debug "autocomplete" {:s s :ecl ecl :max-hits max-hits})
    (web/ok
      (web/render
        [:div.mt-2.grid.grid-cols-1
         (when (seq results)
           [:select {:hx-get     (route/url-for :snomed/result)
                     :hx-trigger "change"
                     :hx-target  (str "#" selected-id)
                     :name       "selected"
                     :size       (or size 5)}
            (for [result results]
              [:option {:value (.-conceptId result)}
               (let [term (.-term result), preferred-term (.-preferredTerm result)]
                 (if (= term preferred-term)
                   term
                   (str term " (" preferred-term ")")))])])
         [:div {:id selected-id}]]))))

(defn result
  [{:keys [params env]}]
  (let [hermes (:hermes env)
        concept-id (some-> (:selected params) parse-long)
        lang-refset-ids (sct/match-locale hermes "en-GB")   ;; TODO: use user's chosen locale
        preferred (when concept-id (:term (sct/preferred-synonym* hermes concept-id lang-refset-ids)))
        synonyms (when concept-id (sct/synonyms hermes concept-id lang-refset-ids))]
    (log/debug "inspect result" concept-id " " preferred)
    (web/ok
      (web/render
        (when concept-id
          [:div.p-4.border-2.shadow-inner
           [:span.font-bold preferred]
           [:div.text-gray-600.text-sm.italic.pl-4
            [:ul
             (for [{:keys [term]} (sort-by :term synonyms)
                   :when (not= term preferred)]
               [:li term])]]])))))