(ns pc4.rsdb.demographics-test
  "Tests for FHIR patient demographics.

  Includes both behavioral tests (end-to-end workflows using synthetic data) and
  unit tests (SQL generation edge cases).

  Test data: components/demographic/resources/demographic/synthetic-patients.edn"
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing]]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [pc4.demographic.protos :as p]
            [pc4.demographic.synthetic :as synth]
            [pc4.rsdb.demographics :as demog]
            [pc4.rsdb.patients :as patients]
            [pc4.rsdb.helper :as helper])
  (:import (java.time LocalDate)))

(stest/instrument)

(defn submap?
  "Check if expected is a submap of actual (all expected keys/values present in actual)"
  [expected actual]
  (let [actual-ns (some-> (keys actual) first namespace)]
    (every? (fn [[k v]]
              (let [actual-k (if (namespace k)
                               k
                               (if actual-ns
                                 (keyword actual-ns (name k))
                                 k))
                    actual-v (get actual actual-k ::not-found)]
                (or (= v actual-v)
                    (and (nil? v) (= "" actual-v))
                    (and (= "" v) (nil? actual-v)))))
            expected)))

(defn compare-submaps
  "Compare collections of maps, checking each expected is submap of corresponding actual"
  [expected-coll actual-coll]
  (and (= (count expected-coll) (count actual-coll))
       (every? identity (map submap? expected-coll actual-coll))))

(defn normalize-hospital
  "Normalize hospital identifier for comparison"
  [h]
  (if (contains? h :t_patient_hospital/hospital_fk)
    {:hospital_fk (:t_patient_hospital/hospital_fk h)
     :patient_identifier (:t_patient_hospital/patient_identifier h)}
    h))

(defn verify-patient-state
  "Verify actual DB state matches expected state from synthetic data"
  [txn patient-pk expected label]
  (testing label
    (let [{:t_patient/keys [addresses patient_hospitals] :as patient}
          (patients/fetch-patient-for-update txn patient-pk)

          expected-patient
          (dissoc expected :addresses :hospitals :telephones)]
      (testing "patient fields"
        (is (submap? expected-patient patient)
            (pr-str {:expected expected-patient :actual patient})))
      (testing "addresses"
        (is (compare-submaps (:addresses expected) addresses)
            (pr-str {:expected (:addresses expected) :actual addresses})))
      (testing "hospital identifiers"
        (let [actual (map normalize-hospital patient_hospitals)]
          (is (= (set (:hospitals expected)) (set actual))
              (pr-str {:expected (:hospitals expected) :actual actual})))))))

(defn process-patient-through-versions
  "Create patient from v1, then update through all subsequent versions, verifying each"
  [txn patient-data]
  (let [versions (:versions patient-data)
        sorted-versions (sort (keys versions))
        v1 (first sorted-versions)

        ;; Extract identifier from v1 FHIR
        v1-fhir (get-in versions [v1 :fhir])
        identifier (first (:org.hl7.fhir.Patient/identifier v1-fhir))
        system (:org.hl7.fhir.Identifier/system identifier)
        value (:org.hl7.fhir.Identifier/value identifier)

        ;; Create provider for v1 and fetch
        prov-v1 (synth/make-synthetic-provider :v v1)
        results (p/fetch prov-v1 system value)]

    ;; Skip patients with duplicate identifiers (e.g., duplicate NHS numbers)
    ;; Those are for testing the multiple-results edge case, not normal workflow
    (when (= 1 (count results))
      (let [fhir-v1 (first results)

            ;; Create patient
            patient-pk (patients/create-patient-from-fhir! txn fhir-v1)

            ;; Verify v1 state
            expected-v1 (get-in versions [v1 :db])]

        (verify-patient-state txn patient-pk expected-v1 (str value " v" v1))

        ;; Update through remaining versions
        (doseq [version (rest sorted-versions)]
      (let [prov (synth/make-synthetic-provider :v version)
            fhir (first (p/fetch prov system value))
            expected (get-in versions [version :db])
            patient-for-update (patients/fetch-patient-for-update txn patient-pk)
            update-sql (demog/update-patient-from-fhir-sql patient-for-update fhir)]

        (doseq [stmt update-sql]
          (jdbc/execute! txn (sql/format stmt)))
        (verify-patient-state txn patient-pk expected (str value " v" version))))))))

