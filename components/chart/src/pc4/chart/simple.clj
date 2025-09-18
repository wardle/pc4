(ns pc4.chart.simple
  "Simple chart generation functionality that separates 
   configuration from data and chart generation"
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import (org.jfree.chart ChartFactory JFreeChart)
           (org.jfree.chart.plot XYPlot)
           (org.jfree.chart.axis NumberAxis NumberTickUnit)
           (org.jfree.data.time TimeSeriesCollection TimeSeries)
           (org.jfree.data.xy XYDataset)
           (java.time LocalDate ZonedDateTime ZoneId)
           (org.jfree.data.time Day)))

(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::title ::non-blank-string)
(s/def ::x-label ::non-blank-string)
(s/def ::y-label ::non-blank-string)
(s/def ::y-lower-bound (s/nilable number?))
(s/def ::y-upper-bound (s/nilable number?))
(s/def ::date-fn (s/or :keyword keyword? :function ifn?))
(s/def ::value-fn (s/or :keyword keyword? :function ifn?))
(s/def ::series-name ::non-blank-string)
(s/def ::tick-unit (s/nilable number?))

(s/def ::chart-config
  (s/keys :req-un [::title ::x-label ::y-label ::series-name]
          :opt-un [::y-lower-bound ::y-upper-bound 
                   ::date-fn ::value-fn ::tick-unit]))

(defn- create-time-series
  "Creates a time series from data using the specified accessor functions"
  [data date-fn value-fn series-name]
  (let [series (TimeSeries. series-name)]
    (doseq [item data]
      (let [date ^LocalDate (date-fn item)
            value (value-fn item)]
        (when (and date value)
          ;; Convert LocalDate to Day for JFreeChart
          (let [day (Day. (.getDayOfMonth date)
                          (.getMonthValue date)
                          (.getYear date))]
            (.addOrUpdate series day value)))))
    series))

(defn configure-chart
  "Configures a chart according to the provided configuration"
  [^JFreeChart chart {:keys [y-lower-bound y-upper-bound y-label tick-unit]}]
  (let [plot (.getXYPlot chart)
        range-axis (NumberAxis. y-label)]
    
    ;; Set axis bounds if provided
    (when y-lower-bound
      (.setLowerBound range-axis y-lower-bound))
    (when y-upper-bound
      (.setUpperBound range-axis y-upper-bound))
    (when tick-unit
      (.setTickUnit range-axis (NumberTickUnit. tick-unit)))
    
    ;; Replace the default range axis with our configured one
    (.setRangeAxis plot 0 range-axis)
    
    chart))

(s/fdef time-series-chart
  :args (s/cat :config ::chart-config)
  :ret ifn?)

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
  (when-not (s/valid? ::chart-config config)
    (throw (ex-info "Invalid chart configuration" 
                    {:explanation (s/explain-str ::chart-config config)
                     :data config})))
  
  (let [{:keys [title x-label y-label series-name 
                date-fn value-fn]
         :or {date-fn :date
              value-fn :value}} config]
    
    (fn [data]
      (when (seq data)
        (let [series (create-time-series data date-fn value-fn series-name)
              dataset (TimeSeriesCollection.)
              _ (.addSeries dataset series)
              chart (ChartFactory/createTimeSeriesChart 
                     title x-label y-label dataset true true false)]
          
          (.setAntiAlias chart true)
          (configure-chart chart config)
          
          chart)))))

;; Example configurations
(def edss-chart-config
  {:title "EDSS Score Over Time"
   :x-label "Date"
   :y-label "EDSS"
   :series-name "EDSS"
   :y-lower-bound 0
   :y-upper-bound 10
   :tick-unit 0.5
   :date-fn :date
   :value-fn :edss})

(def medication-chart-config
  {:title "Medication Dose Over Time"
   :x-label "Date"
   :y-label "Daily Dose (g)"
   :series-name "Dose"
   :y-lower-bound 0
   :date-fn :date
   :value-fn :daily-dose})