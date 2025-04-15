(ns pc4.chart.edss-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [pc4.chart.edss :as edss]
            [pc4.chart.gen :as chart-gen]
            [pc4.chart.common :refer [chart-test-dir-fixture save-test-chart]])
  (:import (java.time LocalDate)
           (org.jfree.chart JFreeChart)))

(use-fixtures :once (chart-test-dir-fixture))

(stest/instrument)

(deftest test-normalize-date
  (testing "Normalizes various date formats"
    (let [today (LocalDate/now)
          date-string (.toString today)]
      (is (= today (edss/normalize-date today)) "Should return LocalDate as is")
      (is (= today (edss/normalize-date date-string)) "Should parse date string correctly")))
  
  (testing "Throws exception for invalid formats"
    (is (thrown? Exception (edss/normalize-date 12345)) "Should reject numeric values")
    (is (thrown? Exception (edss/normalize-date "not-a-date")) "Should reject invalid strings")))

(deftest test-chart-creation
  (testing "Basic chart creation with multiple valid data samples"
    (doseq [edss-scores (gen/sample (gen/vector chart-gen/gen-edss-score 1 10) 5)
            ms-events (gen/sample (gen/vector chart-gen/gen-ms-event 1 5) 3)]
      (let [data {:edss-scores edss-scores, :ms-events ms-events}
            chart (edss/create-edss-timeline-chart data)]
        (is (instance? JFreeChart chart) "Should create a JFreeChart instance with various inputs")
        (save-test-chart chart "basic-chart" data))))
  
  (testing "Chart creation with all optional data samples"
    (doseq [edss-scores (gen/sample (gen/vector chart-gen/gen-edss-score 1 5) 3)
            ms-events (gen/sample (gen/vector chart-gen/gen-ms-event 1 3) 2)
            logmar-scores (gen/sample (gen/vector chart-gen/gen-logmar-score 1 3) 2)
            medications (gen/sample (gen/vector chart-gen/gen-medication 1 3) 2)]
      (let [ms-onset-date (.minusYears (LocalDate/now) 5)
            data {:edss-scores edss-scores 
                 :ms-events ms-events
                 :logmar-scores logmar-scores
                 :medications medications
                 :ms-onset-date ms-onset-date
                 :sex :FEMALE
                 :width 1000
                 :height 800}
            chart (edss/create-edss-timeline-chart data)]
        (is (instance? JFreeChart chart) "Should create a JFreeChart instance with combinations of inputs")
        (save-test-chart chart "full-chart" data)))))

;; Individual function testing instead of generative testing
(deftest test-specific-functions
  (testing "EDSS chart creation function"
    (let [edss-scores (:edss-scores (chart-gen/generate-minimal-data))
          chart (edss/create-edss-chart edss-scores)]
      (is (instance? JFreeChart chart) "Should create EDSS chart with valid data")
      (save-test-chart chart "edss-chart" {:edss-scores edss-scores})))
  
  (testing "MS event chart creation function"
    (let [ms-events (:ms-events (chart-gen/generate-minimal-data))
          chart (edss/create-ms-event-chart ms-events)]
      (is (instance? JFreeChart chart) "Should create MS event chart with valid data")
      (save-test-chart chart "ms-event-chart" {:ms-events ms-events})))
  
  (testing "Combined chart creation function"
    (let [minimal-data (chart-gen/generate-minimal-data)
          chart (edss/create-combined-chart minimal-data)]
      (is (instance? JFreeChart chart) "Should create combined chart with valid data")
      (save-test-chart chart "combined-chart" minimal-data)))
  
  (testing "EDSS timeline chart function"
    (let [minimal-data (chart-gen/generate-minimal-data)
          chart (edss/create-edss-timeline-chart minimal-data)]
      (is (instance? JFreeChart chart) "Should create timeline chart with valid data")
      (save-test-chart chart "edss-timeline-chart" minimal-data))))

;; MSSS functionality testing
(deftest test-msss-functionality
  (testing "Chart creation with MSSS data"
    (let [edss-scores [{:id 1 :date (LocalDate/now) :edss 3.5}]
          ms-events [{:id 1 :date (LocalDate/now) :type :relapse :abbreviation "R"}]
          ms-onset-date (.minusYears (LocalDate/now) 5)
          msss-data {0  {0.0 0.7, 1.0 2.3, 2.0 4.3, 3.0 5.9, 4.0 7.4, 6.0 9.3}
                     5  {0.0 0.1, 1.0 0.5, 2.0 1.5, 3.0 3.0, 4.0 4.6, 6.0 7.6}
                     10 {0.0 0.1, 1.0 0.3, 2.0 0.8, 3.0 1.7, 4.0 3.0, 6.0 6.6}}
          data {:edss-scores edss-scores 
                :ms-events ms-events
                :ms-onset-date ms-onset-date
                :msss-data msss-data}
          chart (edss/create-edss-timeline-chart data)]
      (is (instance? JFreeChart chart)
          "Should create chart with MSSS data")
      (save-test-chart chart "msss-chart" data)))
  
  (testing "Percentile calculation from MSSS data"
    (let [msss-data {5 {0.0 0.1, 1.0 0.5, 2.0 1.5, 3.0 3.0, 4.0 4.6, 6.0 7.6}}]
      (is (= 2.0 (#'edss/percentile-edss-for-duration msss-data 5 1.5))
          "Should find the right EDSS value for a percentile")
      (is (= 3.0 (#'edss/percentile-edss-for-duration msss-data 5 3.5))
          "Should find closest EDSS value when exact match not found")
      (is (= 6.0 (#'edss/percentile-edss-for-duration msss-data 5 9.0))
          "Should return highest EDSS value when percentile is higher than explicitly defined values")
      (is (nil? (#'edss/percentile-edss-for-duration msss-data 10 1.0))
          "Should return nil when duration not found"))))

;; Edge cases testing
(deftest test-edge-cases
  (testing "Empty collections"
    (let [empty-data {:edss-scores [], :ms-events []}
          chart (edss/create-edss-timeline-chart empty-data)]
      (is (nil? chart) "Should return nil with empty collections")))
  
  (testing "Minimal valid data"
    (let [minimal-data (chart-gen/generate-minimal-data)
          data {:edss-scores (:edss-scores minimal-data) 
                :ms-events (:ms-events minimal-data)}
          chart (edss/create-edss-timeline-chart data)]
      (is (instance? JFreeChart chart) "Should create chart with minimal valid data")
      (save-test-chart chart "minimal-chart" data)))
  
  (testing "Nil optional values"
    (let [minimal-data (chart-gen/generate-minimal-data)
          data (dissoc minimal-data :logmar-scores :medications :ms-onset-date)
          chart (edss/create-edss-timeline-chart data)]
      (is (instance? JFreeChart chart) "Should handle missing optional values")
      (save-test-chart chart "optional-values-chart" data)))
          
  (testing "No valid data returns nil"
    (let [empty-data {}
          chart (edss/create-edss-timeline-chart empty-data)]
      (is (nil? chart) "Should return nil when no data is provided"))))

(comment
  (def data (chart-gen/generate-sample-data)))