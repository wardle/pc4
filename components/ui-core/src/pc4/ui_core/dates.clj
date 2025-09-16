(ns pc4.ui-core.dates
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)))

(defn format-date
  "Format a LocalDate in a human-readable format (dd-MMM-yyyy)."
  [^LocalDate date]
  (when date
    (.format date (DateTimeFormatter/ofPattern "dd-MMM-yyyy"))))

(defn format-date-time
  "Format a LocalDateTime in a human-readable format (dd-MMM-yyyy HH:mm)."
  [date-time]
  (when date-time
    (.format date-time (DateTimeFormatter/ofPattern "dd-MMM-yyyy HH:mm"))))

(defn day-of-week
  "Returns a localised day of week (e.g. \"Thu\" for a given TemporalAccessor."
  [t]
  (when t (DateTimeFormatter/.format (DateTimeFormatter/ofPattern "E") t)))
