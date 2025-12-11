(ns pc4.ui.ods
  "Fulcro-based ODS (Organisation Data Service) browser component.
  Provides organisation search and selection with modal dialog interface."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [goog.functions :as gf]
    [pc4.ui.core :as ui]))

;;
;; ============================================================================
;; Utility Functions
;; ============================================================================
;;

(defn extract-org-code
  "Extract the organisation code from a FHIR Organisation's identifier."
  [org]
  (some-> org
          :org.hl7.fhir.Organization/identifier
          first
          :org.hl7.fhir.Identifier/value))

(defn org-ident
  "Generate an ident for an organisation from its code or FHIR structure.
  Can be passed a string code or a FHIR Organisation map."
  [org-or-code]
  (let [code (if (string? org-or-code)
               org-or-code
               (extract-org-code org-or-code))]
    [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id code]))

(defn format-address
  "Format a FHIR Address for display.
  Joins address lines, city, and postal code with commas."
  [{:org.hl7.fhir.Address/keys [line city postalCode]}]
  (let [parts (concat (or line []) [(when city city) (when postalCode postalCode)])]
    (->> parts
         (remove str/blank?)
         (str/join ", "))))

(defn format-distance
  "Format a distance in metres for display.
  Returns 'X km' for distances >= 1000m, otherwise 'X m'."
  [metres]
  (when metres
    (if (>= metres 1000)
      (str (int (/ metres 1000)) " km")
      (str (int metres) " m"))))



;;
;; ============================================================================
;; Organization Component (for normalization)
;; ============================================================================
;;

