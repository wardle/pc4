(ns pc4.rsdb.form-api-test
  (:require [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [pc4.rsdb.interface :as rsdb]
            [pc4.rsdb.nform.api :as nf]
            [pc4.rsdb.nform.impl.form :as form]
            [pc4.rsdb.nform.impl.registry :as registry]))

(stest/instrument)
(def ^:dynamic *conn* nil)

(defn with-conn
  [f]
  (let [ds (pc4.rsdb.helper/get-dev-datasource)]
    (jdbc/with-transaction [txn ds {:rollback-only true :isolation :serializable}]
      (binding [*conn* txn]
        (f)))))

(use-fixtures :each with-conn)

(deftest ^:live test-stores
  (let [store (pc4.rsdb.nform.api/make-form-store *conn*)
        encounter-ids (map :t_encounter/id (rsdb/patient->encounters {:conn *conn*} 14032))
        form-gen (form/gen-form {:mode :insert, :using {:patient_fk   14032 :user_fk 1 :encounter_fk (rand-nth encounter-ids)}})
        forms (map #(dissoc % :id) (gen/sample form-gen (* 500 (count registry/all-form-definitions))))
        generated-types (into #{} (map :form_type) forms)]
    (is (= generated-types registry/all-form-types)
        "Generative tests did not cover all known form types")
    (doseq [form forms]                                     ;; we generate ~500 per known form type
      (let [stored (pc4.rsdb.nform.api/upsert! store form)
            fetched1 (pc4.rsdb.nform.api/form store (:id stored))
            saved (pc4.rsdb.nform.api/upsert! store fetched1)
            fetched2 (pc4.rsdb.nform.api/form store (:id saved))]
        (is (set/subset? (set stored) (set fetched1))
            "Fetched form after save should have same data as that saved")
        (is (= stored fetched1 saved fetched2)
            "Fetching and re-saving and fetching should give same result")))))

(comment
  (with-conn test-stores))