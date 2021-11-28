(ns com.eldrix.pc4.server.rsdb.patients
  (:require [clojure.tools.logging.readable :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.sql]
            [honey.sql :as sql]
            [clojure.string :as str]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [com.eldrix.pc4.server.rsdb.projects :as projects]
            [com.eldrix.pc4.server.dates :as dates]
            [com.eldrix.clods.core :as clods]
            [clojure.spec.alpha :as s])
  (:import (java.time LocalDate LocalDateTime)))



(defn fetch-patient-addresses
  "Returns patient addresses."
  [conn patient-id]
  (db/execute! conn (sql/format {:select   [:id :address1 :address2 :address3 :address4 [:postcode_raw :postcode]
                                            :date_from :date_to :housing_concept_fk :ignore_invalid_address]
                                 :from     [:t_address]
                                 :where    [:= :patient_fk patient-id]
                                 :order-by [[:date_to :desc] [:date_from :desc]]})))

(defn address-for-date
  "Determine the address on a given date, the current date if none given."
  ([sorted-addresses]
   (address-for-date sorted-addresses nil))
  ([sorted-addresses ^LocalDate date]
   (->> sorted-addresses
        (filter #(dates/in-range? (:t_address/date_from %) (:t_address/date_to %) date))
        first)))

(defn fetch-episodes
  "Return the episodes for the given patient."
  [conn patient-identifier]
  (jdbc/execute!
    conn
    (sql/format {:select [:t_episode/*]
                 :from   [:t_episode]
                 :join   [:t_patient [:= :patient_fk :t_patient/id]]
                 :where  [:= :patient_identifier patient-identifier]})))

(defn active-episodes
  ([conn patient-identifier]
   (active-episodes conn patient-identifier (LocalDate/now)))
  ([conn patient-identifier ^LocalDate on-date]
   (->> (fetch-episodes conn patient-identifier)
        (filter #(contains? #{:referred :registered} (projects/episode-status % on-date))))))

(defn active-project-identifiers
  "Returns a set of project identifiers representing the projects to which
  the patient belongs.
  Parameters:
  - conn                : database connection or connection pool
  - patient-identifier  : patient identifier
  - include-parents?    : (optional, default true) - include transitive parents."
  ([conn patient-identifier] (active-project-identifiers conn patient-identifier true))
  ([conn patient-identifier include-parents?]
   (let [active-projects (set (map :t_episode/project_fk (active-episodes conn patient-identifier)))]
     (if-not include-parents?
       active-projects
       (into active-projects (flatten (map #(projects/all-parents-ids conn %) active-projects)))))))

(defn patient-pks-in-projects-sql
  [project-ids on-date]
  (sql/format {:select-distinct :patient_fk
               :from            :t_episode
               :where           [:and
                                 [:in :project_fk project-ids]
                                 [:or
                                  [:is :t_episode/date_discharge nil]
                                  [:> :date_discharge on-date]]
                                 [:or
                                  [:is :date_registration nil]
                                  [:< :date_registration on-date]
                                  [:= :date_registration on-date]]]}))

(defn patient-pks-in-projects
  "Return a set of patients in the projects specified, on the date `on-date`.
  Parameters:
  - conn        : database connection, or pool
  - project-ids : collection of project identifiers
  - on-date     : (optional, default now), date on which to determine membership

  Returns a set of patient primary keys."
  ([conn project-ids] (patient-pks-in-projects conn project-ids (LocalDate/now)))
  ([conn project-ids ^LocalDate on-date]
   (transduce
     (map :t_episode/patient_fk)
     conj
     #{}
     (jdbc/plan conn (patient-pks-in-projects-sql project-ids on-date)))))

(defn pks->identifiers
  "Turn patient primary keys into identifiers."
  [conn pks]
  (transduce
    (map :t_patient/patient_identifier)
    conj
    #{}
    (jdbc/plan conn (sql/format {:select :patient_identifier :from :t_patient :where [:in :id pks]}))))

(defn pk->identifier
  "Turn a single patient primary key into a patient identifier."
  [conn pk]
  (:t_patient/patient_identifier (next.jdbc.plan/select-one! conn [:t_patient/patient_identifier] (sql/format {:select :patient_identifier :from :t_patient :where [:= :id pk]}))))

(defn patient-pks-on-medications-sql
  [medication-concept-ids]
  (sql/format {:select-distinct :patient_fk
               :from            :t_medication
               :where           [:in :medication_concept_fk medication-concept-ids]}))

(defn patient-pks-on-medications
  "Return a set of patient primary keys who are recorded as ever being on one of the
  medications specified.
  Parameters:
  - conn       : database connection, or pool
  - medication-concept=ids : a collection of concept identifiers.

  Returns a set of patient primary keys, not patient_identifier."
  [conn medication-concept-ids]
  (transduce
    (map :t_medication/patient_fk)
    conj
    #{}
    (jdbc/plan conn (patient-pks-on-medications-sql medication-concept-ids))))


(defn create-diagnosis [conn {:t_diagnosis/keys  [concept_fk date_onset date_onset_accuracy date_diagnosis date_diagnosis_accuracy date_to date_to_accuracy status]
                              patient-identifier :t_patient/patient_identifier}]
  (db/execute-one! conn
                   (sql/format {:insert-into [:t_diagnosis]
                                :values      [{:date_onset              date_onset
                                               :date_onset_accuracy     date_onset_accuracy
                                               :date_diagnosis          date_diagnosis
                                               :date_diagnosis_accuracy date_diagnosis_accuracy
                                               :date_to                 date_to
                                               :date_to_accuracy        date_to_accuracy
                                               :status                  (name status)
                                               :concept_fk              concept_fk
                                               :patient_fk              {:select :t_patient/id
                                                                         :from   [:t_patient]
                                                                         :where  [:= :t_patient/patient_identifier patient-identifier]}}]})
                   {:return-keys true}))

(defn update-diagnosis
  [conn {:t_diagnosis/keys [concept_fk id date_onset date_onset_accuracy date_diagnosis date_diagnosis_accuracy date_to date_to_accuracy status]}]
  (db/execute-one! conn
                   (sql/format {:update [:t_diagnosis]
                                :set    {:date_onset              date_onset
                                         :date_onset_accuracy     date_onset_accuracy
                                         :date_diagnosis          date_diagnosis
                                         :date_diagnosis_accuracy date_diagnosis_accuracy
                                         :date_to                 date_to
                                         :date_to_accuracy        date_to_accuracy
                                         :status                  (name status)}
                                :where  [:and
                                         [:= :t_diagnosis/concept_fk concept_fk]
                                         [:= :t_diagnosis/id id]]})
                   {:return-keys true}))

(defn create-medication [conn {:t_medication/keys [medication_concept_fk date_from date_to]
                               patient-identifier :t_patient/patient_identifier}]
  (db/execute-one! conn
                   (sql/format {:insert-into [:t_medication]
                                :values      [{:medication_concept_fk medication_concept_fk
                                               :date_from             date_from
                                               :date_to               date_to
                                               :patient_fk            {:select :t_patient/id
                                                                       :from   [:t_patient]
                                                                       :where  [:= :t_patient/patient_identifier patient-identifier]}}]})
                   {:return-keys true}))

(defn update-medication
  [conn {:t_medication/keys [medication_concept_fk id date_from date_to]}]
  (db/execute-one! conn
                   (sql/format {:update [:t_medication]
                                :set    {:medication_concept_fk medication_concept_fk
                                         :date_from             date_from
                                         :date_to               date_to}
                                :where  [:and
                                         [:= :t_medication/medication_concept_fk medication_concept_fk]
                                         [:= :t_medication/id id]]})
                   {:return-keys true}))

(defn delete-medication
  [conn {:t_medication/keys [id]}]
  (db/execute-one! conn
                   (sql/format {:delete-from [:t_medication]
                                :where       [:= :t_medication/id id]})))

(defn fetch-summary-multiple-sclerosis
  [conn patient-identifier]
  (let [sms (db/execute! conn (sql/format {:select    [:t_summary_multiple_sclerosis/id
                                                       :t_ms_diagnosis/id :t_ms_diagnosis/name
                                                       :t_patient/patient_identifier
                                                       :t_summary_multiple_sclerosis/ms_diagnosis_fk]
                                           :from      [:t_summary_multiple_sclerosis]
                                           :join      [:t_patient [:= :patient_fk :t_patient/id]]
                                           :left-join [:t_ms_diagnosis [:= :ms_diagnosis_fk :t_ms_diagnosis/id]]
                                           :where     [:and
                                                       [:= :t_patient/patient_identifier patient-identifier]
                                                       [:= :t_summary_multiple_sclerosis/is_deleted "false"]]
                                           :order-by  [[:t_summary_multiple_sclerosis/date_created :desc]]}))]
    (when (> (count sms) 1)
      (log/error "Found more than one t_summary_multiple_sclerosis for patient" {:patient-identifier patient-identifier :results sms}))
    (first sms)))

(def ^:private ms-event-relapse-types #{"RO" "RR" "RU" "SO" "SN" "SW" "SU" "PR" "UK" "RW"})

(defn ms-event-is-relapse?
  "Is the given MS event a type of relapse?"
  [{code :t_ms_event_type/abbreviation}]
  (boolean (ms-event-relapse-types code)))

(defn fetch-ms-events
  "Return the MS events for the given summary multiple sclerosis.
  Each event includes an additional :t_ms_event/is_relapse boolean property
  based on whether the event is a type to be counted as a 'relapse' rather than
  another kind of event, such as onset of progressive disease."
  [conn sms-id]
  (->> (db/execute! conn (sql/format {:select    [:t_ms_event/* :t_ms_event_type/*]
                                      :from      [:t_ms_event]
                                      :left-join [:t_ms_event_type [:= :t_ms_event_type/id :ms_event_type_fk]]
                                      :where     [:= :t_ms_event/summary_multiple_sclerosis_fk sms-id]}))
       (map #(assoc % :t_ms_event/is_relapse (ms-event-is-relapse? %)))))

(defn patient-identifier-for-ms-event
  "Returns the patient identifier for a given MS event"
  [conn {ms-event-id :t_ms_event/id :as _event}]
  (:t_patient/patient_identifier (db/execute-one! conn (sql/format {:select [:patient_identifier]
                                                                    :from   [:t_patient]
                                                                    :join   [:t_summary_multiple_sclerosis [:= :t_summary_multiple_sclerosis/patient_fk :t_patient/id]
                                                                             :t_ms_event [:= :t_ms_event/summary_multiple_sclerosis_fk :t_summary_multiple_sclerosis/id]]
                                                                    :where  [:= :t_ms_event/id ms-event-id]}))))

(defn delete-ms-event! [conn {ms-event-id :t_ms_event/id}]
  (db/execute-one! conn (sql/format {:delete-from [:t_ms_event]
                                     :where       [:= :t_ms_event/id ms-event-id]})))

(defn save-ms-diagnosis! [conn {ms-diagnosis-id    :t_ms_diagnosis/id
                                patient-identifier :t_patient/patient_identifier
                                user-id            :t_user/id
                                :as                params}]
  (jdbc/with-transaction
    [tx conn {:isolation :serializable}]
    (if-let [sms (fetch-summary-multiple-sclerosis tx patient-identifier)]
      (do
        (next.jdbc.sql/update! tx :t_summary_multiple_sclerosis
                               {:ms_diagnosis_fk ms-diagnosis-id
                                :user_fk         user-id}
                               {:id (:t_summary_multiple_sclerosis/id sms)}))

      (jdbc/execute-one! tx (sql/format
                              {:insert-into [:t_summary_multiple_sclerosis]
                               ;; note as this table uses legacy WO horizontal inheritance, we use t_summary_seq to generate identifiers manually.
                               :values      [{:t_summary_multiple_sclerosis/id                  {:select [[[:nextval "t_summary_seq"]]]}
                                              :t_summary_multiple_sclerosis/written_information ""
                                              :t_summary_multiple_sclerosis/under_active_review "true"
                                              :t_summary_multiple_sclerosis/date_created        (LocalDateTime/now)
                                              :t_summary_multiple_sclerosis/ms_diagnosis_fk     ms-diagnosis-id
                                              :t_summary_multiple_sclerosis/user_fk             user-id
                                              :t_summary_multiple_sclerosis/patient_fk          {:select :t_patient/id
                                                                                                 :from   [:t_patient]
                                                                                                 :where  [:= :t_patient/patient_identifier patient-identifier]}}]})))))
(def default-ms-event
  {:t_ms_event/site_arm_motor    false
   :t_ms_event/site_ataxia       false
   :t_ms_event/site_bulbar       false
   :t_ms_event/site_cognitive    false
   :t_ms_event/site_diplopia     false
   :t_ms_event/site_face_motor   false
   :t_ms_event/site_face_sensory false
   :t_ms_event/site_leg_motor    false
   :t_ms_event/site_limb_sensory false
   :t_ms_event/site_optic_nerve  false
   :t_ms_event/site_other        false
   :t_ms_event/site_psychiatric  false
   :t_ms_event/site_sexual       false
   :t_ms_event/site_sphincter    false
   :t_ms_event/site_unknown      false
   :t_ms_event/site_vestibular   false})

(defn save-ms-event! [conn event]
  (if (:t_ms_event/id event)
    (next.jdbc.sql/update! conn
                           :t_ms_event
                           (merge default-ms-event event)
                           {:t_ms_event/id (:t_ms_event/id event)})
    (next.jdbc.sql/insert! conn :t_ms_event (merge default-ms-event event))))

(defn save-pseudonymous-patient-lsoa!
  "Special function to store LSOA11 code in place of postal code when working
  with pseudonymous patients. We know LSOA11 represents 1000-1500 members of the
  population. We don't keep an address history, so simply write an address with
  no dates which is the 'current'."
  [conn {patient-identifier :t_patient/patient_identifier lsoa11 :uk.gov.ons.nhspd/LSOA11 :as params}]
  (when-let [patient (db/execute-one! conn (sql/format {:select [:id :patient_identifier :status] :from :t_patient :where [:= :patient_identifier patient-identifier]}))]
    (if-not (= :PSEUDONYMOUS (:t_patient/status patient))
      (throw (ex-info "Invalid operation: cannot save LSOA for non-pseudonymous patient" params))
      (jdbc/with-transaction
        [tx conn {:isolation :serializable}]
        (let [addresses (fetch-patient-addresses tx (:t_patient/id patient))
              current-address (address-for-date addresses)]
          ;; we currently do not support an address history for pseudonymous patients, so either edit or create
          ;; current address
          (if current-address
            (next.jdbc.sql/update! tx :t_address
                                   {:t_address/date_to                nil :t_address/date_from nil
                                    :t_address/address1               lsoa11
                                    :t_address/postcode_raw           nil :t_address/postcode_fk nil
                                    :t_address/ignore_invalid_address "true"
                                    :t_address/address2               nil :t_address/address3 nil :t_address/address4 nil}
                                   {:t_address/id (:t_address/id current-address)})
            (next.jdbc.sql/insert! tx :t_address {:t_address/address1               lsoa11
                                                  :t_address/ignore_invalid_address "true"
                                                  :t_address/patient_fk             (:t_patient/id patient)})))))))



(s/def ::save-encounter (s/keys :req [:t_encounter/encounter_template_fk
                                      :t_encounter/episode_fk
                                      :t_patient/patient_identifier
                                      :t_encounter/date_time]
                                :opt [:t_encounter/id]))

(defn save-encounter!
  "Save an encounter. If there is no :t_encounter/id then a new encounter will
  be created.
  TODO: set encounter lock time on creation or edit...."
  [conn {encounter-id          :t_encounter/id
         encounter-template-id :t_encounter/encounter_template_fk
         episode-id            :t_encounter/episode_fk
         patient-identifier    :t_patient/patient_identifier
         date-time             :t_encounter/date_time
         :as                   encounter}]
  (when-not (s/valid? ::save-encounter encounter)
    (throw (ex-info "Invalid save encounter" (s/explain-data ::save-encounter encounter))))
  (log/info "saving encounter" {:encounter_id encounter-id :encounter encounter})
  (if encounter-id
    (db/execute-one! conn (sql/format {:update [:t_encounter]
                                       :where  [:= :id encounter-id]
                                       :set    {:t_encounter/encounter_template_fk encounter-template-id
                                                :t_encounter/date_time             date-time}})
                     {:return-keys true})
    (db/execute-one! conn (sql/format {:insert-into [:t_encounter]
                                       :values      [{:date_time             date-time
                                                      :encounter_template_fk encounter-template-id
                                                      :patient_fk            {:select :t_patient/id
                                                                              :from   [:t_patient]
                                                                              :where  [:= :t_patient/patient_identifier patient-identifier]}
                                                      :episode_fk            episode-id}]})
                     {:return-keys true})))


(defn delete-encounter!
  [conn encounter-id]
  (db/execute-one! conn (sql/format {:update [:t_encounter]
                                     :where  [:= :id encounter-id]
                                     :set    {:t_encounter/is_deleted "true"}})))

(comment
  (patients-in-projects-sql [1 3 32] (LocalDate/now))
  (patient-identifier-for-ms-event conn 7563)
  (patients-in-projects conn #{1 3 32})
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 2}))

  (save-ms-event! conn {:t_ms_event/ms_event_type_fk              1
                        :t_ms_event/date                          (LocalDate/now)
                        :t_ms_event/summary_multiple_sclerosis_fk 4711})

  (next.jdbc/execute-one! conn (sql/format {:select [[[:nextval "t_summary_seq"]]]}))
  (sql/format {:select [[[:nextval "t_summary_seq"]]]})
  (fetch-summary-multiple-sclerosis conn 1)
  (count (fetch-ms-events conn 4708))
  (save-ms-diagnosis! conn {:t_ms_diagnosis/id 12 :t_patient/patient_identifier 3 :t_user/id 1})
  (fetch-episodes conn 15203)
  (save-pseudonymous-patient-postal-code! conn {:t_patient/patient_identifier 124018
                                                :uk.gov.ons.nhspd/LSOA11      "E01008956"})
  (fetch-patient-addresses conn 124018)
  (active-episodes conn 15203)
  (active-project-identifiers conn 15203)
  (map :t_episode/project_fk (active-episodes conn 15203))
  (projects/all-parents-ids conn 12)
  (projects/all-parents-ids conn 37)
  (projects/all-parents-ids conn 76)
  (projects/all-parents-ids conn 59)
  (save-encounter! conn {:t_encounter/date_time             (LocalDateTime/now)
                         :t_encounter/episode_fk            48224
                         :t_encounter/encounter_template_fk 469})
  )