(ns com.eldrix.pc4.server.rsdb.patients
  (:require [clojure.tools.logging.readable :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.sql]
            [honey.sql :as sql]
            [clojure.string :as str]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [com.eldrix.pc4.server.rsdb.projects :as projects]
            [com.eldrix.pc4.server.dates :as dates]
            [com.eldrix.clods.core :as clods])
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


(comment
  (patients-in-projects-sql [1 3 32] (LocalDate/now))
  (patients-in-projects conn #{1 3 32})
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 2}))

  (next.jdbc/execute-one! conn (sql/format {:select [[[:nextval "t_summary_seq"]]]}))
  (sql/format {:select [[[:nextval "t_summary_seq"]]]})
  (fetch-summary-multiple-sclerosis conn 1)
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

  )