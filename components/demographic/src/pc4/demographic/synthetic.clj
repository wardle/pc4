(ns pc4.demographic.synthetic
  "Synthetic patient data provider for development and testing.

  Test data structure: vector of patient maps with versioned demographics
  [{:versions {1 {:fhir {...} :db {...}}
               2 {:fhir {...} :db {...}}}}]

  - :fhir contains FHIR Patient structure (what demographic service returns)
  - :db contains expected database state (what tests should verify)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [pc4.demographic.protos :as p]
            [pc4.log.interface :as log])
  (:import (java.time LocalDate)))

(def date-keys
  "Keys that contain date strings to be parsed to LocalDate"
  #{:org.hl7.fhir.Patient/birthDate
    :org.hl7.fhir.Patient/deceased
    :org.hl7.fhir.Period/start
    :org.hl7.fhir.Period/end
    :date_birth
    :date_death
    :date_from
    :date_to})

(defn- parse-dates
  "Parse date strings to LocalDate for known date keys"
  [data]
  (cond
    (map? data)
    (into {} (map (fn [[k v]]
                    [k (if (and (date-keys k) (string? v))
                         (LocalDate/parse v)
                         (parse-dates v))])
                  data))

    (coll? data)
    (mapv parse-dates data)

    :else
    data))

(defn load-synthetic-patients
  "Load synthetic patients with versioned demographics from EDN file.

  Returns: vector of patient maps with structure:
  [{:versions {1 {:fhir {...} :db {...}}
               2 {:fhir {...} :db {...}}}}]

  Dates in both :fhir and :db are parsed from strings to LocalDate."
  []
  (-> (io/resource "demographic/synthetic-patients.edn")
      slurp
      edn/read-string
      parse-dates))

(defn find-patient-by-identifier
  "Find patient data for given system/value at specified version.

  Returns the version-specific data map {:fhir ... :db ...} if found, nil otherwise."
  [patients version system value]
  (some (fn [{:keys [versions]}]
          (when-let [version-data (get versions version)]
            (let [fhir-patient (:fhir version-data)
                  identifiers (:org.hl7.fhir.Patient/identifier fhir-patient)]
              (when (some #(and (= system (:org.hl7.fhir.Identifier/system %))
                                (= value (:org.hl7.fhir.Identifier/value %)))
                          identifiers)
                version-data))))
        patients))

(defn make-synthetic-provider
  "Create a synthetic patient provider that loads test data from EDN file.

  Parameters:
  - :v - version number (default 1)

  Returns: implementation of PatientsByIdentifier that returns :fhir data.

  The provider searches through all patient data and returns FHIR patients
  that match the system/value at the specified version.

  Useful for testing demographic create/update workflows."
  [& {:keys [v] :or {v 1}}]
  (let [patients (load-synthetic-patients)]
    (reify p/PatientsByIdentifier
      (fetch [_ system value]
        (log/debug "synthetic patients-by-identifier" {:system system :value value :version v})
        (when-let [version-data (find-patient-by-identifier patients v system value)]
          [(:fhir version-data)])))))

(defn get-expected-db-data
  "Get expected database state for a patient at a specific version.

  Parameters:
  - system: identifier system (e.g., \"https://fhir.nhs.uk/Id/nhs-number\")
  - value: identifier value (e.g., \"1111111111\")
  - version: version number (default 1)

  Returns: the :db map showing expected database state, or nil if not found.

  Use this in tests to compare actual database state with expected state."
  ([system value]
   (get-expected-db-data system value 1))
  ([system value version]
   (let [patients (load-synthetic-patients)]
     (when-let [version-data (find-patient-by-identifier patients version system value)]
       (:db version-data)))))

(comment
  (def patients (load-synthetic-patients))
  (keys (first patients))
  (keys (get-in patients [0 :versions 1]))

  ;; Create a v1 provider
  (def prov-v1 (make-synthetic-provider :v 1))
  (p/fetch prov-v1 "https://fhir.nhs.uk/Id/nhs-number" "1111111111")

  ;; Create a v2 provider
  (def prov-v2 (make-synthetic-provider :v 2))
  (p/fetch prov-v2 "https://fhir.nhs.uk/Id/nhs-number" "1111111111")

  ;; Get expected DB data
  (get-expected-db-data "https://fhir.nhs.uk/Id/nhs-number" "1111111111" 1)
  (get-expected-db-data "https://fhir.nhs.uk/Id/nhs-number" "1111111111" 2))
