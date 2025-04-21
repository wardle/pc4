(ns pc4.chart.simple-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [pc4.chart.gen :as chart-gen]
            [pc4.chart.simple :as simple]
            [pc4.chart.common :refer [chart-test-dir-fixture save-test-chart]])
  (:import (java.time LocalDate)
           (org.jfree.chart JFreeChart)))

(use-fixtures :once (chart-test-dir-fixture "simple-chart-test"))

(stest/instrument)

;; Generate dates by specifying days ago from today (0-3650 days, or 0-10 years)
(def gen-recent-date
  (gen/fmap #(.minusDays (LocalDate/now) %)
            (gen/choose 0 3650)))

(def gen-weight
  (gen/double* {:min 30.0 :max 150.0 :NaN? false :infinite? false}))

(def gen-score
  (gen/choose 0 100))

(def gen-systolic
  (gen/choose 80 200))

(def gen-diastolic
  (gen/choose 40 130))

;; Compound generators
(def gen-weight-record
  (gen/hash-map
    :id chart-gen/gen-positive-id
    :date gen-recent-date
    :weight gen-weight))

(def gen-score-record
  (gen/hash-map
    :id chart-gen/gen-positive-id
    :date gen-recent-date
    :score gen-score))

(def gen-bp-record
  (gen/hash-map
    :id chart-gen/gen-positive-id
    :date gen-recent-date
    :systolic gen-systolic
    :diastolic gen-diastolic))

;; Test for chart-creator function validation
(deftest test-chart-creator-validation
  (testing "Valid configurations are accepted"
    (let [valid-config {:title "Test Chart" 
                        :x-label "Date" 
                        :y-label "Value" 
                        :series-name "Series 1"}]
      (is (fn? (simple/time-series-chart valid-config)) 
          "Should return a function with valid config")))
  
  (testing "Invalid configurations are rejected"
    (let [invalid-configs [{:title "Test Chart"} ; Missing required fields
                          {:title 123 :x-label "X" :y-label "Y" :series-name "S"} ; Wrong type
                          {:title "" :x-label "" :y-label "" :series-name ""}]] ; Empty strings (valid but poor practice)
      (doseq [config invalid-configs]
        (is (thrown? Exception (simple/time-series-chart config))
            (str "Should throw exception for invalid config: " config))))))

;; Test chart creation with weight data
(deftest test-chart-creation-with-weight
  (testing "Creating weight charts with generated data"
    (let [weight-config {:title "Weight Over Time" 
                         :x-label "Date" 
                         :y-label "Weight (kg)" 
                         :series-name "Weight"
                         :y-lower-bound 30.0
                         :y-upper-bound 150.0
                         :date-fn :date
                         :value-fn :weight}]
      
      (doseq [weight-records (gen/sample (gen/vector gen-weight-record 5 15) 3)]
        (let [chart-fn (simple/time-series-chart weight-config)
              chart (chart-fn weight-records)]
          (is (instance? JFreeChart chart)
              "Should create a weight chart with generated data")
          (save-test-chart chart "weight-chart" 
                         {:config weight-config :data weight-records}))))))

;; Test chart creation with score data
(deftest test-chart-creation-with-score
  (testing "Creating score charts with generated data"
    (let [score-config {:title "Assessment Score Over Time" 
                       :x-label "Date" 
                       :y-label "Score" 
                       :series-name "Assessment"
                       :y-lower-bound 0
                       :y-upper-bound 100
                       :tick-unit 10
                       :date-fn :date
                       :value-fn :score}]
      
      (doseq [score-records (gen/sample (gen/vector gen-score-record 5 15) 3)]
        (let [chart-fn (simple/time-series-chart score-config)
              chart (chart-fn score-records)]
          (is (instance? JFreeChart chart)
              "Should create a score chart with generated data")
          (save-test-chart chart "score-chart" 
                         {:config score-config :data score-records}))))))

