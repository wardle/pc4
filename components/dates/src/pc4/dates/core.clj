(ns pc4.dates.core
  (:require [clojure.string :as str])
  (:import (java.time LocalDate Period)
           (java.time.format  DateTimeParseException)
           (java.time.temporal ChronoUnit TemporalAccessor)))

(defn safe-parse-local-date [s]
  (when-not (str/blank? s)
    (try (LocalDate/parse s)
         (catch DateTimeParseException _))))

(defn in-range?
  "Is the date in the range specified, or is the range 'current'?
  Handles open ranges if `from` or `to` nil."
  ([^LocalDate from ^LocalDate to]
   (in-range? from to nil))
  ([^LocalDate from ^LocalDate to ^LocalDate date]
   (let [date' (or date (LocalDate/now))]
     (and (or (nil? from) (not (.isBefore date' from)))
          (or (nil? to) (.isBefore date' to))))))

(defn calculate-age
  "Returns a `java.time.Period` representing the age based on the parameters specified."
  ^Period [^LocalDate date-birth & {:keys [^LocalDate date-death is-deceased? ^LocalDate on-date]}]
  (when date-birth
    (let [on-date' ^LocalDate (or on-date (LocalDate/now))
          deceased? (or is-deceased? (and date-death (or (.isEqual on-date' date-death) (.isAfter on-date' date-death))))]
      (when-not deceased?
        (Period/between date-birth on-date')))))

(defn age-display
  "Displays a chronological age to the NHS CUI age standard:
  - < 2 hours   - minutes
  - < 2 days    - hours
  - < 4 weeks   - days
  - < 1 year    - weeks and days
  - < 2 years   - months and days
  - < 18 years  - years and months
  - >= 18 years - years.

  The minimum unit is determined by the class of `start`. A LocalDate will be
  limited to days but a LocalDateTime or ZonedDateTime, to minutes.

  The display standard is per the NHS' now deprecated standard ISB-1505,
  developed as part of Connecting for Health (CfH).
  See https://webarchive.nationalarchives.gov.uk/20150107150145/http://www.isb.nhs.uk/documents/isb-1505/dscn-09-2010/
  Unfortunately the examples in that DSCN have an error and pluralize the 'hours'."
  [^TemporalAccessor start ^TemporalAccessor end]
  (let [period (Period/between (LocalDate/from start) (LocalDate/from end))
        years (.getYears period)
        months (.getMonths period)]
    (cond
      (>= years 18)
      (str years "y")

      (>= years 2)
      (str years "y " months "m")

      (>= years 1)
      (str (+ (* 12 years) months) "m " (.getDays period) "d")

      (.isNegative period)
      nil

      :else                                                 ;; special handling of infants
      (let [days (.between ChronoUnit/DAYS start end)
            hours? (and (.isSupportedBy ChronoUnit/HOURS start)
                        (.isSupportedBy ChronoUnit/HOURS end))
            hours (when hours? (.between ChronoUnit/HOURS start end))
            minutes? (and (.isSupportedBy ChronoUnit/MINUTES start)
                          (.isSupportedBy ChronoUnit/MINUTES end))
            minutes (when minutes? (.between ChronoUnit/MINUTES start end))]
        (cond
          (>= days 28)
          (str (int (/ days 7)) "w"
               (let [d (mod days 7)] (when-not (= 0 d) (str " " d "d"))))

          (or (>= days 2) (and (not hours?) (>= days 1)))
          (str days "d")

          (not hours?)
          "<1d"

          (>= hours 2)
          (str hours "hr")

          (not minutes?)
          "<2hr"

          :else
          (str minutes "min"))))))




