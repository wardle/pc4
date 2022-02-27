(ns eldrix.pc4-ward.patient.events
  "Events relating to patients.

  Data would be better fetched as required on a per-component level cf. fulcro."
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
   :t_patient/death_certificate
   {:t_patient/address [:uk.gov.ons.nhspd/LSOA11
                        :t_address/lsoa]}])

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

(def patient-medication-properties
  [:t_medication/id
   :t_medication/date_from
   :t_medication/date_to
   :t_medication/more_information
   {:t_medication/medication [:info.snomed.Concept/id
                              :info.snomed.Concept/preferredDescription
                              :info.snomed.Concept/parentRelationshipIds]}])

(def full-patient-properties
  ;; at the moment we download all of the data in one go - this should be replaced by
  ;; fetches that are made when a specific panel is opened. Simpler, more current information
  ;; TODO: break up into modules instead for specific purposes
  (into
    core-patient-properties
    [{:t_patient/diagnoses patient-diagnosis-properties}
     {:t_patient/medications patient-medication-properties}
     {:t_patient/encounters [:t_encounter/id
                             :t_encounter/episode_fk
                             :t_encounter/date_time
                             :t_encounter_template/id
                             {:t_encounter/encounter_template [:t_encounter_template/title :t_encounter_template/id]}
                             :t_encounter/is_deleted
                             :t_encounter/active
                             :t_encounter/form_edss
                             :t_encounter/form_ms_relapse
                             :t_encounter/form_weight_height
                             :t_encounter/form_smoking_history]}
     {:t_patient/summary_multiple_sclerosis [:t_summary_multiple_sclerosis/id
                                             :t_summary_multiple_sclerosis/events
                                             :t_ms_diagnosis/id ; we flatten this to-one attribute
                                             :t_ms_diagnosis/name]}
     {:t_patient/episodes [:t_episode/id
                           :t_episode/project_fk
                           :t_episode/patient_fk
                           {:t_episode/project [:t_project/id
                                                :t_project/name
                                                :t_project/title
                                                :t_project/active?]}
                           :t_episode/date_registration
                           :t_episode/date_discharge
                           :t_episode/stored_pseudonym
                           {:t_episode/diagnoses patient-diagnosis-properties}
                           :t_episode/status]}
     :t_patient/results]))

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

(defn make-save-medication
  [params]
  [{(list 'pc4.rsdb/save-medication params)
    patient-medication-properties}])

(defn make-save-ms-diagnosis
  [patient-identifier ms-diagnosis-id]
  [{(list 'pc4.rsdb/save-ms-diagnosis {:t_patient/patient_identifier patient-identifier
                                       :t_ms_diagnosis/id            ms-diagnosis-id})
    ['*]}])

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
    (tap> {:db db})
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
             (assoc :patient/loading? true)
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
             (dissoc :patient/current :patient/loading?)
             (assoc-in [:errors :open-patient] "Failed to fetch patient: unable to connect to server. Please check your connection and retry."))}))

(rf/reg-event-fx ::handle-fetch-pseudonymous-patient-response
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/search-patient-by-pseudonym}]]
    (js/console.log "fetch pseudonymous patient response: " result)
    {:db (-> db
             (dissoc :patient/loading?)
             (assoc-in [:patient/current :patient] result))}))

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

(rf/reg-event-fx ::handle-failure-response
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "pathom effect failure " response)
    (tap> {:error    "pathom call failure"
           :response response})
    {}))






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

