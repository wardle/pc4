(ns pc4.fhir.interface-test
  (:require [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [pc4.fhir.interface :as fhir])
  (:import (java.time LocalDate)))

(def periods
  [{:id                      :a
    :org.hl7.fhir.Period/end (LocalDate/of 2017 1 1)}
   {:id                        :b
    :org.hl7.fhir.Period/start (LocalDate/of 2018 1 1)
    :org.hl7.fhir.Period/end   (LocalDate/of 2020 1 1)}
   {:id                        :c
    :org.hl7.fhir.Period/start (LocalDate/of 2017 1 1)
    :org.hl7.fhir.Period/end   (LocalDate/of 2020 1 1)}
   {:id                      :d
    :org.hl7.fhir.Period/end (LocalDate/of 2018 1 1)}
   {:id                        :e
    :org.hl7.fhir.Period/start (LocalDate/of 2020 1 1)}])

(deftest test-period-sorting
  (is (= (sort fhir/by-period periods)
         (sort fhir/by-period (shuffle periods))
         (sort fhir/by-period (reverse periods))))
  (is (= (mapv :id (sort fhir/by-period periods)) [:e :b :c :d :a])))

(def identifiers-1
  [{:org.hl7.fhir.Identifier/system "D"
    :org.hl7.fhir.Identifier/value  "X123456"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2022 1 1)}}
   {:org.hl7.fhir.Identifier/system "D"
    :org.hl7.fhir.Identifier/value  "D123456"}
   {:org.hl7.fhir.Identifier/system "D"
    :org.hl7.fhir.Identifier/value  "D987654"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2024 1 1)}}

   {:org.hl7.fhir.Identifier/system "A"
    :org.hl7.fhir.Identifier/value  "A123456"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2022 1 2)}}
   {:org.hl7.fhir.Identifier/system "B"
    :org.hl7.fhir.Identifier/value  "B123456"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2021 1 1)}}
   {:org.hl7.fhir.Identifier/system "C"
    :org.hl7.fhir.Identifier/value  "C123456"
    :org.hl7.fhir.Identifier/period {:org.hl7.fhir.Period/end (LocalDate/of 2023 1 1)}}])

(deftest test-identifiers
  (is (= (mapv :org.hl7.fhir.Identifier/value (sort (fhir/by-periods :org.hl7.fhir.Identifier/period) identifiers-1))
         ["D123456" "D987654" "C123456" "A123456" "X123456" "B123456"]))
  (is (= (mapv :org.hl7.fhir.Identifier/value (filter (fhir/valid-by-period? :org.hl7.fhir.Identifier/period) identifiers-1))
         ["D123456"]))
  (is (= (->> identifiers-1
              (filter (fhir/valid-by-period? :org.hl7.fhir.Identifier/period (LocalDate/of 2022 7 1)))
              (sort (fhir/by-periods :org.hl7.fhir.Identifier/period))
              (mapv :org.hl7.fhir.Identifier/value))
         ["D123456" "D987654" "C123456"]))
  (is (= "D123456" (:org.hl7.fhir.Identifier/value (fhir/best-identifier "D" identifiers-1))))
  (is (nil? (fhir/best-identifier "A" identifiers-1)))
  (is (= "A123456" (:org.hl7.fhir.Identifier/value (fhir/best-identifier "A" (LocalDate/of 2020 1 1) identifiers-1)))))

(def identifiers-2
  [{:org.hl7.fhir.Identifier/system "A"
    :org.hl7.fhir.Identifier/value  "A123456"
    :org.hl7.fhir.Identifier/use    "official"}
   {:org.hl7.fhir.Identifier/system "A"
    :org.hl7.fhir.Identifier/value  "A987654"
    :org.hl7.fhir.Identifier/use    "secondary"}])

(deftest test-identifier-priority-by-use
  (is (= "A123456"
         (:org.hl7.fhir.Identifier/value (fhir/best-identifier "A" identifiers-2))
         (:org.hl7.fhir.Identifier/value (fhir/best-identifier "A" (reverse identifiers-2))))))

