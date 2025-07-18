(ns pc4.http-server.controllers.select-org
  "Organisation selection controller with dropdown and modal search interface.
  Provides single organisation selection using the ODS (Organisation Data Service)
  with configurable display fields and search filtering."
  (:require
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.ui :as ui]
    [pc4.http-server.web :as web]
    [pc4.log.interface :as log]
    [pc4.ods.interface :as ods]))

;; Specs for configuration validation
(s/def ::id string?)
(s/def ::name string?)
(s/def ::label (s/nilable string?))
(s/def ::required boolean?)
(s/def ::disabled boolean?)
(s/def ::org (s/keys :req [:org.hl7.fhir.Organization/identifier
                           :org.hl7.fhir.Organization/name]
                     :opt [:org.hl7.fhir.Organization/active
                           :org.hl7.fhir.Organization/address]))
(s/def ::selected (s/nilable ::org))
(s/def ::placeholder string?)
(s/def ::roles (s/or :single string? :multiple (s/coll-of string?)))
(s/def ::common-orgs (s/coll-of ::org))
(s/def ::fields #(set/subset? % #{:name :address :code :primary-role})) ;; fields to display
(s/def ::active boolean?)
(s/def ::postcode (s/nilable string?))
(s/def ::range (s/nilable number?))
(s/def ::limit number?)
(s/def ::allow-unfiltered boolean?)
(s/def ::params (s/keys :req-un [(or ::id ::name) ::roles]
                        :opt-un [::label ::required ::disabled ::selected ::placeholder
                                 ::common-orgs ::fields ::active
                                 ::postcode ::range ::limit ::allow-unfiltered]))

(defn make-config
  "Creates configuration map from parameters with validation and defaults."
  [{component-name :name, :keys [id label placeholder selected disabled required roles
                                 common-orgs fields active postcode range limit allow-unfiltered] :as params}]
  (when-not (s/valid? ::params params)
    (log/error "invalid parameters" (s/explain-data ::params params))
    (throw (ex-info "invalid parameters" (s/explain-data ::params params))))
  (let [id# (or id component-name (throw (ex-info "invalid parameters: must specify id or name" params)))]
    {:id               id#
     :label            label
     :name             (or component-name id)
     :disabled         (boolean disabled)
     :required         (boolean required)
     :selected         selected
     :placeholder      (or placeholder "= CHOOSE =")
     :target           (str id# "-target")
     :search-target    (str id# "-search-target")
     :action-key       (str id# "-action")
     :mode-key         (str id# "-mode")
     :roles            roles
     :common-orgs      (or common-orgs [])
     :fields           (or fields #{:name :address})
     :active           (if (nil? active) true active)
     :postcode         postcode
     :range            range
     :limit            (or limit 1000)
     :allow-unfiltered (boolean allow-unfiltered)}))


(defn fetch-org
  [env request org-code]
  (pathom/process env request
                  {:pathom/entity {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id org-code}
                   :pathom/eql    [:org.hl7.fhir.Organization/name
                                   :org.hl7.fhir.Organization/address
                                   :org.hl7.fhir.Organization/identifier
                                   :org.hl7.fhir.Organization/active]}))
(defn format-org-display
  "Formats a FHIR Organisation for display based on configured display fields."
  [{:org.hl7.fhir.Organization/keys [name active identifier address] :as fhir-org} fields]
  (when fhir-org
    (let [org-code (:org.hl7.fhir.Identifier/value (first identifier))
          {:org.hl7.fhir.Address/keys [line city postalCode distance]} (first address)
          address# (str/join ", " (remove str/blank? (into (vec line) [city postalCode])))
          org-map {:code   org-code
                   :name   (if active name (str name " (inactive)"))
                   :active active}]
      (cond-> org-map
        distance (assoc :distance distance
                        :distance-display (if (> distance 1000) (str (int (/ distance 1000)) "km") (str (int distance) "m")))
        (and address (:address fields)) (assoc :address address#)))))

(defn resolve-common-orgs
  "Resolves a collection of org codes to FHIR Organisation objects."
  [env request org-codes]
  (->> org-codes
       (map #(fetch-org env request %))
       (filter some?)))

(defn search-organisations
  "Search for organisations using the ODS service and convert to FHIR format."
  [env request params]
  (get (pathom/process env request [{(list 'uk.nhs.ord/search params)
                                     [:org.hl7.fhir.Organization/name
                                      :org.hl7.fhir.Organization/active
                                      :org.hl7.fhir.Organization/identifier
                                      :org.hl7.fhir.Organization/address]}])
       'uk.nhs.ord/search))

(defn make-context
  "Creates template context from config and request."
  [config request]
  (let [selected-org (when (:selected config) (format-org-display (:selected config) (:fields config)))]
    (assoc config
      :url (route/url-for :org/select)
      :search-url (route/url-for :org/search)
      :csrf-token (csrf/existing-token request)
      :hx-vals (web/write-hx-vals :data config)
      :config (pr-str config)
      :selected-org selected-org)))

(defn render*
  "Renders a template with the given config and optional request context."
  ([template config]
   (render* template config nil))
  ([template config request]
   [:div
    {:dangerouslySetInnerHTML
     {:__html
      (web/render-file template (make-context config request))}}]))

(defn ui-select-org
  "An organisation selection control. Displays current selection and allows
  'click to edit' functionality with a modal dialog. When collapsed, it is
  essentially a hidden INPUT element with name 'name' and value as a data
  representation of a HL7 FHIR Organisation.
  
  Parameters:
  - :id          - Control identifier
  - :name        - Form field name
  - :disabled    - Whether control is disabled
  - :selected    - Currently selected FHIR Organisation object
  - :placeholder - Placeholder text when no selection
  - :label       - Control label and modal title
  - :roles       - ODS roles to filter by (required)
  - :common-orgs - Collection of org codes for initial dropdown
  - :fields      - Set of fields to display (default #{:name})
  - :active      - Whether to include only active orgs (default true)
  - :postcode    - Postcode for location-based search
  - :range       - Search radius in metres
  - :limit       - Maximum search results (default 100)
  - :allow-unfiltered - Whether to allow searches without filters (default false)"
  [params]
  (log/debug "rendering org-select:" params)
  (let [config (make-config params)]
    (render* "templates/org/select/display.html" config)))


(defn minimum-chars
  "Return 's' when and only when it meets length 'n'."
  [s n]
  (when (>= (count s) n) s))

(defn org-select-handler
  "HTTP handler for organisation selection actions."
  [{:keys [env form-params] :as request}]
  (let [{:keys [target label required selected common-orgs fields active postcode range limit initial-search?] :as config} (web/read-hx-vals :data form-params)
        trigger (web/hx-trigger request)
        org-code (when (and trigger (not= "cancel" trigger) (not= "clear" trigger)) trigger)
        modal-action {:hx-post (route/url-for :org/select), :hx-target (str "#" target)
                      :hx-vals (web/write-hx-vals :data config)}]
    (log/debug "org-select-handler" {:trigger trigger :org-code org-code :config config})
    (web/ok
      (web/render
        (cond
          ;; user has cancelled, just show control, closing modal dialog
          (= "cancel" trigger)
          (ui-select-org config)

          ;; user has chosen to clear the selection
          (= "clear" trigger)
          (ui-select-org (dissoc config :selected))

          ;; user has selected an organisation
          (and org-code (string? org-code))
          (if-let [fhir-org (fetch-org env request org-code)]
            (ui-select-org (assoc config :selected fhir-org))
            (do
              (log/warn "Organisation not found" {:org-code org-code})
              (ui-select-org (assoc config :error (str "Organisation " org-code " not found")))))

          ;; user opening modal dialog to select an organisation
          :else
          (let [default-sort-by (if (and postcode range) "distance" "name")]
            (ui/ui-modal {:title   (or label "Select Organisation")
                          :hidden? false
                          :size    :xl
                          :actions [(assoc modal-action :id :clear :title "Clear"
                                                        :hidden? (or required (nil? selected)))
                                    (assoc modal-action :id :cancel :title "Cancel")]}
                         (render* "templates/org/select/choose-single.html"
                                  (assoc config :default-sort-by default-sort-by)
                                  request))))))))

(defn org-search-handler
  "HTTP handler for organisation search operations from the form."
  [{:keys [env form-params] :as request}]
  (let [{:keys [fields roles active common-orgs postcode range limit allow-unfiltered] :as config} (web/read-hx-vals :data form-params)
        s (some-> (:s form-params) str/trim (minimum-chars 3))
        postcode-filter (or (:postcode-filter form-params) postcode)
        range-filter (or (some-> (:range-filter form-params) parse-long) range)
        limit-filter (or (some-> (:limit-filter form-params) parse-long) limit)
        sort-by-param (or (:sort-by form-params) "name")

        user-searched? (or s (not (str/blank? (:postcode-filter form-params)))) ; Check if user has typed or entered postcode
        initial-load? (not user-searched?)]

    (log/debug "org-search-handler debug:" {:s                 s
                                            :postcode-filter   postcode-filter
                                            :user-searched?    user-searched?
                                            :initial-load?     initial-load?
                                            :allow-unfiltered  allow-unfiltered
                                            :common-orgs-count (count common-orgs)})

    (web/ok
      (cond
        ;; Case 1: User has performed a search
        user-searched?
        (do (log/debug "org-search-handler: Case 1 - User searched")
            (let [search-params (cond-> {:roles roles :active active :limit limit-filter}
                                  (not-empty postcode-filter) (assoc :from-location {:postcode postcode-filter :range range-filter})
                                  s (assoc :s s))
                  search-results (search-organisations env request search-params)
                  all-orgs (->> search-results
                                (map #(format-org-display % fields))
                                (filter some?)
                                (sort-by (if (= sort-by-param "distance") :distance :name))
                                distinct)]
              (web/render-file "templates/org/select/list-single.html"
                               (assoc (make-context config request)
                                 :organisations all-orgs
                                 :search-performed true))))

        ;; Case 2: Initial load, and unfiltered search is allowed
        (and initial-load? allow-unfiltered)
        (do (log/debug "org-search-handler: Case 2 - Initial load with unfiltered search allowed")
            (let [search-params (cond-> {:roles roles :active active :limit limit}
                                  (not-empty postcode) (assoc :from-location {:postcode postcode :range range}))
                  search-results (search-organisations env request search-params)
                  all-orgs (->> search-results
                                (map #(format-org-display % fields))
                                (filter some?)
                                (sort-by (if (= sort-by-param "distance") :distance :name))
                                distinct)]
              (web/render-file "templates/org/select/list-single.html"
                               (assoc (make-context config request)
                                 :organisations all-orgs
                                 :search-performed true))))

        ;; Case 3: Initial load, filtered search not allowed, but common orgs are present
        (and initial-load? (not allow-unfiltered) (not-empty common-orgs))
        (do (log/debug "org-search-handler: Case 3 - Initial load with common orgs")
            (let [common-results (->> (resolve-common-orgs env request common-orgs)
                                      (map #(format-org-display % fields))
                                      (sort-by :name))]     ; Use sort-by-param if needed
              (web/render-file "templates/org/select/list-single.html"
                               (assoc (make-context config request)
                                 :organisations common-results
                                 :search-performed false))))

        ;; Case 4: Initial load, filtered search required, no common orgs -> show empty state
        :else
        (do (log/debug "org-search-handler: Case 4 - Empty state")
            (web/render-file "templates/org/select/list-single.html"
                             (assoc (make-context config request)
                               :organisations []
                               :search-performed false)))))))