(defsc Organization
  "Fulcro component for NHS Organisation data.
  Provides proper normalization using the ODS code as the ident.
  Used for common organisations (e.g. user's hospitals) that should be
  normalized and shared across the application."
  [_ {id :urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id}]
  {:ident (fn [] (when id [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id id]))
   :query [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id
           :org.hl7.fhir.Organization/name
           :org.hl7.fhir.Organization/active
           :org.hl7.fhir.Organization/identifier
           :org.hl7.fhir.Organization/address]})

;;
;; ============================================================================
;; Display Components
;; ============================================================================
;;

(defn format-org-name
  "Format organisation name."
  [{:org.hl7.fhir.Organization/keys [name]}]
  name)

(defsc OrganisationSearchResult
  "Renders a single organisation search result.
  Shows name, optional distance, optional address, and selection indicator."
  [this {:org.hl7.fhir.Organization/keys [name active identifier address] :as org}
   {:keys [selected? fields onClick]}]
  {:query [:org.hl7.fhir.Organization/name
           :org.hl7.fhir.Organization/active
           :org.hl7.fhir.Organization/identifier
           :org.hl7.fhir.Organization/address]}
  (let [code (some-> identifier first :org.hl7.fhir.Identifier/value)
        first-address (first address)
        distance (:org.hl7.fhir.Address/distance first-address)
        address-str (when (:address fields) (format-address first-address))]
    (div :.relative.px-6.py-3.hover:bg-gray-50.cursor-pointer
      {:onClick (when onClick #(onClick org))}
      ;; Selection indicator
      (when selected?
        (div {:className "absolute top-1/2 right-4 transform -translate-y-1/2 bg-gray-500 text-white w-7 h-7 flex items-center justify-center rounded-full"}
          (dom/svg :.w-4.h-4 {:fill "currentColor" :viewBox "0 0 20 20"}
            (dom/path {:fillRule "evenodd"
                       :d        "M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                       :clipRule "evenodd"}))))
      ;; Content
      (div :.flex.items-center.justify-between
        (div :.flex-1.min-w-0
          (div :.text-sm.font-medium.text-gray-900.truncate
            (format-org-name org)
            (when code
              (dom/span :.ml-2.text-xs.font-normal.text-gray-400
                (str "(" code ")")))
            (when distance
              (dom/span :.ml-2.text-xs.text-gray-500.truncate
                (str "(" (format-distance distance) " away)"))))
          (when (and address-str (not (str/blank? address-str)))
            (div :.text-sm.text-gray-600.truncate address-str)))))))

(def ui-organisation-search-result (comp/computed-factory OrganisationSearchResult {:keyfn extract-org-code}))

(defsc SelectedOrganisation
  "Shows the currently selected organisation in the closed selector state."
  [this {:org.hl7.fhir.Organization/keys [name active identifier address] :as org}
   {:keys [fields]}]
  {:query [:org.hl7.fhir.Organization/name
           :org.hl7.fhir.Organization/active
           :org.hl7.fhir.Organization/identifier
           :org.hl7.fhir.Organization/address]}
  (let [code (when (:code fields) (some-> identifier first :org.hl7.fhir.Identifier/value))
        first-address (first address)
        address-str (when (:address fields) (format-address first-address))]
    (comp/fragment
      (dom/span :.text-black
                (format-org-name org)
                (when code
                  (str " (" code ")")))
      (when (and address-str (not (str/blank? address-str)))
        (dom/span :.text-gray-600.italic.text-sm.block address-str)))))

(def ui-selected-organisation (comp/computed-factory SelectedOrganisation))

;;
;; ============================================================================
;; State Management - Mutations
;; ============================================================================
;;

(defn select-org-ident
  "Returns the ident for a SelectOrg component. Can be passed a map of props, or a raw ID."
  [id-or-props]
  (if (map? id-or-props)
    [:component/id (:db/id id-or-props)]
    [:component/id id-or-props]))

(defn reset-modal-state*
  "Helper to reset modal state to defaults. Used within mutations."
  [state path]
  (-> state
      (assoc-in (conj path :ui/modal-open?) false)
      (assoc-in (conj path :ui/search-text) "")
      (assoc-in (conj path :ui/postcode) "")
      (assoc-in (conj path :ui/results) [])
      (assoc-in (conj path :ui/error) nil)))

(defn set-loading*
  "Helper to set loading state. Used within mutations."
  [state path loading?]
  (-> state
      (assoc-in (conj path :ui/loading?) loading?)
      (assoc-in (conj path :ui/error) nil)))

(defmutation open-org-modal
  "Open the organisation selection modal."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (swap! state assoc-in (conj (select-org-ident id) :ui/modal-open?) true)))

(defmutation close-org-modal
  "Close the organisation selection modal and reset search state."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (swap! state reset-modal-state* (select-org-ident id))))

(defmutation select-org
  "Select an organisation and close the modal.
  Note: This mutation only closes the modal. The actual selection is handled
  by the parent component via the onSelect callback."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (swap! state reset-modal-state* (select-org-ident id))))

(defmutation clear-selection
  "Clear the selected organisation and close the modal.
  Note: This mutation only closes the modal. The actual clearing is handled
  by the parent component via the onSelect callback with nil."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (swap! state reset-modal-state* (select-org-ident id))))

;;
;; ============================================================================
;; Search Function
;; ============================================================================
;;

(defn set-results*
  "Helper to set search results. Used within mutations."
  [state path results]
  (-> state
      (assoc-in (conj path :ui/loading?) false)
      (assoc-in (conj path :ui/results) (or results []))))

(defn set-error*
  "Helper to set error state. Used within mutations."
  [state path error]
  (-> state
      (assoc-in (conj path :ui/loading?) false)
      (assoc-in (conj path :ui/error) error)))

(defmutation search-organisations
  "Search for organisations via ODS service."
  [{:keys [id] :as params}]
  (action [{:keys [state]}]
          (swap! state set-loading* (select-org-ident id) true))
  (remote [env]
          (-> env
              (m/with-server-side-mutation 'uk.nhs.ord/search)
              (m/returning OrganisationSearchResult)))
  (ok-action [{:keys [state result]}]
             (let [results (get-in result [:body 'uk.nhs.ord/search])]
               (swap! state set-results* (select-org-ident id) results)))
  (error-action [{:keys [state]}]
                (swap! state set-error* (select-org-ident id) "Search failed")))

(def search-orgs
  "Debounced function to search for organisations."
  (letfn [(do-search [comp id params]
            (comp/transact! comp [(search-organisations (assoc params :id id))]))]
    (gf/debounce do-search 400)))

;;
;; ============================================================================
;; Search Modal Component
;; ============================================================================
;;

(defsc OrgSearchModal
  "Modal dialog for searching and selecting organisations.
   Purely presentational - renders current values and calls onChange for filter updates.
   The selected value is passed as a computed prop from the parent."
  [this {:ui/keys [search-text postcode range sort-by limit results error loading? common-orgs] :as props}
   {:keys [selected fields onSelect onChange]}]
  (let [search-performed? (or (>= (count search-text) 3) (>= (count postcode) 2))
        selected-code (extract-org-code selected)
        ;; Filter out the currently selected org from results to avoid duplication
        all-results (if search-performed? results (or common-orgs []))
        display-results (if selected-code
                          (remove #(= selected-code (extract-org-code %)) all-results)
                          all-results)
        current-filters {:search-text search-text :postcode postcode :range range :sort-by sort-by :limit limit}]
    (div :.space-y-6
      ;; Filters section
      (div :.bg-gray-50.p-4.rounded-lg
        (div :.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-4.gap-4
          ;; Name/Address search
          (div
            (dom/label :.block.text-sm.font-medium.text-gray-700.mb-1 {:htmlFor "org-search-text"} "Name or Address")
            (dom/input :.block.w-full.px-3.py-2.border.border-gray-300.rounded-md.shadow-sm.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm
              {:id          "org-search-text"
               :type        "search"
               :autoFocus   true
               :placeholder "e.g. St James's"
               :value       (or search-text "")
               :onChange    #(onChange (assoc current-filters :search-text (evt/target-value %)))}))
          ;; Postcode filter
          (div
            (dom/label :.block.text-sm.font-medium.text-gray-700.mb-1 {:htmlFor "org-postcode"} "Postcode")
            (dom/input :.block.w-full.px-3.py-2.border.border-gray-300.rounded-md.shadow-sm.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm
              {:id          "org-postcode"
               :type        "text"
               :placeholder "e.g. CF14 4XW"
               :value       (or postcode "")
               :onChange    #(onChange (assoc current-filters :postcode (evt/target-value %)))}))
          ;; Distance filter
          (div
            (dom/label :.block.text-sm.font-medium.text-gray-700.mb-1 {:htmlFor "org-range"} "Distance (km)")
            (dom/input :.block.w-full.px-3.py-2.border.border-gray-300.rounded-md.shadow-sm.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm
              {:id          "org-range"
               :type        "number"
               :placeholder "e.g. 10"
               :value       (str range)
               :onChange    #(onChange (assoc current-filters :range (some-> (evt/target-value %) js/parseInt)))}))
          ;; Sort and Limit
          (div :.grid.grid-cols-2.gap-2
            (div
              (dom/label :.block.text-sm.font-medium.text-gray-700.mb-1 {:htmlFor "org-sort-by"} "Sort by")
              (dom/select :.block.w-full.px-3.py-2.border.border-gray-300.rounded-md.shadow-sm.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm
                          {:id       "org-sort-by"
                           :value    (or sort-by "name")
                           :onChange #(onChange (assoc current-filters :sort-by (evt/target-value %)))}
                          (dom/option {:value "name"} "Name")
                          (dom/option {:value "distance"} "Distance")))
            (div
              (dom/label :.block.text-sm.font-medium.text-gray-700.mb-1 {:htmlFor "org-limit"} "Limit")
              (dom/input :.block.w-full.px-3.py-2.border.border-gray-300.rounded-md.shadow-sm.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm
                {:id          "org-limit"
                 :type        "number"
                 :placeholder "100"
                 :value       (str limit)
                 :onChange    #(onChange (assoc current-filters :limit (some-> (evt/target-value %) js/parseInt)))})))))
      ;; Results section
      (div :.overflow-y-auto.h-96.max-h-96.border.rounded-lg.bg-white {:id "org-results"}
        (cond
          ;; Error message
          error
          (ui/box-error-message :message error)

          ;; Loading
          loading?
          (div :.flex.justify-center.items-center.h-full
            (ui/ui-loading {}))

          ;; Results
          :else
          (comp/fragment
            ;; Current selection pinned at top (if any)
            (when selected
              (comp/fragment
                (div :.bg-gray-50
                  (div :.px-6.py-2.text-xs.font-medium.text-gray-500.uppercase.tracking-wide
                    "Current selection")
                  (ui-organisation-search-result selected
                                                 {:key       (str "selected-" selected-code)
                                                  :selected? true
                                                  :fields    fields
                                                  :onClick   onSelect}))
                (div :.border-b.border-gray-300)))

            ;; Results count
            (when search-performed?
              (div :.text-sm.text-gray-500.px-6.pt-2
                (str "Showing " (count display-results) " result" (when (not= 1 (count display-results)) "s"))))

            (cond
              ;; Has results
              (seq display-results)
              (div :.divide-y.divide-gray-200
                (for [org display-results
                      :let [code (extract-org-code org)]]
                  (ui-organisation-search-result org
                                                 {:key       code
                                                  :selected? false
                                                  :fields    fields
                                                  :onClick   onSelect})))

              ;; No results after search
              search-performed?
              (div :.px-6.py-8.text-center
                (div :.text-sm.text-gray-500 "No organisations found matching your search criteria.")
                (div :.text-xs.text-gray-400.mt-1 "Try broadening your search or adjusting the location filters."))

              ;; Initial state - no search yet
              :else
              (div :.px-6.py-8.text-center
                (div :.text-sm.text-gray-500 "Start typing to search for organisations...")))))))))

(def ui-org-search-modal (comp/computed-factory OrgSearchModal))

;;
;; ============================================================================
;; Main SelectOrg Component
;; ============================================================================
;;

(defsc SelectOrg
  "Main organisation selector component.
  Displays current selection and opens modal for search/selection.
  The selected value is passed as a computed prop (:selected) from the parent,
  ensuring single source of truth for form state."
  [this {id                             :db/id
         :ui/keys                       [modal-open?]
         {hospitals :t_user/hospitals} :session/authenticated-user
         :as                            props}
   {:keys [selected label title placeholder fields roles disabled onSelect] :or {placeholder "= CHOOSE ="}}]
  {:ident         (fn [] (select-org-ident (:db/id props)))
   :query         [:db/id
                   :ui/modal-open?
                   :ui/search-text
                   :ui/postcode
                   :ui/range
                   :ui/sort-by
                   :ui/limit
                   :ui/results
                   :ui/error
                   :ui/loading?
                   {[:session/authenticated-user '_]
                    [{:t_user/hospitals (comp/get-query Organization)}]}]
   :initial-state (fn [{:keys [id]}]
                    {:db/id          id
                     :ui/modal-open? false
                     :ui/search-text ""
                     :ui/postcode    ""
                     :ui/range       nil
                     :ui/sort-by     "name"
                     :ui/limit       100
                     :ui/results     []
                     :ui/error       nil
                     :ui/loading?    false})}
  (let [open-modal #(comp/transact! this [(open-org-modal {:id id})])
        close-modal #(comp/transact! this [(close-org-modal {:id id})])
        do-select (fn [org]
                    (comp/transact! this [(select-org {:id id :org org})])
                    (when onSelect (onSelect org)))
        do-clear #(do (comp/transact! this [(clear-selection {:id id})])
                      (when onSelect (onSelect nil)))
        do-change (fn [{:keys [search-text postcode range sort-by limit]}]
                    (m/set-value! this :ui/search-text search-text)
                    (m/set-value! this :ui/postcode postcode)
                    (m/set-value! this :ui/range range)
                    (m/set-value! this :ui/sort-by sort-by)
                    (m/set-value! this :ui/limit limit)
                    (when (or (>= (count search-text) 3) (>= (count postcode) 2))
                      (search-orgs this id
                                   (cond-> {:roles (if (string? roles) [roles] (vec roles))}
                                           (>= (count search-text) 3)
                                           (assoc :s search-text)
                                           (>= (count postcode) 2)
                                           (assoc :from-location (cond-> {:postcode postcode}
                                                                         range (assoc :range (* range 1000))))
                                           limit
                                           (assoc :limit limit)))))]
    (div :.org-select-component {:id id}
      ;; Display control (closed state)
      (if disabled
        ;; Disabled state
        (div :.border.border-gray-300.px-4.py-2.rounded.bg-gray-100.cursor-not-allowed.opacity-70
          (if selected
            (ui-selected-organisation selected {:fields fields})
            (dom/span :.text-gray-600.italic placeholder)))
        ;; Enabled state - clickable
        (div :.border.border-gray-300.px-4.py-2.rounded.bg-gray-50.cursor-pointer.hover:bg-gray-100
          {:onClick open-modal}
          (if selected
            (ui-selected-organisation selected {:fields fields})
            (dom/span :.text-gray-600.italic placeholder))))

      ;; Modal dialog
      (when modal-open?
        (ui/ui-modal
          {:title   (or label "Select Organisation")
           :onClose close-modal
           :actions [{:id      :clear
                      :title   "Clear"
                      :hidden? (nil? selected)
                      :onClick do-clear}
                     {:id      :cancel
                      :title   "Cancel"
                      :onClick close-modal}]}
          (ui-org-search-modal (assoc props :ui/common-orgs hospitals)
                               {:selected selected
                                :fields   (or fields #{:name :address})
                                :onSelect do-select
                                :onChange do-change}))))))

(def ui-select-org
  "Factory function for the SelectOrg component.

  Common organisations (for quick select) are automatically loaded from the
  authenticated user's hospitals via the session.

  Props (passed as first arg):
  - :db/id - Unique component identifier (required)

  Computed props (passed as second arg):
  - :selected    - Currently selected organisation (FHIR format) - owned by parent
  - :label       - Label text
  - :placeholder - Text when nothing selected (default '= CHOOSE =')
  - :roles       - String or vector of ODS role codes
  - :fields      - Set of display fields #{:name :address :code}
  - :disabled    - Read-only mode
  - :onSelect    - Callback fn receiving selected org (or nil to clear)"
  (comp/computed-factory SelectOrg {:keyfn :db/id}))