(def names-1
  [{:org.hl7.fhir.HumanName/use    "official"
    :org.hl7.fhir.HumanName/family "A"
    :org.hl7.fhir.HumanName/period {:org.hl7.fhir.Period/end (LocalDate/of 2022 1 1)}}
   {:org.hl7.fhir.HumanName/use    "official"
    :org.hl7.fhir.HumanName/family "B"
    :org.hl7.fhir.HumanName/period {:org.hl7.fhir.Period/start (LocalDate/of 2022 1 1)}}
   {:org.hl7.fhir.HumanName/use    "maiden"
    :org.hl7.fhir.HumanName/family "C"}])

(deftest test-human-names
  (is (= [false true true] (mapv (fhir/valid-by-period? :org.hl7.fhir.HumanName/period (LocalDate/of 2024 1 1)) names-1)))
  (is (= [true false true] (mapv (fhir/valid-by-period? :org.hl7.fhir.HumanName/period (LocalDate/of 2021 1 1)) names-1)))
  (is (= "B" (:org.hl7.fhir.HumanName/family (fhir/best-human-name names-1))))
  (is (= "A" (:org.hl7.fhir.HumanName/family (fhir/best-human-name (LocalDate/of 2021 12 31) names-1))))
  (is (= "B" (:org.hl7.fhir.HumanName/family (fhir/best-human-name (LocalDate/of 2022 1 1) names-1))))
  (is (= "C" (:org.hl7.fhir.HumanName/family (fhir/best-human-name (LocalDate/now) "maiden" names-1)))))

(def contact-points-1
  [{:org.hl7.fhir.ContactPoint/system "email"
    :org.hl7.fhir.ContactPoint/value  "A"
    :org.hl7.fhir.ContactPoint/rank   2
    :org.hl7.fhir.ContactPoint/period {:org.hl7.fhir.Period/start (LocalDate/of 2022 1 1)}}
   {:org.hl7.fhir.ContactPoint/system "email"
    :org.hl7.fhir.ContactPoint/value  "B"
    :org.hl7.fhir.ContactPoint/rank   1}
   {:org.hl7.fhir.ContactPoint/system "email"
    :org.hl7.fhir.ContactPoint/value  "C"
    :org.hl7.fhir.ContactPoint/rank   2
    :org.hl7.fhir.ContactPoint/period {:org.hl7.fhir.Period/end (LocalDate/of 2022 1 1)}}
   {:org.hl7.fhir.ContactPoint/system "email"
    :org.hl7.fhir.ContactPoint/value  "D"
    :org.hl7.fhir.ContactPoint/rank   2
    :org.hl7.fhir.ContactPoint/period {:org.hl7.fhir.Period/end (LocalDate/of 2021 1 1)}}])

(deftest test-contact-points
  (is (= "BA" (str/join (map :org.hl7.fhir.ContactPoint/value (fhir/valid-contact-points (LocalDate/of 2024 1 1) contact-points-1)))))
  (is (= "BC" (str/join (map :org.hl7.fhir.ContactPoint/value (fhir/valid-contact-points (LocalDate/of 2021 7 1) contact-points-1)))))
  (is (= "BA" (str/join (map :org.hl7.fhir.ContactPoint/value (fhir/valid-contact-points (LocalDate/of 2022 1 1) contact-points-1)))))
  (is (= "BACD"
         (str/join (map :org.hl7.fhir.ContactPoint/value (sort fhir/by-contact-point-rank-and-period contact-points-1)))
         (str/join (map :org.hl7.fhir.ContactPoint/value (sort fhir/by-contact-point-rank-and-period (shuffle contact-points-1))))))
  (is (= "B"
         (:org.hl7.fhir.ContactPoint/value (fhir/best-contact-point "email" contact-points-1))
         (:org.hl7.fhir.ContactPoint/value (fhir/best-contact-point "email" (shuffle contact-points-1)))))
  (is (empty? (fhir/best-contact-point "telephone" contact-points-1)))
  (is (= "E" (:org.hl7.fhir.ContactPoint/value (fhir/best-contact-point "telephone"
                                                                        (conj contact-points-1 {:org.hl7.fhir.ContactPoint/system "telephone"
                                                                                                :org.hl7.fhir.ContactPoint/value  "E"}))))))

