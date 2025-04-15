(ns pc4.chart.medication-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [pc4.chart.medication :as medication]
            [pc4.chart.gen :as chart-gen]
            [pc4.chart.common :refer [chart-test-dir-fixture save-test-chart]])
  (:import (org.jfree.chart JFreeChart)
           (org.jfree.chart.plot CombinedDomainXYPlot XYPlot)
           (java.time LocalDate)))

;; Register the fixture
(use-fixtures :once (chart-test-dir-fixture "medication-test"))

(stest/instrument)

(deftest test-normalize-date
  (testing "Normalizes various date formats"
    (let [today (LocalDate/now)
          date-string (.toString today)]
      (is (= today (medication/normalize-date today)) "Should return LocalDate as is")
      (is (= today (medication/normalize-date date-string)) "Should parse date string correctly")))
  
  (testing "Throws exception for invalid formats"
    (is (thrown? Exception (medication/normalize-date 12345)) "Should reject numeric values")
    (is (thrown? Exception (medication/normalize-date "not-a-date")) "Should reject invalid strings")))

(deftest test-single-medication-chart
  (testing "Create chart for a single medication"
    (let [meds (gen/generate (gen/vector chart-gen/gen-medication 1 3))
          med-name (-> meds first :name)
          chart (medication/create-medication-chart meds)]
      (is (instance? JFreeChart chart) "Should create a JFreeChart instance")
      (is (instance? XYPlot (.getPlot chart)) "Should have an XYPlot")
      (is (= med-name (.getText (.getTitle chart))) "Chart title should match medication name")
      (save-test-chart chart "single-medication" {:medications meds}))))

(deftest test-multiple-medications-chart
  (testing "Create chart with multiple medications"
    (let [med1 (assoc (gen/generate chart-gen/gen-medication) :name "Medication A")
          med2 (assoc (gen/generate chart-gen/gen-medication) :name "Medication B")
          med3 (assoc (gen/generate chart-gen/gen-medication) :name "Medication C")
          meds [med1 med2 med3]
          chart (medication/create-medications-chart meds)
          plot (.getPlot chart)]
      (is (instance? JFreeChart chart) "Should create a JFreeChart instance")
      (is (instance? CombinedDomainXYPlot plot) "Should have a CombinedDomainXYPlot")
      (is (= 3 (count (medication/get-medication-plots chart))) "Should have 3 subplots")
      (save-test-chart chart "multiple-medications" {:medications meds}))))

(deftest test-edge-cases
  (testing "Empty medications collection"
    (is (thrown? Exception (medication/create-medication-chart [])) 
        "Should throw exception for empty collection"))
  
  (testing "Very long medication name"
    (let [long-name (apply str (repeat 50 "x"))
          med {:id 1 :name long-name :start-date (LocalDate/now)}
          chart (medication/create-medication-chart [med])]
      (is (instance? JFreeChart chart) "Should handle very long medication names")
      (save-test-chart chart "long-medication-name" {:medications [med]})))
  
  (testing "Same medication multiple times"
    (let [start (LocalDate/now)
          meds [{:id 1 :name "Test Med" :start-date start :end-date (.plusMonths start 3)}
                {:id 2 :name "Test Med" :start-date (.plusMonths start 4) :end-date (.plusMonths start 6)}]
          chart (medication/create-medications-chart meds)]
      (is (instance? JFreeChart chart) "Should handle multiple instances of same medication")
      (is (= 1 (count (medication/get-medication-plots chart))) "Should group medications by name")
      (save-test-chart chart "repeated-medication" {:medications meds}))))

(deftest test-plot-extraction
  (testing "Extract plots from medication chart"
    (let [meds (gen/generate (gen/vector chart-gen/gen-medication 3 5))
          chart (medication/create-medications-chart meds)
          plots (medication/get-medication-plots chart)]
      (is (seq plots) "Should extract plots from chart")
      (is (every? #(instance? XYPlot %) plots) "All extracted items should be XYPlots")
      (save-test-chart chart "extracted-plots" {:medications meds
                                               :plot-count (count plots)}))))