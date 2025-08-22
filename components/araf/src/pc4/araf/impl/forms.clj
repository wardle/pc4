(ns pc4.araf.impl.forms
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import [java.time LocalDate]))

(defn load-forms-config
  "Loads and processes forms.edn, returning forms grouped by araf-type with latest first."
  []
  (let [forms (edn/read-string (slurp (io/resource "araf/forms.edn")))]
    (->> (map #(update % :date (fn [date-str] (LocalDate/parse date-str))) forms)
         (sort-by :date #(compare %2 %1))
         (group-by :araf-type))))

(defn form-config
  "Returns form configuration for the given parameters.
   - araf-type: type of ARAF form e.g. :valproate-female
   - version: version string e.g. \"v5.3\" (optional, defaults to latest)"
  ([araf-type]
   (when araf-type
     (-> (load-forms-config)
         (get araf-type)
         first)))
  ([araf-type version]
   (when (and araf-type (not (str/blank? version)))
     (->> (load-forms-config)
          (get araf-type)
          (filter #(String/.equalsIgnoreCase version (:version %)))
          first))))

(comment
  (form-config :valproate/female-not-applicable)
  (form-config :valproate/female))
