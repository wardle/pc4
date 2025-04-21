(ns pc4.chart.medication
  "Medication charting functionality that can be used independently or
   as part of combined charts (e.g., EDSS charts)."
  (:require
    [clojure.spec.alpha :as s])
  (:import
    (java.time LocalDate LocalDateTime)
    (java.awt Font)
    (org.jfree.chart JFreeChart)
    (org.jfree.chart.axis DateAxis NumberAxis)
    (org.jfree.chart.plot CombinedDomainXYPlot Plot PlotOrientation XYPlot)
    (org.jfree.chart.renderer.xy XYBarRenderer StandardXYBarPainter)
    (org.jfree.data.time SimpleTimePeriod TimePeriodValues TimePeriodValuesCollection)))

;; Specs for medication data
(s/def ::id any?)
(s/def ::name string?)
(s/def ::date (s/or :local-date #(instance? LocalDate %)
                    :iso-date-string (s/and string? #(try
                                                       (LocalDate/parse %)
                                                       true
                                                       (catch Exception _ false)))))
(s/def ::start-date ::date)
(s/def ::end-date (s/nilable ::date))
(s/def ::daily-dose (s/nilable (s/and number? #(>= % 0))))
(s/def ::medication (s/keys :req-un [::id ::name ::start-date]
                            :opt-un [::end-date ::daily-dose]))
(s/def ::medications (s/coll-of ::medication))

(defn normalize-date
  "Normalize date to LocalDate.
   Accepts either a LocalDate instance or an ISO date string."
  ^LocalDate [date]
  (cond
    (nil? date) date
    (instance? LocalDate date) date
    (string? date) (LocalDate/parse date)
    :else (throw (ex-info "Invalid date format" {:date date}))))

(defn- local-date->util-date
  "Convert LocalDate to java.util.Date"
  ^java.util.Date [^LocalDate local-date]
  (-> local-date
      (.atStartOfDay (java.time.ZoneId/systemDefault))
      .toInstant
      java.util.Date/from))

(defn- now
  "Current date as a LocalDate"
  ^LocalDate []
  (LocalDate/now))

(s/fdef create-medication-dataset
  :args (s/cat :medications ::medications)
  :ret #(instance? TimePeriodValuesCollection %))

(defn- create-medication-dataset
  "Create a dataset for medications of a specific type"
  ^TimePeriodValuesCollection [medications]
  (let [series (TimePeriodValues. (-> medications first :name))
        collection (TimePeriodValuesCollection.)]

    (doseq [{:keys [start-date end-date daily-dose]} medications
            :when start-date                                ;; TODO: could use earliest known date instead?
            :let [start (normalize-date start-date)
                  end (if end-date (normalize-date end-date) (now))
                  dose (or daily-dose 1.0)
                  time-period (SimpleTimePeriod.
                                (local-date->util-date start)
                                (local-date->util-date end))]]
      (.add series time-period (double dose)))

    (.addSeries collection series)
    collection))

(s/fdef create-medication-chart
  :args (s/cat :medications (s/and ::medications seq))
  :ret #(instance? JFreeChart %))

(defn create-medication-chart
  "Create a chart for a single medication type
  
  Parameters:
  - medications: collection of maps with :id, :name, :start-date keys (must be non-empty)
                and optional :end-date and :daily-dose
  
  Returns a JFreeChart with a bar chart for the medication"
  ^JFreeChart [medications]
  (when-not (seq medications)
    (throw (IllegalArgumentException. "Empty medications collection")))

  (let [med-name (-> medications first :name)
        dataset (create-medication-dataset medications)
        range-axis (NumberAxis. med-name)
        domain-axis (DateAxis. "Date")
        renderer (doto (XYBarRenderer.)
                   (.setBarPainter (StandardXYBarPainter.))
                   (.setShadowVisible false))
        plot (XYPlot. dataset domain-axis range-axis renderer)]

    (.setTickLabelsVisible range-axis false)

    (doto (JFreeChart. med-name JFreeChart/DEFAULT_TITLE_FONT plot false)
      (.setAntiAlias true))))

(defn create-medication-plot
  ^Plot [med-name meds]
  (let [font (Font. "SansSerif" Font/PLAIN 9)
        dataset (create-medication-dataset meds)
        domain-axis (DateAxis. "Date")
        range-axis (doto (NumberAxis.)
                     (.setLabel (if (> (count med-name) 30)
                                  (str (subs med-name 0 27) "...")
                                  med-name))
                     (.setLabelFont font)
                     (.setLabelAngle (/ Math/PI 2))
                     (.setTickLabelsVisible false))
        renderer (doto (XYBarRenderer.)
                   (.setBarPainter (StandardXYBarPainter.))
                   (.setShadowVisible false))]
    (XYPlot. dataset domain-axis range-axis renderer) ))

(s/fdef create-medications-chart
  :args (s/cat :medications ::medications)
  :ret #(instance? JFreeChart %))

(defn create-medications-chart
  "Create a combined chart showing multiple medications over time
  
  Parameters:
  - medications: collection of medication maps with :id, :name, :start-date, 
                 and optional :end-date and :daily-dose
                 
  Returns a JFreeChart with subplots for each medication type"
  ^JFreeChart [medications]
  (let [font (Font. "SansSerif" Font/PLAIN 9)
        domain-axis (DateAxis. "Date")
        plot (CombinedDomainXYPlot. domain-axis)
        medications-by-name (group-by :name medications)    ;; group by name so multiple courses are shown
        plots (reduce-kv (fn [acc med-name meds]
                           (conj acc [med-name (create-medication-plot med-name meds)])) [] medications-by-name)]

    (.setGap plot 0)

    ;; Add a subplot for each medication type
    (doseq [[_ medplot] (sort-by first plots)]
      (.add plot medplot 1))

    (.setOrientation plot PlotOrientation/VERTICAL)

    ;; Format domain axis
    (.setLabelFont domain-axis font)
    (.setTickLabelFont domain-axis font)

    (doto (JFreeChart. "" font plot false)
      (.setAntiAlias true))))

(s/fdef get-medication-plots
  :args (s/cat :medication-chart #(instance? JFreeChart %))
  :ret (s/nilable (s/coll-of #(instance? XYPlot %))))

(defn get-medication-plots
  "Get medication plots from a medication chart
  
  Parameters:
  - medication-chart: JFreeChart created by create-medications-chart
  
  Returns a sequence of XYPlot objects that can be added to another chart"
  [^JFreeChart medication-chart]
  (seq (.getSubplots ^CombinedDomainXYPlot (.getPlot medication-chart))))