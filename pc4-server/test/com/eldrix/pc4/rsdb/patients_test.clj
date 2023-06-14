(ns com.eldrix.pc4.rsdb.patients-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest use-fixtures is]]
            [com.eldrix.pc4.rsdb.patients :as patients]
            [com.eldrix.pc4.rsdb.projects :as projects]
            [next.jdbc :as jdbc])
  (:import (java.time LocalDate)))

(stest/instrument)
(def ^:dynamic *conn* nil)
(def ^:dynamic *patient* nil)

(def test-db-connection-spec
  "Database connection specification for tests."
  {:dbtype "postgresql" :dbname "rsdb"})

(def patient
  {:salt       "1" :user-id 1 :project-id 1
   :nhs-number "9999999999" :sex :MALE :date-birth (LocalDate/of 1980 1 1)})

(defn with-patient
  [f]
  (with-open [conn (jdbc/get-connection test-db-connection-spec)]
    (jdbc/with-transaction [txn conn {:rollback-only true :isolation :serializable}]
      (let [patient (projects/register-legacy-pseudonymous-patient txn patient)]
        (binding [*conn* txn
                  *patient* patient]
          (f))))))

(use-fixtures :each with-patient)

(deftest test-patient
  (is (= (:t_patient/nhs_number *patient*) (:nhs-number patient))))

(deftest test-create-medication
  (let [patient-identifier (:t_patient/patient_identifier *patient*)
        med (patients/create-medication *conn* {:t_patient/patient_identifier       patient-identifier
                                                :t_medication/medication_concept_fk 774459007 ;; alemtuzumab
                                                :t_medication/as_required           true
                                                :t_medication/date_from             (LocalDate/of 2020 1 1)
                                                :t_medication/date_to               nil
                                                :t_medication/reason_for_stopping   :NOT_APPLICABLE})]
    (is (= 774459007 (:t_medication/medication_concept_fk med)))
    (is (= (LocalDate/of 2020 1 1) (:t_medication/date_from med)))
    (let [med' (patients/update-medication *conn* (assoc med :t_medication/date_to (LocalDate/of 2020 1 3)))
          meds (patients/fetch-all-medication *conn* *patient*)]
      (is (= 774459007 (:t_medication/medication_concept_fk med')))
      (is (= (LocalDate/of 2020 1 1) (:t_medication/date_from med')))
      (is (= (LocalDate/of 2020 1 3) (:t_medication/date_to med')))
      (is (= med' (first meds)))
      (patients/delete-medication *conn* med')
      (is (empty? (patients/fetch-all-medication *conn* *patient*))))))

(deftest test-medication-with-events
  (let [med (patients/create-medication *conn* {:t_patient/patient_identifier       (:t_patient/patient_identifier *patient*)
                                                :t_medication/medication_concept_fk 774459007 ;; alemtuzumab
                                                :t_medication/as_required           true
                                                :t_medication/date_from             (LocalDate/of 2020 1 1)
                                                :t_medication/date_to               (LocalDate/of 2020 1 1)
                                                :t_medication/reason_for_stopping   :ADVERSE_EVENT})]))