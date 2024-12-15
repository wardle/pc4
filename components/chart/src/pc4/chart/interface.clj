(ns pc4.chart.interface
  (:require
    [incanter.core :as incanter]
    [incanter.charts :as charts])
  (:import (java.util Date TimeZone)
           (java.time LocalDate ZoneId)
           (org.jfree.chart ChartFactory)
           (org.jfree.chart.labels XYItemLabelGenerator)
           (org.jfree.chart.renderer.xy XYLineAndShapeRenderer)
           (org.jfree.data.time Day RegularTimePeriod TimeSeries)))


#_(defn make-time-series
    [^RegularTimePeriod period title data date-fn value-fn]
    (let [time-series (TimeSeries. title)]
      (doseq [item data]
        (let [date (date-fn item)
              value (value-fn item)]
          (when (and date #(instance? Date date) value)
            (.addOrUpdate time-series (RegularTimePeriod/createInstance period ^Date date (TimeZone/getDefault) nil)))))))

#_(def edss-label-generator
    "An label generator that "
    (reify XYItemLabelGenerator
      (generateLabel [this dataset series item]
        "")))




(comment
  (def dataset (make-time-series (Day.) "Test" [] :date :value))
  (def chart (ChartFactory/createTimeSeriesChart "" "Date" "EDSS" dataset false false false))
  (.setAntiAlias chart true)
  (def plot (.getXYPlot chart))
  (def range (.getRangeAxis plot))
  (.setLowerBound range 0)
  (.setUpperBound range 10)
  (def renderer (XYLineAndShapeRenderer.))
  (.setSeriesShapesVisible renderer 0 true)
  (.setUseOutlinePaint renderer true)
  (.setUseFillPaint renderer true)
  (.setRenderer plot renderer)
  (.setSeriesItemLabelGenerator 0 (edss-label-generator))
  (.setSeriesItemLabelVisible renderer 0 true))


(defn local-date->epoch-millis
  [^LocalDate date]
  (.toEpochMilli (.toInstant (.atStartOfDay date (ZoneId/systemDefault)))))


(comment
  (require '[incanter.charts :as charts])
  (require '[incanter.stats :as stats])
  (require '[incanter.core :as incanter])
  (def x [1 2 3 4 5])
  (def x [(LocalDate/of 1970 1 1)
          (LocalDate/of 1980 1 1)
          (LocalDate/of 1985 1 1)
          (LocalDate/of 2000 1 1)
          (LocalDate/of 2020 1 1)])
  (def y [1 2 3.5 nil 10])
  (incanter/view
    (doto
      (charts/time-series-plot (map local-date->epoch-millis x) y
                               :x-label "Date"
                               :y-label "EDSS"
                               :points true)
      (charts/set-stroke :width 0.5 :dash 4)
      (charts/set-point-size 5)))
  (incanter/view (doto (charts/scatter-plot x y)
                   (charts/set-theme :default)))
  (incanter/view (charts/histogram (stats/sample-normal 10000 :mean 10)) :width 700 :height 700))
