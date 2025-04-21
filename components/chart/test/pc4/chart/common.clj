(ns pc4.chart.common
  "Common utilities for chart tests, including functions to save charts 
   to PNG files for visual inspection."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (org.jfree.chart JFreeChart ChartUtils)))

;; Create a shared temporary directory for all chart tests
(def shared-chart-test-dir 
  (delay 
    (let [temp-dir (Files/createTempDirectory 
                    "chart-tests-" 
                    (into-array FileAttribute []))]
      (println "All chart test files will be saved to:" (.toString temp-dir))
      (.toFile temp-dir))))

;; Dynamic var for backward compatibility
(def ^:dynamic *chart-test-dir* nil)

;; Counter for unique filenames 
(def test-chart-counter (atom 0))

(defn save-test-chart 
  "Save a chart to a PNG file in the test directory along with its metadata.
   
   Parameters:
   - chart: The JFreeChart instance to save
   - prefix: Name prefix for the file
   - data: The data used to create the chart
   
   Returns the full path to the saved PNG file."
  [^JFreeChart chart prefix data]
  (when (and chart *chart-test-dir*)
    (let [counter (swap! test-chart-counter inc)
          basename (format "%03d-%s" counter prefix)
          png-file (io/file *chart-test-dir* (str basename ".png"))
          edn-file (io/file *chart-test-dir* (str basename ".edn"))]
      
      ;; Save chart as PNG
      (ChartUtils/saveChartAsPNG png-file chart 800 600)
      
      ;; Save metadata as EDN
      (with-open [w (io/writer edn-file)]
        (binding [*print-length* 100
                  *print-level* 10]
          (pprint/pprint {:timestamp (java.util.Date.)
                          :filename (.getName png-file)
                          :data data} 
                         w)))
      
      (.getAbsolutePath png-file))))

(defn chart-test-dir-fixture 
  "Test fixture that sets up the shared test directory for chart output.
   
   The prefix parameter is kept for backward compatibility but is ignored
   as all charts are saved to the same shared directory.
   
   Usage: 
   (use-fixtures :once (chart-test-dir-fixture))"
  ([] (chart-test-dir-fixture "chart-test"))
  ([_prefix]
   (fn [f]
     (binding [*chart-test-dir* @shared-chart-test-dir]
       (try
         (f)
         (finally
           ;; We don't delete the directory to allow inspection of the charts
           nil))))))