(ns pc4.chart.edss
  "A pure, data-driven implementation of EDSS charting functionality.
   This namespace provides functions to create EDSS charts without direct
   database coupling. Data is validated using clojure.spec."
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [pc4.chart.medication :as medication])
  (:import
    (java.time LocalDate ZoneId)
    (java.util Date)
    (org.jfree.chart ChartFactory ChartUtils JFreeChart)
    (org.jfree.chart.axis DateAxis ValueAxis DateTickUnit DateTickUnitType NumberTickUnit)
    (org.jfree.chart.labels ItemLabelAnchor ItemLabelPosition XYItemLabelGenerator)
    (org.jfree.chart.plot CombinedDomainXYPlot PlotOrientation XYPlot)
    (org.jfree.chart.renderer.xy XYLineAndShapeRenderer)
    (org.jfree.chart.ui TextAnchor)                         ;; Changed from org.jfree.ui in JFreeChart 1.5.x
    (org.jfree.data.time Day TimeSeries TimeSeriesCollection TimeTableXYDataset)
    (org.jfree.data.xy XYDataset)
    (java.awt BasicStroke Color Font Stroke)
    (java.text SimpleDateFormat)))

;; Basic utility functions
(defn normalize-date
  "Normalize date to LocalDate. 
   Accepts either a LocalDate instance or an ISO date string."
  ^LocalDate [date]
  (cond
    (instance? LocalDate date) date
    (string? date) (LocalDate/parse date)
    :else (throw (ex-info "Invalid date format" {:date date}))))

(defn local-date->date
  "Convert LocalDate to java.util.Date"
  ^Date [^LocalDate local-date]
  (-> local-date
      (.atStartOfDay (ZoneId/systemDefault))
      .toInstant
      Date/from))

(defn date->day
  "Convert a date to JFreeChart Day object"
  ^Day [date]
  (when date
    (let [local-date (normalize-date date)
          util-date (local-date->date local-date)]
      (Day. util-date))))

;; Specs for input data
(s/def ::id any?)
(s/def ::date (s/or :local-date #(instance? LocalDate %)
                    :iso-date-string (s/and string? #(try
                                                       (LocalDate/parse %)
                                                       true
                                                       (catch Exception _ false)))))
