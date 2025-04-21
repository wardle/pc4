(ns pc4.http-server.controllers.patient.charts
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [io.pedestal.http.route :as route]
            [pc4.chart.interface :as chart]
            [pc4.http-server.web :as web]
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

(defn encounter->edss-chart-data
  [{:t_encounter/keys [^LocalDateTime date_time form_edss form_ms_relapse]}]
  {:id          (:t_form_edss/id form_edss)
   :date        (.toLocalDate date_time)
   :edss        (some-> form_edss :t_form_edss/score parse-double)
   :in-relapse? (:t_form_ms_relapse/in_relapse form_ms_relapse)})

(defn fetch-edss-results
  [pathom patient-identifier {:keys [^LocalDate start-date ^LocalDate end-date]}]
  (let [start-date# (when start-date (.atStartOfDay start-date))
        end-date# (when end-date (.atStartOfDay end-date))
        {:t_patient/keys [date_death] :as patient}
        (pathom {:pathom/entity {:t_patient/patient_identifier patient-identifier}
                 :pathom/eql    [:t_patient/date_death
                                 {:t_patient/encounters
                                  [:t_encounter/id :t_encounter/date_time :t_encounter/is_deleted
                                   {:t_encounter/form_edss [:t_form_edss/id :t_form_edss/score]}
                                   {:t_encounter/form_ms_relapse [:t_form_ms_relapse/in_relapse]}]}]})
        results
        (->> (cond->> (:t_patient/encounters patient)
                      start-date# (remove #(.isBefore ^LocalDateTime (:t_encounter/date_time %) start-date#))
                      end-date# (remove #(.isAfter ^LocalDateTime (:t_encounter/date_time %) end-date#)))
             (map encounter->edss-chart-data))]
    (if date_death                                          ;; impute EDSS 10 on date of death, if patient deceased
      (conj results {:date date_death :edss 10})
      results)))

(defn fetch-ms-events
  [pathom patient-identifier]
  (let [patient (pathom {:disable-auth true}
                        {:pathom/entity {:t_patient/patient_identifier patient-identifier}
                         :pathom/eql    [{:t_patient/summary_multiple_sclerosis
                                          [{:t_summary_multiple_sclerosis/events
                                            [:t_ms_event/id :t_ms_event/date :t_ms_event/type :t_ms_event/is_relapse :t_ms_event_type/abbreviation]}]}]})]
    (reduce (fn [acc {:t_ms_event/keys [id date type is_relapse] :t_ms_event_type/keys [abbreviation] :as event}]
              (println event)
              (if is_relapse
                (conj acc {:id id, :date date, :type type, :abbreviation abbreviation})
                acc))
            []
            (:t_summary_multiple_sclerosis/events (:t_patient/summary_multiple_sclerosis patient)))))

(def fake-edss-scores
  [{:id 1 :date (LocalDate/of 2010 3 1) :edss 3.5 :in-relapse? true}
   {:id 2 :date (LocalDate/of 2020 1 1) :edss 1.0}
   {:id 3 :date (LocalDate/of 2024 1 1) :edss 1.5}])

(defn make-edss
  [{:keys [pathom] :as env} patient-identifier {:keys [start-date end-date width height] :as opts}]
  (clojure.pprint/pprint {:env env})
  (clojure.pprint/pprint (fetch-edss-results pathom patient-identifier {}))
  (chart/create-edss-timeline-chart
    {:edss-scores   (fetch-edss-results pathom patient-identifier {})
     :ms-events     (fetch-ms-events pathom patient-identifier)
     :ms-onset-date (LocalDate/of 2010 3 1)
     :msss-data     (rsdb/msss-lookup {:type :roxburgh})
     :start-date    (parse-date start-date)
     :end-date      (parse-date end-date)
     :width         (or (safe-parse-long width) default-chart-width)
     :height        (or (safe-parse-long height) default-chart-height)}))

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


(defn chart-handler
  "Handler function for generating graphical patient charts.
   Parses request parameters, generates the appropriate chart based on type,
   and returns the chart as a PNG image.
   
   Parameters:
   - request: The HTTP request containing chart parameters
   
   Expected request parameters:
   - patient-identifier: From path params in the URL
   - type: The type of chart to generate (e.g., 'edss', 'medication')
   - Additional parameters depending on chart type
   
   Returns a response with the chart PNG image and appropriate headers."
  [{:keys [params path-params env] :as request}]
  (let [patient-identifier (some-> path-params :patient-identifier parse-long)
        {:keys [f spec] :as chart} (get supported-charts (some-> params :type))
        {:keys [width height]} params]
    (if (and patient-identifier chart f spec)
      (let [conformed-params (s/conform spec params)]
        (log/debug "Chart request parameters:" params)
        (if-not (= ::s/invalid conformed-params)
          (if-let [chart (f env patient-identifier conformed-params)] ;; generate the chart
            {:status  200                                   ;; and then stream content
             :headers {"Content-Type" "image/png"}
             :body    (chart/stream-chart chart (or (safe-parse-long width) default-chart-width) (or (safe-parse-long height) default-chart-height))}
            (web/server-error (str "Failed to generate chart using params:" conformed-params)))
          (web/bad-request (str "Invalid parameters for chart type: " (s/explain-str spec params)))))
      (web/bad-request))))