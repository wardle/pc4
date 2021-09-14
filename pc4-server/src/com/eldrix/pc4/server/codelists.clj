(ns com.eldrix.pc4.server.codelists
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes])
  (:import (java.util.regex Pattern)))

(s/def ::ecl string?)
(s/def ::atc-code (s/or :pattern #(instance? Pattern %) :string string?))
(s/def ::atc (s/or :atc-codes (s/coll-of ::atc-code) :atc-code ::atc-code))
(s/def ::codelist (s/keys :req-un [(or ::ecl ::atc)]))
(s/valid? ::codelist {:ecl #"djdjd"})

(defn disjoint?
  "Are sets disjoint, so that no set shares a member with any other set?
  Note this is different to determining the intersection between the sets.
  e.g.
    (clojure.set/intersection #{1 2} #{2 3} #{4 5})  => #{}   ; no intersection
    (disjoint? #{1 2} #{2 3} #{4 5})                 => false ; not disjoint."
  [& sets]
  (apply distinct? (apply concat sets)))

(defn expand-atc
  "Expands ATC codes into a set of identifiers."
  [{:com.eldrix/keys [dmd hermes]} & atc-codes]
  (when-not (s/valid? ::atc atc-codes)
    (throw (ex-info "invalid ATC codes:" (s/explain-data ::atc-codes atc-codes))))
  (->> atc-codes
       (map #(dmd/atc->snomed-ecl dmd %))
       (remove #(= "" %))
       (mapcat #(hermes/expand-ecl-historic hermes %))
       (map :conceptId)
       (into #{})))

(defn expand-codelist
  "Generate a codelist based on the data specified.
  A codelist can be specified using a combination:
  - :info.snomed/ECL : SNOMED CT expression (expression constraint language)
  - :no.whocc/ATC    : A vector of ATC code regexps.

  A codelist will be created from the union of the results of any definitions."
  [{:com.eldrix/keys [hermes dmd] :as system} {:keys [ecl atc] :as codelist}]
  (when-not (s/valid? ::codelist codelist)
    (throw (ex-info "invalid codelist" (s/explain-data ::codelist codelist))))
  (set/union (when ecl (into #{} (map :conceptId (hermes/expand-ecl-historic hermes ecl))))
             (when atc (if (coll? atc) (apply expand-atc system atc)
                                       (expand-atc system atc)))))

(defn make-codelist
  "Make a codelist from the inclusions and exclusions specified.
  The configuration will be treated as inclusions, for convenience, if
  no inclusions are explicitly stated."
  [system {:keys [inclusions exclusions] :as config}]
  (let [incl (when inclusions (expand-codelist system inclusions))
        excl (when exclusions (expand-codelist system exclusions))]
    (cond
      (and inclusions exclusions)
      (set/difference incl excl)
      inclusions
      incl
      (and exclusions (not inclusions))
      (throw (ex-info "missing ':inclusions' clause on codelist definition" config))
      :else (expand-codelist system config))))
