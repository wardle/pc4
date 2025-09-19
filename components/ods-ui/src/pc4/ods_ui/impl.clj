(ns pc4.ods-ui.impl
  "Organisation selection controller with dropdown and modal search interface.
  Provides single organisation selection using the ODS (Organisation Data Service)
  with configurable display fields and search filtering."
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [io.pedestal.interceptor :as intc]
    [pc4.ui.interface :as ui]
    [pc4.log.interface :as log]
    [pc4.ods.interface :as ods]
    [pc4.web.interface :as web]))

(defn make-env-intc
  "The handlers all expect 'ods' and 'pathom' to be in the http request.
  - ods    : an ODS service (see pc4.pds.interface/svc).
  We create a Pathom environment, add the ODS graph resolvers and then create
  a boundary interface from it that will resolve EQL queries."
  [ods]
  (let [env (-> {:com.eldrix.clods.graph/svc ods}
                (pci/register ods/graph-resolvers))
        pathom (p.eql/boundary-interface env)]
    (intc/interceptor
      {:name  ::inject-env
       :enter (fn [ctx] (update-in ctx [:request] assoc :ods ods :pathom pathom))})))

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
  "Fetch organization by code using pathom process function."
  [{:keys [pathom] :as request} org-code]
  (pathom request
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

(defn fetch-common-orgs
  "Resolves a collection of org codes to FHIR Organisation objects."
  [request org-codes]
  (->> org-codes
       (map #(fetch-org request %))
       (remove nil?)))

(defn do-search
  "Search for organisations using the ODS service and convert to FHIR format."
  [{:keys [pathom] :as request} params]
  (get (pathom request
               [{(list 'uk.nhs.ord/search params)
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
      (ui/render-file template (make-context config request))}}]))

(defn ui-select-org
  "Create a UI component for organization selection.

  Parameters:
  - :id/:name    - Component identifier
  - :label       - Label text for the component
  - :roles       - Organization roles to filter by
  - :selected    - Currently selected organization
  - :disabled    - Whether component is disabled
  - :required    - Whether selection is required
  - :common-orgs - List of common organizations to show
  - :fields      - Display fields (:name, :address, :code, :primary-role)
  - :active      - Whether to show only active organizations
  - :postcode    - Postcode for location-based search
  - :range       - Search radius in metres
  - :limit       - Maximum search results (default 100)
  - :allow-unfiltered - Whether to allow searches without filters (default false)"
  [params]
  (let [config (make-config params)]
    (render* "templates/org/select/display-org.html" config)))


(defn minimum-chars
  "Return 's' when and only when it meets length 'n'."
  [s n]
  (when (>= (count s) n) s))

(defn org-select-handler
  "HTTP handler for organisation selection actions."
  [{:keys [form-params] :as request}]
  (let [{:keys [target label required selected common-orgs fields active postcode range limit initial-search?] :as config} (web/read-hx-vals :data form-params)
        trigger (web/hx-trigger request)
        org-code (when (and trigger (not= "cancel" trigger) (not= "clear" trigger)) trigger)
        modal-action {:hx-post (route/url-for :org/select), :hx-target (str "#" target)
                      :hx-vals (web/write-hx-vals :data config)}]
    (web/ok
      (ui/render
        (cond
          ;; user has cancelled, just show control, closing modal dialog
          (= "cancel" trigger)
          (ui-select-org config)

          ;; user has chosen to clear the selection
          (= "clear" trigger)
          (ui-select-org (dissoc config :selected))

          ;; user has selected an organisation
          (and org-code (string? org-code))
          (if-let [fhir-org (fetch-org request org-code)]
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
                          :actions [(assoc modal-action :id :clear :title "Clear" :hidden? (nil? selected))
                                    (assoc modal-action :id :cancel :title "Cancel")]}
                         (render* "templates/org/select/choose-org.html"
                                  (-> config
                                      (assoc :sort-by default-sort-by)
                                      (update :range #(when % (/ % 1000))))
                                  request))))))))

(defn org-search-handler
  "HTTP handler for organisation search operations from the form."
  [{:keys [form-params ods] :as request}]
  (let [{:keys [allow-unfiltered common-orgs fields range roles] :as config} (web/read-hx-vals :data form-params)
        search-text (some-> (:s form-params) str/trim (minimum-chars 3))
        postcode (some-> (:postcode form-params) str/trim (minimum-chars 2))
        {:keys [OSNRTH1M OSEAST1M] :as coords} (when postcode (ods/os-grid-reference ods postcode))
        range-km (or (some-> (:range form-params) str/trim parse-long) (when range (/ range 1000)) 10)
        limit (some-> (:limit form-params) str/trim parse-long)
        trigger-id (web/hx-trigger request)
        sort-by-fn (if coords (if (= trigger-id "postcode") :distance (keyword (get form-params :sort-by "distance"))) :name)
        filtered? (or search-text coords)
        _ (log/debug "org search" {:s search-text :postcode postcode :coords coords :trigger-id trigger-id})
        ;; calculate search params, or list of orgs, or error
        {:keys [error orgs search] :as result}
        (cond
          (and postcode (not coords))
          {:error "Invalid postcode"}
          (and (not allow-unfiltered) (not filtered?))
          {:error "Enter search term(s)"}
          (and (not filtered?) allow-unfiltered (seq common-orgs))
          {:orgs common-orgs}
          (and postcode (not range-km))
          {:error "You must enter a range"}
          :else
          {:search (cond-> {:roles roles}
                     search-text (assoc :s search-text)
                     coords (assoc :from-location {:oseast1m OSEAST1M :osnrth1m OSNRTH1M})
                     range-km (assoc-in [:from-location :range] (* range-km 1000)))})
        _ (log/info "org search" {:config config :postcode postcode :range-km range-km :limit limit :sort-by sort-by-fn})
        orgs# (when-not error
                (->> (if (seq orgs) orgs (do-search request search))
                     (map #(format-org-display % fields))
                     (sort-by sort-by-fn)))]
    (log/info "result" result)
    (web/ok
      (ui/render-file "templates/org/select/list-orgs.html"
                      (-> (make-context config request)
                          (assoc :organisations orgs#
                                 :search-performed filtered?
                                 :error error
                                 :sort-by (name sort-by-fn)
                                 :range range-km))))))