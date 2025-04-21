(ns pc4.chart.interface
  "Public interfaces for chart generation"
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [pc4.chart.edss :as edss]
    [pc4.chart.medication :as medication]
    [pc4.chart.simple :as simple])
  (:import (java.time LocalDate ZoneId)
           (org.jfree.chart ChartFactory JFreeChart ChartUtils)
           (org.jfree.chart.plot XYPlot)
           (java.io ByteArrayOutputStream File OutputStream)
           (javax.imageio ImageIO)
           (java.awt.image BufferedImage)))

;; EDSS timeline chart
(defn create-edss-timeline-chart
  "Create an EDSS timeline chart with comprehensive patient data.
  
  Parameters:
  - params: A map containing any of the following (all are optional):
      :edss-scores - collection of maps with :id, :date, :edss, and optional :in-relapse?
      :ms-events - collection of maps with :id, :date, :type, :abbreviation
      :logmar-scores - collection of visual acuity scores (maps with :id, :date, :left-logmar, :right-logmar)
      :medications - collection of medication records (maps with :id, :name, :start-date, :end-date)
      :ms-onset-date - date of MS onset for MSSS calculations
      :msss-data - map of MSSS values keyed by disease duration and EDSS score
      :start-date, :end-date - date range for chart
      :width, :height - dimensions of chart in pixels
  
  Returns:
  - A JFreeChart object with plots for each provided data type
  - nil if no data is provided that can be plotted"
  [params]
  (edss/create-edss-timeline-chart params))

;; Medication charts
(defn create-medication-chart
  "Create a chart for a single medication type
  
  Parameters:
  - medications: collection of maps of the same medication, with :id, :name, :start-date keys
                 and optional :end-date and :daily-dose
  
  Returns a JFreeChart object for a single medication"
  [medications]
  (medication/create-medication-chart medications))

(defn create-medications-chart
  "Create a combined chart showing multiple medications over time
  
  Parameters:
  - medications: collection of medication maps with :id, :name, :start-date keys 
                 and optional :end-date and :daily-dose
                 
  Returns a JFreeChart with subplots for each medication type"
  [medications]
  (medication/create-medications-chart medications))

(defn chart-to-image
  "Convert a JFreeChart to a BufferedImage for display or saving.
  Returns nil if chart is nil."
  [^JFreeChart chart width height]
  (when chart
    (.createBufferedImage chart width height)))

(defn save-chart
  "Save a JFreeChart to a PNG file
  
  Parameters:
  - chart: the JFreeChart to save
  - filename: path where the chart should be saved
  - width: image width in pixels
  - height: image height in pixels
  
  Returns:
  - The absolute path to the saved file
  - nil if chart is nil"
  [^JFreeChart chart filename width height]
  (when chart
    (let [file (io/file filename)]
      (ChartUtils/saveChartAsPNG file chart width height)
      (.getAbsolutePath file))))


(defn write-chart
  "Return a JFreeChart as a byte array."
  ^bytes [^JFreeChart chart width height]
  (when chart
    (with-open [baos (ByteArrayOutputStream.)]
      (ChartUtils/writeChartAsPNG baos chart width height)
      (.toByteArray baos))))

(defn stream-chart
  "Returns a function that will write out the chart to the outputstream
  specified."
  [^JFreeChart chart width height]
  (fn [^OutputStream out]
    (ChartUtils/writeChartAsPNG out chart width height)))

;; Simple chart creation functions
(defn time-series-chart
  "Creates a function that generates a time series chart based on configuration.
   
   Parameters:
   - config: A map containing chart configuration:
     - :title - Chart title
     - :x-label - X-axis label
     - :y-label - Y-axis label
     - :series-name - Name for the data series
     - :y-lower-bound - Optional lower bound for Y-axis
     - :y-upper-bound - Optional upper bound for Y-axis
     - :date-fn - Function to extract date (defaults to :date)
     - :value-fn - Function to extract value (defaults to :value)
     - :tick-unit - Optional tick unit for Y-axis
   
   Returns:
   - A function that takes a collection of data items and returns a JFreeChart
   
   Throws:
   - Exception if config doesn't match the required specification"
  [config]
  (simple/time-series-chart config))