(deftest ^:live test-all-synthetic-patients
  (testing "Process all patients through all versions from synthetic-patients.edn"
    (let [ds (helper/get-dev-datasource)
          patients (synth/load-synthetic-patients)]

      (doseq [patient-data patients]
        (jdbc/with-transaction [txn ds {:rollback-only true :isolation :serializable}]
          (process-patient-through-versions txn patient-data))))))

;;; Unit tests for SQL generation edge cases
;;;
;;; These tests verify specific edge cases and business logic that may not be
;;; fully exercised by the behavioral tests above.

(def base-fhir-patient
  {:org.hl7.fhir.Patient/identifier          []
   :org.hl7.fhir.Patient/active              true
   :org.hl7.fhir.Patient/name                []
   :org.hl7.fhir.Patient/birthDate           (LocalDate/of 1980 1 1)
   :org.hl7.fhir.Patient/generalPractitioner []
   :org.hl7.fhir.Patient/gender              "female"
   :org.hl7.fhir.Patient/address             []
   :org.hl7.fhir.Patient/telecom             []})

(deftest test-patient-update
  (testing "Basic patient update SQL generation"
    (let [sql-1 (#'demog/update-patient-sql {:t_patient/id 1} base-fhir-patient)
          sql-2 (#'demog/update-patient-sql {:t_patient/id 1}
                                            (assoc base-fhir-patient :org.hl7.fhir.Patient/deceased true))]
      (is (= "FEMALE" (get-in sql-1 [0 :set :sex])))
      (is (nil? (get-in sql-1 [0 :set :date_death])))
      (is (and (= (get-in sql-2 [0 :set :date_death])
                  (get-in sql-2 [0 :set :date_birth]))
               (= "UNKNOWN" (get-in sql-2 [0 :set :date_death_accuracy])))
          "When a patient is marked as 'deceased', date of death should be set to date of birth, and accuracy set as unknown"))))

(deftest test-patient-general-practitioner
  (testing "GP mapping from FHIR to database"
    (let [sql (#'demog/update-patient-sql
               {:t_patient/id 1}
               (assoc base-fhir-patient
                      :org.hl7.fhir.Patient/generalPractitioner
                      [{:uk.nhs.fhir.Id/ods-organization-code "V8199900",
                        :org.hl7.fhir.Reference/type          "Organization",
                        :org.hl7.fhir.Reference/identifier    #:org.hl7.fhir.Identifier{:system "https://fhir.nhs.uk/Id/ods-organization-code",
                                                                                        :value  "V8199900"}}
                       {:uk.org.hl7.fhir.Id/gmp-number     "G8640097",
                        :org.hl7.fhir.Reference/type       "Practitioner",
                        :org.hl7.fhir.Reference/identifier #:org.hl7.fhir.Identifier{:system "https://fhir.hl7.org.uk/Id/gmp-number",
                                                                                     :value  "G8640097"}}]))]
      (is (= "V8199900" (get-in sql [0 :set :surgery_fk])))
      (is (= "G8640097" (get-in sql [0 :set :general_practitioner_fk]))))))

