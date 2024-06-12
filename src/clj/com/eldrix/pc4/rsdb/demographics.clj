(ns com.eldrix.pc4.rsdb.demographics
  "Update a patient record from external authoritative sources. The code here is
  loosely based on legacy rsdb: https://github.com/wardle/rsdb/blob/master/RSNews/src/main/java/com/eldrix/rsdb/patient/PatientRegister.java

  The public API consists of

  - update-patient
  This takes a database connection, a function that acts as a demographic
  authority and existing patient data. There is no guarantee that the record
  will be updated, as that depends on the patient's authoritative demographic
  configuration, local system configuration and network connectivity."
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [com.eldrix.pc4.fhir :as fhir]
    [com.eldrix.nhspd.postcode :as postcode]
    [com.eldrix.pc4.rsdb.db :as db]
    [honey.sql :as sql]
    [next.jdbc :as jdbc])
  (:import
    (java.time LocalDate LocalDateTime)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private identifier-system->legacy-hospital
  "Mapping from modern fhir namespaces to legacy hospital identifiers. We only
  need to cover legacy hospitals that can contribute to authoritative
  demographics here. All others can be ignored."
  {"https://fhir.ctmuhb.nhs.wales/Id/pas-identifier" "RYLB3" ;; Prince Charles Hospital / CTMUHB
   "https://fhir.abuhb.nhs.wales/Id/pas-identifier"  "RVFAR" ;;Royal Gwent Hospital / ABUHB
   "https://fhir.sbuhb.nhs.wales/Id/pas-identifier"  "RYMC7" ;; Morriston Hospital / SBUHB
   "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "RWMBV"}) ;; UHW / CAVUHB

(def ^:private supported-identifiers
  "A set of supported identifier systems that can be mapped to legacy hospital
  identifiers."
  (set (keys identifier-system->legacy-hospital)))

(defn fhir-gender->rsdb-sex [gender]
  (case gender "male" "MALE", "female" "FEMALE", "UNKNOWN"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private match-nnns
  "Generates SQL to match on NHS numbers returning patient primary keys."
  [fhir-patient]
  (let [clauses
        (->> (:org.hl7.fhir.Patient/identifier fhir-patient)
             (filter #(= "https://fhir.nhs.uk/Id/nhs-number" (:org.hl7.fhir.Identifier/system %)))
             (map (fn [{:org.hl7.fhir.Identifier/keys [value]}]
                    [:= :nhs_number value])))]
    (case (count clauses)
      0 nil
      1 {:select :id :from :t_patient :where (first clauses)}
      {:select :id :from :t_patient :where (cons :or clauses)})))

(defn ^:private match-crns
  "Generates SQL to match on hospital numbers, returning patient primary keys."
  [fhir-patient]
  (let [clauses
        (->> (:org.hl7.fhir.Patient/identifier fhir-patient)
             (map (fn [{:org.hl7.fhir.Identifier/keys [system value]}]
                    (when-let [hospital-id (identifier-system->legacy-hospital system)]
                      [:and
                       [:= :hospital_fk hospital-id]
                       [:ilike :patient_identifier value]])))
             (remove nil?))]
    (case (count clauses)
      0 nil
      1 {:select [[:patient_fk :id]] :from :t_patient_hospital
         :where  (first clauses)}
      {:select [[:patient_fk :id]] :from :t_patient_hospital
       :where  (cons :or clauses)})))

(s/fdef match-patient-identifiers-sql
  :args (s/cat :patient :org.hl7.fhir/Patient))
(defn ^:private match-patient-identifiers-sql
  "Return SQL that will return the primary keys of any existing registered
  patients who match the given patient. Matching occurs by virtue of
  - a match of NHS number
  - a match of hospital identifier"
  [fhir-patient]
  (let [clauses
        (remove nil? [(match-nnns fhir-patient) (match-crns fhir-patient)])]
    (case (count clauses)
      0 nil, 1 (first clauses), {:union clauses})))

(defn ^:private match-human-name
  "Returns SQL to match a HL7 FHIR HumanName. Generates a query that will
  include all combinations of first names when there is more than one given
  name. For example,
  ```
  (human-name->sql
    {:org.hl7.fhir.HumanName/family \"Smith\"
     :org.hl7.fhir.HumanName/given  [\"Mark\" \"David\" \"Geoffrey\"]})
  =>
  (:or\n [:and [:ilike :first_names \"Mark\"] [:ilike :last_name \"Smith\"]]
   [:and [:ilike :first_names \"David\"] [:ilike :last_name \"Smith\"]]
   [:and [:ilike :first_names \"Geoffrey\"] [:ilike :last_name \"Smith\"]]
   [:and [:ilike :first_names \"Mark David\"] [:ilike :last_name \"Smith\"]]
   [:and [:ilike :first_names \"Mark David Geoffrey\"] [:ilike :last_name \"Smith\"]])
  ```"
  [{:org.hl7.fhir.HumanName/keys [given family]}]
  (let [combos
        (when (and (seq given) family)
          (distinct
            (concat
              (map #(vector % family) given)                ;; each first name with the family name
              (for [ss (reductions #(str %1 " " %2) given), s [family]] ;; first name(s) plus family name
                [ss s]))))]
    (case (count combos)
      0 nil
      1 (let [[first-names last-name] (first combos)]
          [:and
           [:ilike :first_names first-names]
           [:ilike :last_name last-name]])
      (cons :or
            (map (fn [[first-names last-name]]
                   [:and
                    [:ilike :first_names first-names]
                    [:ilike :last_name last-name]]) combos)))))

(defn ^:private match-any-human-name
  [fhir-human-names]
  (let [clauses (remove nil? (map match-human-name fhir-human-names))]
    (case (count clauses)
      0 nil, 1 (first clauses), (cons :or clauses))))

(defn ^:private match-address-by-postcode
  "Return SQL to match against the postal code specified. Legacy rsdb stored the
  user entered postal code and tried to map to NHSPD when possible. We therefore
  try all combinations in order to match."
  [{:org.hl7.fhir.Address/keys [postalCode]}]
  (when postalCode
    (let [pcd2 (postcode/normalize postalCode)              ;; normalize according to PCD2 standard
          egif (postcode/egif postalCode)]
      {:select    [[:patient_fk :id]]
       :from      :t_address
       :left-join [:t_postcode [:= :postcode_fk :t_postcode/postcode]]
       :where     [:or
                   [:= :postcode_raw egif]                  ;; match with egif version of postcode
                   [:= :postcode_raw pcd2]                  ;; match with pcd2 normalised version of postcode
                   [:= :postcode pcd2]                      ;; or match on mapped postcode from :t_postcode
                   [:ilike :postcode_raw postalCode]]})))   ;; or match on raw user entered data

(defn ^:private match-any-address-by-postcode
  [fhir-addresses]
  (let [clauses (remove nil? (map match-address-by-postcode fhir-addresses))]
    (case (count clauses)
      0 nil, 1 (first clauses), (cons :or clauses))))

(defn ^:private exact-match-on-demography-sql
  "Return SQL to match on names, date of birth, gender, and postal codes), or
  nil. To match, a patient must have the same name, date of birth, gender and
  at least one matching address by virtue of postal code. This is designed for
  high specificity at the cost of sensitivity."
  [fhir-patient]
  (let [dob (:org.hl7.fhir.Patient/birthDate fhir-patient)
        gender (:org.hl7.fhir.Patient/gender fhir-patient)
        name-clause (match-any-human-name (:org.hl7.fhir.Patient/name fhir-patient))
        address-clause (match-any-address-by-postcode (:org.hl7.fhir.Patient/address fhir-patient))]
    ;; only return a query when we have clauses for date of birth, at least one name and at least one address:
    (when (and dob gender name-clause address-clause)
      {:intersect [{:select :id
                    :from   :t_patient
                    :where  [:and
                             [:= :date_birth dob]
                             [:= :sex (fhir-gender->rsdb-sex gender)]
                             name-clause]}
                   address-clause]})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn exact-match-by-identifier
  "Returns a set of patient primary keys for patients that match the given
  patient `fhir-patient`."
  [conn fhir-patient]
  (when-let [sql (match-patient-identifiers-sql fhir-patient)]
    (into #{} (map :id) (jdbc/plan conn (sql/format sql)))))

(defn exact-match-on-demography
  "Returns a set of patient primary keys for patients that match the names, date
  of birth and address of the given patient `fhir-patient`."
  [conn fhir-patient]
  (when-let [sql (exact-match-on-demography-sql fhir-patient)]
    (into #{} (map :id) (jdbc/plan conn (sql/format sql)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef update-patient-identifiers-sql
  :args (s/cat :existing-patient (s/keys :req [:t_patient/id])
               :patient-hospitals (s/coll-of (s/keys :req [:t_patient_hospital/hospital_fk :t_patient_hospital/patient_identifier]))
               :fhir-patient :org.hl7.fhir/Patient))
(defn ^:private update-patient-identifiers-sql
  "For a given patient, return SQL to insert any missing identifiers.
  Parameters:
  - patient-pk : patient primary key
  - patient-hospitals : a sequence of 'existing' identifiers
     (:t_patient_hospital/hospital_identifier and :t_patient_hospital/patient_identifier)
  - fhir-patient"
  [{patient-pk :t_patient/id} patient-hospitals fhir-patient]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef update-patient-telephones-sql
  :args (s/cat :existing-patient (s/keys :req [:t_patient/id])
               :existing-telephones (s/coll-of (s/keys :req [:t_patient_telephone/telephone :t_patient_telephone/description]))
               :fhir-patient :org.hl7.fhir/Patient))
(defn ^:private update-patient-telephones-sql
  [{patient-pk :t_patient/id} telephones fhir-patient]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef update-patient-sql
  :args (s/cat :existing-patient (s/keys :req [:t_patient/id]) :fhir-patient :org.hl7.fhir/Patient))
(defn ^:private update-patient-sql
  "Generate SQL as data structures to update :t_patient based on
  `fhir-patient`."
  [{patient-pk :t_patient/id} fhir-patient]
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
        set-map (cond-> {:sex                 (fhir-gender->rsdb-sex (:org.hl7.fhir.Patient/gender fhir-patient))
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
      :where  [:= :id patient-pk]}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private fhir-address->t_address
  [{:org.hl7.fhir.Address/keys [line city district state country postalCode period]}]
  {:address1     (str/trim (str/join ", " line))
   :address2     (str/trim (or city ""))
   :address3     (str/trim (str/join " " [district state]))
   :address4     (str/trim (or country ""))
   :postcode_raw (str/trim (or postalCode ""))
   :date_from    (:org.hl7.fhir.Period/start period)
   :date_to      (:org.hl7.fhir.Period/end period)})

(defn- address-for-match [a]
  (select-keys a [:address1 :address2 :address3 :address4 :postcode_raw :date_from :date_to]))

(defn- matching-address? [a1 a2]
  (= (address-for-match a1) (address-for-match a2)))

(s/fdef update-patient-addresses-sql
  :args (s/cat :existing-patient (s/keys :req [:t_patient/id])
               :old-addresses (s/coll-of (s/keys :req [:t_address/id :t_address/address1 :t_address/address2 :t_address/address3 :t_address/address4
                                                       :t_address/postcode_raw :t_address/date_from :t_address/date_to]))
               :fhir-patient :org.hl7.fhir/Patient))
(defn ^:private update-patient-addresses-sql
  "Given an authoritative source of data (`fhir-patient`), return SQL to delete
   and insert new address records for the given patient. This performs what is
   essentially a merge operation, preserving existing records based on address
   dates from and to."
  [{patient-pk :t_patient/id} old-addresses fhir-patient]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef update-patient-from-external-sql
  :args (s/cat :existing-patient (s/keys :req [:t_patient/id :t_patient/addresses
                                               :t_patient/telephones :t_patient/patient_hospitals])
               :fhir-patient :org.hl7.fhir/Patient))
(defn ^:private update-patient-from-external-sql
  "Generate SQL as Clojure data structures to update a patient based on data
  from an external authority as pc4 FHIR data structures."
  [{patient-pk :t_patient/id, :t_patient/keys [addresses telephones patient_hospitals] :as patient} fhir-patient]
  (concat (update-patient-sql patient fhir-patient)
          (update-patient-identifiers-sql patient patient_hospitals fhir-patient)
          (update-patient-telephones-sql patient telephones fhir-patient)
          (update-patient-addresses-sql patient addresses fhir-patient)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef matching-patient-identifiers?
  :args (s/cat :existing-patient (s/keys :req [:t_patient/id :t_patient/nhs_number :t_patient/patient_hospitals])
               :fhir-patient :org.hl7.fhir/Patient))
(defn ^:private matching-patient-identifiers?
  "Does at least one of the patient identifiers match, given an existing record
  and externally derived patient data."
  [{patient-pk      :t_patient/id, nhsnumber :t_patient/nhs_number,
    :t_patient/keys [patient_hospitals]} fhir-patient]
  (let [known-authorities (assoc identifier-system->legacy-hospital "https://fhir.nhs.uk/Id/nhs-number" :nhs-number) ;; list of 'known' authorities
        ;; generate a list of existing identifiers, including nhs number when known
        ids-1 (cond-> (into #{} (map (fn [{:t_patient_hospital/keys [hospital_fk patient_identifier]}]
                                       (vector hospital_fk (str/upper-case patient_identifier)))) patient_hospitals)
                nhsnumber (conj (vector :nhs-number nhsnumber)))
        ;; generate a list of incoming identifiers, mapped to legacy hospital_fk identifiers
        ids-2 (into #{}
                    (comp (map #(vector (known-authorities (:org.hl7.fhir.Identifier/system %)) (str/upper-case (:org.hl7.fhir.Identifier/value %)))) ;; tuple of mapped hospital_fk and value
                          (filter (fn [[hospital_fk _]] hospital_fk))) ;;only include identifiers with a local mapped hospital_fk
                    (:org.hl7.fhir.Patient/identifier fhir-patient))] ;; list of external identifiers
    (boolean (seq (set/intersection ids-1 ids-2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private patient-to-update-spec
  (s/keys :req [:t_patient/id :t_patient/authoritative_demographics
                :t_patient/nhs_number :t_patient/patient_hospitals]))

(defn ^:private update-patient-from-external-sql*
  "Return a sequence of SQL statements (as Clojure data) to update patient data
  based on data derived from an external authority.
  Essentially the same as `update-patient-from-external-sql but with added
  validation and additional options (e.g. `update-last-updated` and `match`."
  [{patient-pk :t_patient/id :as patient} fhir-patient
   {:keys [update-last-updated match] :or {update-last-updated true, match true}}]
  (when-not (s/valid? patient-to-update-spec patient)
    (throw (ex-info "invalid patient data" (s/explain-data patient-to-update-spec patient))))
  (when-not (s/valid? :org.hl7.fhir/Patient fhir-patient)
    (throw (ex-info "invalid patient data" (s/explain-data :org.hl7.fhir/Patient fhir-patient))))
  (when (and match (not (matching-patient-identifiers? patient fhir-patient)))
    (throw (ex-info "unable to update existing patient: no matching identifier"
                    {:patient patient, :external (:org.hl7.fhir.Patient/identifier fhir-patient)})))
  (cond-> (update-patient-from-external-sql patient fhir-patient)
    update-last-updated
    (conj {:update :t_patient :where [:= :id patient-pk]
           :set    {:authoritative_last_updated (LocalDateTime/now)}})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti demographic-authority
  "For a given patient, return a map of :authority and :identifier that can be
  used to fetch authoritative demographics. Identifier is a FHIR identifier.
  This currently uses a model of demographic authorities as per legacy RSDB,
  duplicating its functionality. This could potentially switch to using
  different logic in the future. Not all patients have a demographic authority;
  those patients are managed locally."
  :t_patient/authoritative_demographics)

(defmethod demographic-authority :EMPI
  [{:t_patient/keys [nhs_number]}]
  (when nhs_number
    {:authority  :EMPI
     :identifier {:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
                  :org.hl7.fhir.Identifier/value  nhs_number}}))

(defmethod demographic-authority :CAVUHB
  [{:t_patient/keys [nhs_number patient_hospitals]}]
  (let [cav-hospital-fk (get identifier-system->legacy-hospital "https://fhir.cavuhb.nhs.wales/Id/pas-identifier")
        cav-ph (first (filter (fn [{:t_patient_hospital/keys [authoritative hospital_fk]}]
                                (and authoritative (= cav-hospital-fk hospital_fk))) patient_hospitals))]
    (cond
      ;; if we have an authoritative CAV CRN, resolve against CAV as authority using that
      cav-ph
      {:authority  :CAVUHB
       :identifier {:org.hl7.fhir.Identifier/system "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
                    :org.hl7.fhir.Identifier/value  (:t_patient_hospital/patient_identifier cav-ph)}}
      ;; if not, and we have NHS number, resolve against CAV as authority using that
      nhs_number
      {:authority  :CAVUHB
       :identifier {:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
                    :org.hl7.fhir.Identifier/value  nhs_number}})))

(defmethod demographic-authority :default
  [_]
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private fetch-from-authority
  "Call demographic service if an authority can be identified, or returns nil
  - demographic-service : a fn that takes an authority and FHIR identifier
                          returning a FHIR representation of patient data
  - patient             : existing patient data."
  [demographic-service patient]
  (when-let [{:keys [authority identifier]} (demographic-authority patient)]
    (demographic-service authority identifier)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private fetch-patient-for-update
  [txn patient-pk]
  (assoc (db/execute-one! txn (sql/format {:select :* :from :t_patient :where [:= :id patient-pk]}))
    :t_patient/addresses (db/execute! txn (sql/format {:select :* :from :t_address :where [:= :patient_fk patient-pk]}))
    :t_patient/telephones (db/execute! txn (sql/format {:select :* :from :t_patient_telephone :where [:= :patient_fk patient-pk]}))
    :t_patient/patient_hospitals (db/execute! txn (sql/format {:select :* :from :t_patient_hospital :where [:= :patient_fk patient-pk]}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef update-patient
  :args (s/cat :conn any? :demographic-service fn?, :patient map? :opts (s/? map?)))
(defn update-patient
  "Returns a sequence of Clojure data structures representing the SQL necessary
  to update the given patient using data from an  external authority. The
  authority is determined automatically, and when available, used to update
  the patient. Returns nil if no update possible.

  Parameters:
  - conn : a database connection; needed only if refetch needed so can be nil
  - demographic-service : a function that, given an authority and identifier can
                          return a FHIR representation of patient data, or nil
  - patient : existing patient data - including
       - :t_patient/id
       - :t_patient/authoritative_demographics
       - :t_patient/nhs_number
       - :t_patient/patient_hospitals
       - :t_patient/addresses
       - :t_patient/telephones
     If the existing patient data is insufficient, a refetch will be performed
  - opts : options map, including:
      - :refetch : force a refetch patient data prior to update
      - :match   : check that at least one identifier matches, default true"
  ([conn demographic-service patient]
   (update-patient conn demographic-service patient {}))
  ([conn demographic-service {patient-pk :t_patient/id, :as patient}
    {:keys [refetch] :or {refetch false} :as opts}]
   (let [patient'
         (cond
           ;; refresh patient data before updating if requested
           refetch (fetch-patient-for-update conn patient-pk)
           ;; if the caller has provided only patient-pk, fetch required data
           (and patient-pk (= 1 (count patient))) (fetch-patient-for-update conn patient-pk)
           ;; if the caller knows what they are doing, use that data
           (s/valid? patient-to-update-spec patient) patient
           :else
           (throw (ex-info "Invalid patient data; provide either only primary key or valid data"
                           (s/explain-data patient-to-update-spec patient))))]
     (when-let [fhir-patient (fetch-from-authority demographic-service patient')]
       (update-patient-from-external-sql* patient' fhir-patient opts)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))

  (update-patient
    nil
    (fn [auth identifier]
      {:org.hl7.fhir.Patient/gender              "female"
       :org.hl7.fhir.Patient/address             []
       :org.hl7.fhir.Patient/telecom             [{:org.hl7.fhir.ContactPoint/system "phone"
                                                   :org.hl7.fhir.ContactPoint/value  "02920747747"
                                                   :org.hl7.fhir.ContactPoint/use    "work"}]
       :org.hl7.fhir.Patient/generalPractitioner []
       :org.hl7.fhir.Patient/name                [{:org.hl7.fhir.HumanName/family "Smith"
                                                   :org.hl7.fhir.HumanName/given  ["Mark"]
                                                   :org.hl7.fhir.HumanName/prefix ["Dr"]
                                                   :org.hl7.fhir.HumanName/use    "usual"}]
       :org.hl7.fhir.Patient/active              true
       :org.hl7.fhir.Patient/birthDate           (LocalDate/of 1990 1 1)
       :org.hl7.fhir.Patient/identifier          [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
                                                   :org.hl7.fhir.Identifier/value  "1111111111"}]})
    {:t_patient/id                         1
     :t_patient/nhs_number                 "1111111111"
     :t_patient/authoritative_demographics :EMPI
     :t_patient/patient_hospitals          []
     :t_patient/addresses                  []
     :t_patient/telephones                 []})

  (fetch-patient-for-update conn 1001)
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


