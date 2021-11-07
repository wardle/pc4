(ns eldrix.pc4-ward.patient.events
  "Events relating to patients."
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.events :as events]
            [eldrix.pc4-ward.server :as srv]))

(def core-patient-properties                                ;; TODO: these properties to be generic properties not rsdb.
  [:t_patient/id
   :t_patient/patient_identifier
   :t_patient/first_names
   :t_patient/last_name
   :t_patient/sex
   :t_patient/date_birth
   :t_patient/status
   :t_patient/date_death
   :t_patient/current_address])

(def patient-diagnosis-properties
  [:t_diagnosis/id
   :t_diagnosis/date_onset
   :t_diagnosis/date_diagnosis
   :t_diagnosis/date_to
   :t_diagnosis/status
   :t_diagnosis/date_onset_accuracy
   :t_diagnosis/date_diagnosis_accuracy
   :t_diagnosis/date_to_accuracy
   {:t_diagnosis/diagnosis [:info.snomed.Concept/id
                            :info.snomed.Concept/preferredDescription
                            :info.snomed.Concept/parentRelationshipIds]}])

(def full-patient-properties
  (into
    core-patient-properties
    [{:t_patient/diagnoses patient-diagnosis-properties}
     :t_patient/medications
     :t_patient/encounters
     :t_patients/summary_multiple_sclerosis
     :t_patient/episodes]))

(defn make-search-by-legacy-pseudonym
  [project-id pseudonym]
  [{(list 'pc4.rsdb/search-patient-by-pseudonym
          {:project-id project-id
           :pseudonym  pseudonym})
    (conj core-patient-properties
          :t_episode/stored_pseudonym
          :t_episode/project_fk)}])

(defn make-fetch-pseudonymous-patient
  "Create an operation to fetch a patient via project-specific pseudonym."
  [project-id pseudonym]
  [{(list 'pc4.rsdb/search-patient-by-pseudonym
          {:project-id project-id
           :pseudonym  pseudonym})
    (conj full-patient-properties
          :t_episode/stored_pseudonym
          :t_episode/project_fk)}])

(defn make-register-pseudonymous-patient
  "Register or fetch a patient with the details specified."
  [{:keys [project-id nhs-number date-birth sex] :as params}]
  [{(list 'pc4.rsdb/register-patient-by-pseudonym
          params)
    (conj full-patient-properties
          :t_episode/stored_pseudonym
          :t_episode/project_fk)}])

(defn make-fetch-patient
  "Create an operation to fetch a full patient record with the
  patient-identifier specified."
  [patient-identifier]
  [{[:t_patient/patient_identifier patient-identifier]
    full-patient-properties}])

(defn make-save-diagnosis
  [params]
  [{(list 'pc4.rsdb/save-diagnosis params)
    patient-diagnosis-properties}])

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
    (-> db
        (dissoc :patient/current))))

(rf/reg-event-db ::clear-open-patient-error
  []
  (fn [db [_ patient]]
    (-> db
        (update-in [:errors] dissoc :open-patient))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Search by legacy pseudonym
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(rf/reg-event-fx ::search-legacy-pseudonym
  (fn [{db :db} [_ project-id pseudonym]]
    (js/console.log "search by pseudonym" project-id pseudonym)
    (cond-> {:db (-> db
                     (dissoc :patient/search-legacy-pseudonym)
                     (update-in [:errors] dissoc ::search-legacy-pseudonym))}
            (>= (count pseudonym) 3)
            (assoc :fx [[:pathom {:params     (make-search-by-legacy-pseudonym project-id pseudonym)
                                  :token      (get-in db [:authenticated-user :io.jwt/token])
                                  :on-success [::handle-search-pseudonym-response]
                                  :on-failure [::handle-search-pseudonym-failure]}]]))))

(rf/reg-event-fx ::handle-search-pseudonym-response
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/search-patient-by-pseudonym}]]
    (js/console.log "search by pseudonym response: " result)
    {:db (assoc db :patient/search-legacy-pseudonym result)}))

(rf/reg-event-fx ::handle-search-pseudonym-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "search by pseudonym failure: response " response)
    {:db (-> db
             (dissoc :patient/search-legacy-pseudonym)
             (assoc-in [:errors ::search-legacy-pseudonym] "Failed to search for patient: unable to connect to server. Please check your connection and retry."))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Open, refresh and close a pseudonymous patient record
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(rf/reg-event-fx ::open-pseudonymous-patient
  (fn [{db :db} [_ project-id pseudonym]]
    (js/console.log "opening pseudonymous patient record:" project-id pseudonym)
    {:db (-> db
             (dissoc :patient/current)
             (update-in [:errors] dissoc :open-patient))
     :fx [[:pathom {:params     (make-fetch-pseudonymous-patient project-id pseudonym)
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-fetch-pseudonymous-patient-response]
                    :on-failure [::handle-fetch-pseudonymous-patient-failure]}]]}))

;; refresh current patient
;; this has to do things differently based on whether patient is a full record
;; or a pseudonymous patient
(rf/reg-event-fx ::refresh-current-patient
  (fn [{db :db} _]
    (let [patient-identifier (get-in db [:patient/current :patient :t_patient/patient_identifier])
          project-id (get-in db [:patient/current :patient :t_episode/project_fk])
          pseudonym (get-in db [:patient/current :patient :t_episode/stored_pseudonym])]
      (if (and project-id pseudonym)
        {:fx [[:pathom {:params     (make-fetch-pseudonymous-patient project-id pseudonym)
                        :token      (get-in db [:authenticated-user :io.jwt/token])
                        :on-success [::handle-fetch-pseudonymous-patient-response]
                        :on-failure [::handle-fetch-pseudonymous-patient-failure]}]]}
        {:fx [[:pathom {:params     (make-fetch-patient patient-identifier)
                        :token      (get-in db [:authenticated-user :io.jwt/token])
                        :on-success [::handle-fetch-patient-response]
                        :on-failure [::handle-fetch-patient-failure]}]]}))))

