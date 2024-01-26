(ns com.eldrix.pc4.rsdb.patients
  (:require [clojure.set :as set]
            [clojure.tools.logging.readable :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.sql]
            [honey.sql :as sql]
            [com.eldrix.pc4.fhir :as fhir]
            [com.eldrix.pc4.rsdb.db :as db]
            [com.eldrix.pc4.rsdb.projects :as projects]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.clods.core :as clods])
  (:import (java.time LocalDate LocalDateTime)
           (java.time.format DateTimeFormatter)
           (java.time.temporal Temporal)))

(s/def ::clods #(satisfies? clods/ODS %))

(s/fdef set-cav-authoritative-demographics!
  :args (s/cat :clods ::clods, :txn ::db/txn
               :pt (s/keys :req [:t_patient/id])
               :ph (s/keys :req [:t_patient_hospital/id
                                 :t_patient_hospital/patient_fk
                                 (or :t_patient_hospital/hospital_fk :t_patient_hospital/hospital_identifier)
                                 :t_patient_hospital/patient_identifier])))
(defn set-cav-authoritative-demographics!
  "Set the authoritative source of demographics for a patient.
  This will be deprecated in the future and can only be used to use CAVUHB as
  the authoritative source for demographics data for the patient specified.
  Parameters:
  - ods  - 'clods' organisational data services instance
  - txn  - database connection [in a transaction]
  - pt   - patient, with key :t_patient/id
  - ph   - patient-hospital, with keys referencing hospital and patient identifier"
  [ods txn {patient-pk :t_patient/id :as pt}
   {ph-id :t_patient_hospital/id crn :t_patient_hospital/patient_identifier :t_patient_hospital/keys [patient_fk hospital_fk hospital_identifier] :as ph}]
  (when-not (= patient-pk patient_fk)
    (throw (ex-info "Mismatch between patient ids:" {:patient pt :patient-hospital ph})))
  (when (str/blank? crn)
    (throw (ex-info "Missing hospital number " ph)))
  (let [{:keys [root extension]} (if hospital_fk {:root nil :extension hospital_fk}
                                                 (clods/parse-org-id hospital_identifier))
        org (clods/fetch-org ods root extension)
        cavuhb (clods/fetch-org ods nil "7A4")]
    (if-not (clods/related? ods org cavuhb)
      (throw (ex-info "Invalid organisation. Must be CAVUHB." {:patient ph :org org}))
      ;; first, set patient record so it uses an authority for demographics
      (do
        (jdbc/execute-one! txn (sql/format {:update :t_patient
                                            :set    {:authoritative_demographics "CAVUHB"}
                                            :where  [:= :id patient-pk]}))
        ;; next, set the patient_hospital record to be authoritative
        (jdbc/execute-one! txn (sql/format {:update :t_patient_hospital
                                            :set    {:authoritative true}
                                            :where  [:and [:= :id ph-id] [:= :patient_fk patient-pk]]}))
        ;; and finally, ensure all other hospital numbers are not authoritative
        (jdbc/execute-one! txn (sql/format {:update :t_patient_hospital
                                            :set    {:authoritative false}
                                            :where  [:and [:<> :id ph-id] [:= :patient_fk patient-pk]]}))))))


(defn fetch-patient [conn {patient-pk :t_patient/id}]
  (db/execute-one! conn (sql/format {:select :*
                                     :from   :t_patient
                                     :where  [:= :id patient-pk]})))

(s/fdef fetch-patient-addresses
  :args (s/cat :conn ::db/conn :patient (s/keys :req [:t_patient/id])))
(defn fetch-patient-addresses
  "Returns patient addresses ordered using date_from descending."
  [conn {patient-pk :t_patient/id}]
  (db/execute! conn (sql/format {:select   [:id :address1 :address2 :address3 :address4 [:postcode_raw :postcode]
                                            :date_from :date_to :housing_concept_fk :ignore_invalid_address]
                                 :from     [:t_address]
                                 :where    [:= :patient_fk patient-pk]
                                 :order-by [[:date_to :desc] [:date_from :desc]]})))

(s/fdef address-for-date
  :args (s/cat :addresses (s/coll-of (s/keys :req [:t_address/date_from :t_address/date_to]))
               :on-date (s/? (s/nilable #(instance? LocalDate %)))))
(defn address-for-date
  "Given a collection of addresses sorted by date_from in descending order,
  determine the address on a given date, the current date if none given."
  ([sorted-addresses]
   (address-for-date sorted-addresses nil))
  ([sorted-addresses ^LocalDate date]
   (->> sorted-addresses
        (filter #(db/date-in-range? (:t_address/date_from %) (:t_address/date_to %) date))
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

(s/fdef fetch-death-certificate
  :args (s/cat :conn ::db/conn :patient (s/keys :req [:t_patient/id])))
(defn fetch-death-certificate
  "Return a death certificate for the patient specified.
  Parameters:
  conn - a database connection
  patient - a map containing :t_patient/id"
  [conn {patient-pk :t_patient/id}]
  (jdbc/execute-one! conn (sql/format {:select [:*]
                                       :from   [:t_death_certificate]
                                       :where  [:= :patient_fk patient-pk]})))

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
       (into active-projects (mapcat #(projects/all-parents-ids conn %) active-projects))))))

(s/def ::on-date #(instance? LocalDate %))
(s/def ::patient-status #{:FULL :PSEUDONYMOUS :STUB :FAKE :DELETED :MERGED})
(s/def ::discharged? boolean?)
(s/fdef patient-ids-in-projects
  :args (s/cat :conn ::db/conn
               :project-ids (s/coll-of pos-int?)
               :opts (s/keys* :opt-un [::on-date ::patient-status ::discharged?])))
(defn patient-ids-in-projects
  "Returns a set of patient identifiers in the projects specified.
  Parameters:
  - conn        : database connectable
  - project-ids : project identifiers
  - on-date     : optional, determine membership on this date, default today
  - patient-status : 'status' of patient.
  - "
  [conn project-ids & {:keys [^LocalDate on-date patient-status discharged?]
                       :or   {on-date        (LocalDate/now)
                              patient-status #{:FULL :PSEUDONYMOUS}
                              discharged?    false}}]
  (into #{} (map :t_patient/patient_identifier)
        (jdbc/plan conn (sql/format {:select-distinct :patient_identifier
                                     :from            :t_patient
                                     :left-join       [:t_episode [:= :patient_fk :t_patient/id]]
                                     :where           [:and
                                                       [:in :project_fk project-ids]
                                                       [:in :t_patient/status (map name patient-status)]
                                                       (when-not discharged?
                                                         [:or
                                                          [:is :t_episode/date_discharge nil]
                                                          [:> :date_discharge on-date]])
                                                       [:or
                                                        [:is :date_registration nil]
                                                        [:< :date_registration on-date]
                                                        [:= :date_registration on-date]]]}))))


(s/fdef pks->identifiers
  :args (s/cat :conn ::db/conn :pks (s/coll-of pos-int?)))
(defn pks->identifiers
  "Turn patient primary keys into identifiers."
  [conn pks]
  (into #{} (map :t_patient/patient_identifier)
        (jdbc/plan conn (sql/format {:select :patient_identifier :from :t_patient :where [:in :id pks]}))))

(defn pk->identifier
  "Turn a single patient primary key into a patient identifier."
  [conn pk]
  (:t_patient/patient_identifier
    (next.jdbc.plan/select-one! conn [:t_patient/patient_identifier]
                                (sql/format {:select :patient_identifier :from :t_patient :where [:= :id pk]}))))

(defn patient-identifier->pk
  "Turn a single patient identifier into the primary key."
  [conn patient-identifier]
  (:t_patient/id
    (next.jdbc.plan/select-one! conn [:t_patient/id] (sql/format {:select :id :from :t_patient :where [:= :patient_identifier patient-identifier]}))))



(defn paged-encounters-sql
  "Generate SQL for paging through encounters in descending date time order
  for a given patient.
  Parameters:
  - patient-pk : patient's primary key
  - first-id   : (optional) first encounter id to return
  - page-size  : (optional, default 20), number of results per page

  One additional row will be returned than requested. This should be used to
  generate the `first-id` for the next page request."
  [patient-pk {:keys [first-id page-size] :or {page-size 20}}]
  (cond-> {:select   :id :from :t_encounter
           :join     [:t_patient [:= :t_patient.id :patient_fk]]
           :order-by [[:date_time :desc] [:id :desc]]}
    (pos-int? page-size)
    (assoc :limit (inc page-size))
    first-id
    (assoc :where [:>= :id first-id])))







(defn diagnosis-active?
  ([diagnosis] (diagnosis-active? diagnosis (LocalDate/now)))
  ([{:t_diagnosis/keys [^LocalDate date_diagnosis ^LocalDate date_to]} ^LocalDate on-date]
   (and (or (nil? date_diagnosis) (.isBefore date_diagnosis on-date) (.isEqual date_diagnosis on-date))
        (or (nil? date_to) (.isAfter date_to on-date)))))

(s/fdef create-diagnosis
  :args (s/cat :conn ::db/conn
               :diagnosis (s/keys :req [:t_patient/patient_identifier
                                        :t_diagnosis/concept_fk :t_diagnosis/status]
                                  :opt [:t_diagnosis/date_onset :t_diagnosis/date_onset_accuracy
                                        :t_diagnosis/date_diagnosis :t_diagnosis/date_diagnosis_accuracy
                                        :t_diagnosis/date_to :t_diagnosis/date_to_accuracy])))

(defn create-diagnosis
  [conn {:t_diagnosis/keys  [concept_fk date_onset date_onset_accuracy date_diagnosis date_diagnosis_accuracy date_to date_to_accuracy status]
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
  [conn {:t_diagnosis/keys [id concept_fk date_onset date_onset_accuracy date_diagnosis date_diagnosis_accuracy date_to date_to_accuracy status]}]
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


(s/fdef fetch-medications
  :args (s/cat :conn ::db/conn :patient (s/keys :req [(or :t_patient/patient_identifier :t_patient/id)])))
(defn fetch-medications
  "Return all medication for a patient. Data are returned as a sequence of maps
  each representing a medication record."
  [conn {patient-identifier :t_patient/patient_identifier, patient-pk :t_patient/id :as req}]
  (db/execute! conn (sql/format
                      (cond
                        patient-pk
                        {:select [:t_medication/*]
                         :from   [:t_medication]
                         :where  [:= :patient_fk patient-pk]}
                        patient-identifier
                        {:select [:t_medication/*]
                         :from   [:t_medication]
                         :join   [:t_patient [:= :patient_fk :t_patient/id]]
                         :where  [:= :patient-identifier patient-identifier]}
                        :else
                        (throw (ex-info "Either t_patient/id or t_patient/patient_identifier must be provided" req))))))

(defn fetch-medication-events
  "Returns medication events for the medication records specified.
  Results are guaranteed to match the ordering of the input identifiers."
  [conn medication-ids]
  (let [results (db/execute! conn (sql/format {:select [:*]
                                               :from   :t_medication_event
                                               :where  [:in :medication_fk medication-ids]}))
        index (group-by :t_medication_event/medication_fk results)]
    (reduce (fn [acc id]
              (conj acc (get index id []))) [] medication-ids)))

(s/fdef fetch-medications-and-events
  :args (s/cat :conn ::db/repeatable-read-txn :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier)])))
(defn fetch-medications-and-events
  "Returns a sequence of medications, as well as any medication events nested
  under key :t_medication/events. "
  [txn patient]
  (let [meds (fetch-medications txn patient)
        evts (when (seq meds) (fetch-medication-events txn (map :t_medication/id meds)))]
    (map #(assoc %1 :t_medication/events %2) meds evts)))

(def default-medication-event
  {:t_medication_event/type                       :ADVERSE_EVENT
   :t_medication_event/severity                   nil
   :t_medication_event/sample_obtained_antibodies false
   :t_medication_event/event_concept_fk           nil
   :t_medication_event/action_taken               nil
   :t_medication_event/description_of_reaction    nil
   :t_medication_event/infusion_start_date_time   nil
   :t_medication_event/drug_batch_identifier      nil
   :t_medication_event/premedication              nil
   :t_medication_event/reaction_date_time         nil})

(defn unparse-medication-event
  [medication-id evt]
  (-> (merge default-medication-event evt)
      (assoc :t_medication_event/medication_fk medication-id)
      (update :t_medication_event/type {:INFUSION_REACTION "INFUSION_REACTION" ;; TODO: fix consistency of type in legacy rsdb
                                        :ADVERSE_EVENT     "AdverseEvent"})
      (update :t_medication_event/severity #(when % (name %)))))


(s/fdef upsert-medication!
  :args (s/cat :conn ::db/txn
               :medication (s/keys
                             :req [:t_medication/patient_fk :t_medication/medication_concept_fk]
                             :opt [:t_medication/id :t_medication/events])))
(defn upsert-medication!
  "Insert or update a medication record. "
  [txn {:t_medication/keys [reason_for_stopping id events] :as med}]
  (let [med' (cond-> (select-keys med [:t_medication/id :t_medication/medication_concept_fk
                                       :t_medication/date_from :t_medication/date_to
                                       :t_medication/reason_for_stopping :t_medication/patient_fk
                                       :t_medication/date_from_accuracy :t_medication/date_to_accuracy
                                       :t_medication/temporary_stop :t_medication/more_information :t_medication/as_required])
               reason_for_stopping (update :t_medication/reason_for_stopping name))
        med'' (db/parse-entity
                (if id
                  ;; delete all related events iff we are updating a record
                  (do (next.jdbc.sql/delete! txn :t_medication_event {:medication_fk id})
                      (next.jdbc.sql/update! txn :t_medication med' {:id id} {:return-keys true}))
                  ;; just create a new record
                  (next.jdbc.sql/insert! txn :t_medication med')))
        id' (:t_medication/id med'')
        events' (mapv #(unparse-medication-event id' %) events)]
    (log/info "upserted medication" med'')
    (log/info "upserting medication events" events')
    (if (seq events')
      (assoc med'' :t_medication/events (mapv db/parse-entity (next.jdbc.sql/insert-multi! txn :t_medication_event events' {:return-keys true})))
      med'')))

(s/fdef delete-medication!
  :args (s/cat :conn ::db/conn :medication (s/keys :req [:t_medication/id])))
(defn delete-medication!
  [conn {:t_medication/keys [id]}]
  (next.jdbc.sql/delete! conn :t_medication_event {:medication_fk id})
  (next.jdbc.sql/delete! conn :t_medication {:id id}))

(defn fetch-summary-multiple-sclerosis
  [conn patient-identifier]
  (let [sms (db/execute! conn (sql/format {:select    [:t_summary_multiple_sclerosis/id
                                                       :t_ms_diagnosis/id :t_ms_diagnosis/name
                                                       :t_summary_multiple_sclerosis/patient_fk
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

(def ms-event-types
  "Defines the types of MS event; these are recorded in the database in legacy
  rsdb but this defines this static data with event types that are permitted to
  follow when ordered by date."
  [{:id           1
    :abbreviation "RO"
    :onset        true
    :relapse      true
    :description  "Relapse at disease onset but no fixed disability"
    :followed-by  #{"RU", "RR", "RW", "SW", "POP"}}
   {:id           2
    :abbreviation "RR"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse but no fixed disability"
    :followed-by  #{"POP", "RU", "RR", "RW", "SW"}}
   {:id           3
    :abbreviation "RU"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse but outcome unknown"
    :followed-by  #{"POP", "RU", "RR", "SW"}}
   {:id           4
    :abbreviation "SO"
    :onset        true
    :relapse      true
    :description  "Relapse at disease onset with fixed disability"
    :followed-by  #{"SW", "SU", "SN", "POP"}}
   {:id           5
    :abbreviation "SN"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse but return to previous level of fixed disability"
    :followed-by  #{"POP", "SW", "SU", "SN"}}
   {:id           6
    :abbreviation "SW"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse in patient with increased level of fixed disability"
    :followed-by  #{"POP", "SW", "SU", "SN"}}
   {:id           7
    :abbreviation "SU"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse in patient with stable fixed disability but outcome unknown"
    :followed-by  #{"POP", "SW", "SU", "SN"}}
   {:id           8
    :abbreviation "POR"
    :onset        true
    :relapse      false
    :progressive  true
    :description  "Progressive from disease onset"
    :followed-by  #{"PR"}}
   {:id           9
    :abbreviation "POP"
    :onset        false
    :relapse      false
    :progressive  true
    :description  "Onset of secondary progressive disease"
    :followed-by  #{"PR"}}
   {:id           10
    :abbreviation "PR"
    :onset        false
    :relapse      true
    :progressive  true
    :description  "Relapse superimposed on progressive disease"
    :followed-by  #{"PR"}}
   {:id           11
    :abbreviation "UK"
    :onset        true
    :relapse      true
    :description  "Unknown"
    :followed-by  nil}
   {:id           12
    :abbreviation "RW"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse with fixed disability"
    :followed-by  #{"POP", "SN", "SU", "SW"}}])

(def ms-event-type-by-abbreviation
  (reduce (fn [acc {abbrev :abbreviation, :as event-type}] (assoc acc abbrev event-type)) {} ms-event-types))

(def ms-event-type-at-onset
  (map :abbreviation (filter :onset ms-event-types)))

(def ms-event-followed-by
  (reduce (fn [acc {:keys [abbreviation followed-by]}] (assoc acc abbreviation followed-by)) {} ms-event-types))

(s/fdef ms-event-ordering-errors
  :args (s/cat :events (s/coll-of (s/keys :req [:t_ms_event_type/abbreviation]))))
(defn ms-event-ordering-errors
  "Given an already sorted sequence of events, returns a sequence of errors.
  Each result will include:
  - :error     - error code; one of :first-event or :ordering
  - :events    - the events providing context
  - :expected  - a set of abbreviations representing expected type(s)
  - :actual    - what was actually found"
  [events]
  (let [first-event (first events)
        events' (remove #(= "UK" (:t_ms_event_type/abbreviation %)) events)] ;; remove any 'unknown' events, as they can appear anywhere
    (when (seq events')
      (let [errors (if-not (:onset (ms-event-type-by-abbreviation (:t_ms_event_type/abbreviation first-event)))
                     [{:error    :first-event, :events [first-event],
                       :expected ms-event-type-at-onset :actual (:t_ms_event_type/abbreviation first-event)}]
                     [])]
        (into errors
              (comp (map (fn [[{a' :t_ms_event_type/abbreviation, :as a}
                               {b' :t_ms_event_type/abbreviation, :as b}]]
                           (let [followed-by (get-in ms-event-type-by-abbreviation [a' :followed-by])]
                             (when-not (followed-by b')
                               {:error :ordering, :events [a b], :expected followed-by, :actual b'}))))
                    (remove nil?))
              (partition 2 1 events'))))))

(defn ms-event-ordering-error->en-GB
  [{:keys [error events expected actual]}]
  (when error
    (let [abbrev (get-in events [0 :t_ms_event_type/abbreviation])
          date (some-> (get-in events [0 :t_ms_event/date]) (.format (DateTimeFormatter/ofPattern "d-MMM-YYYY")))
          expected' (map #(str "'" % "'") expected)]
      (case error
        :first-event
        (str "The first event must be one of: " (str/join ", " expected'))
        :ordering
        (str "Event '" abbrev "' (" date ") must be followed by"
             (if (= 1 (count expected'))
               (str ": " (first expected'))
               (str " one of: " (str/join ", " expected)))
             ", not '" actual "'")))))




(comment

  (let [ex1 ["RO" "UK" "RU"]]))




(defn ms-event-is-relapse?
  "Is the given MS event a type of relapse?"
  [{abbrev :t_ms_event_type/abbreviation}]
  (get-in ms-event-type-by-abbreviation [abbrev :relapse]))

(defn ms-event-is-progressive?
  [{abbrev :t_ms_event_type/abbreviation}]
  (get-in ms-event-type-by-abbreviation [abbrev :progressive]))

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
       (map #(assoc % :t_ms_event/is_relapse (ms-event-is-relapse? %)
                      :t_ms_event/is_progressive (ms-event-is-progressive? %)
                      :t_ms_event/type (select-keys % [:t_ms_event_type/id :t_ms_event_type/name
                                                       :t_ms_event_type/abbreviation])))))

(defn patient-identifier-for-ms-event
  "Returns the patient identifier for a given MS event"
  [conn {ms-event-id :t_ms_event/id :as _event}]
  (:t_patient/patient_identifier (db/execute-one! conn (sql/format {:select [:patient_identifier]
                                                                    :from   [:t_patient]
                                                                    :join   [:t_summary_multiple_sclerosis [:= :t_summary_multiple_sclerosis/patient_fk :t_patient/id]
                                                                             :t_ms_event [:= :t_ms_event/summary_multiple_sclerosis_fk :t_summary_multiple_sclerosis/id]]
                                                                    :where  [:= :t_ms_event/id ms-event-id]}))))

(s/fdef delete-ms-event!
  :args (s/cat :conn ::db/conn :event (s/keys :req [:t_ms_event/id])))

(defn delete-ms-event! [conn {ms-event-id :t_ms_event/id}]
  (db/execute-one! conn (sql/format {:delete-from [:t_ms_event]
                                     :where       [:= :t_ms_event/id ms-event-id]})))

(s/fdef save-ms-diagnosis!
  :args (s/cat :txn ::db/repeatable-read-txn :ms-diagnosis (s/keys :req [:t_ms_diagnosis/id :t_patient/patient_identifier :t_user/id])))
(defn save-ms-diagnosis! [txn {ms-diagnosis-id    :t_ms_diagnosis/id
                               patient-identifier :t_patient/patient_identifier
                               user-id            :t_user/id
                               :as                params}]
  (if-let [sms (fetch-summary-multiple-sclerosis txn patient-identifier)]
    (next.jdbc.sql/update! txn :t_summary_multiple_sclerosis
                           {:ms_diagnosis_fk ms-diagnosis-id
                            :user_fk         user-id}
                           {:id (:t_summary_multiple_sclerosis/id sms)}
                           {:return-keys true})

    (jdbc/execute-one! txn (sql/format
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
                                                                                                :where  [:= :t_patient/patient_identifier patient-identifier]}}]})
                       {:return-keys true})))
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
                           {:t_ms_event/id (:t_ms_event/id event)}
                           {:return-keys true})
    (next.jdbc.sql/insert! conn :t_ms_event (merge default-ms-event event))))




(s/fdef save-pseudonymous
  :args (s/cat :txn ::db/repeatable-read-txn
               :patient (s/keys :req [:t_patient/patient_identifier :uk.gov.ons.nhspd/LSOA11])))
(defn save-pseudonymous-patient-lsoa!
  "Special function to store LSOA11 code in place of postal code when working
  with pseudonymous patients. We know LSOA11 represents 1000-1500 members of the
  population. We don't keep an address history, so simply write an address with
  no dates which is the 'current'."
  [txn {patient-identifier :t_patient/patient_identifier, lsoa11 :uk.gov.ons.nhspd/LSOA11, :as params}]
  (when-let [patient (db/execute-one! txn (sql/format {:select [:id :patient_identifier :status] :from :t_patient :where [:= :patient_identifier patient-identifier]}))]
    (if-not (= :PSEUDONYMOUS (:t_patient/status patient))
      (throw (ex-info "Invalid operation: cannot save LSOA for non-pseudonymous patient" params))
      (let [addresses (fetch-patient-addresses txn patient)
            current-address (address-for-date addresses)]
        ;; we currently do not support an address history for pseudonymous patients, so either edit or create
        ;; current address
        (if current-address
          (next.jdbc.sql/update! txn :t_address
                                 {:t_address/date_to                nil
                                  :t_address/date_from              nil
                                  :t_address/address1               lsoa11
                                  :t_address/postcode_raw           nil
                                  :t_address/postcode_fk            nil
                                  :t_address/ignore_invalid_address "true"
                                  :t_address/address2               nil
                                  :t_address/address3               nil
                                  :t_address/address4               nil}
                                 {:t_address/id (:t_address/id current-address)}
                                 {:return-keys true})
          (next.jdbc.sql/insert! txn :t_address {:t_address/address1               lsoa11
                                                 :t_address/ignore_invalid_address "true"
                                                 :t_address/patient_fk             (:t_patient/id patient)}
                                 {:return-keys true}))))))



(s/def ::save-encounter (s/keys :req [:t_encounter/date_time
                                      :t_encounter/patient_fk
                                      :t_encounter/encounter_template_fk]
                                :opt [:t_encounter/id
                                      :t_encounter/episode_fk]))
(defn save-encounter-sql
  [{:t_encounter/keys [id encounter_template_fk patient_fk episode_fk date_time notes]
    :t_patient/keys   [patient_identifier] :as encounter}]
  (when-not (s/valid? ::save-encounter encounter)
    (throw (ex-info "Invalid save encounter" (s/explain-data ::save-encounter encounter))))
  (if id
    {:update [:t_encounter]
     :where  [:= :id id]
     :set    {:encounter_template_fk encounter_template_fk
              :date_time             date_time
              :notes                 notes
              :episode_fk            episode_fk}}
    {:insert-into [:t_encounter]
     :values      [{:date_time             date_time
                    :notes                 notes
                    :encounter_template_fk encounter_template_fk
                    :patient_fk            (or patient_fk {:select :t_patient/id
                                                           :from   [:t_patient]
                                                           :where  [:= :t_patient/patient_identifier patient_identifier]})
                    :episode_fk            episode_fk}]}))


(s/fdef save-encounter!
  :args (s/cat :conn ::db/conn :encounter ::save-encounter))
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
  (db/execute-one! conn (sql/format (save-encounter-sql encounter)) {:return-keys true}))

(defn delete-encounter!
  [conn encounter-id]
  (db/execute-one! conn (sql/format {:update [:t_encounter]
                                     :where  [:= :id encounter-id]
                                     :set    {:t_encounter/is_deleted "true"}})
                   {:return-keys true}))


(s/fdef set-date-death
  :args (s/cat :conn ::db/conn :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier) :t_patient/date_death])))

(defn set-date-death
  [conn {patient-pk :t_patient/id, patient-identifier :t_patient/patient_identifier, date_death :t_patient/date_death :as params}]
  (db/execute-one!
    conn
    (sql/format {:update [:t_patient]
                 :where  (cond
                           patient-pk [:= :id patient-pk]
                           patient-identifier [:= :patient_identifier patient-identifier]
                           :else (throw (ex-info "missing patient pk or identifier" params)))
                 :set    {:date_death date_death}})
    {:return-keys true}))


(s/fdef notify-death!
  :args (s/cat :conn ::db/serializable-txn
               :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier)
                                      :t_patient/date_death]
                                :opt [:t_death_certificate/part1a
                                      :t_death_certificate/part1b
                                      :t_death_certificate/part1c
                                      :t_death_certificate/part2])))
(defn notify-death!
  [txn {patient-pk                :t_patient/id
        patient-identifier        :t_patient/patient_identifier
        date_death                :t_patient/date_death :as patient
        :t_death_certificate/keys [part1a part1b part1c part2]}]
  (let [patient-pk (or patient-pk (patient-identifier->pk txn patient-identifier))
        patient' (assoc patient :t_patient/id patient-pk)
        _ (log/info "Fetching certificate " patient')
        existing-certificate (fetch-death-certificate txn patient')]
    (cond
      ;; if there's a date of death, and an existing certificate, update both
      (and date_death existing-certificate)
      (do (set-date-death txn patient')
          (assoc patient' :t_patient/death_certificate
                          (jdbc/execute-one! txn (sql/format {:update [:t_death_certificate]
                                                              :where  [:= :id (:t_death_certificate/id existing-certificate)]
                                                              :set    {:t_death_certificate/part1a part1a
                                                                       :t_death_certificate/part1b part1b
                                                                       :t_death_certificate/part1c part1c
                                                                       :t_death_certificate/part2  part2}})
                                             {:return-keys true})))
      ;; patient has died, but no existing certificate
      date_death
      (do (set-date-death txn patient')
          (assoc patient' :t_patient/death_certificate
                          (jdbc/execute-one! txn (sql/format {:insert-into :t_death_certificate
                                                              :values      [{:t_death_certificate/patient_fk patient-pk
                                                                             :t_death_certificate/part1a     part1a
                                                                             :t_death_certificate/part1b     part1b
                                                                             :t_death_certificate/part1c     part1c
                                                                             :t_death_certificate/part2      part2}]})
                                             {:return-keys true})))
      ;; patient has not died, clear date of death and delete death certificate
      :else
      (do (set-date-death txn patient')
          (jdbc/execute-one! txn (sql/format {:delete-from [:t_death_certificate]
                                              :where       [:= :t_death_certificate/patient_fk patient-pk]}))
          (dissoc patient' :t_patient/death_certificate)))))



(comment
  (patient-identifier-for-ms-event conn 7563)
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

  (fetch-medications-and-events conn {:t_patient/patient_identifier 14032})

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
  (notify-death! conn {:t_patient/patient_identifier 124010
                       :t_patient/date_death         (LocalDate/now)}))
