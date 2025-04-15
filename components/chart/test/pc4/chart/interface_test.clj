(ns pc4.chart.interface-test
  (:require [clojure.test :as test :refer [deftest testing is use-fixtures]]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [pc4.chart.interface :as chart]
            [pc4.chart.gen :as chart-gen]
            [pc4.chart.common :refer [chart-test-dir-fixture save-test-chart]])
  (:import (org.jfree.chart JFreeChart)
           (java.awt.image BufferedImage)
           (java.io File)))

;; Register the fixture
(use-fixtures :once (chart-test-dir-fixture "chart-interface-test"))

(stest/instrument)

(deftest create-charts-test
  (testing "Create EDSS timeline chart"
    (let [sample-data (chart-gen/generate-sample-data)
          data {:edss-scores (:edss-scores sample-data)
                :ms-events (:ms-events sample-data)
                :logmar-scores (:logmar-scores sample-data)
                :medications (:medications sample-data)}
          chart (chart/create-edss-timeline-chart data)]
      (is (instance? JFreeChart chart) "Should create a valid JFreeChart")
      (save-test-chart chart "interface-edss-timeline" data)))
  
  (testing "Create medication chart"
    (let [medications (gen/generate (gen/vector chart-gen/gen-medication 1 3))
          chart (chart/create-medication-chart medications)]
      (is (instance? JFreeChart chart) "Should create a valid JFreeChart")
      (save-test-chart chart "interface-medication" {:medications medications})))
  
  (testing "Create medications chart"
    (let [medications (gen/generate (gen/vector chart-gen/gen-medication 3 5))
          chart (chart/create-medications-chart medications)]
      (is (instance? JFreeChart chart) "Should create a valid JFreeChart")
      (save-test-chart chart "interface-medications" {:medications medications})))
      
  (testing "Empty data handling"
    (let [data {}
          chart (chart/create-edss-timeline-chart data)]
      (is (nil? chart) "Should return nil with no data"))))

(deftest chart-conversion-test
  (testing "Handles nil chart"
    (let [image (chart/chart-to-image nil 400 300)]
      (is (nil? image) "Should return nil for nil chart"))
    
    (let [path (chart/save-chart nil "test.png" 400 300)]
      (is (nil? path) "Should return nil when saving nil chart"))))

;; Skip file saving test in automated tests
(comment
  (deftest save-chart-test
    (testing "Save chart to file"
      (let [minimal-data (chart-gen/generate-minimal-data)
            chart (chart/create-edss-timeline-chart 
                    {:edss-scores (:edss-scores minimal-data)
                     :ms-events (:ms-events minimal-data)})
            temp-file (File/createTempFile "chart-test" ".png")
            _ (.deleteOnExit temp-file)
            saved-path (chart/save-chart chart (.getAbsolutePath temp-file) 400 300)]
        (is (.exists (File. saved-path)) "File should exist")
        (is (> (.length (File. saved-path)) 0) "File should not be empty")))))