(rf/reg-event-fx ::handle-fetch-patient-response
  []
  (fn [{db :db} [_ response]]
    (js/console.log "fetch  patient response: " response)
    {:db (assoc-in db [:patient/current :patient] (first (vals response)))}))

(rf/reg-event-fx ::handle-fetch-patient-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "fetch patient failure: response " response)
    {:db (-> db
             (dissoc :patient/current)
             (assoc-in [:errors :open-patient] "Failed to fetch patient: unable to connect to server. Please check your connection and retry."))}))

(rf/reg-event-fx ::handle-fetch-pseudonymous-patient-response
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/search-patient-by-pseudonym}]]
    (js/console.log "fetch pseudonymous patient response: " result)
    {:db (assoc-in db [:patient/current :patient] result)}))

;; #(rfe/push-state :patient-by-project-pseudonym {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)})
(rf/reg-event-fx ::handle-fetch-pseudonymous-patient-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "fetch pseudonymous patient failure: response " response)
    {:db (-> db
             (dissoc :patient/current)
             (assoc-in [:errors :open-patient] "Failed to fetch pseudonymous patient: unable to connect to server. Please check your connection and retry."))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Search or register a pseudonymous patient
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(rf/reg-event-fx ::register-pseudonymous-patient
  (fn [{db :db} [_ {:keys [project-id nhs-number date-birth sex] :as params}]]
    (js/console.log "searching or registering pseudonymous patient record:" params)
    {:db (-> db
             (dissoc :patient/current)
             (update-in [:errors] dissoc :open-patient))
     :fx [[:pathom {:params     (make-register-pseudonymous-patient params)
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-register-pseudonymous-patient-response]
                    :on-failure [::handle-register-pseudonymous-patient-failure]}]]}))

(rf/reg-event-fx ::handle-register-pseudonymous-patient-response
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/register-patient-by-pseudonym}]]
    (js/console.log "register pseudonymous patient response: " result)
    {:db (assoc-in db [:patient/current :patient] result)
     :fx [[:dispatch [::events/push-state :patient-by-project-pseudonym {:project-id (:t_episode/project_fk result)
                                                                         :pseudonym  (:t_episode/stored_pseudonym result)}]]]}))

(rf/reg-event-fx ::handle-register-pseudonymous-patient-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "register pseudonymous patient failure: response " response)
    {:db (-> db
             (dissoc :patient/current)
             (assoc-in [:errors :open-patient] (or (get-in response [:response :message]) (:status-text response))))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-event-db ::set-current-diagnosis
  []
  (fn [db [_ diagnosis]]
    (-> db
        (assoc-in [:patient/current :current-diagnosis] diagnosis))))

(rf/reg-event-db ::clear-diagnosis
  []
  (fn [db _]
    (-> db
        (update-in [:patient/current] dissoc :current-diagnosis))))

(rf/reg-event-fx ::save-diagnosis
  (fn [{db :db} [_ params]]
    {:db (-> db
             (update-in [:errors] dissoc ::save-diagnosis))
     :fx [[:pathom {:params     (make-save-diagnosis params)
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::handle-save-diagnosis
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/save-diagnosis}]]
    (js/console.log "save diagnosis response: " result)
    {:fx [[:dispatch-n [[::refresh-current-patient]
                        [::clear-diagnosis]]]]}))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LEGACY Cardiff and Vale specific fetch - DEPRECATED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-cav-fetch-patient-op
  "This is a Cardiff and Vale specific operation to fetch patient data directly
  from PMS. This is a leaky abstraction that will be removed in the future."
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

(rf/reg-event-fx                                            ;;; DEPRECATED
  ::fetch []
  (fn [{db :db} [_ identifier]]
    (js/console.log "WARNING: LEGACY DEPRECATED fetch patient " identifier)
    {:db (-> db
             (dissoc :patient/search-results)
             (update-in [:errors] dissoc ::fetch))
     :fx [[:pathom {:params     (make-cav-fetch-patient-op {:pas-identifier identifier})
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-fetch-response]
                    :on-failure [::handle-fetch-failure]}]]}))

(rf/reg-event-fx ::handle-fetch-response                    ;;DEPRECATED
  []
  (fn [{db :db} [_ {pt 'wales.nhs.cavuhb/fetch-patient}]]
    (js/console.log "fetch patient response: " pt)
    {:db (assoc db :patient/search-results (if pt [pt] []))}))

(rf/reg-event-fx ::handle-fetch-failure                     ;;DEPRECATED
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "fetch patient failure: response " response)
    {:db (-> db
             (dissoc :patient/search-results)
             (assoc-in [:errors ::fetch] "Failed to fetch patient: unable to connect to server. Please check your connection and retry."))}))

(defn ^:deprecated fetch-patient
  [s & {:keys [handler error-handler token] :or {error-handler srv/default-error-handler}}]
  (srv/do! {:params        (make-cav-fetch-patient-op {:pas-identifier s})
            :token         token
            :handler       #(handler (get % 'wales.nhs.cavuhb/fetch-patient))
            :error-handler error-handler}))
