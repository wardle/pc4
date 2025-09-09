(ns pc4.araf.impl.db-test
  (:require [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest use-fixtures is run-tests testing]]
            [com.eldrix.nhsnumber :as nnn]
            [next.jdbc :as jdbc]
            [pc4.araf.impl.db :as db]
            [pc4.araf.impl.token :as token]
            [pc4.araf.impl.qr :as qr])
  (:import (java.io File)
           (java.time Duration Instant)))

(stest/instrument)

(def ^:dynamic *conn* nil)

(def test-db-connection-spec
  "Database connection specification for tests."
  {:dbtype "postgresql" :dbname "araf_patient"})

(defn with-db
  [f]
  (with-open [conn (jdbc/get-connection test-db-connection-spec)]
    (jdbc/with-transaction [txn conn {:rollback-only true}]
      (binding [*conn* txn]
        (f)))))

(use-fixtures :each with-db)

(deftest test-create-and-fetch-request
  (testing "create-request generates a request with access key and araf-type"
    (let [nnn (nnn/random)
          araf :valproate
          expires (.plus (Instant/now) (Duration/ofDays 14))
          {:keys [access_key long_access_key nhs_number araf_type] :as request} (db/create-request *conn* nnn araf expires)]
      (is (= nnn nhs_number))
      (is (= araf araf_type))

      (testing "fetch-request retrieves the created request"
        (let [fetched (db/fetch-request* *conn* nnn access_key)]
          (is (= request fetched)))
        (let [fetched (db/fetch-request* *conn* long_access_key)]
          (is (= request fetched))))))

  (testing "fetch-request with invalid access key returns error"
    (let [result (db/fetch-request* *conn* (nnn/random) "INVALID1")]
      (is (= (:error result) db/error-no-matching-request))))

  (testing "create-request generates unique tokens"
    (let [nhs-number "1111111111"
          expires (Instant/.plus (Instant/now) (Duration/ofDays 14))
          requests (repeatedly 200 #(db/create-request *conn* nhs-number :valproate expires))
          tokens (map :access_key requests)]
      (is (= 200 (count tokens)))
      (is (= (count tokens) (count (distinct tokens)))))))

(deftest test-expired-request
  (testing "fetch-request does not return expired requests"
    (let [nhs-number "5555555555"
          araf-type :valproate
          expires (Instant/.minus (Instant/now) (Duration/ofDays 1))
          {:keys [access_key] :as request} (db/create-request *conn* nhs-number araf-type expires)]
      (is (some? request))
      (let [result (db/fetch-request* *conn* nhs-number access_key)]
        (is (= (:error result) db/error-no-matching-request))))))

(deftest test-qr-code-generation
  (testing "generate-qr-code creates a QR code image file"
    (let [base-url "https://example.com/araf"
          long-access-key (token/gen-long-access-key)
          qr-bytes (qr/generate base-url long-access-key)
          temp-file (File/createTempFile "qr-test-" ".png")]
      (is (some? qr-bytes))
      (is (> (count qr-bytes) 0))
      (io/copy qr-bytes temp-file)
      (is (.exists temp-file))
      (is (> (.length temp-file) 0))
      (println "QR code written to:" (.getAbsolutePath temp-file)))))

(comment
  (run-tests))