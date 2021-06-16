(ns eldrix.pc4-ward.org.events
  "Events relating to health and care organisations.
  At the moment, this is hard-coded to use the UK search service from clods
  (https://github.com/wardle/clods). It is entirely reasonable to add additional
  context (e.g. country or lat/lon) and configure the backend service
  to handle the request more magically."
  (:require [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.server :as srv]))

;; TODO: add more parameter checks
(s/def ::s string?)
(s/def ::n string?)
(s/def ::roles (s/or :string string? :coll coll?))
(s/def ::search-parameters (s/keys :opt-un [::s ::n ::roles]))

(rf/reg-event-fx
  ::search-uk []
  (fn [{db :db} [_ id params]]
    (when-not (s/valid? ::search-parameters params)
      (js/console.log "search uk org: invalid parameters " (s/explain-str ::search-parameters params)))
    (js/console.log "search UK organization id: " id " params: " params)
    {:db (-> db
             (update :organization/search dissoc :id)
             (update-in [:errors] dissoc ::search))
     :fx [[:http-xhrio (srv/make-xhrio-request {:params     (srv/make-search-uk-org params)
                                                :token      (get-in db [:authenticated-user :io.jwt/token])
                                                :on-success [::handle-search-response id]
                                                :on-failure [::handle-search-failure id]})]]}))

(rf/reg-event-fx ::handle-search-response
  []
  (fn [{db :db} [_ id {orgs 'uk.nhs.ord/search :as response}]]
    (js/console.log "search org response: " response)
    {:db (assoc-in db [:organization/search-results id] orgs)}))

(rf/reg-event-fx ::handle-search-failure
  []
  (fn [{:keys [db]} [_ id response]]
    (js/console.log "search org failure: response " response)
    {:db (-> db
             (update-in [:organization/search-results] dissoc id)
             (assoc-in [:errors ::search] "Failed to search for organisation: unable to connect to server. Please check your connection and retry."))}))


(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (rf/dispatch-sync [::search-uk :fred {:n "Castle Gate" :roles "RO72"}])
  (rf/dispatch-sync [::search-uk :fred {:n "Univ Wales" :roles "RO148"}])
  @(rf/subscribe [:eldrix.pc4-ward.org.subs/search-results :fred])

  (rf/dispatch-sync [:eldrix.pc4-ward.user.events/do-login "wales.nhs.uk" "ma090906" "password"])
  @(rf/subscribe [:eldrix.pc4-ward.user.subs/authenticated-user])
  )