;; Test with custom accessor functions
(deftest test-custom-accessors
  (testing "Using custom accessor functions for blood pressure data"
    (let [bp-config {:title "Blood Pressure Derived Value"
                    :x-label "Date"
                    :y-label "Pulse Pressure"
                    :series-name "Pulse Pressure"
                    :y-lower-bound 20
                    :date-fn :date
                    ;; Calculate pulse pressure (systolic - diastolic)
                    :value-fn (fn [item] (- (:systolic item) (:diastolic item)))}
          bp-data (gen/generate (gen/vector gen-bp-record 10))]
      
      (let [chart-fn (simple/time-series-chart bp-config)
            chart (chart-fn bp-data)]
        (is (instance? JFreeChart chart)
            "Should create chart with custom accessor function")
        (save-test-chart chart "blood-pressure-chart" 
                       {:config bp-config :data bp-data})))))

;; Test with data transformation
(deftest test-data-transformation
  (testing "Creating a chart with transformed data"
    (let [weight-records (gen/generate (gen/vector gen-weight-record 10))
          
          ;; Configuration for BMI calculation
          ;; Assumes each record has height in meters
          bmi-config {:title "BMI Over Time"
                     :x-label "Date"
                     :y-label "BMI"
                     :series-name "BMI"
                     :y-lower-bound 15
                     :y-upper-bound 40
                     :date-fn :date
                     ;; For demo, we're using a fixed height of 1.75m
                     :value-fn (fn [item] (/ (:weight item) (* 1.75 1.75)))}
          
          chart-fn (simple/time-series-chart bmi-config)
          chart (chart-fn weight-records)]
      
      (is (instance? JFreeChart chart)
          "Should create chart with data transformation")
      (save-test-chart chart "bmi-chart" 
                     {:config bmi-config :data weight-records}))))

;; Test with edge cases
(deftest test-edge-cases
  (testing "Empty data returns nil"
    (let [chart-fn (simple/time-series-chart {:title "Empty Chart" 
                                          :x-label "Date" 
                                          :y-label "Value" 
                                          :series-name "No Data"})]
      (is (nil? (chart-fn []))
          "Should return nil when no data is provided")))
  
  (testing "Mixed valid and invalid data points"
    (let [data [{:id 1 :date (LocalDate/now) :weight 75.5}
                {:id 2 :date nil :weight 80.0} ; Missing date
                {:id 3 :date (LocalDate/now) :weight nil} ; Missing value
                {:id 4 :date (LocalDate/now) :weight 67.2}]
          config {:title "Mixed Data Chart" 
                 :x-label "Date" 
                 :y-label "Weight" 
                 :series-name "Mixed Data"}
          chart-fn (simple/time-series-chart config)
          chart (chart-fn data)]
      
      (is (instance? JFreeChart chart)
          "Should create chart, skipping invalid data points")
      (save-test-chart chart "mixed-data-chart" 
                     {:config config :data data}))))

;; Test with fixed sample data (not generated)
(deftest test-fixed-sample-data
  (testing "Creating chart with fixed sample data"
    (let [weight-data [{:id 1 :date (LocalDate/now) :weight 75.5}
                       {:id 2 :date (.minusMonths (LocalDate/now) 1) :weight 76.2}
                       {:id 3 :date (.minusMonths (LocalDate/now) 2) :weight 77.8}
                       {:id 4 :date (.minusMonths (LocalDate/now) 3) :weight 79.1}
                       {:id 5 :date (.minusMonths (LocalDate/now) 4) :weight 78.3}]
          weight-config {:title "Weight Over Time" 
                        :x-label "Date" 
                        :y-label "Weight (kg)" 
                        :series-name "Weight"
                        :y-lower-bound 70.0
                        :y-upper-bound 80.0
                        :tick-unit 1.0
                        :date-fn :date
                        :value-fn :weight}
          chart-fn (simple/time-series-chart weight-config)
          chart (chart-fn weight-data)]
      
      (is (instance? JFreeChart chart)
          "Should create chart with fixed sample data")
      (save-test-chart chart "sample-weight-chart" 
                     {:config weight-config :data weight-data}))))