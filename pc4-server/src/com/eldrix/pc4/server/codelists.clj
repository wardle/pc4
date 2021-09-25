(ns com.eldrix.pc4.server.codelists
  "Codelists provides functionality to generate a list of codes from different
  specifications.

  There are two broad approaches:

  1. One can generate a canonical set of codes given an input specification, and
  use those as required to test data. This approach is good when you have
  millions of rows of data and lots of checks to perform. Set-up time is longer
  but checks should be quicker. It is possible to generate a crossmap table
  to demonstrate how selection has occurred.

  2. One can test a set of identifiers against a specification. This approach
  is good when a codelist is very large, and fewer checks are needed. Set-up
  time is small but checks may take longer.

  Currently, this namespace supports (1) but it might be good to make the
  concept of a codelist as a first-class 'thing' that can be used for codelist
  membership checks regardless of internal implementation. For (1) it simply
  checks set membership, but for (2), the source concept would be mapped to
  the appropriate code system(s) and the check done based on the rules of each
  code system.

  In many situations both approaches might be necessary. You might generate a
  codelist for documentation purposes but use a different approach to check each
  row of source data."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.util.regex Pattern)))

(s/def ::ecl string?)
(s/def ::atc-code (s/or :pattern #(instance? Pattern %) :string string?))
(s/def ::atc (s/or :atc-codes (s/coll-of ::atc-code) :atc-code ::atc-code))
(s/def ::icd10-code string?)
(s/def ::icd10 (s/or :icd10-codes (s/coll-of ::icd10-code) :icd10-code ::icd10-code))
(s/def ::codelist (s/keys :req-un [(or ::ecl ::atc ::icd10)]))

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
    (throw (ex-info "invalid ATC codes:" (s/explain-data ::atc atc-codes))))
  (->> atc-codes
       (map #(dmd/atc->snomed-ecl dmd %))
       (remove #(= "" %))
       (mapcat #(hermes/expand-ecl-historic hermes %))
       (map :conceptId)
       (into #{})))

(defn expand-icd10 [{:com.eldrix/keys [hermes]} icd10-codes]
  (->> icd10-codes
       (map #(map :referencedComponentId (hermes/reverse-map-range hermes 447562003 %)))
       (map set)
       (map #(hermes/with-historical hermes %))
       (apply set/union)))

(defn expand-codelist
  "Generate a codelist based on the data specified.
  A codelist can be specified using a combination:
  - :ecl   : SNOMED CT expression (expression constraint language)
  - :atc   : A vector of ATC code regexps.
  - :icd10 : A vector of ICD10 code prefixes.

  A codelist will be created from the union of the results of any definitions."
  [{:com.eldrix/keys [hermes dmd] :as system} {:keys [ecl atc icd10] :as codelist}]
  (when-not (s/valid? ::codelist codelist)
    (throw (ex-info "invalid codelist" (s/explain-data ::codelist codelist))))
  (set/union (when ecl (into #{} (map :conceptId (hermes/expand-ecl-historic hermes ecl))))
             (when atc (if (coll? atc) (apply expand-atc system atc)
                                       (expand-atc system atc)))
             (when icd10 (if (coll? icd10) (expand-icd10 system icd10)
                                           (expand-icd10 system [icd10])))))

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

(defn to-icd10
  "Map a collection of concept identifiers to a set of ICD-10 codes."
  [{:com.eldrix/keys [hermes]} concept-ids]
  (->> (hermes/with-historical hermes concept-ids)
       (mapcat #(hermes/get-component-refset-items hermes % 447562003))
       (map :mapTarget)
       (filter identity)
       (into #{})))

(defn is-trade-family?
  "Is the product a type of trade family product?
  We simply use the TF reference set as a check for membership."
  [{:com.eldrix/keys [hermes]} concept-id]
  (hermes/get-component-refset-items hermes concept-id 999000631000001100))

(defn to-atc
  "Map a collection of concept identifiers to a set of ATC codes.
  The UK dm+d via the dmd library supports VTMs, VMPs, AMPs, AMPPs and VMPPs,
  but cannot map from TF concepts. As such, this checks whether the product is
  a TF concept id, and simply uses the VMPs instead."
  [{:com.eldrix/keys [hermes dmd] :as system} concept-ids]
  (->> (hermes/with-historical hermes concept-ids)
       (mapcat (fn [concept-id]
              (if (is-trade-family? system concept-id)
                (distinct (map #(dmd/atc-for-product dmd %) (hermes/get-child-relationships-of-type hermes concept-id snomed/IsA)))
                (vector (dmd/atc-for-product dmd concept-id)))))
       set))

(comment
  (require '[portal.api :as p])
  (p/open)
  (add-tap #'p/submit)
  (require '[com.eldrix.pc4.server.system :as pc4])
  (def system (pc4/init :dev [:pathom/env]))
  (defn ps [id] (:term (hermes/get-preferred-synonym (:com.eldrix/hermes system) id "en-GB")))
  (ps 24700007)

  (map ps (make-codelist system {:inclusions {:icd10 "G35"}}))
  (map ps (make-codelist system {:inclusions {:atc #"N07AA0.*"}
                                 :exclusions {:atc #"N07AA02"}}))

  (tap> (make-codelist system {:inclusions {:atc #"N07AA0.*"}
                               :exclusions {:atc #"N07AA02"}}))

  )