(rf/reg-event-fx ::save-medication
  (fn [{db :db} [_ params]]
    {:db (-> db
             (update-in [:errors] dissoc ::save-medication))
     :fx [[:pathom {:params     (make-save-medication params)
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::handle-save-diagnosis
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/save-diagnosis}]]
    (js/console.log "save diagnosis response: " result)
    {:fx [[:dispatch-n [[::refresh-current-patient]
                        [::clear-diagnosis]
                        [::clear-medication]]]]}))


(rf/reg-event-fx ::save-ms-diagnosis
  []
  (fn [{db :db} [_ {patient-identifier :t_patient/patient_identifier ms-diagnosis-id :t_ms_diagnosis/id}]]
    {:fx [[:pathom {:params     (make-save-ms-diagnosis patient-identifier ms-diagnosis-id)
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))



(rf/reg-event-db ::set-current-medication
  []
  (fn [db [_ medication]]
    (-> db
        (assoc-in [:patient/current :current-medication] medication))))

(rf/reg-event-db ::clear-medication
  []
  (fn [db _]
    (-> db
        (update-in [:patient/current] dissoc :current-medication))))

(rf/reg-event-fx ::save-pseudonymous-postcode
  []
  (fn [{db :db} [_ {patient-identifier :t_patient/patient_identifier postcode :uk.gov.ons.nhspd/PCD2 :as params}]]
    {:fx [[:pathom {:params     [{(list 'pc4.rsdb/save-pseudonymous-patient-postal-code params)
                                  ['*]}]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::save-ms-event
  []
  (fn [{db :db} [_ params]]
    (js/console.log "saving ms event " params)
    {:fx [[:pathom {:params     [{(list 'pc4.rsdb/save-ms-event params)
                                  ['*]}]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::delete-ms-event
  []
  (fn [{db :db} [_ params]]
    (js/console.log "deleting ms event " params)
    {:fx [[:pathom {:params     [{(list 'pc4.rsdb/delete-ms-event params)
                                  ['*]}]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))


(rf/reg-event-fx ::save-encounter
  []
  (fn [{db :db} [_ params]]
    (let [params' (cond-> params
                          (:t_encounter_template/id params)
                          (assoc :t_encounter/encounter_template_fk (:t_encounter_template/id params))
                          (:t_ms_disease_course/id params)
                          (assoc :t_form_ms_relapse/ms_disease_course_fk (:t_ms_disease_course/id params))
                          (and (:t_form_edss/edss_score params) (not (:t_form_ms_relapse/in_relapse params)))
                          (assoc :t_form_ms_relapse/in_relapse false)
                          (and (nil? (:t_ms_disease_course/id params)) ;; this would be better as a database default value!
                               (or (:t_form_edss/edss_score params)
                                   (not (nil? (:t_form_ms_relapse/in_relapse params)))))
                          (assoc :t_form_ms_relapse/ms_disease_course_fk 1)
                          (and (:t_smoking_history/status params) (not (:t_smoking_history/current_cigarettes_per_day params)))
                          (assoc :t_smoking_history/current_cigarettes_per_day 0))]
      (js/console.log "saving encounter" params)
      {:fx [[:pathom {:params     [{(list 'pc4.rsdb/save-encounter params')
                                    ['*]}]
                      :token      (get-in db [:authenticated-user :io.jwt/token])
                      :on-success [::handle-save-diagnosis]
                      :on-failure [::handle-failure-response]}]]})))

(rf/reg-event-fx ::delete-encounter
  []
  (fn [{db :db} [_ {encounter-id :t_encounter/id patient-identifier :t_patient/patient_identifier}]]
    (js/console.log "deleting encounter" encounter-id)
    {:fx [[:pathom {:params     [(list 'pc4.rsdb/delete-encounter {:t_encounter/id encounter-id :t_patient/patient_identifier patient-identifier})]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::save-result
  []
  (fn [{db :db} [_ result]]
    {:fx [[:pathom {:params     [(list 'pc4.rsdb/save-result result)]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-result]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::handle-save-result
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/save-result}]]
    (js/console.log "save result response: " result)
    {:fx [[:dispatch-n [[::refresh-current-patient]
                        [::clear-current-result]]]]}))

(rf/reg-event-db ::clear-current-result
  []
  (fn [db _]
    (-> db
        (update-in [:patient/current] dissoc :current-result))))

(rf/reg-event-db ::set-current-result
  []
  (fn [db [_ result]]
    (-> db
        (update-in [:patient/current] assoc :current-result result))))

(rf/reg-event-fx ::delete-result
  []
  (fn [{db :db} [_ result]]
    {:fx [[:pathom {:params     [(list 'pc4.rsdb/delete-result result)]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::notify-death
  []
  (fn [{db :db} [_ params]]
    {:fx [[:pathom {:params     [(list 'pc4.rsdb/notify-death params)]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::save-admission
  []
  (fn [{db :db} [_ admission]]
    {:fx [[:pathom {:params     [(list 'pc4.rsdb/save-admission admission)]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))

(rf/reg-event-fx ::delete-admission
  []
  (fn [{db :db} [_ admission]]
    {:fx [[:pathom {:params     [(list 'pc4.rsdb/delete-admission admission)]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-save-diagnosis]
                    :on-failure [::handle-failure-response]}]]}))
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
