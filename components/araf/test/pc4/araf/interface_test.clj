(ns pc4.araf.interface-test
  (:require [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest use-fixtures is testing]]
            [com.eldrix.nhsnumber :as nnn]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [pc4.araf.impl.token :as token]
            [pc4.araf.interface :as araf])
  (:import (java.io File)
           (java.time Duration Instant)))

(stest/instrument)

(def ^:dynamic *service* nil)

(def test-db-connection-spec
  "Database connection specification for tests."
  {:dbtype "postgresql" :dbname "araf_remote"})

(defn with-araf-service
  [f]
  (let [config {::araf/patient {:ds test-db-connection-spec :secret "abc"}}
        system (ig/init config)]
    (try
      (let [{:keys [ds] :as svc} (::araf/patient system)]
        (with-open [conn (jdbc/get-connection ds)]
          (jdbc/with-transaction [tx conn {:rollback-only true}]
            (binding [*service* (assoc svc :ds tx)]         ;; replace the ds with a transaction
              (f)))))
      (finally
        (ig/halt! system)))))

(use-fixtures :each with-araf-service)

(deftest test-create-and-fetch-request
  (testing "create-request generates a request with access key and araf-type"
    (let [nnn (nnn/random)
          araf :valproate
          expires (.plus (Instant/now) (Duration/ofDays 14))
          {:keys [access_key long_access_key nhs_number araf_type] :as request} (araf/create-request *service* nnn araf expires)]
      (is (= nnn nhs_number))
      (is (= araf araf_type))

      (testing "fetch-request retrieves the created request"
        (let [fetched (araf/fetch-request* (:ds *service*) nnn access_key)]
          (is (= request fetched)))
        (let [fetched (araf/fetch-request* (:ds *service*) long_access_key)]
          (is (= request fetched))))))

  (testing "fetch-request with invalid access key returns error"
    (let [result (araf/fetch-request* (:ds *service*) (nnn/random) "INVALID1")]
      (is (= (:error result) araf/error-no-matching-request))))

  (testing "create-request generates unique tokens"
    (let [nhs-number "1111111111"
          expires (Instant/.plus (Instant/now) (Duration/ofDays 14))
          requests (repeatedly 200 #(araf/create-request *service* nhs-number :valproate expires))
          tokens (map :access_key requests)]
      (is (= 200 (count tokens)))
      (is (= (count tokens) (count (distinct tokens)))))))

(deftest test-expired-request
  (testing "fetch-request does not return expired requests"
    (let [nhs-number "5555555555"
          araf-type :valproate
          expires (Instant/.minus (Instant/now) (Duration/ofDays 1))
          {:keys [access_key] :as request} (araf/create-request *service* nhs-number araf-type expires)]
      (is (some? request))
      (let [result (araf/fetch-request* (:ds *service*) nhs-number access_key)]
        (is (= (:error result) araf/error-no-matching-request))))))

(deftest test-qr-code-generation
  (testing "generate-qr-code creates a QR code image file"
    (let [base-url "https://example.com/araf"
          long-access-key (token/gen-long-access-key)
          qr-bytes (araf/generate-qr-code base-url long-access-key)
          temp-file (File/createTempFile "qr-test-" ".png")]
      (is (some? qr-bytes))
      (is (> (count qr-bytes) 0))
      (io/copy qr-bytes temp-file)
      (is (.exists temp-file))
      (is (> (.length temp-file) 0))
      (println "QR code written to:" (.getAbsolutePath temp-file)))))

(deftest test-jwt-functions
  (testing "generate and validate valid JWT"
    (let [svc {:secret "secret"}]
      (dotimes [_ 10]
        (is (araf/valid-jwt? svc (araf/generate-jwt svc))))))

  (testing "reject expired token"
    (let [svc {:secret "secret"}
          timestamp (Instant/parse "2025-01-01T12:00:00Z")
          token (araf/generate-jwt svc {:now timestamp})
          current-time (Instant/parse "2025-01-01T12:10:00Z")] ; 10 minutes later
      (is (not (araf/valid-jwt? svc token current-time)))))

  (testing "reject invalid token"
    (let [svc {:secret "secret"}]
      (is (not (araf/valid-jwt? svc "invalid-token"))))))