(def address-test-data
  [{:addresses [{:line "home" :use "home"}
                {:line "work" :use "work"}
                {:line "old" :use "old"}]
    :tests     [{:description "Default use preference should select home over work and old"
                 :expected    "home"}]}
   {:addresses [{:line "work" :use "work"}
                {:line "old" :use "old"}]
    :tests     [{:description "By default, should prefer work over old when no home present"
                 :expected    "work"}]}
   {:addresses [{:line "home-current" :use "home" :from "2022-01-01"}
                {:line "home-expired" :use "home" :to "2022-01-01"}
                {:line "work-always" :use "work"}]
    :tests     [{:description "Should select current home address based on period"
                 :expected    "home-current"}
                {:description "Should select historically valid home address for past date"
                 :on          "2021-01-01"
                 :expected    "home-expired"}
                {:description "Should prefer home address with open start over work"
                 :on          "2020-01-01"
                 :expected    "home-expired"}
                {:description "Should return home with open start when valid on historical date"
                 :on          "2010-01-01"
                 :expected    "home-expired"}]}
   {:addresses [{:line "no-use"}]
    :tests     [{:description "Should select address without use field when only option"
                 :expected    "no-use"}]}
   {:addresses [{:line "no-use"}
                {:line "home-with-use" :use "home"}]
    :tests     [{:description "Should prefer home use over no use field"
                 :expected    "home-with-use"}]}
   {:addresses [{:line "home-old-1" :use "home" :from "2020-01-01" :to "2021-01-01"}
                {:line "home-old-2" :use "home" :from "2019-01-01" :to "2020-01-01"}
                {:line "work-always" :use "work"}]
    :tests     [{:description "Should return work when all home addresses have expired"
                 :on          "2024-01-01"
                 :expected    "work-always"}
                {:description "Should return work before all home addresses are valid"
                 :on          "2018-01-01"
                 :expected    "work-always"}]}
   {:addresses [{:line "home-expired" :use "home" :from "2019-01-01" :to "2020-01-01"}
                {:line "work-expired" :use "work" :from "2018-01-01" :to "2019-01-01"}]
    :tests     [{:description "Should return nil when all addresses have expired"
                 :on          "2024-01-01"
                 :expected    nil}]}
   {:addresses []
    :tests     [{:description "Should return nil for empty address collection"
                 :expected    nil}]}
   {:addresses [{:line "home" :use "home"}
                {:line "work" :use "work"}
                {:line "billing" :use "billing"}]
    :tests     [{:description "Priority :home-only should return only home addresses"
                 :priority    :home-only
                 :expected    "home"}]}
   {:addresses [{:line "work" :use "work"}
                {:line "billing" :use "billing"}]
    :tests     [{:description "Priority :home-only should return nil when no home address"
                 :priority    :home-only
                 :expected    nil}]}
   {:addresses [{:line "temp" :use "temp" :from "2024-01-15"}
                {:line "billing" :use "billing"}
                {:line "home" :use "home"}]
    :tests     [{:description "Priority :correspondence should prefer temp within 3 months"
                 :priority    :correspondence
                 :on          "2024-02-01"
                 :expected    "temp"}
                {:description "Priority :correspondence should prefer billing over home when no recent temp"
                 :priority    :correspondence
                 :on          "2024-06-01"
                 :expected    "billing"}]}
   {:addresses [{:line "temp" :use "temp" :from "2024-01-15"}
                {:line "home" :use "home"}]
    :tests     [{:description "Priority :correspondence should use home when no billing or recent temp"
                 :priority    :correspondence
                 :on          "2024-06-01"
                 :expected    "home"}]}])

(defn- make-address [{:keys [line use from to]}]
  (cond-> {:org.hl7.fhir.Address/line       [line]
           :org.hl7.fhir.Address/postalCode "CF14 4XW"}
    use (assoc :org.hl7.fhir.Address/use use)
    (or from to) (assoc :org.hl7.fhir.Address/period
                        (cond-> {}
                          from (assoc :org.hl7.fhir.Period/start (LocalDate/parse from))
                          to (assoc :org.hl7.fhir.Period/end (LocalDate/parse to))))))

(deftest test-addresses
  (doseq [{:keys [addresses tests]} address-test-data]
    (let [addrs (shuffle (map make-address addresses))]
      (doseq [{:keys [description on expected priority]} tests]
        (let [opts (cond-> {}
                     on (assoc :on (LocalDate/parse on))
                     priority (assoc :priority priority))
              result (fhir/best-address opts addrs)]
          (is (= expected (some-> result :org.hl7.fhir.Address/line first))
              description))))))


