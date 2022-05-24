(ns com.eldrix.pc4.dates-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [com.eldrix.pc4.dates :as dates])
  (:import (java.time LocalDateTime ZonedDateTime ZoneId LocalDate)))

(def display-age-examples
  [{:start       (LocalDateTime/of 2020 04 29 19 29 23)
    :end         (LocalDateTime/of 2020 04 29 20 59 45)
    :description "1 hour 30 minutes"
    :expected    "90min"}
   {:start       (ZonedDateTime/of (LocalDateTime/of 2020 03 29 0 30 0)
                                   (ZoneId/of "Europe/London"))
    :end         (ZonedDateTime/of (LocalDateTime/of 2020 03 29 3 0 0)
                                   (ZoneId/of "Europe/London"))
    :description "1 hour 30 minutes, but spanning daylight savings"
    :expected    "90min"}
   {:start       (LocalDateTime/of 2010 04 28 13 45 00)
    :end         (LocalDateTime/of 2010 04 29 15 50 21)
    :description "1 day 2 hours 5 minutes"
    :expected    "26hr"}
   {:start       (LocalDateTime/of 2020 10 31 20 05 52)
    :end         (LocalDateTime/of 2020 11 04 13 12 52)
    :description "3 days 17 hours 7 minutes"
    :expected    "3d"}
   {:start       (LocalDateTime/of 2020 3 29 0 30 0)
    :end         (LocalDateTime/of 2020 4 25 5 32 0)
    :description "27 days 5 hours 2 minutes"
    :expected    "27d"}
   {:start       (LocalDateTime/of 2020 3 29 0 30 0)
    :end         (LocalDateTime/of 2020 4 26 5 32 0)
    :description "28 days 5 hours 2 minutes"
    :expected    "4w"}
   {:start       (LocalDateTime/of 2020 3 29 0 30 0)
    :end         (LocalDateTime/of 2020 4 27 5 32 0)
    :description "29 days 5 hours 2 minutes"
    :expected    "4w 1d"}
   {:start       (LocalDateTime/of 2020 3 29 0 30 0)
    :end         (LocalDateTime/of 2021 3 30 5 30 0)
    :description "1 year 1 day 5 hours"
    :expected    "12m 1d"}
   {:start       (LocalDateTime/of 2020 3 29 0 30 0)
    :end         (LocalDateTime/of 2021 4 6 5 30 0)
    :description "1 year 8 days 5 hours"
    :expected    "12m 8d"}
   {:start       (LocalDateTime/of 2020 3 29 0 30 0)
    :end         (LocalDateTime/of 2021 5 7 5 30 0)
    :description "1 year 39 days 5 hours"
    :expected    "13m 8d"}
   {:start       (LocalDate/of 2010 1 1)
    :end         (LocalDate/of 2014 2 9)
    :description "4 years 39 days"
    :expected    "4y 1m"}
   {:start       (LocalDate/of 2005 3 1)
    :end         (LocalDate/of 2021 7 21)
    :description "16 years 4 months"
    :expected    "16y 4m"}
   {:start       (LocalDate/of 2000 1 1)
    :end         (LocalDate/of 2024 05 1)
    :description "24 years"
    :expected    "24y"}
   {:start       (ZonedDateTime/of (LocalDateTime/of 2000 03 29 0 30 0)
                                   (ZoneId/of "Europe/London"))
    :end         (LocalDate/of 2020 2 1)
    :description "mixed temporal"
    :expected    "19y"}
   {:start       (LocalDate/of 2020 01 01)
    :end         (LocalDate/of 2019 2 1)
    :description "invalid inputs"
    :expected    nil}])

(defn test-display-age
  [{:keys [start end _description expected] :as example}]
  (when (and start end)
    (let [result (dates/age-display start end)]
      (assoc example
        :result result
        :success (= expected result)))))

(deftest display-age
  (let [results (map test-display-age display-age-examples)]
    (doseq [result results]
      (is (= (:result result) (:expected result)) (:description result)))))

(comment
  (map test-display-age display-age-examples)
  (run-tests))