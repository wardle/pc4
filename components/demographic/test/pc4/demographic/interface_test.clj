(ns pc4.demographic.interface-test
  (:require [clojure.test :refer :all]
            [pc4.demographic.interface :as demographic]
            [pc4.demographic.protos :as p]
            [pc4.demographic.synthetic :as synth])
  (:import (java.time LocalDate)))

;; Test fixtures
(def ^:dynamic *provider* nil)

(use-fixtures :once
  (fn [f]
    (binding [*provider* (synth/make-synthetic-provider :v 1)]
      (f))))

;; Convenience accessors for test patients
(defn patient-a [] (first (p/fetch *provider* "https://fhir.nhs.uk/Id/nhs-number" "1111111111")))
(defn patient-b [] (first (p/fetch *provider* "https://fhir.nhs.uk/Id/nhs-number" "2222222222")))
(defn patient-c [] (first (p/fetch *provider* "https://fhir.nhs.uk/Id/nhs-number" "3333333333")))

(deftest test-patients-by-identifier-returns-nil-when-no-providers
  (testing "Returns nil when no providers configured"
    (let [svc {:providers []}]
      (is (nil? (demographic/patients-by-identifier svc "system" "value"))))))

(deftest test-patients-by-identifier-returns-nil-when-no-matches
  (testing "Returns nil when no provider returns results"
    (let [svc {:providers [{:id :p1 :svc *provider*}
                           {:id :p2 :svc *provider*}]}]
      (is (nil? (demographic/patients-by-identifier svc "https://fhir.nhs.uk/Id/nhs-number" "0000000000"))))))

(deftest test-patients-by-identifier-tries-providers-in-order
  (testing "Tries providers in order, skipping those that return nil"
    (let [empty-provider (reify p/PatientsByIdentifier (fetch [_ _ _] nil))
          svc {:providers [{:id :p1 :svc empty-provider}
                           {:id :p2 :svc *provider*}
                           {:id :p3 :svc *provider*}]}]
      (is (= [(patient-a)]
             (demographic/patients-by-identifier svc "https://fhir.nhs.uk/Id/nhs-number" "1111111111"))))))

(deftest test-patients-by-identifier-stops-at-first-match
  (testing "Stops at first provider that returns results"
    (let [provider-1 (reify p/PatientsByIdentifier
                       (fetch [_ _ _]
                         [(patient-a)]))
          provider-2 (reify p/PatientsByIdentifier
                       (fetch [_ _ _]
                         (throw (ex-info "should only call first provider" {}))))
          svc {:providers [{:id :p1 :svc provider-1}
                           {:id :p2 :svc provider-2}]}]
      (demographic/patients-by-identifier svc "system" "value"))))

(deftest test-patients-by-identifier-filters-by-provider-id
  (testing "Only queries specified provider when :provider-id option given"
    (let [provider-a (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-a)]))
          provider-b (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-b)]))
          svc {:providers [{:id :provider-a :svc provider-a}
                           {:id :provider-b :svc provider-b}]}]
      (is (= [(patient-a)]
             (demographic/patients-by-identifier svc "system" "value" {:provider-id :provider-a})))
      (is (= [(patient-b)]
             (demographic/patients-by-identifier svc "system" "value" {:provider-id :provider-b}))))))

(deftest test-patients-by-identifier-filters-by-system
  (testing "Only queries providers that support the specified system"
    (let [svc {:providers [{:id      :p1
                            :systems #{"https://fhir.nhs.uk/Id/nhs-number"}
                            :svc     (reify p/PatientsByIdentifier
                                       (fetch [_ _ _] (throw (ex-info "should not call this provider" {}))))}
                           {:id      :p2
                            :systems #{"https://fhir.cavuhb.nhs.wales/Id/pas-identifier"}
                            :svc     *provider*}]}]
      (is (= [(patient-a)]
             (demographic/patients-by-identifier
               svc
               "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
               "A999998"))))))

(deftest test-patients-by-identifier-provider-without-systems-matches-all
  (testing "Provider with no declared systems is tried for any system"
    (let [provider-nhs (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-a)]))
          provider-all (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-b)]))
          svc {:providers [{:id :p1
                            :systems #{"https://fhir.nhs.uk/Id/nhs-number"}
                            :svc provider-nhs}
                           {:id :p2
                            ;; No :systems key - matches all
                            :svc provider-all}]}]
      ;; Provider-1 matches by system
      (is (= [(patient-a)]
             (demographic/patients-by-identifier
               svc
               "https://fhir.nhs.uk/Id/nhs-number"
               "value")))
      ;; Provider-1 doesn't match, falls through to provider-2
      (is (= [(patient-b)]
             (demographic/patients-by-identifier
               svc
               "https://fhir.other.system/Id/other"
               "value"))))))

(deftest test-patients-by-identifier-ask-all-systems-bypasses-filtering
  (testing ":ask-all-systems option bypasses system filtering"
    (let [provider (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-a)]))
          svc {:providers [{:id :p1
                            :systems #{"https://fhir.nhs.uk/Id/nhs-number"}
                            :svc provider}]}]
      ;; Without :ask-all-systems, system doesn't match
      (is (nil? (demographic/patients-by-identifier
                  svc
                  "https://fhir.other.system/Id/other"
                  "value")))
      ;; With :ask-all-systems, provider is queried anyway
      (is (= [(patient-a)]
             (demographic/patients-by-identifier
               svc
               "https://fhir.other.system/Id/other"
               "value"
               {:ask-all-systems true}))))))

