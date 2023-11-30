(ns eldrix.pc4-ward.org.events
  "Events relating to health and care organisations.
  At the moment, this is hard-coded to use the UK search service from clods
  (https://github.com/wardle/clods). It is entirely reasonable to add additional
  context (e.g. country or lat/lon) and configure the backend service
  to handle the request more magically."
  (:require [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [pc4.server :as srv]))

(defn make-fetch-uk-org
  "Fetch a UK organisation. Conceivably this could be deprecated
  in favour of a more general approach in which we use identifier tuples
  at the client level. At the moment, we hardcode UK, but this could
  be a configurable option at runtime at the client."
  [org-identifier]
  {[:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id org-identifier]
   [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id
    :org.w3.2004.02.skos.core/prefLabel]})

(defn make-fetch-uk-orgs [org-identifiers]
  (into [] (map make-fetch-uk-org org-identifiers)))

(defn make-search-uk-org
  [params]
  [{(list 'uk.nhs.ord/search
          params)
    [:org.hl7.fhir.Organization/name
     :org.hl7.fhir.Organization/identifier
     :org.hl7.fhir.Organization/address
     :org.hl7.fhir.Organization/active]}])

(defn official-identifier
  "Return a tuple representing the official identifier of the organization."
  [{:org.hl7.fhir.Organization/keys [identifier]}]
  (let [id (first (filter #(= :org.hl7.fhir.identifier-use/official (:org.hl7.fhir.Identifier/use %)) identifier))]
    [(:org.hl7.fhir.Identifier/system id) (:org.hl7.fhir.Identifier/value id)]))


;; TODO: add more parameter checks
(s/def ::s string?)
(s/def ::n string?)
(s/def ::roles (s/or :string string? :coll coll?))
(s/def ::search-parameters (s/keys :opt-un [::s ::n ::roles]))

(rf/reg-event-fx
  ::search-uk []
  (fn [{db :db} [_ id params]]
    (js/console.log "search UK organization id: " id " params: " params)
    {:db (-> db
             (update :organization/search dissoc :id)
             (update-in [:errors] dissoc ::search))
     :fx [[:pathom {:params     (make-search-uk-org params)
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-search-response id (js/Date.)]
                    :on-failure [::handle-search-failure id]}]]}))

(rf/reg-event-db
  ::clear-search-results
  (fn [db [_ id]]
    (update-in db [:organization/search-results] dissoc id)))

(rf/reg-event-fx ::handle-search-response
  []
  (fn [{db :db} [_ id date {orgs 'uk.nhs.ord/search :as response}]]
    (js/console.log "search org response: " response)
    ;; be careful to not overwrite results from later autocompletion, which may be returned more quickly
    (let [existing (get-in db [:organization/search-results id :date])]
      (when (or (not existing) (> date existing))
        {:db (assoc-in db [:organization/search-results id] {:date date :results orgs})}))))

(rf/reg-event-fx ::handle-search-failure
  []
  (fn [{:keys [db]} [_ id response]]
    (js/console.log "search org failure: response " response)
    {:db (-> db
             (update-in [:organization/search-results] dissoc id)
             (assoc-in [:errors :organization/search] "Failed to search for organisation: unable to connect to server. Please check your connection and retry."))}))

(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (rf/dispatch-sync [::search-uk :fred {:n "penylan" :roles "RO72" :limit 10 :from-location {:postcode "CF14 4XW"}}])
  (rf/dispatch-sync [::search-uk :fred {:n "royal glam" :roles "RO148" :from-location {:postcode "CF14 4XW"}}])
  (def result @(rf/subscribe [:eldrix.pc4-ward.org.subs/search-results :fred]))
  (tap> result)
  (rf/dispatch-sync [:eldrix.pc4-ward.user.events/do-login {:username "ma090906" :password "password"}])
  @(rf/subscribe [:eldrix.pc4-ward.user.subs/authenticated-user]))