(def valid-edss-values #{0.0 1.0 1.5 2.0 2.5 3.0 3.5 4.0 4.5 5.0 5.5 6.0 6.5 7.0 7.5 8.0 8.5 9.0 9.5 10.0})
(s/def ::edss (s/nilable (s/and number? valid-edss-values)))
(s/def ::in-relapse? (s/nilable boolean?))
(s/def ::edss-score (s/keys :req-un [::id ::date ::edss]
                            :opt-un [::in-relapse?]))
(s/def ::edss-scores (s/coll-of ::edss-score))

(s/def ::type keyword?)
(s/def ::abbreviation string?)
(s/def ::ms-event (s/keys :req-un [::id ::date ::type ::abbreviation]))
(s/def ::ms-events (s/coll-of ::ms-event))

;; Reuse medication specs from medication.clj
(s/def ::medications ::medication/medications)

(s/def ::left-logmar (s/nilable (s/and number? #(<= -0.4 % 3.0))))
(s/def ::right-logmar (s/nilable (s/and number? #(<= -0.4 % 3.0))))
(s/def ::logmar-score (s/keys :req-un [::id ::date]
                              :opt-un [::left-logmar ::right-logmar]))
(s/def ::logmar-scores (s/coll-of ::logmar-score))

;; Helper functions for preparing other types of scores
(defn prepare-ms-event-scores
  "Normalize, filter, and sort MS event scores by date.
   Returns a vector of prepared events for efficient indexed access."
  [ms-events]
  (->> ms-events
       (map (fn [event]
              (update event :date normalize-date)))
       (filter #(:date %))
       (sort-by :date)
       (vec)))

(defn prepare-logmar-scores
  "Normalize, filter, and sort logMAR scores by date.
   Returns a vector of prepared scores for efficient indexed access."
  [scores]
  (->> scores
       (map (fn [score]
              (update score :date normalize-date)))
       (filter #(:date %))
       (sort-by :date)
       (vec)))

(s/def ::sex #{:MALE :FEMALE :UNKNOWN})
(s/def ::lower-age-onset (s/nilable (s/and int? #(>= % 0))))
(s/def ::upper-age-onset (s/nilable (s/and int? #(> % 0))))
(s/def ::ms-onset-date ::date)
(s/def ::start-date ::date)
(s/def ::end-date (s/nilable ::date))
(s/def ::width pos-int?)
(s/def ::height pos-int?)

(s/def ::chart-options
  (s/keys :opt-un [::start-date ::end-date ::sex
                   ::lower-age-onset ::upper-age-onset
                   ::include-edss-only-in-person?
                   ::include-edss-in-relapse?
                   ::width ::height
                   ::msss-data]))

(defn prepare-edss-chart-scores
  "Normalize, filter, and sort EDSS scores by date.
   Filters out invalid scores that cannot be charted (those without date or edss value).
   Returns a vector of prepared scores for efficient indexed access."
  [scores]
  (->> scores
       (map (fn [score]
              (update score :date normalize-date)))
       (filter (fn [score]
                 (and (:date score) (:edss score))))
       (sort-by :date)
       (vec)))

(defn years-between
  "Calculate years between two dates"
  [^LocalDate start, ^LocalDate end]
  (.until start end java.time.temporal.ChronoUnit/YEARS))

(defn plus-years
  "Add years to a date"
  [^LocalDate date, years]
  (.plusYears date years))

(defn plus-days
  "Add days to a date"
  [^LocalDate date, days]
  (.plusDays date days))

(defn before?
  "Check if date1 is before date2"
  [^LocalDate date1, ^LocalDate date2]
  (.isBefore date1 date2))

(defn now
  "Current date as a LocalDate"
  ^LocalDate []
  (LocalDate/now))

(s/fdef create-edss-dataset
  :args (s/cat :prepared-scores (s/coll-of ::edss-score))
  :ret #(instance? TimeSeriesCollection %))

(defn- create-edss-dataset
  "Create a time series dataset for EDSS scores using prepared scores"
  ^TimeSeriesCollection [prepared-scores]
  (let [series (TimeSeries. "EDSS")]
    (doseq [{:keys [date edss]} prepared-scores]
      (.addOrUpdate series (date->day date) edss))
    (doto (TimeSeriesCollection.)
      (.addSeries series))))

(s/fdef create-ms-event-dataset
  :args (s/cat :prepared-events (s/coll-of ::ms-event))
  :ret #(instance? TimeSeriesCollection %))

(defn- create-ms-event-dataset
  "Create a time series dataset for MS events using prepared events"
  ^TimeSeriesCollection [prepared-events]
  (let [series (TimeSeries. "Events")]
    (doseq [{:keys [date]} prepared-events]
      (.addOrUpdate series (date->day date) 1.0))
    (doto (TimeSeriesCollection.)
      (.addSeries series))))

(s/fdef create-logmar-dataset
  :args (s/cat :prepared-scores (s/coll-of ::logmar-score))
  :ret #(instance? TimeSeriesCollection %))

(defn- create-logmar-dataset
  "Create a time series dataset for logMAR scores using prepared scores"
  ^TimeSeriesCollection [prepared-scores]
  (let [left-series (TimeSeries. "Left")
        right-series (TimeSeries. "Right")]
    (doseq [{:keys [date left-logmar right-logmar]} prepared-scores]
      (when left-logmar
        (.addOrUpdate left-series (date->day date) left-logmar))
      (when right-logmar
        (.addOrUpdate right-series (date->day date) right-logmar)))
    (doto (TimeSeriesCollection.)
      (.addSeries left-series)
      (.addSeries right-series))))


;; Define specs for MSSS data
(s/def ::disease-duration nat-int?)
(s/def ::edss-value (s/and number? #(<= 0 % 10)))
(s/def ::msss-value number?)
(s/def ::edss-map (s/map-of ::edss-value ::msss-value))
(s/def ::msss-lookup (s/map-of ::disease-duration ::edss-map))

(s/fdef percentile-edss-for-duration
  :args (s/cat :msss-data ::msss-lookup
               :duration-years ::disease-duration
               :percentile number?)
  :ret (s/nilable ::edss-value))

(defn percentile-edss-for-duration
  "Get EDSS value for a specific percentile at a given disease duration
  
  Parameters:
  - msss-data: Map of MSSS values keyed by disease duration then by EDSS
  - duration-years: Disease duration in years
  - percentile: Percentile value (0-10)
  
  Returns EDSS value for the percentile (or nil if not found)"
  [msss-data duration-years percentile]
  (when-let [duration-data (get msss-data duration-years)]
    ;; Find closest EDSS value to target percentile
    (let [matching-entries (->> duration-data
                                (sort-by val)
                                (filter #(>= percentile (val %))))]
      (when (seq matching-entries)
        (key (last matching-entries))))))

(s/fdef create-msss-dataset
  :args (s/cat :ms-onset-date ::ms-onset-date
               :start-date (s/nilable ::start-date)
               :end-date (s/nilable ::end-date)
               :msss-data ::msss-lookup)
  :ret #(instance? TimeTableXYDataset %))

(defn- create-msss-dataset
  "Create dataset for Multiple Sclerosis Severity Score percentiles
  
  Parameters:
  - ms-onset-date: Date of MS onset
  - start-date: Start date for chart (defaults to onset date)
  - end-date: End date for chart (defaults to current date)
  - msss-data: Map of MSSS values keyed by disease duration then by EDSS
  
  Returns a TimeTableXYDataset for plotting"
  ^TimeTableXYDataset [ms-onset-date start-date end-date msss-data]
  (let [dataset (TimeTableXYDataset.)
        start (if start-date (normalize-date start-date) (normalize-date ms-onset-date))
        end (if end-date (normalize-date end-date) (now))
        onset (normalize-date ms-onset-date)]

    (when (and onset msss-data (seq msss-data))
      (loop [date start]
        (when (before? date end)
          (let [duration-years (int (years-between onset date))]
            (when-let [duration-data (get msss-data duration-years)]
              ;; Find EDSS values for standard percentiles
              (let [edss5 (percentile-edss-for-duration msss-data duration-years 0.5)
                    edss25 (percentile-edss-for-duration msss-data duration-years 2.5)
                    edss50 (percentile-edss-for-duration msss-data duration-years 5.0)
                    edss75 (percentile-edss-for-duration msss-data duration-years 7.5)
                    edss95 (percentile-edss-for-duration msss-data duration-years 9.5)]

                ;; Add all percentiles that have valid data
                (when edss5 (.add dataset (date->day date) edss5 "5th"))
                (when edss25 (.add dataset (date->day date) edss25 "25th"))
                (when edss50 (.add dataset (date->day date) edss50 "Median"))
                (when edss75 (.add dataset (date->day date) edss75 "75th"))
                (when edss95 (.add dataset (date->day date) edss95 "95th"))))

            (recur (plus-years date 1)))))

      dataset)))

(s/fdef edss-label-generator
  :args (s/cat :prepared-scores (s/coll-of ::edss-score))
  :ret #(instance? XYItemLabelGenerator %))

(defn edss-label-generator
  "Create a label generator for EDSS points that shows 'R' for in-relapse points.
   
   Takes a vector of prepared scores (already normalized, filtered, sorted, and
   converted to a vector) and uses efficient indexed lookup to determine which
   points should be labeled with 'R'."
  ^XYItemLabelGenerator [prepared-scores]
  (reify XYItemLabelGenerator
    (generateLabel [_this dataset series item]
      (if-let [score (get prepared-scores item)]
        (if (:in-relapse? score) "R" "")
        ""))))

(s/fdef ms-event-label-generator
  :args (s/cat :prepared-events (s/coll-of ::ms-event))
  :ret #(instance? XYItemLabelGenerator %))

(defn ms-event-label-generator
  "Create a label generator for MS events that shows abbreviation.
   
   Takes a vector of prepared events (already normalized, filtered, sorted, and
   converted to a vector) and uses efficient indexed lookup to determine the label."
  ^XYItemLabelGenerator [prepared-events]
  (reify XYItemLabelGenerator
    (generateLabel [_this dataset series item]
      ;; Since we're using the exact same prepared events to create both the dataset
      ;; and the labels, the item index directly corresponds to the position in our vector
      (if-let [event (get prepared-events item)]
        (:abbreviation event)
        ""))))

(s/fdef quartile-label-generator
  :args (s/cat)
  :ret #(instance? XYItemLabelGenerator %))

(defn quartile-label-generator
  "Creates a label generator for MSSS percentile lines that shows labels only for 
   the last point in each series, using the series key as the label text.
   
   This ensures the chart isn't cluttered with too many labels while still
   providing identification for each percentile line."
  ^XYItemLabelGenerator []
  (reify XYItemLabelGenerator
    (generateLabel [_this dataset series item]
      ;; Only show label for the last item in each series
      (when (= item (dec (.getItemCount dataset series)))
        (.getSeriesKey dataset series)))))

(s/fdef configure-edss-plot
  :args (s/cat :prepared-scores (s/coll-of ::edss-score))
  :ret #(instance? XYPlot %))

(defn configure-edss-plot
  "Configure an EDSS plot with standard settings.
   Takes prepared EDSS scores and returns a configured XYPlot."
  ^XYPlot [prepared-scores]
  (let [dataset (create-edss-dataset prepared-scores)
        chart (ChartFactory/createTimeSeriesChart "" "Date" "EDSS" dataset false false false)
        plot (.getXYPlot chart)
        range-axis (.getRangeAxis plot)]
    
    ;; Configure axis
    (.setLowerBound range-axis 0)
    (.setUpperBound range-axis 10)
    (.setTickUnit range-axis (NumberTickUnit. 0.5))
    (.setMinorTickCount range-axis 0)  ;; Disable minor ticks
    (.setMinorTickMarksVisible range-axis false)
    
    ;; Configure renderer
    (let [renderer (XYLineAndShapeRenderer.)]
      (.setSeriesShapesVisible renderer 0 true)
      (.setUseOutlinePaint renderer true)
      (.setUseFillPaint renderer true)
      
      ;; Make the main EDSS line thicker for better visibility
      (.setSeriesStroke renderer 0 (BasicStroke. 2.0))
      
      ;; Set up label generator for relapse indicators
      (let [generator (edss-label-generator prepared-scores)]
        (.setSeriesItemLabelGenerator renderer 0 generator)
        (.setSeriesItemLabelsVisible renderer 0 true))
      
      (.setRenderer plot renderer))
    
    plot))

(s/fdef create-edss-chart
  :args (s/cat :edss-scores ::edss-scores)
  :ret #(instance? JFreeChart %))

(defn create-edss-chart
  "Create an EDSS chart from the given scores"
  ^JFreeChart [edss-scores]
  (let [prepared-scores (prepare-edss-chart-scores edss-scores)
        plot (configure-edss-plot prepared-scores)
        chart (JFreeChart. "" JFreeChart/DEFAULT_TITLE_FONT plot false)]
    
    (.setAntiAlias chart true)
    chart))

(s/fdef configure-ms-event-plot
  :args (s/cat :prepared-events (s/coll-of ::ms-event))
  :ret #(instance? XYPlot %))

(defn configure-ms-event-plot
  "Configure an MS events plot with standard settings.
   Takes prepared MS events and returns a configured XYPlot."
  ^XYPlot [prepared-events]
  (let [dataset (create-ms-event-dataset prepared-events)
        chart (ChartFactory/createTimeSeriesChart "" "Date" "Event" dataset false false false)
        plot (.getXYPlot chart)
        range-axis (.getRangeAxis plot)]
    
    ;; Configure the y-axis
    (.setTickLabelsVisible range-axis false)
    
    ;; Move the axis label further from the plot
    (.setLabelInsets range-axis (org.jfree.chart.ui.RectangleInsets. 0.0 0.0 0.0 25.0))
    (.setLabelFont range-axis (Font. "sanserif" Font/BOLD 12))
    
    ;; Configure renderer
    (let [renderer (XYLineAndShapeRenderer.)]
      (.setSeriesShapesVisible renderer 0 false)
      (.setSeriesLinesVisible renderer 0 false)
      (.setUseOutlinePaint renderer true)
      (.setUseFillPaint renderer true)
      
      ;; Set up label generator for event types
      (let [generator (ms-event-label-generator prepared-events)]
        (.setSeriesItemLabelGenerator renderer 0 generator)
        (.setSeriesItemLabelsVisible renderer 0 true)
        (.setSeriesItemLabelFont renderer 0 (Font. "sanserif" Font/PLAIN 8))
        (.setSeriesPositiveItemLabelPosition renderer 0
                                           (ItemLabelPosition. ItemLabelAnchor/CENTER
                                                               TextAnchor/CENTER)))
      
      (.setRenderer plot renderer))
    
    plot))

(s/fdef create-ms-event-chart
  :args (s/cat :ms-events ::ms-events)
  :ret #(instance? JFreeChart %))

(defn create-ms-event-chart
  "Create a chart displaying MS events"
  ^JFreeChart [ms-events]
  (let [prepared-events (prepare-ms-event-scores ms-events)
        dataset (create-ms-event-dataset prepared-events)
        chart (ChartFactory/createTimeSeriesChart "" "Date" "Event" dataset false false false)]

    (.setAntiAlias chart true)
    (let [plot (.getXYPlot chart)
          range-axis (.getRangeAxis plot)]
      ;; Configure the y-axis
      (.setTickLabelsVisible range-axis false)

      ;; Move the axis label further from the plot
      (.setLabelInsets range-axis (org.jfree.chart.ui.RectangleInsets. 0.0 0.0 0.0 25.0))
      (.setLabelFont range-axis (Font. "sanserif" Font/BOLD 12))

      (let [renderer (XYLineAndShapeRenderer.)]
        (.setSeriesShapesVisible renderer 0 false)
        (.setSeriesLinesVisible renderer 0 false)
        (.setUseOutlinePaint renderer true)
        (.setUseFillPaint renderer true)
        (.setRenderer plot renderer)

        ;; Set up label generator for event types using the same prepared events
        (let [generator (ms-event-label-generator prepared-events)]
          (.setSeriesItemLabelGenerator renderer 0 generator)
          (.setSeriesItemLabelsVisible renderer 0 true)
          (.setSeriesItemLabelFont renderer 0 (Font. "sanserif" Font/PLAIN 8))
          (.setSeriesPositiveItemLabelPosition renderer 0
                                               (ItemLabelPosition. ItemLabelAnchor/CENTER
                                                                   TextAnchor/CENTER)))))
    chart))

(s/fdef configure-logmar-plot
  :args (s/cat :prepared-scores (s/coll-of ::logmar-score))
  :ret #(instance? XYPlot %))

(defn configure-logmar-plot
  "Configure a logMAR plot with standard settings.
   Takes prepared logMAR scores and returns a configured XYPlot."
  ^XYPlot [prepared-scores]
  (let [dataset (create-logmar-dataset prepared-scores)
        chart (ChartFactory/createTimeSeriesChart "" "Date" "logMAR" dataset false false false)
        plot (.getXYPlot chart)
        range-axis (.getRangeAxis plot)]
    
    ;; Configure axis
    (.setLowerBound range-axis -0.4)
    (.setUpperBound range-axis 3.0)
    
    ;; Configure renderer
    (let [renderer (XYLineAndShapeRenderer.)]
      (.setSeriesShapesVisible renderer 0 true)
      (.setSeriesShapesVisible renderer 1 true)
      (.setUseOutlinePaint renderer true)
      (.setUseFillPaint renderer true)
      (.setRenderer plot renderer))
    
    plot))

(s/fdef create-logmar-chart
  :args (s/cat :logmar-scores ::logmar-scores)
  :ret #(instance? JFreeChart %))

(defn create-logmar-chart
  "Create a chart displaying logMAR visual acuity scores"
  ^JFreeChart [logmar-scores]
  (let [prepared-scores (prepare-logmar-scores logmar-scores)
        plot (configure-logmar-plot prepared-scores)
        chart (JFreeChart. "" JFreeChart/DEFAULT_TITLE_FONT plot false)]
    
    (.setAntiAlias chart true)
    chart))

;; MSSS configuration function
(s/fdef configure-msss-renderer
  :args (s/cat :plot #(instance? XYPlot %)
               :ms-onset-date ::ms-onset-date
               :start-date (s/nilable ::start-date)
               :end-date (s/nilable ::end-date)
               :msss-data ::msss-lookup)
  :ret nil?)

(defn configure-msss-renderer
  "Configure MSSS renderer for an existing EDSS plot.
   Adds MSSS dataset and configures the renderer for percentile visualization.
   
   Parameters:
   - plot: The XYPlot to add MSSS data to
   - ms-onset-date: Date of MS onset
   - start-date: Optional start date for chart
   - end-date: Optional end date for chart
   - msss-data: MSSS lookup data
   
   Returns nil."
  [plot ms-onset-date start-date end-date msss-data]
  ;; First create and add the dataset
  (let [msss-dataset (create-msss-dataset ms-onset-date start-date end-date msss-data)]
    (.setDataset plot 1 msss-dataset)
    
    ;; Then create and configure the renderer
    (let [line-renderer (XYLineAndShapeRenderer. true false)
          dashed (BasicStroke. 1.0 BasicStroke/CAP_BUTT BasicStroke/JOIN_BEVEL
                               0 (float-array [5]) 0)
          series-count (.getSeriesCount msss-dataset)]
      
      ;; Ensure lines are drawn correctly for dotted patterns
      (.setDrawSeriesLineAsPath line-renderer true)
      
      ;; Basic settings for all lines
      (.setDefaultItemLabelFont line-renderer (Font. "SansSerif" Font/BOLD 10))
      
      ;; Configure all series with consistent settings
      (let [label-generator (quartile-label-generator)
            label-position (ItemLabelPosition. ItemLabelAnchor/OUTSIDE12 TextAnchor/CENTER_LEFT)]
        (doseq [i (range series-count)]
          (doto line-renderer
            ;; No shapes (markers) on any percentile lines
            (.setSeriesShapesVisible i false)
            
            ;; Enable labels with the same generator
            (.setSeriesItemLabelsVisible i true)
            (.setSeriesItemLabelGenerator i label-generator)
            
            ;; Same label position for all series
            (.setSeriesPositiveItemLabelPosition i label-position))))
      
      ;; Series-specific styling based on percentile names
      (doseq [i (range series-count)]
        (let [series-key (str (.getSeriesKey msss-dataset i))]
          (case series-key
            "5th"  (doto line-renderer
                     (.setSeriesStroke i dashed)
                     (.setSeriesPaint i (Color. 0.8 0.2 0.2 1.0))
                     (.setSeriesItemLabelPaint i (Color. 0.8 0.2 0.2 1.0)))
            
            "25th" (doto line-renderer
                     (.setSeriesStroke i dashed)
                     (.setSeriesPaint i (Color. 0.2 0.2 0.8 1.0))
                     (.setSeriesItemLabelPaint i (Color. 0.2 0.2 0.8 1.0)))
            
            "Median" (doto line-renderer
                       (.setSeriesStroke i (BasicStroke. 1.5))
                       (.setSeriesPaint i (Color. 0.2 0.8 0.2 1.0))
                       (.setSeriesItemLabelPaint i (Color. 0.2 0.8 0.2 1.0)))
            
            "75th" (doto line-renderer
                     (.setSeriesStroke i dashed)
                     (.setSeriesPaint i (Color. 0.8 0.4 0.0 1.0))
                     (.setSeriesItemLabelPaint i (Color. 0.8 0.4 0.0 1.0)))
            
            "95th" (doto line-renderer
                     (.setSeriesStroke i dashed)
                     (.setSeriesPaint i (Color. 0.8 0.0 0.0 1.0))
                     (.setSeriesItemLabelPaint i (Color. 0.8 0.0 0.0 1.0)))
            
            ;; Default for any other series
            (doto line-renderer
              (.setSeriesStroke i dashed)
              (.setSeriesPaint i Color/DARK_GRAY)
              (.setSeriesItemLabelPaint i Color/DARK_GRAY)))))
      
      ;; Set the renderer for the plot
      (.setRenderer plot 1 line-renderer)))
  nil)

(s/fdef create-combined-chart
  :args (s/cat :params (s/keys :opt-un [::edss-scores ::ms-events ::logmar-scores
                                        ::medications ::ms-onset-date
                                        ::start-date ::end-date ::msss-data ::width ::height]))
  :ret (s/nilable #(instance? JFreeChart %)))

(defn create-combined-chart
  "Create a combined chart with EDSS, MS events, and optionally logMAR and medications
  
  Parameters:
  - params: A map containing:
    - :edss-scores - collection of EDSS score maps (optional)
    - :ms-events - collection of MS event maps (optional)
    - :logmar-scores - collection of visual acuity scores (optional)
    - :medications - collection of medication records (optional)
    - :ms-onset-date - date of MS onset for MSSS calculations (optional)
    - :start-date, :end-date - date range for chart (optional)
    - :msss-data - map of MSSS values keyed by disease duration and EDSS score (optional)
    - :width, :height - dimensions of chart (optional, defaults to 800x600)
  
  Returns a JFreeChart object with plots for each provided data type, 
  or nil if no data is available to create any plots"
  ^JFreeChart [params]
  (let [{:keys [edss-scores ms-events logmar-scores medications ms-onset-date
                start-date end-date msss-data width height]
         :or   {width 800, height 600}} params
        domain-axis (DateAxis. "Date")
        plot (CombinedDomainXYPlot. domain-axis)]

    (.setGap plot 1)

    ;; Prepare all scores upfront
    (let [prepared-edss-scores (prepare-edss-chart-scores edss-scores)
          prepared-ms-events (prepare-ms-event-scores ms-events)
          prepared-logmar-scores (prepare-logmar-scores logmar-scores)]

      ;; Add EDSS plot if scores are provided
      (when (seq prepared-edss-scores)
        (let [edss-plot (configure-edss-plot prepared-edss-scores)]
          
          ;; Add MSSS renderer if we have onset date and MSSS data
          (when (and ms-onset-date (seq msss-data))
            (configure-msss-renderer edss-plot ms-onset-date start-date end-date msss-data))

          (.add plot edss-plot 5)))

      ;; Add MS events plot if events are provided
      (when (seq prepared-ms-events)
        (let [ms-event-plot (configure-ms-event-plot prepared-ms-events)]
          (.add plot ms-event-plot 1)))

      ;; Add logMAR plot if data is available
      (when (seq prepared-logmar-scores)
        (let [logmar-plot (configure-logmar-plot prepared-logmar-scores)]
          (.add plot logmar-plot 2))))

    ;; Add medications if available
    (when (seq medications)
      (let [medication-chart (medication/create-medications-chart medications)]
        (doseq [med-plot (medication/get-medication-plots medication-chart)]
          (.add plot med-plot 1))))

    ;; Only create a chart if at least one plot was added
    (when (pos? (count (.getSubplots plot)))
      ;; Configure domain axis
      (.setOrientation plot PlotOrientation/VERTICAL)
      (let [date-axis (.getDomainAxis plot)
            formatter (SimpleDateFormat. "yyyy")]
        ;; In JFreeChart 1.5.x, the API changed to use DateTickUnitType
        (.setTickUnit date-axis (DateTickUnit. DateTickUnitType/YEAR 1 formatter))
        ;; Make sure date labels are visible
        (.setVisible date-axis true)
        (.setTickLabelsVisible date-axis true)
        (.setTickMarksVisible date-axis true)
        (.setLabel date-axis "Date"))

      ;; Create the chart with visible axes but no legend
      (doto (JFreeChart. "" JFreeChart/DEFAULT_TITLE_FONT plot false)
        (.setAntiAlias true)))))

;; Public API
(s/fdef create-edss-timeline-chart
  :args (s/cat :params (s/keys :opt-un [::edss-scores ::ms-events ::logmar-scores ::medications
                                        ::ms-onset-date ::start-date ::end-date
                                        ::sex ::width ::height ::msss-data]))
  :ret (s/nilable #(instance? JFreeChart %)))

(defn create-edss-timeline-chart
  "Creates an EDSS timeline chart with the provided data.

  Parameters:
  - params: A map containing any of the following (all are optional):
    - :edss-scores - collection of maps with :id, :date, :edss, and optional :in-relapse?
    - :ms-events - collection of maps with :id, :date, :type, and :abbreviation
    - :logmar-scores - collection of visual acuity scores
    - :medications - collection of medication records
    - :ms-onset-date - date of MS onset for MSSS calculations
    - :start-date, :end-date - date range for chart
    - :sex - patient sex (:MALE, :FEMALE, :UNKNOWN)
    - :width, :height - dimensions of chart
    - :msss-data - map of MSSS values keyed by disease duration and EDSS score

  Returns:
  - A JFreeChart object that can be rendered to various formats.
  - nil if no data is provided that can be plotted.
  
  The chart will only include plots for the data types that are provided."
  ^JFreeChart [params]
  (create-combined-chart params))


(s/fdef render-chart-to-file
  :args (s/cat :chart #(instance? JFreeChart %)
               :filename string?
               :width pos-int?
               :height pos-int?)
  :ret string?)

(defn render-chart-to-file
  "Utility function to render a chart to a file for visual inspection"
  ^String [^JFreeChart chart ^String filename ^Integer width ^Integer height]
  (let [file (io/file filename)]
    (ChartUtils/saveChartAsPNG file chart width height)
    (.getAbsolutePath file)))