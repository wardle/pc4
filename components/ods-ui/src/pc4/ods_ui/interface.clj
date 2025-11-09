(ns pc4.ods-ui.interface
  (:require
    [clojure.spec.alpha :as s]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [integrant.core :as ig]
    [io.pedestal.interceptor :as intc]
    [pc4.log.interface :as log]
    [pc4.ods.interface :as ods]
    [pc4.ods-ui.impl :as impl]))

(s/def ::ods ods/valid-service?)
(s/def ::config
  (s/keys :req-un [::ods]))

(defmethod ig/init-key ::svc
  [_ {:keys [ods] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid ods-ui config" (s/explain-data ::config config))))
  (let [resolvers (map force (flatten @(ods/graph-resolvers)))]
    (when (zero? (count resolvers))
      (throw (ex-info "No ODS resolvers registered! This will cause searches to fail."
                      {:resolvers-fn (ods/graph-resolvers)})))
    (let [env (-> (pci/register resolvers)
                  (assoc :com.eldrix.clods.graph/svc ods))
          pathom (p.eql/boundary-interface env)]
      {:intc (intc/interceptor
               {:name  ::inject-env
                :enter (fn [ctx]
                         (update ctx :request assoc :ods ods :pathom pathom))})})))

(s/def ::routes-params
  (s/keys :opt-un [::interceptors]))

(defn routes
  "Return the routes to support the ODS user interface."
  [{:keys [intc]} {:keys [interceptors] :or {interceptors []} :as params}]
  (when-not (s/valid? ::routes-params params)
    (throw (ex-info "invalid parameters:" (s/explain-data ::routes-params params))))
  #{["/ui/org/select" :post (conj interceptors intc impl/org-select-handler) :route-name :org/select]
    ["/ui/org/search" :post (conj interceptors intc impl/org-search-handler) :route-name :org/search]})

(defn ui-select-org
  "Create a UI component for organization selection.
  An application using this component *must* include the [[routes]] in its
  routing table.
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
  (impl/ui-select-org params))