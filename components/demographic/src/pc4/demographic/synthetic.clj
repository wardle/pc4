(ns pc4.demographic.synthetic
  "Synthetic patient data provider for development and testing"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [pc4.demographic.protos :as p]
            [pc4.log.interface :as log])
  (:import (java.time LocalDate)))

(defn- parse-date-string
  "Parse ISO date string to LocalDate"
  [s]
  (when (string? s)
    (LocalDate/parse s)))

(defn- parse-patient-dates
  "Walk patient data and parse birthDate strings to LocalDate"
  [patient]
  (cond-> patient
    (:org.hl7.fhir.Patient/birthDate patient)
    (update :org.hl7.fhir.Patient/birthDate parse-date-string)))

(defn load-synthetic-patients
  "Load synthetic patients from EDN file and parse dates"
  []
  (with-open [r (io/reader (io/resource "demographic/synthetic-patients.edn"))]
    (mapv parse-patient-dates (edn/read (java.io.PushbackReader. r)))))

(defn find-patient-by-identifier
  "Find patients matching system and value in patient list"
  [patients system value]
  (seq (filter
         (fn [patient]
           (some #(and (= system (:org.hl7.fhir.Identifier/system %))
                       (= value (:org.hl7.fhir.Identifier/value %)))
                 (:org.hl7.fhir.Patient/identifier patient)))
         patients)))

(defn make-synthetic-provider
  "Create a synthetic patient provider that loads test data from EDN file.
  Useful for testing and development."
  []
  (reify p/PatientsByIdentifier
    (fetch [_ system value]
      (log/debug "fake patients-by-identifier" {:system system :value value})
      (find-patient-by-identifier (load-synthetic-patients) system value))))