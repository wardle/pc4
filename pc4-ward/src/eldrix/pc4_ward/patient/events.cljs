(ns eldrix.pc4-ward.patient.events
  "Events relating to patients."
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.server :as srv]))

(defn make-cav-fetch-patient-op
  [{:keys [pas-identifier]}]
  [{(list 'wales.nhs.cavuhb/fetch-patient
          {:system "http://fhir.cavuhb.nhs.wales/Id/pas-identifier" :value pas-identifier})
    [:org.hl7.fhir.Patient/birthDate
     :wales.nhs.cavuhb.Patient/DATE_DEATH
     :uk.nhs.cfh.isb1505/display-age
     :wales.nhs.cavuhb.Patient/IS_DECEASED
     :wales.nhs.cavuhb.Patient/ADDRESSES
     :wales.nhs.cavuhb.Patient/HOSPITAL_ID
     :uk.nhs.cfh.isb1504/nhs-number
     :uk.nhs.cfh.isb1506/patient-name
     :org.hl7.fhir.Patient/identifier
     :org.hl7.fhir.Patient/name
     :wales.nhs.cavuhb.Patient/SEX
     :org.hl7.fhir.Patient/gender
     :org.hl7.fhir.Patient/deceased
     :org.hl7.fhir.Patient/currentAddress]}])

(rf/reg-event-fx
  ::fetch []
  (fn [{db :db} [_ identifier]]
    (js/console.log "fetch patient " identifier)
    {:db (-> db
             (dissoc :patient/search-results)
             (update-in [:errors] dissoc ::fetch))
     :fx [[:http-xhrio (srv/make-xhrio-request {:params     (make-cav-fetch-patient-op {:pas-identifier identifier})
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

(rf/reg-event-db ::set-current-patient
  []
  (fn [db [_ patient]]
    (js/console.log "selecting patient " patient)
    (assoc-in db [:patient/current :patient] patient)))

(rf/reg-event-db ::close-current-patient
  []
  (fn [db [_ patient]]
    (js/console.log "closing patient" patient)
    (dissoc db :patient/current patient)))




(defn make-search-by-legacy-pseudonym
  [project-id pseudonym]
  [{(list 'pc4.rsdb/search-patient-by-pseudonym
          {:project-id project-id
           :pseudonym  pseudonym})
    [:t_patient/id
     :t_patient/patient_identifier
     :t_patient/first_names
     :t_patient/last_name
     :t_patient/date_birth
     :t_patient/status
     :t_patient/date_death
     :t_episode/stored_pseudonym]}])

(rf/reg-event-fx ::search-legacy-pseudonym
  (fn [{db :db} [_ project-id pseudonym]]
    (js/console.log "search by pseudonym" project-id pseudonym)
    (cond-> {:db (-> db
                     (dissoc :patient/search-legacy-pseudonym)
                     (update-in [:errors] dissoc ::search-legacy-pseudonym))}
            (>= (count pseudonym) 3)
            (assoc :fx [[:http-xhrio (srv/make-xhrio-request {:params     (make-search-by-legacy-pseudonym project-id pseudonym)
                                                              :token      (get-in db [:authenticated-user :io.jwt/token])
                                                              :on-success [::handle-search-pseudonym-response]
                                                              :on-failure [::handle-search-pseudonym-failure]})]]))))

(rf/reg-event-fx ::handle-search-pseudonym-response
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/search-patient-by-pseudonym}]]
    (js/console.log "search by pseudonym response: " result)
    {:db (assoc db :patient/search-legacy-pseudonym result)}))

(rf/reg-event-fx ::handle-search-pseudonym-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "fetch patient failure: response " response)
    {:db (-> db
             (dissoc :patient/search-legacy-pseudonym)
             (assoc-in [:errors ::search-legacy-pseudonym] "Failed to search for patient: unable to connect to server. Please check your connection and retry."))}))






(defn fetch-patient
  [s & {:keys [handler error-handler token] :or {error-handler srv/default-error-handler}}]
  (srv/do! {:params        (make-cav-fetch-patient-op {:pas-identifier s})
            :token         token
            :handler       #(handler (get % 'wales.nhs.cavuhb/fetch-patient))
            :error-handler error-handler}))
