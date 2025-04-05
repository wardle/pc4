(ns pc4.http-server.controllers.snomed
  (:require
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.http-server.ui :as ui]
    [pc4.http-server.web :as web]
    [pc4.log.interface :as log]
    [pc4.snomedct.interface :as sct]))

(defn make-config
  [{:keys [id disabled name placeholder selected-concept common-concepts ecl max-hits size] :as params}]
  (let [id# (or id name (throw (ex-info "invalid configuration; id or name mandatory" params)))]
    {:id               id#                                  ;; id for whole UI control
     :name             (or name id)
     :placeholder      placeholder
     :disabled         disabled
     :select-id        (str id# "-select")                  ;; id for the SELECT popup
     :autocomplete-id  (str id# "-autocomplete")            ;; id for the textfield autocomplete
     :search-id        (str id# "-search")                  ;; id for the content of the search string
     :results-id       (str id# "-results")                 ;; id for the results of the search
     :selected-id      (str id# "-selected")                ;; id to show the selected result
     :spinner-id       (str id# "-spinner")
     :selected-concept selected-concept
     :common-concepts  common-concepts
     :ecl              (or ecl (throw (ex-info "missing 'ecl' for SNOMED control" params)))
     :max-hits         (or max-hits 512)
     :size             size}))

(defn make-id+term
  [concept-id term]
  (if (and concept-id term)
    (str concept-id "|" term)
    (throw (ex-info "invalid id+term" {:concept-id concept-id :term term}))))

(defn parse-id+term [s]
  (let [[concept-id term] (str/split s #"\|" 2)]
    (vector (parse-long concept-id) term)))

(defn- ui-select*
  "HTML SELECT for SNOMED CT concept.
   - selected-concept : a map representing currently selected concept
   - common-concepts  : a collection of maps representing common concepts.
   Each 'item' should be a map of the form:
   { :info.snomed.Concept/id xxx
     :info.snomed.Concept/preferredDescription
          {:info.snomed.Description/term \"xxx\"}}"
  [{:keys [select-id disabled name selected-concept common-concepts other-action] :as config}]
  (log/debug "ui-select*" config)
  (let [selected-concept-id (:info.snomed.Concept/id selected-concept)
        concept-ids (into #{} (map :info.snomed.Concept/id) common-concepts) ;; need to find out whether selected is already in our common concept list
        options (if (or (nil? selected-concept) (concept-ids selected-concept-id)) common-concepts (conj common-concepts selected-concept))]
    [:div.mt-2 {:id select-id}
     [:div.grid.grid-cols-1
      [:select.col-start-1.row-start-1.w-full.appearance-none.rounded-md.bg-white.py-1.5.pl-3.pr-8.text-base.outline.outline-1.-outline-offset-1.outline-gray-300.focus:outline.focus:outline-2.focus:-outline-offset-2.focus:outline-indigo-600
       (cond-> {:name  name
                :class (if disabled ["bg-gray-100" "text-gray-600"] ["bg-white" "text-gray-800"])}
         disabled (assoc :disabled "disabled"))
       (for [{:info.snomed.Concept/keys [id preferredDescription] :as option} (sort-by (comp :info.snomed.Description/term :info.snomed.Concept/preferredDescription) options)
             :let [preferredTerm (:info.snomed.Description/term preferredDescription)]]
         (if (and selected-concept-id (= id selected-concept-id))
           [:option {:value (make-id+term id preferredTerm) :selected "selected"} preferredTerm]
           [:option {:value (make-id+term id preferredTerm)} preferredTerm]))]
      [:svg.pointer-events-none.col-start-1.row-start-1.mr-2.size-5.self-center.justify-self-end.text-gray-500.sm:size-4 {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true" :data-slot "icon"}
       [:path {:fill-rule "evenodd" :d "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z" :clip-rule "evenodd"}]]]
     (when (and other-action (not disabled))
       [:button.w-full.inline-flex.justify-center.rounded-md.border.shadow-sm.px-4.py-2.text-base.font-medium.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-red-500.sm:w-auto.sm:text-sm
        (merge {:type "button"} other-action)
        "Other..."])]))

(defn- ui-autocomplete*
  "A SNOMED autocomplete control based on a textfield.
  The chosen result will be 'selected' using the 'name' parameter."
  [{:keys [autocomplete-id search-id results-id spinner-id placeholder] :as config}]
  [:div.mt-2 {:id autocomplete-id}
   (ui/ui-textfield* {:name         search-id
                      :autofocus    true
                      :placeholder  (or placeholder "Search")
                      :hx-indicator (str "#" spinner-id)
                      :hx-post      (route/url-for :snomed/autocomplete-results)
                      :hx-target    (str "#" results-id)
                      :hx-trigger   "input changed delay:500ms, keyup[key=='Enter'], load"
                      :hx-vals      (web/write-hx-vals :data config)})
   [:div {:id results-id}]])

(defn- ui-select-autocomplete*
  "A combined HTML SELECT and autocomplete for SNOMED CT."
  [{:keys [id spinner-id selected-concept common-concepts autocompleting] :as config}]
  [:div {:id id}                                            ;; this can get replaced dynamically
   (if (or autocompleting (and (nil? selected-concept) (empty? common-concepts)))
     (ui-autocomplete*
       (assoc config
         :close-action {:hx-post      (route/url-for :snomed/autocomplete)
                        :hx-target    (str "#" id)
                        :hx-indicator (str "#" spinner-id)
                        :hx-vals      (web/write-hx-vals :data (assoc config :autocompleting false))}))
     (ui-select*
       (assoc config
         :other-action {:hx-post      (route/url-for :snomed/autocomplete)
                        :hx-indicator (str "#" spinner-id)
                        :hx-target    (str "#" id)
                        :hx-vals      (web/write-hx-vals :data (assoc config :autocompleting true))}))
     )
   (ui/ui-spinner {:id spinner-id})])

(defn ui-select-autocomplete
  "HTML select/autocomplete user interface control. This can be dropped into any
  form and allows the user to select from common concepts if provided, or choose
  to search using an autocompletion textfield. "
  [{:keys [id name disabled selected-concept common-concepts ecl size max-hits placeholder] :as params}]
  (let [config (make-config params)]
    (when (= name "concept-id")
      (throw (ex-info "invalid 'name' for UI SNOMED control element" {:s name})))
    (log/trace "ui-select-autocomplete" config)
    (ui-select-autocomplete* config)))

(defn autocomplete-handler
  [{:keys [form-params] :as request}]
  (log/debug "autocomplete" form-params)
  (let [concept-id (some-> (:concept-id form-params) parse-long)
        preferred (:preferred-synonym form-params)
        save? (some? (web/hx-trigger request))
        config (web/read-hx-vals :data form-params)]
    (log/debug "trigger" (web/hx-trigger request) "concept id: " concept-id "preferred " preferred)
    (web/ok
      (web/render
        (ui-select-autocomplete*
          (if save?
            (assoc config :selected-concept {:info.snomed.Concept/id concept-id
                                             :info.snomed.Concept/preferredDescription
                                             {:info.snomed.Description/term preferred}})
            config))))))

(defn autocomplete-results-handler
  [{:keys [form-params env]}]
  (log/debug "autocomplete-results" form-params)
  (let [hermes (:hermes env)
        {:keys [search-id ecl max-hits selected-id spinner-id size close-action] :as config} (web/read-hx-vals :data form-params)
        s (get form-params (keyword search-id))             ;; our data tells us the name of the form value to use
        results (when-not (str/blank? s) (sct/search hermes {:s s :constraint ecl :max-hits max-hits :fuzzy 0 :fallback-fuzzy 2}))
        default-result (first results)]
    (log/debug "autocomplete-results" {:s s :ecl ecl :max-hits max-hits})
    (web/ok
      (web/render
        [:div.mt-2.grid.grid-cols-1
         (if (seq results)
           [:select {:hx-post      (route/url-for :snomed/autocomplete-selected-result)
                     :hx-vals      (web/write-hx-vals :data config)
                     :hx-trigger   "change,load"
                     :hx-indicator (str "#" spinner-id)
                     :hx-target    (str "#" selected-id)
                     :name         "concept-id"
                     :size         (or size 5)}
            (for [result results]
                  [:option (cond-> {:value (.-conceptId result)}
                             (= result default-result) (assoc :selected true)) ;; always select first option by default
                   (let [term (.-term result), preferred-term (.-preferredTerm result)]
                     (if (= term preferred-term)
                       term
                       (str term " (" preferred-term ")")))])]
           (ui/ui-button close-action "Cancel"))
         [:div {:id selected-id}]]))))

(defn autocomplete-selected-result-handler
  [{:keys [form-params env]}]
  (let [hermes (:hermes env)
        concept-id (some-> (:concept-id form-params) parse-long)
        lang-refset-ids (sct/match-locale hermes "en-GB")   ;; TODO: use user's chosen locale
        preferred (when concept-id (:term (sct/preferred-synonym* hermes concept-id lang-refset-ids)))
        synonyms (when concept-id (sct/synonyms hermes concept-id lang-refset-ids))
        {:keys [id close-action] :as config} (web/read-hx-vals :data form-params)]
    (log/debug "inspect result" concept-id " " preferred)
    (web/ok
      (web/render
        (when concept-id
          [:div.grid.grid-cols-1.border-2.shadow-inner.p-4
           [:input {:type "hidden" :name "preferred-synonym" :value preferred}] ;; smuggle synonym so can be used for selected concept
           [:div.col-span-1
            [:span.font-bold preferred]]
           [:div.col-span-1.text-gray-600.text-sm.italic.pl-4
            [:ul
             (for [{:keys [term]} (sort-by :term synonyms)
                   :when (not= term preferred)]
               [:li term])]]
           [:div.col-span-1.m-2
            (ui/ui-button (assoc close-action :id (str id "-save")) "Save") ;; add id for trigger for save
            (ui/ui-button close-action "Cancel")]])))))