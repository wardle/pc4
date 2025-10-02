(ns pc4.demographic.interface-test
  (:require [clojure.test :refer :all]
            [pc4.demographic.interface :as demographic]
            [pc4.demographic.protos :as p]
            [pc4.demographic.synthetic :as synth])
  (:import (java.time LocalDate)))

;; Test data - FHIR Patient structures
(def patient-a
  {:org.hl7.fhir.Patient/identifier
   [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
     :org.hl7.fhir.Identifier/value "1111111111"}]
   :org.hl7.fhir.Patient/name
   [{:org.hl7.fhir.HumanName/family "Smith"
     :org.hl7.fhir.HumanName/given ["John"]}]})

(def patient-b
  {:org.hl7.fhir.Patient/identifier
   [{:org.hl7.fhir.Identifier/system "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
     :org.hl7.fhir.Identifier/value "A999998"}]
   :org.hl7.fhir.Patient/name
   [{:org.hl7.fhir.HumanName/family "Jones"
     :org.hl7.fhir.HumanName/given ["David"]}]})

(def patient-c
  {:org.hl7.fhir.Patient/identifier
   [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
     :org.hl7.fhir.Identifier/value "2222222222"}]
   :org.hl7.fhir.Patient/name
   [{:org.hl7.fhir.HumanName/family "Williams"
     :org.hl7.fhir.HumanName/given ["Sarah"]}]})