(deftest test-identifier-update
  (testing "Hospital identifier synchronization"
    (let [identifiers [{:org.hl7.fhir.Identifier/system "https://fhir.ctmuhb.nhs.wales/Id/pas-identifier"
                        :org.hl7.fhir.Identifier/value  "A123456"}
                       {:org.hl7.fhir.Identifier/system "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
                        :org.hl7.fhir.Identifier/value  "A999998"}]
          patient (assoc base-fhir-patient :org.hl7.fhir.Patient/identifier identifiers)
          sql-1 (#'demog/update-patient-identifiers-sql {:t_patient/id 1} [] patient)
          sql-2 (#'demog/update-patient-identifiers-sql
                 {:t_patient/id 1}
                 [{:t_patient_hospital/hospital_fk        "RWMBV"
                   :t_patient_hospital/patient_identifier "A999998"}]
                 patient)
          sql-3 (#'demog/update-patient-identifiers-sql
                 {:t_patient/id 1}
                 [{:t_patient_hospital/hospital_fk        "RWMBV"
                   :t_patient_hospital/patient_identifier "A999998"}
                  {:t_patient_hospital/hospital_fk        "RYLB3"
                   :t_patient_hospital/patient_identifier "A123456"}]
                 patient)]
      (is (= [[1 "RYLB3" "A123456"] [1 "RWMBV" "A999998"]]
             (get-in sql-1 [0 :values]))
          "When no existing identifiers, two new identifiers should be added")
      (is (= [[1 "RYLB3" "A123456"]]
             (get-in sql-2 [0 :values]))
          "Only missing identifiers should be inserted")
      (is (nil? (seq sql-3))
          "Should be a no-op when all identifiers already exist"))))

(deftest test-telephone
  (testing "Telephone number edge cases"
    (let [existing-1 {:t_patient_telephone/telephone   "02920 747747"
                      :t_patient_telephone/description "Home"}
          existing-2 (assoc existing-1 :t_patient_telephone/telephone "999")
          new-1 {:org.hl7.fhir.ContactPoint/system "phone"
                 :org.hl7.fhir.ContactPoint/use    "home"
                 :org.hl7.fhir.ContactPoint/value  "02920 747747"}
          new-2 (assoc new-1 :org.hl7.fhir.ContactPoint/value "999")
          sql-1 (#'demog/update-patient-telephones-sql
                 {:t_patient/id 1}
                 [existing-1]
                 base-fhir-patient)
          sql-2 (#'demog/update-patient-telephones-sql
                 {:t_patient/id 1}
                 [existing-1]
                 (assoc base-fhir-patient :org.hl7.fhir.Patient/telecom [new-1]))
          sql-3 (#'demog/update-patient-telephones-sql
                 {:t_patient/id 5}
                 [existing-1]
                 (assoc base-fhir-patient :org.hl7.fhir.Patient/telecom [new-1 new-2]))
          sql-4 (#'demog/update-patient-telephones-sql
                 {:t_patient/id 5}
                 [existing-1 existing-2]
                 (assoc base-fhir-patient :org.hl7.fhir.Patient/telecom [new-1 new-2]))]
      (is (nil? (seq sql-1)) "Should be a no-op if there are no new telephone records")
      (is (nil? (seq sql-2)) "Should be a no-op if record already exists")
      (is (= [[5 "999" "phone"]] (get-in sql-3 [0 :values])) "Should add a single missing record")
      (is (nil? (seq sql-4)) "Should not add a record as records already exist"))))

(def base-old-address
  {:t_address/id           123
   :t_address/address1     "1 Station Road"
   :t_address/address2     "Heath"
   :t_address/address3     "Cardiff"
   :t_address/address4     ""
   :t_address/postcode_raw "CF14 4XW"
   :t_address/date_from    nil
   :t_address/date_to      nil})

(def base-fhir-address
  {:org.hl7.fhir.Address/line       ["1 Station Road"]
   :org.hl7.fhir.Address/postalCode "CF14 4XW"
   :org.hl7.fhir.Address/city       "Heath"
   :org.hl7.fhir.Address/district   "Cardiff"
   :org.hl7.fhir.Address/country    ""})

(deftest test-update-address-no-change
  (testing "Address update is no-op when address unchanged"
    (is (empty? (#'demog/update-patient-addresses-sql
                 {:t_patient/id 1}
                 [base-old-address]
                 (assoc base-fhir-patient
                        :org.hl7.fhir.Patient/address [base-fhir-address]))))))

(deftest test-update-address-simple
  (testing "Simple address insertion"
    (let [sql (#'demog/update-patient-addresses-sql
               {:t_patient/id 1}
               []
               (assoc base-fhir-patient
                      :org.hl7.fhir.Patient/address [base-fhir-address]))]
      (is (= 1 (count (get-in sql [0 :values]))))
      (is (= 1 (get-in sql [0 :values 0 :patient_fk])))
      (is (= "1 Station Road" (get-in sql [0 :values 0 :address1]))))))

(deftest test-update-address-replace-all
  (testing "When external data has no start or end date, it replaces all prior address history"
    (let [sql (#'demog/update-patient-addresses-sql
               {:t_patient/id 1}
               [(assoc base-old-address :t_address/id 1)
                (assoc base-old-address :t_address/id 2)
                (assoc base-old-address :t_address/id 3)]
               (assoc base-fhir-patient
                      :org.hl7.fhir.Patient/address [(assoc base-fhir-address :org.hl7.fhir.Address/line ["10 Station Road"])]))]
      (is (= {:delete-from :t_address, :where [:in :id #{1 2 3}]}
             (get sql 0)) "All existing records should be deleted")
      (is (= 1 (get-in sql [1 :values 0 :patient_fk])) "A new record replacing address history should be inserted")
      (is (= "10 Station Road" (get-in sql [1 :values 0 :address1]))))))

(deftest test-update-address-truncation
  (testing "New address with start date truncates existing address history"
    (let [sql (#'demog/update-patient-addresses-sql
               {:t_patient/id 1}
               [(assoc base-old-address :t_address/id 1
                       :t_address/date_from (LocalDate/of 1990 1 1)
                       :t:_address/date_to (LocalDate/of 2000 1 1))
                (assoc base-old-address :t_address/id 2
                       :t_address/date_from (LocalDate/of 2010 1 1)
                       :t_address/date_to (LocalDate/of 2020 1 1))
                (assoc base-old-address :t_address/id 3
                       :t_address/date_from (LocalDate/of 2020 1 1))]
               (assoc base-fhir-patient
                      :org.hl7.fhir.Patient/address [(assoc base-fhir-address :org.hl7.fhir.Address/line ["UHW"]
                                                            :org.hl7.fhir.Address/period {:org.hl7.fhir.Period/start (LocalDate/of 1995 1 1)})]))]
      (is (= {:delete-from :t_address, :where [:in :id #{2 3}]}
             (get sql 0))
          "The second address should be deleted as it starts after the new authority-derived data")
      (is (= {:update :t_address, :set {:date_to (LocalDate/of 1995 1 1)}, :where [:in :id [1]]}
             (get sql 1)))
      (is (= :t_address (get-in sql [2 :insert-into])))
      (is (= 1 (get-in sql [2 :values 0 :patient_fk])))
      (is (= "UHW" (get-in sql [2 :values 0 :address1]))))))

(def match-tests
  [{:pt-1 {:t_patient/id 1, :t_patient/nhs_number "1111111111" :t_patient/patient_hospitals []}
    :pt-2 (assoc base-fhir-patient
                 :org.hl7.fhir.Patient/identifier [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
                                                    :org.hl7.fhir.Identifier/value  "1111111111"}])
    :exp  true}
   {:pt-1 {:t_patient/id 1, :t_patient/nhs_number "1111111111" :t_patient/patient_hospitals []}
    :pt-2 (assoc base-fhir-patient
                 :org.hl7.fhir.Patient/identifier [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
                                                    :org.hl7.fhir.Identifier/value  "1211111111"}])
    :exp  false}
   {:pt-1 {:t_patient/id                1, :t_patient/nhs_number "1111111111"
           :t_patient/patient_hospitals [{:t_patient_hospital/hospital_fk "RYMC7" :t_patient_hospital/patient_identifier "UNIT1234"}]}
    :pt-2 (assoc base-fhir-patient
                 :org.hl7.fhir.Patient/identifier [{:org.hl7.fhir.Identifier/system "https://fhir.sbuhb.nhs.wales/Id/pas-identifier"
                                                    :org.hl7.fhir.Identifier/value  "UNIT1234"}])
    :exp  true}
   {:pt-1 {:t_patient/id                1, :t_patient/nhs_number "1111111111"
           :t_patient/patient_hospitals [{:t_patient_hospital/hospital_fk "RYMC7" :t_patient_hospital/patient_identifier "UNIT1234"}]}
    :pt-2 (assoc base-fhir-patient
                 :org.hl7.fhir.Patient/identifier [{:org.hl7.fhir.Identifier/system "https://fhir.sbuhb.nhs.wales/Id/pas-identifier"
                                                    :org.hl7.fhir.Identifier/value  "UNIT12345"}])
    :exp  false}
   {:pt-1 {:t_patient/id                1, :t_patient/nhs_number "1111111111"
           :t_patient/patient_hospitals [{:t_patient_hospital/hospital_fk "RYMC7" :t_patient_hospital/patient_identifier "UNIT1234"}]}
    :pt-2 (assoc base-fhir-patient
                 :org.hl7.fhir.Patient/identifier [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
                                                    :org.hl7.fhir.Identifier/value  "1211111111"}
                                                   {:org.hl7.fhir.Identifier/system "https://fhir.sbuhb.nhs.wales/Id/pas-identifier"
                                                    :org.hl7.fhir.Identifier/value  "unit1234"}])
    :exp  true}])

(deftest test-match-patients-by-identifiers
  (testing "Patient matching logic by NHS number and hospital identifiers"
    (doseq [{:keys [pt-1 pt-2 exp]} match-tests]
      (is (= exp (#'demog/matching-patient-identifiers? pt-1 pt-2))))))

(comment
  (def ds (helper/get-dev-datasource))
  (def patients (synth/load-synthetic-patients))
  (count patients)
  (keys (first patients)))
