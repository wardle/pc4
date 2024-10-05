(ns pc4.fhir.interface-test
  (:require [clojure.test :as test :refer [deftest is]]
            [pc4.fhir.interface :as fhir])
  (:import (java.time LocalDate)))

(def periods
  [{:id :a
    :org.hl7.fhir.Period/end (LocalDate/of 2017 1 1)}
   {:id :b
    :org.hl7.fhir.Period/start (LocalDate/of 2018 1 1)
    :org.hl7.fhir.Period/end (LocalDate/of 2020 1 1)}
   {:id :c
    :org.hl7.fhir.Period/start (LocalDate/of 2017 1 1)
    :org.hl7.fhir.Period/end (LocalDate/of 2020 1 1)}
   {:id :d
    :org.hl7.fhir.Period/end (LocalDate/of 2018 1 1)}
   {:id :e
    :org.hl7.fhir.Period/start (LocalDate/of 2020 1 1)}])

(deftest test-period-sorting
  (is (= (sort fhir/by-period periods)
         (sort fhir/by-period (shuffle periods))
         (sort fhir/by-period (reverse periods))))
  (is (= (mapv :id (sort fhir/by-period periods)) [:e :b :c :d :a])))

(def coll
  [{:org.hl7.fhir.Identifier/system "D"
    :org.hl7.fhir.Identifier/value "X123456"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2022 1 1)}}
   {:org.hl7.fhir.Identifier/system "D"
    :org.hl7.fhir.Identifier/value "D123456"}
   {:org.hl7.fhir.Identifier/system "D"
    :org.hl7.fhir.Identifier/value "D987654"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2024 1 1)}}

   {:org.hl7.fhir.Identifier/system "A"
    :org.hl7.fhir.Identifier/value "A123456"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2022 1 2)}}
   {:org.hl7.fhir.Identifier/system "B"
    :org.hl7.fhir.Identifier/value "B123456"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2021 1 1)}}
   {:org.hl7.fhir.Identifier/system "C"
    :org.hl7.fhir.Identifier/value "C123456"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2023 1 1)}}])

(deftest test-identifiers
  (is (= (mapv :org.hl7.fhir.Identifier/value (sort (fhir/by-periods :org.hl7.fhir.Identifier/period) coll))
         ["D123456" "D987654" "C123456" "A123456" "X123456" "B123456"]))
  (is (= (mapv :org.hl7.fhir.Identifier/value (filter (fhir/valid-by-period? :org.hl7.fhir.Identifier/period) coll))
         ["D123456"]))
  (is (= (->> coll
              (filter (fhir/valid-by-period? :org.hl7.fhir.Identifier/period (LocalDate/of 2022 7 1)))
              (sort (fhir/by-periods :org.hl7.fhir.Identifier/period))
              (mapv :org.hl7.fhir.Identifier/value))
         ["D123456" "D987654" "C123456"]))
  (is  (= "D123456" (:org.hl7.fhir.Identifier/value (fhir/best-identifier "D" coll))))
  (is  (nil? (fhir/best-identifier "A" coll)))
  (is (= "A123456" (:org.hl7.fhir.Identifier/value (fhir/best-identifier "A" (LocalDate/of 2020 1 1) coll)))))

(def ids
  [{:org.hl7.fhir.Identifier/system "A"
    :org.hl7.fhir.Identifier/value "A123456"
    :org.hl7.fhir.Identifier/use "official"}
   {:org.hl7.fhir.Identifier/system "A"
    :org.hl7.fhir.Identifier/value "A987654"
    :org.hl7.fhir.Identifier/use "secondary"}])

(deftest test-identifier-priority-by-use
  (is (= "A123456"
         (:org.hl7.fhir.Identifier/value (fhir/best-identifier "A" ids))
         (:org.hl7.fhir.Identifier/value (fhir/best-identifier "A" (reverse ids))))))

(def names
  [{:org.hl7.fhir.HumanName/use "official"
    :org.hl7.fhir.HumanName/family "A"
    :org.hl7.fhir.HumanName/period {:org.hl7.fhir.Period/end (LocalDate/of 2022 1 1)}}
   {:org.hl7.fhir.HumanName/use "official"
    :org.hl7.fhir.HumanName/family "B"
    :org.hl7.fhir.HumanName/period {:org.hl7.fhir.Period/start (LocalDate/of 2022 1 1)}}
   {:org.hl7.fhir.HumanName/use "maiden"
    :org.hl7.fhir.HumanName/family "C"}])

(deftest test-human-names
  (is (= [false true true] (mapv (fhir/valid-by-period? :org.hl7.fhir.HumanName/period (LocalDate/of 2024 1 1)) names)))
  (is (= [true false true] (mapv (fhir/valid-by-period? :org.hl7.fhir.HumanName/period (LocalDate/of 2021 1 1)) names)))
  (is (= "B" (:org.hl7.fhir.HumanName/family (fhir/best-human-name names))))
  (is (= "A" (:org.hl7.fhir.HumanName/family (fhir/best-human-name (LocalDate/of 2021 12 31) names))))
  (is (= "B" (:org.hl7.fhir.HumanName/family (fhir/best-human-name (LocalDate/of 2022 1 1) names))))
  (is (= "C" (:org.hl7.fhir.HumanName/family (fhir/best-human-name (LocalDate/now) "maiden" names)))))