(deftest test-patients-by-identifier-only-single-match-returns-unwrapped
  (testing ":only-single-match returns single patient (not vector)"
    (let [svc {:providers [{:id :p1 :svc *provider*}]}]
      (is (= (patient-a)
             (demographic/patients-by-identifier
               svc
               "https://fhir.nhs.uk/Id/nhs-number"
               "1111111111"
               {:only-single-match true}))))))

(deftest test-patients-by-identifier-only-single-match-throws-on-multiple
  (testing ":only-single-match throws exception when multiple patients returned"
    (let [provider (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-a) (patient-b)]))
          svc {:providers [{:id :p1 :svc provider}]}]
      (is (thrown? Exception
            (demographic/patients-by-identifier
              svc
              "system"
              "value"
              {:only-single-match true}))))))

(deftest test-patients-by-identifier-combined-filters
  (testing "Can combine :provider-id and system filtering"
    (let [provider-1 (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-a)]))
          provider-2 (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-b)]))
          provider-3 (reify p/PatientsByIdentifier (fetch [_ _ _] [(patient-c)]))
          svc {:providers [{:id :p1
                            :systems #{"sys-a"}
                            :svc provider-1}
                           {:id :p2
                            :systems #{"sys-b"}
                            :svc provider-2}
                           {:id :p3
                            :systems #{"sys-b"}
                            :svc provider-3}]}]
      (is (= [(patient-b)]
             (demographic/patients-by-identifier
               svc
               "sys-b"
               "value"
               {:provider-id :p2}))
          "Only provider 2 should match both provider-id and system")
      (is (nil? (demographic/patients-by-identifier
                  svc
                  "sys-b"
                  "value"
                  {:provider-id :p1}))
          "Provider 1 should not match as does not support"))))

(deftest test-patients-by-identifier-returns-empty-sequence-as-nil
  (testing "Empty sequence from provider is treated as no match"
    (let [provider-1 (reify p/PatientsByIdentifier
                       (fetch [_ _ _] []))  ; Empty vector
          provider-2 (reify p/PatientsByIdentifier
                       (fetch [_ _ _] [(patient-a)]))
          svc {:providers [{:id :p1 :svc provider-1}
                           {:id :p2 :svc provider-2}]}]
      (is (= [(patient-a)]
             (demographic/patients-by-identifier svc "system" "value"))
          "A provider returning an empty sequence should force fallthrough to next provider"))))

(deftest test-synthetic-provider
  (let [provider *provider*]
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
        (is (nil? result))))

    (testing "Multiple patients with same NHS number are all returned"
      (let [result (p/fetch provider "https://fhir.nhs.uk/Id/nhs-number" "9948749804")]
        (is (seq result))
        (is (= 2 (count result)) "Should return both patients with duplicate NHS number")
        (is (= #{"Roberts" "Edwards"}
               (set (map #(get-in % [:org.hl7.fhir.Patient/name 0 :org.hl7.fhir.HumanName/family]) result)))
            "Should include both Roberts and Edwards")))))

(deftest test-normalize-identifier
  (testing "Default normalization strips whitespace and uppercases"
    (is (= "A123456" (demographic/normalize "https://fhir.unknown.system/Id/foo" " a123456 ")))
    (is (= "TEST123" (demographic/normalize "https://fhir.unknown.system/Id/foo" "test 123"))))

  (testing "NHS number normalization delegates to nhs-number component"
    (is (= "1111111111" (demographic/normalize "https://fhir.nhs.uk/Id/nhs-number" "111 111 1111")))
    (is (= "1111111111" (demographic/normalize "https://fhir.nhs.uk/Id/nhs-number" "111-111-1111")))))

(deftest test-validate-identifier
  (testing "Default validation accepts any value"
    (is (true? (demographic/validate "https://fhir.unknown.system/Id/foo" "anything")))
    (is (true? (demographic/validate "https://fhir.nhs.wales/Id/empi-number" "12345"))))

  (testing "NHS number validation"
    (is (true? (demographic/validate "https://fhir.nhs.uk/Id/nhs-number" "1111111111")))
    (is (false? (demographic/validate "https://fhir.nhs.uk/Id/nhs-number" "1234567890")))
    (is (false? (demographic/validate "https://fhir.nhs.uk/Id/nhs-number" "invalid"))))

  (testing "CAV CRN validation"
    (is (true? (demographic/validate "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "A123456")))
    (is (true? (demographic/validate "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "Z999999")))
    (is (true? (demographic/validate "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "A123456X")))
    (is (false? (demographic/validate "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "123456")))
    (is (false? (demographic/validate "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "A12345")))
    (is (false? (demographic/validate "https://fhir.cavuhb.nhs.wales/Id/pas-identifier" "AA123456")))))

(deftest test-format-identifier
  (testing "Default formatting returns value unchanged"
    (is (= "A123456" (demographic/format "https://fhir.unknown.system/Id/foo" "A123456")))
    (is (= "12345" (demographic/format "https://fhir.nhs.wales/Id/empi-number" "12345"))))

  (testing "NHS number formatting adds spaces"
    (is (= "111 111 1111" (demographic/format "https://fhir.nhs.uk/Id/nhs-number" "1111111111")))))

