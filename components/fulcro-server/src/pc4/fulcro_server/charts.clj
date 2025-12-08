(ns pc4.fulcro-server.charts
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [pc4.chart.interface :as chart]
            [pc4.log.interface :as log]
            [pc4.rsdb.interface :as rsdb])
  (:import (java.time LocalDate LocalDateTime)))

(s/def ::date-str (s/and string? #(try (LocalDate/parse %) true (catch Exception _ false))))
(s/def ::start-date ::date-str)
(s/def ::end-date ::date-str)

(s/def ::edss-params
  (s/keys :opt-un [::start-date ::end-date]))

(s/def ::medication-params
  (s/keys :opt-un [::start-date ::end-date]))

(defn safe-parse-long [s]
  (when-not (str/blank? s)
    (parse-long s)))

(defn parse-date [s]
  (when-not (str/blank? s)
    (try
      (LocalDate/parse s)
      (catch Exception e
        (log/warn "Failed to parse date:" s)
        nil))))

(def default-chart-width 800)
(def default-chart-height 600)

(s/def :t_medication/medication (s/keys [:info.snomed.Concept/preferredDescription]))
(s/def ::medication (s/keys :req [:t_medication/id
                                  :t_medication/dose
                                  :t_medication/date_from
                                  :t_medication/date_to
                                  :t_medication/medication]))
(s/def ::medications (s/coll-of ::medication))
(defn medications->chart-data
  "Format medication data for use with the charting functions.
   Transforms from database format to chart format, calculating
   daily doses.

   Parameters:
   - medications: Collection of medications including nested SNOMED CT preferred term

   Returns a collection of medication maps suitable for charting."
  [medications]
  (->> medications
       (map (fn [{:t_medication/keys [id date_from date_to medication_concept_fk medication] :as med}]
              (let [concept-term (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
                {:id         id
                 :name       (or concept-term (str "Medication " medication_concept_fk))
                 :start-date date_from
                 :end-date   date_to
                 :daily-dose (rsdb/calculate-total-daily-dose med)})))))

(defn fetch-ms-events
  [pathom patient-identifier]
  (let [patient (pathom {:disable-auth true}
                        {:pathom/entity {:t_patient/patient_identifier patient-identifier}
                         :pathom/eql    [{:t_patient/summary_multiple_sclerosis
                                          [{:t_summary_multiple_sclerosis/events
                                            [:t_ms_event/id :t_ms_event/date :t_ms_event/type :t_ms_event/is_relapse :t_ms_event_type/abbreviation]}]}]})]
    (reduce (fn [acc {:t_ms_event/keys [id date type is_relapse] :t_ms_event_type/keys [abbreviation] :as event}]
              (if is_relapse
                (conj acc {:id id, :date date, :type type, :abbreviation abbreviation})
                acc))
            []
            (:t_summary_multiple_sclerosis/events (:t_patient/summary_multiple_sclerosis patient)))))

(defn make-edss
  [{:keys [pathom rsdb] :as env} patient-identifier {:keys [start-date end-date width height] :as opts}]
  (let [patient-pk (rsdb/patient-identifier->pk rsdb patient-identifier)
        relapse-dates (reduce (fn [acc {:keys [date_time in_relapse]}]
                                (if in_relapse (conj acc (LocalDateTime/.toLocalDate date_time)) acc))
                              #{} (rsdb/forms rsdb {:patient-pk patient-pk :form-type :relapse/v1 :select #{:date-time} :is-deleted false}))
        forms (->> (rsdb/forms rsdb {:patient-pk patient-pk
                                     :form-types [:edss/v1] :select #{:date-time}
                                     :is-deleted false})
                   (map (fn [{:keys [date_time edss] :as form}]
                          (let [date (LocalDateTime/.toLocalDate date_time)]
                            (assoc form :date date
                                        :in-relapse? (relapse-dates date)
                                        :edss (parse-double edss))))))]
    (log/debug "relapses" relapse-dates)
    (log/debug "edss results:" forms)
    (chart/create-edss-timeline-chart
      {:edss-scores   forms
       :ms-events     (fetch-ms-events pathom patient-identifier)
       :ms-onset-date (LocalDate/of 2010 3 1)
       :msss-data     (rsdb/msss-lookup {:type :roxburgh})
       :start-date    (parse-date start-date)
       :end-date      (parse-date end-date)
       :width         (or (safe-parse-long width) default-chart-width)
       :height        (or (safe-parse-long height) default-chart-height)})))

(defn make-medication
  "Create a medication chart for a patient"
  [{:keys [pathom] :as env} patient-identifier {:keys [start-date end-date width height] :as opts}]
  (let [patient (pathom {:disable-auth true}
                        {:pathom/entity {:t_patient/patient_identifier patient-identifier}
                         :pathom/eql    [{:t_patient/medications
                                          [:t_medication/id
                                           :t_medication/date_from
                                           :t_medication/date_to
                                           :t_medication/dose
                                           :t_medication/units
                                           :t_medication/frequency
                                           :t_medication/medication_concept_fk
                                           {:t_medication/medication
                                            [{:info.snomed.Concept/preferredDescription
                                              [:info.snomed.Description/term]}]}]}]})
        chart-medications (medications->chart-data (:t_patient/medications patient))]
    (log/debug "patient for med chart" patient)
    (log/debug "chart medications" chart-medications)
    (when (seq chart-medications)
      (chart/create-medications-chart chart-medications))))

(def supported-charts
  {"edss"       {:spec ::edss-params
                 :f    make-edss}
   "medication" {:spec ::medication-params
                 :f    make-medication}})

(def chart-handler
  "Interceptor for generating graphical patient charts.

   Expected path params:
   - patient-identifier: The patient identifier

   Expected query params:
   - type: The type of chart to generate (e.g., 'edss', 'medication')
   - Additional parameters depending on chart type

   Returns a response with the chart PNG image and appropriate headers."
  {:name ::chart-handler
   :enter
   (fn [{:keys [rsdb pathom] :as ctx}]
     (let [path-params (get-in ctx [:request :path-params])
           params (get-in ctx [:request :query-params])
           patient-identifier (some-> path-params :patient-identifier parse-long)
           {:keys [f spec]} (get supported-charts (:type params))
           {:keys [width height]} params
           env {:rsdb rsdb :pathom pathom}]
       (assoc ctx :response
                  (if (and patient-identifier f spec)
                    (let [conformed-params (s/conform spec params)]
                      (log/debug "Chart request parameters:" params)
                      (if-not (= ::s/invalid conformed-params)
                        (if-let [chart-obj (f env patient-identifier conformed-params)]
                          {:status  200
                           :headers {"Content-Type" "image/png"}
                           :body    (chart/stream-chart chart-obj
                                                        (or (safe-parse-long width) default-chart-width)
                                                        (or (safe-parse-long height) default-chart-height))}
                          {:status 500 :body (str "Failed to generate chart using params:" conformed-params)})
                        {:status 400 :body (str "Invalid parameters for chart type: " (s/explain-str spec params))}))
                    {:status 400 :body "Bad request"}))))})
