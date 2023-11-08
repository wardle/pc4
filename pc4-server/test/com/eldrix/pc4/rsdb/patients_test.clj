(ns com.eldrix.pc4.rsdb.patients-test
  (:require [clojure.test :refer [deftest is]]
            [com.eldrix.pc4.rsdb.patients :as patients])
  (:import (java.time LocalDate)))


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
  (let [sql-1 (patients/update-patient-sql {:t_patient/id 1} base-fhir-patient)
        sql-2 (patients/update-patient-sql {:t_patient/id 1}
                                           (assoc base-fhir-patient :org.hl7.fhir.Patient/deceased true))]
    (is (= "FEMALE" (get-in sql-1 [0 :set :sex])))
    (is (nil? (get-in sql-1 [0 :set :date_death])))
    (is (and (= (get-in sql-2 [0 :set :date_death])
                (get-in sql-2 [0 :set :date_birth]))
             (= "UNKNOWN" (get-in sql-2 [0 :set :date_death_accuracy])))
        "When a patient is marked as 'deceased', date of death should be set to date of birth, and accuracy set as unknown")))

(deftest test-patient-general-practitioner
  (let [sql (patients/update-patient-sql
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
    (is (= "G8640097" (get-in sql [0 :set :general_practitioner_fk])))))

(deftest test-identifier-update
  (let [identifiers [{:org.hl7.fhir.Identifier/system "https://fhir.ctmuhb.nhs.wales/Id/pas-identifier"
                      :org.hl7.fhir.Identifier/value  "A123456"}
                     {:org.hl7.fhir.Identifier/system "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
                      :org.hl7.fhir.Identifier/value  "A999998"}]
        patient (assoc base-fhir-patient :org.hl7.fhir.Patient/identifier identifiers)
        sql-1 (patients/update-patient-identifiers-sql {:t_patient/id 1} [] patient)
        sql-2 (patients/update-patient-identifiers-sql
                {:t_patient/id 1}
                [{:t_patient_hospital/hospital_fk "RWMBV"
                  :t_patient_hospital/patient_identifier  "A999998"}]
                patient)
        sql-3 (patients/update-patient-identifiers-sql
                {:t_patient/id 1}
                [{:t_patient_hospital/hospital_fk "RWMBV"
                  :t_patient_hospital/patient_identifier  "A999998"}
                 {:t_patient_hospital/hospital_fk "RYLB3"
                  :t_patient_hospital/patient_identifier  "A123456"}]
                patient)]
    (is (= [[1 "RYLB3" "A123456"] [1 "RWMBV" "A999998"]]
           (get-in sql-1 [0 :values]))
        "When no existing identifiers, two new identifiers should be added")
    (is (= [[1 "RYLB3" "A123456"]]
           (get-in sql-2 [0 :values]))
        "Only missing identifiers should be inserted")
    (is (nil? (seq sql-3))
        "Should be a no-op when all identifiers already exist")))

(deftest test-telephone
  (let [existing-1 {:t_patient_telephone/telephone   "02920 747747"
                    :t_patient_telephone/description "Home"}
        existing-2 (assoc existing-1 :t_patient_telephone/telephone "999")
        new-1 {:org.hl7.fhir.ContactPoint/system "phone"
               :org.hl7.fhir.ContactPoint/use    "home"
               :org.hl7.fhir.ContactPoint/value  "02920 747747"}
        new-2 (assoc new-1 :org.hl7.fhir.ContactPoint/value "999")
        sql-1 (patients/update-patient-telephones-sql
                {:t_patient/id 1}
                [existing-1]
                base-fhir-patient)
        sql-2 (patients/update-patient-telephones-sql
                {:t_patient/id 1}
                [existing-1]
                (assoc base-fhir-patient :org.hl7.fhir.Patient/telecom [new-1]))
        sql-3 (patients/update-patient-telephones-sql
                {:t_patient/id 5}
                [existing-1]
                (assoc base-fhir-patient :org.hl7.fhir.Patient/telecom [new-1 new-2]))
        sql-4 (patients/update-patient-telephones-sql
                {:t_patient/id 5}
                [existing-1 existing-2]
                (assoc base-fhir-patient :org.hl7.fhir.Patient/telecom [new-1 new-2]))]
    (is (nil? (seq sql-1)) "Should be a no-op if there are no new telephone records")
    (is (nil? (seq sql-2)) "Should be a no-op if record already exists")
    (is (= [[5 "999" "phone" ]] (get-in sql-3 [0 :values ])) "Should add a single missing record")
    (is (nil? (seq sql-4))) "Should not add a record as records already exist"))

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
  (is (empty? (patients/update-patient-addresses-sql
                {:t_patient/id 1}
                [base-old-address]
                (assoc base-fhir-patient
                  :org.hl7.fhir.Patient/address [base-fhir-address])))))

(deftest test-update-address-simple
  (let [sql (patients/update-patient-addresses-sql
              {:t_patient/id 1}
              []
              (assoc base-fhir-patient
                :org.hl7.fhir.Patient/address [base-fhir-address]))]
    (is (= 1 (count (get-in sql [0 :values]))))
    (is (= 1 (get-in sql [0 :values 0 :patient_fk])))
    (is (= "1 Station Road" (get-in sql [0 :values 0 :address1])))))

(deftest test-update-address-replace-all
  "When external data has no start or end date, then it should replace all
  prior address history."
  (let [sql (patients/update-patient-addresses-sql
              {:t_patient/id 1}
              [(assoc base-old-address :t_address/id 1)
               (assoc base-old-address :t_address/id 2)
               (assoc base-old-address :t_address/id 3)]
              (assoc base-fhir-patient
                :org.hl7.fhir.Patient/address [(assoc base-fhir-address :org.hl7.fhir.Address/line ["10 Station Road"])]))]
    (is (= {:delete-from :t_address, :where [:in :id #{1 2 3}]}
           (get sql 0)) "All existing records should be deleted")
    (is (= 1 (get-in sql [1 :values 0 :patient_fk])) "A new record replacing address history should be inserted")
    (is (= "10 Station Road" (get-in sql [1 :values 0 :address1])))))

; ;With new external data, we'd expect the current address history to be truncated.
(deftest test-update-address-truncation
  (let [sql (patients/update-patient-addresses-sql
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
    (is (= "UHW" (get-in sql [2 :values 0 :address1])))))


  