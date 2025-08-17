(ns pc4.araf.interface-test
  (:require [clojure.java.io :as io]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest use-fixtures is testing]]
            [com.eldrix.nhsnumber :as nnn]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
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
  (let [config {:pc4.araf.interface/patient {:ds test-db-connection-spec}}
        system (ig/init config)]
    (try
      (let [{:keys [ds]} (::araf/patient system)]
        (with-open [conn (jdbc/get-connection ds)]
          (jdbc/with-transaction [tx conn {:rollback-only true}]
            (binding [*service* {:ds tx}]
              (f)))))
      (finally
        (ig/halt! system)))))

(use-fixtures :each with-araf-service)

(deftest test-create-and-fetch-request
  (testing "create-request generates a request with token and araf-type"
    (let [nhs-number (nnn/random)
          araf-type :valproate
          expires (.plus (Instant/now) (Duration/ofDays 14))
          request (araf/create-request *service* nhs-number araf-type expires)]
      (is (some? request))
      (is (= nhs-number (:request/nhs_number request)))
      (is (= araf-type (:request/araf_type request)))
      (is (string? (:request/token request)))
      (is (= 8 (count (:request/token request))))
      
      (testing "fetch-request retrieves the created request"
        (let [fetched (araf/fetch-request* (:ds *service*) (:request/token request) nhs-number)]
          (is (= request fetched))))))

  (testing "fetch-request with invalid token returns error"
    (let [result (araf/fetch-request* (:ds *service*) "INVALID1" (nnn/random))]
      (is (= (:error result) araf/error-no-matching-request))))

  (testing "create-request generates unique tokens"
    (let [nhs-number "1111111111"
          expires (Instant/.plus (Instant/now) (Duration/ofDays 14))
          requests (repeatedly 200 #(araf/create-request *service* nhs-number :valproate expires))
          tokens (map :request/token requests)]
      (is (= 200 (count tokens)))
      (is (= (count tokens) (count (distinct tokens))))
      (is (every? #(= 8 (count %)) tokens)))))

(deftest test-expired-request
  (testing "fetch-request does not return expired requests"
    (let [nhs-number "5555555555"
          araf-type :valproate
          expires (Instant/.minus (Instant/now) (Duration/ofDays 1))
          request (araf/create-request *service* nhs-number araf-type expires)]
      (is (some? request))
      (let [result (araf/fetch-request* (:ds *service*) (:request/token request) nhs-number)]
        (is (= (:error result) araf/error-no-matching-request))))))

(deftest test-qr-code-generation
  (testing "generate-qr-code creates a QR code image file"
    (let [base-url "https://example.com/araf"
          access-key "TEST1234"
          nhs-number "1111111111"
          qr-bytes (araf/generate-qr-code base-url access-key nhs-number)
          temp-file (File/createTempFile "qr-test-" ".png")]
      (is (some? qr-bytes))
      (is (> (count qr-bytes) 0))
      (io/copy qr-bytes temp-file)
      (is (.exists temp-file))
      (is (> (.length temp-file) 0))
      (println "QR code written to:" (.getAbsolutePath temp-file)))))