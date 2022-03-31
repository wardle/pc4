(ns com.eldrix.pc4.server.dates
  (:require [cognitect.transit :as transit])
  (:import (java.time LocalDate LocalDateTime ZonedDateTime Period)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit TemporalAccessor)))

(defn in-range?
  "Is the date in the range specified, or is the range 'current'?
  Handles open ranges if `from` or `to` nil."
  ([^LocalDate from ^LocalDate to]
   (in-range? from to nil))
  ([^LocalDate from ^LocalDate to ^LocalDate date]
   (let [date' (or date (LocalDate/now))]
     (and (or (nil? from) (not (.isBefore date' from)))
          (or (nil? to) (.isBefore date' to))))))


(defn ^Period calculate-age
  "Calculate the age based on the parameters specified."
  [^LocalDate date-birth & {:keys [^LocalDate date-death is-deceased? ^LocalDate on-date]}]
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
      (let [days (.between (ChronoUnit/DAYS) start end)
            hours? (and (.isSupportedBy (ChronoUnit/HOURS) start)
                        (.isSupportedBy (ChronoUnit/HOURS) end))
            hours (when hours? (.between (ChronoUnit/HOURS) start end))
            minutes? (and (.isSupportedBy (ChronoUnit/MINUTES) start)
                          (.isSupportedBy (ChronoUnit/MINUTES) end))
            minutes (when minutes? (.between (ChronoUnit/MINUTES) start end))]
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

(def transit-writers
  {LocalDateTime
   (transit/write-handler (constantly "LocalDateTime")
                          (fn [^LocalDateTime date]
                            (.format date DateTimeFormatter/ISO_LOCAL_DATE_TIME)))

   ZonedDateTime
   (transit/write-handler (constantly "ZonedDateTime")
                          (fn [^ZonedDateTime date]
                            (.format date DateTimeFormatter/ISO_ZONED_DATE_TIME)))

   LocalDate
   (transit/write-handler (constantly "LocalDate")
                          (fn [^LocalDate date]
                            (.format date DateTimeFormatter/ISO_LOCAL_DATE)))})

(def transit-readers
  {"LocalDateTime" (transit/read-handler (fn [^String s]
                                           (LocalDateTime/parse s)))
   "ZonedDateTime" (transit/read-handler (fn [^String s]
                                           (ZonedDateTime/parse s)))
   "LocalDate"     (transit/read-handler (fn [^String s]
                                           (LocalDate/parse s)))})

(comment

  (def out (java.io.ByteArrayOutputStream. 2000))
  (def w (transit/writer out :json {:handlers transit-writers}))
  (transit/write w (LocalDate/now))
  (.toString out)
  (def in (java.io.ByteArrayInputStream. (.toByteArray out)))
  (def r (transit/reader in :json {:handlers transit-readers}))
  (def x (transit/read r))
  (print x))