;; Examples
(comment
  ;; Example of using the EDSS timeline chart
  (def sample-edss-scores
    [{:id 1 :date (LocalDate/now) :edss 3.5 :in-relapse? false}
     {:id 2 :date (.minusMonths (LocalDate/now) 3) :edss 2.5 :in-relapse? false}
     {:id 3 :date (.minusMonths (LocalDate/now) 6) :edss 4.0 :in-relapse? true}])
  
  (def sample-ms-events
    [{:id 1 :date (.minusMonths (LocalDate/now) 6) :type :relapse :abbreviation "R"}
     {:id 2 :date (.minusMonths (LocalDate/now) 2) :type :mri :abbreviation "M"}])
  
  ;; Example MSSS data structure (normally from pc4.rsdb.msss/msss-lookup)
  (def sample-msss-data
    {0  {0.0 0.7, 1.0 2.3, 1.5 3.5, 2.0 4.3, 2.5 5.1, 3.0 5.9, 3.5 6.7, 4.0 7.4, 4.5 8.0, 5.0 8.5, 5.5 9.0, 6.0 9.3, 6.5 9.5, 7.0 9.8, 7.5 9.9, 8.0 10.0},
     5  {0.0 0.1, 1.0 0.5, 1.5 0.9, 2.0 1.5, 2.5 2.2, 3.0 3.0, 3.5 3.8, 4.0 4.6, 4.5 5.4, 5.0 6.2, 5.5 6.9, 6.0 7.6, 6.5 8.1, 7.0 8.7, 7.5 9.1, 8.0 9.5, 8.5 9.7, 9.0 9.9, 9.5 10.0},
     10 {0.0 0.1, 1.0 0.3, 1.5 0.5, 2.0 0.8, 2.5 1.2, 3.0 1.7, 3.5 2.3, 4.0 3.0, 4.5 3.9, 5.0 4.8, 5.5 5.7, 6.0 6.6, 6.5 7.4, 7.0 8.1, 7.5 8.7, 8.0 9.2, 8.5 9.6, 9.0 9.8, 9.5 10.0}})
  
  ;; Basic chart without MSSS data
  (def basic-chart (create-edss-timeline-chart 
                    {:edss-scores sample-edss-scores 
                     :ms-events sample-ms-events
                     :ms-onset-date (.minusYears (LocalDate/now) 2)}))
  
  ;; Chart with MSSS data included
  (def msss-chart (create-edss-timeline-chart 
                    {:edss-scores sample-edss-scores 
                     :ms-events sample-ms-events
                     :ms-onset-date (.minusYears (LocalDate/now) 2)
                     :msss-data sample-msss-data}))
  
  ;; Save chart to file
  (save-chart msss-chart "/tmp/edss-chart.png" 800 600)
  
  ;; Example of using the simple chart creator
  (def weight-config
    {:title "Weight Over Time"
     :x-label "Date"
     :y-label "Weight (kg)"
     :series-name "Weight"
     :y-lower-bound 50
     :y-upper-bound 100
     :date-fn :date
     :value-fn :weight})
  
  (def create-weight-chart (time-series-chart weight-config))
  
  (def weight-data
    [{:id 1 :date (LocalDate/now) :weight 75.5}
     {:id 2 :date (.minusMonths (LocalDate/now) 1) :weight 76.2}
     {:id 3 :date (.minusMonths (LocalDate/now) 2) :weight 77.8}
     {:id 4 :date (.minusMonths (LocalDate/now) 3) :weight 79.1}
     {:id 5 :date (.minusMonths (LocalDate/now) 4) :weight 78.3}])
  
  (def weight-chart (create-weight-chart weight-data))
  
  (save-chart weight-chart "/tmp/weight-chart.png" 800 600))