;; Helper to create a mock provider with fixed data
(defn mock-provider
  "Create a mock provider that returns patients from a lookup map.

  Parameters:
  - patient-map: map of value -> sequence of patients
                 e.g. {\"1111111111\" [patient-a], \"A999998\" [patient-b]}

  Returns: implementation of PatientsByIdentifier protocol"
  [patient-map]
  (reify p/PatientsByIdentifier
    (fetch [_ _system value]
      (get patient-map value))))

(deftest test-patients-by-identifier-returns-nil-when-no-providers
  (testing "Returns nil when no providers configured"
    (let [svc {:providers []}]
      (is (nil? (demographic/patients-by-identifier svc "system" "value"))))))

(deftest test-patients-by-identifier-returns-nil-when-no-matches
  (testing "Returns nil when no provider returns results"
    (let [provider-1 (mock-provider {})
          provider-2 (mock-provider {})
          svc {:providers [{:id :p1 :svc provider-1}
                           {:id :p2 :svc provider-2}]}]
      (is (nil? (demographic/patients-by-identifier svc "system" "unknown-value"))))))

(deftest test-patients-by-identifier-tries-providers-in-order
  (testing "Tries providers in order, skipping those that return nil"
    (let [provider-1 (mock-provider {})  ; Returns nil
          provider-2 (mock-provider {"1111111111" [patient-a]})  ; Returns patient-a
          provider-3 (mock-provider {"1111111111" [patient-b]})  ; Not reached
          svc {:providers [{:id :p1 :svc provider-1}
                           {:id :p2 :svc provider-2}
                           {:id :p3 :svc provider-3}]}]
      (is (= [patient-a]
             (demographic/patients-by-identifier svc "system" "1111111111"))))))

(deftest test-patients-by-identifier-stops-at-first-match
  (testing "Stops at first provider that returns results"
    (let [call-count (atom 0)
          provider-1 (reify p/PatientsByIdentifier
                       (fetch [_ _ _]
                         (swap! call-count inc)
                         [patient-a]))
          provider-2 (reify p/PatientsByIdentifier
                       (fetch [_ _ _]
                         (swap! call-count inc)
                         [patient-b]))
          svc {:providers [{:id :p1 :svc provider-1}
                           {:id :p2 :svc provider-2}]}]
      (demographic/patients-by-identifier svc "system" "value")
      (is (= 1 @call-count) "Should only call first provider"))))

(deftest test-patients-by-identifier-filters-by-provider-id
  (testing "Only queries specified provider when :provider-id option given"
    (let [provider-1 (mock-provider {"123" [patient-a]})
          provider-2 (mock-provider {"123" [patient-b]})
          svc {:providers [{:id :provider-a :svc provider-1}
                           {:id :provider-b :svc provider-2}]}]
      (is (= [patient-a]
             (demographic/patients-by-identifier svc "system" "123" {:provider-id :provider-a})))
      (is (= [patient-b]
             (demographic/patients-by-identifier svc "system" "123" {:provider-id :provider-b}))))))

(deftest test-patients-by-identifier-filters-by-system
  (testing "Only queries providers that support the specified system"
    (let [provider-1 (mock-provider {"123" [patient-a]})
          provider-2 (mock-provider {"123" [patient-b]})
          svc {:providers [{:id :p1
                            :systems #{"https://fhir.nhs.uk/Id/nhs-number"}
                            :svc provider-1}
                           {:id :p2
                            :systems #{"https://fhir.cavuhb.nhs.wales/Id/pas-identifier"}
                            :svc provider-2}]}]
      (is (= [patient-a]
             (demographic/patients-by-identifier
               svc
               "https://fhir.nhs.uk/Id/nhs-number"
               "123")))
      (is (= [patient-b]
             (demographic/patients-by-identifier
               svc
               "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
               "123"))))))

(deftest test-patients-by-identifier-provider-without-systems-matches-all
  (testing "Provider with no declared systems is tried for any system"
    (let [provider-1 (mock-provider {"123" [patient-a]})
          provider-2 (mock-provider {"123" [patient-b]})
          svc {:providers [{:id :p1
                            :systems #{"https://fhir.nhs.uk/Id/nhs-number"}
                            :svc provider-1}
                           {:id :p2
                            ;; No :systems key - matches all
                            :svc provider-2}]}]
      ;; Provider-1 matches by system
      (is (= [patient-a]
             (demographic/patients-by-identifier
               svc
               "https://fhir.nhs.uk/Id/nhs-number"
               "123")))
      ;; Provider-1 doesn't match, falls through to provider-2
      (is (= [patient-b]
             (demographic/patients-by-identifier
               svc
               "https://fhir.other.system/Id/other"
               "123"))))))

(deftest test-patients-by-identifier-ask-all-systems-bypasses-filtering
  (testing ":ask-all-systems option bypasses system filtering"
    (let [provider-1 (mock-provider {"123" [patient-a]})
          svc {:providers [{:id :p1
                            :systems #{"https://fhir.nhs.uk/Id/nhs-number"}
                            :svc provider-1}]}]
      ;; Without :ask-all-systems, system doesn't match
      (is (nil? (demographic/patients-by-identifier
                  svc
                  "https://fhir.other.system/Id/other"
                  "123")))
      ;; With :ask-all-systems, provider is queried anyway
      (is (= [patient-a]
             (demographic/patients-by-identifier
               svc
               "https://fhir.other.system/Id/other"
               "123"
               {:ask-all-systems true}))))))

(deftest test-patients-by-identifier-only-single-match-returns-unwrapped
  (testing ":only-single-match returns single patient (not vector)"
    (let [provider (mock-provider {"123" [patient-a]})
          svc {:providers [{:id :p1 :svc provider}]}]
      (is (= patient-a
             (demographic/patients-by-identifier
               svc
               "system"
               "123"
               {:only-single-match true}))))))

(deftest test-patients-by-identifier-only-single-match-throws-on-multiple
  (testing ":only-single-match throws exception when multiple patients returned"
    (let [provider (mock-provider {"123" [patient-a patient-b]})
          svc {:providers [{:id :p1 :svc provider}]}]
      (is (thrown? Exception
            (demographic/patients-by-identifier
              svc
              "system"
              "123"
              {:only-single-match true}))))))

(deftest test-patients-by-identifier-combined-filters
  (testing "Can combine :provider-id and system filtering"
    (let [provider-1 (mock-provider {"123" [patient-a]})
          provider-2 (mock-provider {"123" [patient-b]})
          provider-3 (mock-provider {"123" [patient-c]})
          svc {:providers [{:id :p1
                            :systems #{"sys-a"}
                            :svc provider-1}
                           {:id :p2
                            :systems #{"sys-b"}
                            :svc provider-2}
                           {:id :p3
                            :systems #{"sys-b"}
                            :svc provider-3}]}]
      ;; Only p2 matches both provider-id and system
      (is (= [patient-b]
             (demographic/patients-by-identifier
               svc
               "sys-b"
               "123"
               {:provider-id :p2})))
      ;; p1 matches provider-id but not system
      (is (nil? (demographic/patients-by-identifier
                  svc
                  "sys-b"
                  "123"
                  {:provider-id :p1}))))))

(deftest test-patients-by-identifier-returns-empty-sequence-as-nil
  (testing "Empty sequence from provider is treated as no match"
    (let [provider-1 (reify p/PatientsByIdentifier
                       (fetch [_ _ _] []))  ; Empty vector
          provider-2 (mock-provider {"123" [patient-a]})
          svc {:providers [{:id :p1 :svc provider-1}
                           {:id :p2 :svc provider-2}]}]
      ;; seq of [] is nil, so should continue to provider-2
      (is (= [patient-a]
             (demographic/patients-by-identifier svc "system" "123"))))))

(deftest test-synthetic-provider
  (let [provider (synth/make-synthetic-provider)]
    (testing "NHS number lookup returns patient with parsed date"
      (let [result (p/fetch provider "https://fhir.nhs.uk/Id/nhs-number" "1111111111")]
        (is (seq result))
        (is (= 1 (count result)))
        (is (= "Smith" (get-in (first result) [:org.hl7.fhir.Patient/name 0 :org.hl7.fhir.HumanName/family])))
        (is (instance? LocalDate (:org.hl7.fhir.Patient/birthDate (first result))))
        (is (= (LocalDate/parse "1975-03-15") (:org.hl7.fhir.Patient/birthDate (first result))))))

    (testing "CRN lookup returns correct patient"
      (let [result (p/fetch provider "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "A999998")]
        (is (seq result))
        (is (= 1 (count result)))
        (is (= "Smith" (get-in (first result) [:org.hl7.fhir.Patient/name 0 :org.hl7.fhir.HumanName/family])))))

    (testing "Non-existent identifier returns nil"
      (let [result (p/fetch provider "https://fhir.nhs.uk/Id/nhs-number" "0000000000")]
        (is (nil? result))))

    (testing "Unknown system returns nil"
      (let [result (p/fetch provider "https://fhir.unknown.system/Id/foo" "1111111111")]
        (is (nil? result))))))

