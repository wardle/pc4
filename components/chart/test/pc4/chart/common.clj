(ns pc4.chart.common
  "Common utilities for chart tests, including functions to save charts 
   to PNG files for visual inspection."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (org.jfree.chart JFreeChart ChartUtils)))

;; Create a dynamic var to hold the temporary directory
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
  "Test fixture that creates a temporary directory for chart output.
   
   Parameters:
   - prefix: Optional prefix for the temp directory name (default is 'chart-test')
   
   Usage: 
   (use-fixtures :once (chart-test-dir-fixture \"my-test\"))"
  ([] (chart-test-dir-fixture "chart-test"))
  ([prefix]
   (fn [f]
     (let [temp-dir (Files/createTempDirectory 
                      (str prefix "-") 
                      (into-array FileAttribute []))]
       (println "Chart test files will be saved to:" (.toString temp-dir))
       (binding [*chart-test-dir* (.toFile temp-dir)]
         (reset! test-chart-counter 0)
         (try
           (f)
           (finally
             ;; We don't delete the directory to allow inspection of the charts
             nil)))))))