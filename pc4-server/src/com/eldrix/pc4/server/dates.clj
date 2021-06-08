(ns com.eldrix.pc4.server.dates
  (:require [cognitect.transit :as transit])
  (:import (java.time LocalDate LocalDateTime ZonedDateTime Period ZoneId)
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
  limited to days but a LocalDateTime or ZonedDateTime, to minutes."
  [^TemporalAccessor start ^TemporalAccessor end]
  (let [period (Period/between (LocalDate/from start) (LocalDate/from end))
        years (.getYears period)
        months (.getMonths period)]
    (cond
      (.isNegative period)
      nil

      (>= years 18)
      (str years "y")

      (>= years 2)
      (str years "y " months "m")

      (>= years 1)
      (str (+ (* 12 years) months) "m " (.getDays period) "d")

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
          (str hours "hrs")

          (not minutes?)
          "<2hr"

          :else
          (str minutes "min"))))))

(comment
  (age-display (LocalDate/of 1970 01 01) (LocalDate/now))
  (age-display (java.time.LocalDate/of 2021 6 7) (java.time.LocalDate/now))
  (age-display (java.time.LocalDateTime/of 1902 3 12 21 18 16) (java.time.LocalDateTime/now))
  (.between (ChronoUnit/HOURS) (java.time.LocalDateTime/of 2021 06 07 17 10 16) (java.time.LocalDateTime/now))
  (.between (ChronoUnit/HOURS) (java.time.LocalDate/of 2021 06 06) (java.time.LocalDate/now))

  )


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
  (print x)
  )

