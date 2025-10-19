(ns pc4.demographic.identifier
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [pc4.nhs-number.interface :as nhs-number]))

(defn- matches-pattern
  "Returns a validator function that checks if value matches the given regexp pattern."
  [pattern]
  (fn [value]
    (boolean (re-matches pattern value))))

(defmulti normalize
  "Normalize an identifier value for the given system.
  Default behavior: strips whitespace and converts to uppercase."
  (fn [system _value] system))

(defmethod normalize :default
  [_system value]
  (some-> value (str/replace #"\s" "") str/upper-case))

(defmulti validate
  "Validate an identifier value for the given system.
  Default behavior: accepts any value (returns true)."
  (fn [system _value] system))

(defmethod validate :default
  [_system _value]
  true)

(defmulti format
  "Format an identifier value for display.
  Default behavior: returns value unchanged."
  (fn [system _value] system))

(defmethod format :default
  [_system value]
  value)

(defmethod normalize "https://fhir.nhs.uk/Id/nhs-number"
  [_system value]
  (nhs-number/normalise value))

(defmethod validate "https://fhir.nhs.uk/Id/nhs-number"
  [_system value]
  (nhs-number/valid? value))

(defmethod format "https://fhir.nhs.uk/Id/nhs-number"
  [_system value]
  (nhs-number/format-nnn value))

(def ^:private crn-validator (matches-pattern #"^[A-Z]\d{6}[A-Z]?$"))

(defmethod validate "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
  [_system value]
  (crn-validator value))
