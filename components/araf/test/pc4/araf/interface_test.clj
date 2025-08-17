(ns pc4.araf.interface-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest use-fixtures is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [pc4.araf.interface :as araf]))

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
    (let [nhs-number "1234567890"
          araf-type :valproate
          request (araf/create-request *service* nhs-number araf-type)]
      (is (some? request))
      (is (= nhs-number (:request/nhs_number request)))
      (is (= araf-type (:request/araf_type request)))
      (is (string? (:request/token request)))
      (is (= 8 (count (:request/token request))))
      
      (testing "fetch-request retrieves the created request"
        (let [fetched (araf/fetch-request *service* (:request/token request) nhs-number)]
          (is (= request fetched))))))

  (testing "fetch-request with invalid token returns error"
    (let [result (araf/fetch-request *service* "INVALID1" "1234567890")]
      (is (= (:error result) araf/error-no-matching-request))))

  (testing "create-request generates unique tokens"
    (let [nhs-number "1111111111"
          requests (repeatedly 200 #(araf/create-request *service* nhs-number :valproate))
          tokens (map :request/token requests)]
      (is (= 200 (count tokens)))
      (is (= (count tokens) (count (distinct tokens))))
      (is (every? #(= 8 (count %)) tokens)))))

(deftest test-qr-code-generation
  (testing "generate-qr-code creates a QR code image file"
    (let [base-url "https://example.com/araf"
          access-key "TEST1234"
          nhs-number "9876543210"
          qr-bytes (araf/generate-qr-code base-url access-key nhs-number)
          temp-file (java.io.File/createTempFile "qr-test-" ".png")]
      (is (some? qr-bytes))
      (is (> (count qr-bytes) 0))
      (io/copy qr-bytes temp-file)
      (is (.exists temp-file))
      (is (> (.length temp-file) 0))
      (println "QR code written to:" (.getAbsolutePath temp-file)))))