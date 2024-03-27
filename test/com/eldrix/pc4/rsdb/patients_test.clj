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
  (let [med (patients/upsert-medication! *conn* {:t_medication/patient_fk            (:t_patient/id *patient*)
                                                 :t_medication/medication_concept_fk 774459007 ;; alemtuzumab
                                                 :t_medication/as_required           true
                                                 :t_medication/date_from             (LocalDate/of 2020 1 1)
                                                 :t_medication/date_to               nil
                                                 :t_medication/reason_for_stopping   :NOT_APPLICABLE})]
    (is (= 774459007 (:t_medication/medication_concept_fk med)))
    (is (= (LocalDate/of 2020 1 1) (:t_medication/date_from med)))
    (let [med' (patients/upsert-medication! *conn* (assoc med :t_medication/date_to (LocalDate/of 2020 1 3)))
          meds (patients/fetch-medications *conn* *patient*)]
      (is (= 774459007 (:t_medication/medication_concept_fk med')))
      (is (= (LocalDate/of 2020 1 1) (:t_medication/date_from med')))
      (is (= (LocalDate/of 2020 1 3) (:t_medication/date_to med')))
      (is (= (dissoc med' :t_medication/events) (first meds))) ;; upsert-medication! will return {:t_medication/events []}
      (patients/delete-medication! *conn* med')
      (is (empty? (patients/fetch-medications *conn* *patient*))))))

(deftest test-medication-with-events
  (let [events [{:t_medication_event/type             :ADVERSE_EVENT
                 :t_medication_event/event_concept_fk 19307009}
                {:t_medication_event/type     :INFUSION_REACTION
                 :t_medication_event/severity :LIFE_THREATENING}]
        med (patients/upsert-medication!
              *conn* {:t_medication/patient_fk            (:t_patient/id *patient*)
                      :t_medication/medication_concept_fk 774459007 ;; alemtuzumab
                      :t_medication/as_required           true
                      :t_medication/date_from             (LocalDate/of 2020 1 1)
                      :t_medication/date_to               (LocalDate/of 2020 1 1)
                      :t_medication/reason_for_stopping   :ADVERSE_EVENT
                      :t_medication/events                events})
        meds (patients/fetch-medications-and-events *conn* *patient*)]
    (is (= 2 (count (:t_medication/events (first meds)))))
    (is (= (set (map #(merge {:t_medication_event/severity nil :t_medication_event/event_concept_fk nil} %) events))
           (set (map #(select-keys % [:t_medication_event/type :t_medication_event/event_concept_fk :t_medication_event/severity])
                     (:t_medication/events (first meds))))))
    (is (= med (first meds)))
    ;; update, this time with a single adverse event
    (let [med2 (patients/upsert-medication! *conn* (assoc med :t_medication/events (take 1 events)))]
      (is (= 1 (count (:t_medication/events med2))))
      (is (= 19307009 (get-in med2 [:t_medication/events 0 :t_medication_event/event_concept_fk]))))
    ;; update to the original... the ids might change, but the content must be the same
    (let [med3 (patients/upsert-medication! *conn* (assoc med :t_medication/events events))]
      (is (= (dissoc med :t_medication/events) (dissoc med3 :t_medication/events)))
      (is (= 2 (count (:t_medication/events med3))))
      (is (= (set (map #(dissoc % :t_medication_event/id) (:t_medication/events med)))
          (set (map #(dissoc % :t_medication_event/id) (:t_medication/events med3))))))
    (let [med4 (patients/upsert-medication! *conn* (assoc med :t_medication/events []))
          meds (patients/fetch-medications-and-events *conn* *patient*)]
      (is (= 0 (count (:t_medication/events med4))))
      (is (= med4 (first meds)))
      (patients/delete-medication! *conn* med4)
      (is (= 0 (count (patients/fetch-medications-and-events *conn* *patient*)))))))
