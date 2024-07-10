(ns pc4.dates.interface
  (:require [pc4.dates.core :as dates])
  (:import (java.time LocalDate Period)
           (java.time.temporal TemporalAccessor)))

(defn safe-parse-local-date [s]
  (dates/safe-parse-local-date s))

(defn in-range?
  "Is the date in the range specified, or is the range 'current'?
  Handles open ranges if `from` or `to` nil."
  ([^LocalDate from ^LocalDate to]
   (dates/in-range? from to))
  ([^LocalDate from ^LocalDate to ^LocalDate date]
   (dates/in-range? from to date)))

(defn calculate-age
  "Returns a `java.time.Period` representing the age based on the parameters specified."
  ^Period [^LocalDate date-birth & {:keys [^LocalDate date-death is-deceased? ^LocalDate on-date] :as params}]
  (dates/calculate-age date-birth params))

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
  (dates/age-display start end))




