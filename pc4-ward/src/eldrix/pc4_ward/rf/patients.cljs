(ns eldrix.pc4-ward.rf.patients
  "Events and subscriptions relating to patients."
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.server :as srv]))

(rf/reg-event-fx
  ::fetch []
  (fn [{db :db} [_ identifier]]
    (js/console.log "fetch patient " identifier)
    {:db (-> db
             (dissoc :patient/search-results)
             (update-in [:errors] dissoc ::login))
     :fx [[:http-xhrio (srv/make-xhrio-request {:params     (srv/make-cav-fetch-patient-op {:pas-identifier identifier})
                                                :token      (get-in db [:authenticated-user :io.jwt/token])
                                                :on-success [::handle-fetch-response]
                                                :on-failure [::handle-fetch-failure]})]]}))

(rf/reg-event-fx ::handle-fetch-response
  []
  (fn [{db :db} [_ {pt 'wales.nhs.cavuhb/fetch-patient}]]
    (js/console.log "fetch patient response: " pt)
    {:db (assoc db :patient/search-results (if pt [pt] []))}))

(rf/reg-event-fx ::handle-fetch-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "fetch patient failure: response " response)
    {:db (-> db
             (dissoc :patient/search-results)
             (assoc-in [:errors ::fetch] "Failed to fetch patient: unable to connect to server. Please check your connection and retry."))}))

(rf/reg-event-db ::clear-search-results
  []
  (fn [db _]
    (dissoc db :patient/search-results)))

(rf/reg-sub ::search-results
  (fn [db]
    (:patient/search-results db)))


(rf/reg-event-db ::set-current-patient
  []
  (fn [db [_ patient]]
    (js/console.log "selecting patient " patient)
    (assoc db :patient/current patient)))

(rf/reg-event-db ::close-current-patient
  []
  (fn [db [_ patient]]
    (js/console.log "closing patient" patient)
    (dissoc db :patient/current patient)))

(rf/reg-sub ::current-patient
  (fn [db]
    (:patient/current db)))