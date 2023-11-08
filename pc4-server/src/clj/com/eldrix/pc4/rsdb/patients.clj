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

(def identifier-system->legacy-hospital
  {"https://fhir.ctmuhb.nhs.wales/Id/pas-identifier" "RYLB3" ;; Prince Charles Hospital
   "https://fhir.abuhb.nhs.wales/Id/pas-identifier"  "RVFAR" ;;Royal Gwent
   "https://fhir.sbuhb.nhs.wales/Id/pas-identifier"  "RYMC7" ;; Morriston Hospital
   "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "RWMBV"}) ;; UHW})

(def supported-identifiers (set (keys identifier-system->legacy-hospital)))

(def coll-of-patient-hospitals-spec
  (s/coll-of (s/keys :req [:t_patient_hospital/hospital_fk :t_patient_hospital/patient_identifier])))

(defn update-patient-identifiers-sql
  "For a given patient, return SQL to insert any missing identifiers.
  Parameters:
  - patient-pk : patient primary key
  - patient-hospitals : a sequence of 'existing' identifiers
     (:t_patient_hospital/hospital_identifier and :t_patient_hospital/patient_identifier)
  - fhir-patient"
  [{patient-pk :t_patient/id} patient-hospitals fhir-patient]
  (when-not (s/valid? coll-of-patient-hospitals-spec patient-hospitals)
    (throw (ex-info "invalid patient hospital data" (s/explain-data coll-of-patient-hospitals-spec patient-hospitals))))
  (when-not (s/valid? :org.hl7.fhir/Patient fhir-patient)
    (throw (ex-info "invalid patient data" (s/explain-data :org.hl7.fhir/Patient fhir-patient))))
  (let [identifiers (:org.hl7.fhir.Patient/identifier fhir-patient)
        existing (into #{} (map #(vector (:t_patient_hospital/hospital_fk %)
                                         (:t_patient_hospital/patient_identifier %)))
                       patient-hospitals)
        updated (into #{} (comp
                            (filter #(supported-identifiers (:org.hl7.fhir.Identifier/system %)))
                            (map #(vector (identifier-system->legacy-hospital (:org.hl7.fhir.Identifier/system %))
                                          (:org.hl7.fhir.Identifier/value %)))) identifiers)
        to-insert (set/difference updated existing)]
    (when (seq to-insert)
      [{:insert-into :t_patient_hospital
        :columns     [:patient_fk :hospital_fk :patient_identifier]
        :values      (mapv #(cons patient-pk %) to-insert)}])))

(def coll-of-telephones-spec
  (s/coll-of (s/keys :req [:t_patient_telephone/telephone :t_patient_telephone/description])))

(defn update-patient-telephones-sql
  [{patient-pk :t_patient/id} telephones fhir-patient]
  (when-not (s/valid? :org.hl7.fhir/Patient fhir-patient)
    (throw (ex-info "invalid patient data" (s/explain-data :org.hl7.fhir/Patient fhir-patient))))
  (when-not (s/valid? coll-of-telephones-spec telephones)
    (throw (ex-info "invalid telephone data" (s/explain-data coll-of-telephones-spec telephones))))
  (let [normalise #(str/replace % #"\D" "")
        existing (reduce (fn [acc {:t_patient_telephone/keys [telephone]}]
                           (into acc (remove str/blank? [telephone (normalise telephone)]))) #{} telephones)
        updated (->> (:org.hl7.fhir.Patient/telecom fhir-patient)
                     (remove (fn [{:org.hl7.fhir.ContactPoint/keys [system value]}]
                               (or (not= "phone" system)
                                   (contains? existing value)
                                   (contains? existing (normalise value))))))]
    (when (seq updated)
      [{:insert-into :t_patient_telephone
        :columns     [:patient_fk :telephone :description]
        :values      (mapv #(vector patient-pk (:org.hl7.fhir.ContactPoint/value %) (:org.hl7.fhir.ContactPoint/system %)) updated)}])))

(defn update-patient-sql
  "Generate SQL as data structures to update :t_patient based on
  `fhir-patient`."
  [{patient-pk :t_patient/id} fhir-patient]
  (if-not (s/valid? :org.hl7.fhir/Patient fhir-patient)
    (throw (ex-info "invalid patient data" (s/explain-data :org.hl7.fhir/Patient fhir-patient)))
    (let [human-name (first (:org.hl7.fhir.Patient/name fhir-patient))
          birth-date (let [dob (:org.hl7.fhir.Patient/birthDate fhir-patient)]
                       (cond (instance? LocalDate dob) dob
                             (instance? LocalDateTime dob) (.toLocalDate ^LocalDateTime dob)))
          [date-death death-accuracy] (let [deceased (:org.hl7.fhir.Patient/deceased fhir-patient)]
                                        (cond (instance? LocalDateTime deceased) [(.toLocalDate deceased) "DAY"]
                                              (instance? LocalDate deceased) [deceased "DAY"]
                                              (and (boolean? deceased) deceased) [birth-date "UNKNOWN"]))
          nhs-number (:org.hl7.fhir.Identifier/value (first (filter #(= "https://fhir.nhs.uk/Id/nhs-number" (:org.hl7.fhir.Identifier/system %)) (:org.hl7.fhir.Patient/identifier fhir-patient))))
          email (:org.hl7.fhir.ContactPoint/value (first (filter #(= "email" (:org.hl7.fhir.ContactPoint/system %)) (:org.hl7.fhir.Patient/telecom fhir-patient))))
          surgery-id (first (fhir/gp-surgery-identifiers fhir-patient))
          gp-id (first (fhir/general-practitioner-identifiers fhir-patient))
          set-map (cond-> {:sex                 (case (:org.hl7.fhir.Patient/gender fhir-patient) "male" "MALE", "female" "FEMALE", "UNKNOWN")
                           :date_birth          birth-date
                           :date_death          date-death
                           :date_death_accuracy death-accuracy}
                    human-name
                    (assoc :first_names (str/join " " (:org.hl7.fhir.HumanName/given human-name))
                           :last_name (:org.hl7.fhir.HumanName/family human-name)
                           :title (str/join " " (:org.hl7.fhir.HumanName/prefix human-name)))
                    nhs-number
                    (assoc :nhs_number nhs-number)
                    email
                    (assoc :email email)
                    surgery-id
                    (assoc :surgery_fk (:org.hl7.fhir.Identifier/value surgery-id))
                    gp-id
                    (assoc :general_practitioner_fk (:org.hl7.fhir.Identifier/value gp-id)))]
      [{:update :t_patient
        :set    set-map
        :where  [:= :id patient-pk]}])))

(defn fhir-address->t_address
  [{:org.hl7.fhir.Address/keys [line city district state country postalCode period]}]
  {:address1     (str/trim (str/join ", " line))
   :address2     (str/trim (or city ""))
   :address3     (str/trim (str/join " " [district state]))
   :address4     (str/trim (or country ""))
   :postcode_raw (str/trim (or postalCode ""))
   :date_from    (:org.hl7.fhir.Period/start period)
   :date_to      (:org.hl7.fhir.Period/end period)})

(defn address-for-match [a]
  (select-keys a [:address1 :address2 :address3 :address4 :postcode_raw :date_from :date_to]))

(defn matching-address? [a1 a2]
  (= (address-for-match a1) (address-for-match a2)))

(def coll-of-addresses-spec
  (s/coll-of (s/keys :req [:t_address/id :t_address/address1 :t_address/address2 :t_address/address3 :t_address/address4
                           :t_address/postcode_raw :t_address/date_from :t_address/date_to])))
(defn update-patient-addresses-sql
  "Given an authoritative source of data (`fhir-patient`), return SQL to delete
   and insert new address records for the given patient. This performs what is
   essentially a merge operation, preserving existing records based on address
   dates from and to."
  [{patient-pk :t_patient/id} old-addresses fhir-patient]
  (when-not (s/valid? :org.hl7.fhir/Patient fhir-patient)
    (throw (ex-info "invalid patient data" (s/explain-data :org.hl7.fhir/Patient fhir-patient))))
  (when-not (s/valid? coll-of-addresses-spec old-addresses)
    (throw (ex-info "invalid address data" (s/explain-data coll-of-addresses-spec old-addresses))))
  (let [old-addresses (map #(update-keys % (comp keyword name)) old-addresses)
        old-address-ids (into #{} (map :id) old-addresses)
        new-addresses (map fhir-address->t_address (:org.hl7.fhir.Patient/address fhir-patient))
        new-address (first new-addresses)]
    (if (and (= 1 (count new-addresses)) (nil? (:date_from new-address)) (nil? (:date_to new-address)))
      ;; if external patient data contains a single address record with no dates => completely replace our address history
      (let [to-keep (into #{} (comp (filter #(matching-address? new-address %)) (map :id)) old-addresses) ;; existing record to be kept that matches authority record
            to-delete (set/difference old-address-ids to-keep)] ;; delete any others
        (filterv some?
                 [(when (seq to-delete) {:delete-from :t_address :where [:in :id to-delete]})
                  (when-not (seq to-keep) {:insert-into :t_address :values [(assoc new-address :patient_fk patient-pk :ignore_invalid_address true)]})]))
      ;; external patient data has either multiple records, or dates => merge with existing data
      (let [old-records (into #{} (map address-for-match) old-addresses) ;; a set of existing records
            to-insert (set/difference (set new-addresses) old-records) ;; a set of new records to be merged
            earliest-from-external (when-let [dates (seq (remove nil? (map :date_from to-insert)))] (apply min dates)) ;; earliest record from authority, or nil
            to-keep (if earliest-from-external (filter #(and (some? (:date_from %)) (.isBefore (:date_from %) earliest-from-external)) old-addresses) old-addresses) ;; existing records to be kept
            to-truncate (when earliest-from-external (filter #(or (nil? (:date_to %)) (.isAfter (:date_to %) earliest-from-external)) to-keep)) ;; existing records needing truncation
            to-delete (set/difference old-address-ids (set (map :id to-keep)))] ;; existing records to be deleted]
        (filterv some?
                 [(when (seq to-delete) {:delete-from :t_address :where [:in :id to-delete]})
                  (when (seq to-truncate) {:update :t_address :set {:date_to earliest-from-external} :where [:in :id (mapv :id to-truncate)]})
                  (when (seq to-insert) {:insert-into :t_address :values (mapv #(assoc % :patient_fk patient-pk :ignore_invalid_address true) to-insert)})])))))


(defn initialise-patient-from-external-sql
  "Generate SQL as Clojure data structures to update a patient based on data
  from an external authority as pc4 FHIR data structures.
  Loosely based on legacy rsdb: https://github.com/wardle/rsdb/blob/master/RSNews/src/main/java/com/eldrix/rsdb/patient/PatientRegister.java"
  [{patient-pk :t_patient/id, :t_patient/keys [addresses telephones patient_hospitals] :as patient} fhir-patient]
  (let [core (update-patient-sql patient fhir-patient)
        identifiers (update-patient-identifiers-sql patient patient_hospitals fhir-patient)
        telephones (update-patient-telephones-sql patient telephones fhir-patient)
        addresses (update-patient-addresses-sql patient addresses fhir-patient)]
    (concat core
            identifiers
            telephones
            addresses)))

(defn initialise-patient-from-external
  [txn patient-pk fhir-patient]
  (let [patient (jdbc/execute-one! txn (sql/format {:select :* :from :t_patient :where [:= :id patient-pk]}))
        addresses (jdbc/execute! txn (sql/format {:select :* :from :t_address :where [:= :patient_fk patient-pk]}))
        telephones (jdbc/execute! txn (sql/format {:select :* :from :t_patient_telephone :where [:= :patient_fk patient-pk]}))
        hospitals (jdbc/execute! txn (sql/format {:select :* :from :t_patient_hospital :where [:= :patient_fk patient-pk]}))]
    (doseq [stmt (initialise-patient-from-external-sql
                   (assoc patient :t_patient/addresses addresses ;; nest the related structures
                                  :t_patient/telephones telephones
                                  :t_patient/patient_hospitals hospitals)
                   fhir-patient)]
      (jdbc/execute! txn (sql/format stmt)))))



(comment
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (require '[com.eldrix.concierge.wales.empi :as empi])
  (def pts (empi/resolve-fake "https://fhir.nhs.uk/Id/nhs-number" "1234567890"))
  (def pt (first pts))
  (require '[com.eldrix.pc4.fhir])

  (update-patient-sql
    {:t_patient/id 1} (assoc (first pts) :org.hl7.fhir.Patient/deceased true))
  (update-patient-identifiers-sql
    {:t_patient/id 1}
    [{:t_patient_hospital/hospital_identifier "RWMBV"
      :t_patient_hospital/patient_identifier  "A999998"}]
    [{:org.hl7.fhir.Identifier/system "https://fhir.ctmuhb.nhs.wales/Id/pas-identifier"
      :org.hl7.fhir.Identifier/value  "A123456"}
     {:org.hl7.fhir.Identifier/system "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
      :org.hl7.fhir.Identifier/value  "A999998"}])
  (update-patient-telephones-sql
    {:t_patient/id 1} [{:t_patient_telephone/telephone "0121 433 4383" :t_patient_telephone/description "Home"}
                       {:t_patient_telephone/telephone "Job's Well Road" :t_patient_telephone/description "Work"}
                       {:t_patient_telephone/telephone "0206 123 4567" :t_patient_telephone/description "Work"}] (first pts))

  (update-patient-addresses-sql
    {:t_patient/id 1} [{:t_address/id       123 :t_address/address1 "Pembrokeshire & Derwen Nhs , Cwm Seren" :t_address/address2 "Job's Well Road"
                        :t_address/address3 "Carmarthen, Carmarthenshire" :t_address/address4 "" :t_address/postcode_raw "SA31 3BB" :t_address/date_from nil :t_address/date_to nil}
                       {:t_address/id       57 :t_address/address1 "10 Station Road" :t_address/address2 ""
                        :t_address/address3 "Cardiff" :t_address/address4 "" :t_address/postcode_raw "CF14 4XW" :t_address/date_from nil :t_address/date_to nil}] (first pts))
  (update-patient-addresses-sql
    {:t_patient/id 1} [{:t_address/id       57 :t_address/address1 "10 Station Road" :t_address/address2 ""
                        :t_address/address3 "Cardiff" :t_address/address4 "" :t_address/postcode_raw "CF14 4XW" :t_address/date_from nil :t_address/date_to nil}]
    (assoc (first pts) :org.hl7.fhir.Patient/address
                       [{:org.hl7.fhir.Address/line   ["10 Station Road"] :org.hl7.fhir.Address/postalCode "CF14 4XW"
                         :org.hl7.fhir.Address/city   "" :org.hl7.fhir.Address/district "" :org.hl7.fhir.Address/country ""
                         :org.hl7.fhir.Address/period {:org.hl7.fhir.Period/start (LocalDate/of 2020 1 1)
                                                       :org.hl7.fhir.Period/end   (LocalDate/of 2021 1 1)}}]))
  (update-patient-addresses-sql
    {:t_patient/id 1} [{:t_address/id       64 :t_address/address1 "11 Station Road" :t_address/address2 ""
                        :t_address/address3 "Cardiff" :t_address/address4 "" :t_address/postcode_raw "CF14 4XW" :t_address/date_from (LocalDate/of 2019 6 1) :t_address/date_to (LocalDate/of 2021 1 1)}
                       {:t_address/id       57 :t_address/address1 "10 Station Road" :t_address/address2 ""
                        :t_address/address3 "Cardiff" :t_address/address4 "" :t_address/postcode_raw "CF14 4XW" :t_address/date_from (LocalDate/of 2018 6 1) :t_address/date_to (LocalDate/of 2021 1 1)}]
    (assoc (first pts) :org.hl7.fhir.Patient/address
                       [{:org.hl7.fhir.Address/line   ["10 Station Road"] :org.hl7.fhir.Address/postalCode "CF14 4XW"
                         :org.hl7.fhir.Address/city   "" :org.hl7.fhir.Address/district "Cardiff" :org.hl7.fhir.Address/country ""
                         :org.hl7.fhir.Address/period {:org.hl7.fhir.Period/start (LocalDate/of 2019 6 1)
                                                       :org.hl7.fhir.Period/end   (LocalDate/of 2021 1 1)}}])))



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
       (into active-projects (flatten (map #(projects/all-parents-ids conn %) active-projects)))))))

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
              (conj acc (get index id))) [] medication-ids)))

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
                           {:t_ms_event/id (:t_ms_event/id event)})
    (next.jdbc.sql/insert! conn :t_ms_event (merge default-ms-event event))))

(s/fdef save-pseudonymous
  :args (s/cat :txn ::db/repeatable-read-txn
               :patient (s/keys :req [:t_patient/patient_identifier :uk.gov.ons.nhspd/LSOA11])))
(defn save-pseudonymous-patient-lsoa!
  "Special function to store LSOA11 code in place of postal code when working
  with pseudonymous patients. We know LSOA11 represents 1000-1500 members of the
  population. We don't keep an address history, so simply write an address with
  no dates which is the 'current'."
  [txn {patient-identifier :t_patient/patient_identifier lsoa11 :uk.gov.ons.nhspd/LSOA11 :as params}]
  (when-let [patient (db/execute-one! txn (sql/format {:select [:id :patient_identifier :status] :from :t_patient :where [:= :patient_identifier patient-identifier]}))]
    (if-not (= :PSEUDONYMOUS (:t_patient/status patient))
      (throw (ex-info "Invalid operation: cannot save LSOA for non-pseudonymous patient" params))
      (let [addresses (fetch-patient-addresses txn patient)
            current-address (address-for-date addresses)]
        ;; we currently do not support an address history for pseudonymous patients, so either edit or create
        ;; current address
        (if current-address
          (next.jdbc.sql/update! txn :t_address
                                 {:t_address/date_to                nil :t_address/date_from nil
                                  :t_address/address1               lsoa11
                                  :t_address/postcode_raw           nil :t_address/postcode_fk nil
                                  :t_address/ignore_invalid_address "true"
                                  :t_address/address2               nil :t_address/address3 nil :t_address/address4 nil}
                                 {:t_address/id (:t_address/id current-address)})
          (next.jdbc.sql/insert! txn :t_address {:t_address/address1               lsoa11
                                                 :t_address/ignore_invalid_address "true"
                                                 :t_address/patient_fk             (:t_patient/id patient)}))))))



(s/def ::save-encounter (s/keys :req [:t_encounter/encounter_template_fk
                                      :t_encounter/episode_fk
                                      :t_patient/patient_identifier
                                      :t_encounter/date_time]
                                :opt [:t_encounter/id]))

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


(s/fdef set-date-death
  :args (s/cat :conn ::db/conn :patient (s/keys :req [:t_patient/id :t_patient/date_death])))

(defn set-date-death [conn {patient-pk :t_patient/id date_death :t_patient/date_death}]
  (jdbc/execute-one! conn (sql/format {:update [:t_patient]
                                       :where  [:= :id patient-pk]
                                       :set    {:date_death date_death}})))


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
          (jdbc/execute-one! txn (sql/format {:update [:t_death_certificate]
                                              :where  [:= :id (:t_death_certificate/id existing-certificate)]
                                              :set    {:t_death_certificate/part1a part1a
                                                       :t_death_certificate/part1b part1b
                                                       :t_death_certificate/part1c part1c
                                                       :t_death_certificate/part2  part2}})))
      ;; patient has died, but no existing certificate
      date_death
      (do (set-date-death txn patient')
          (jdbc/execute-one! txn (sql/format {:insert-into :t_death_certificate
                                              :values      [{:t_death_certificate/patient_fk patient-pk
                                                             :t_death_certificate/part1a     part1a
                                                             :t_death_certificate/part1b     part1b
                                                             :t_death_certificate/part1c     part1c
                                                             :t_death_certificate/part2      part2}]})))
      ;; patient has not died, clear date of death and delete death certificate
      :else
      (do (set-date-death txn patient')
          (jdbc/execute-one! txn (sql/format {:delete-from [:t_death_certificate]
                                              :where       [:= :t_death_certificate/patient_fk patient-pk]}))))))



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
