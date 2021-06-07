(ns com.eldrix.pc4.server.dates
  (:require [cognitect.transit :as transit])
  (:import (java.time LocalDate LocalDateTime ZonedDateTime)
           (java.time.format DateTimeFormatter)))


(defn in-range?
  "Is the date in the range specified, or is the range 'current'?
  Handles open ranges if `from` or `to` nil."
  ([^LocalDate from ^LocalDate to]
   (in-range? from to nil))
  ([^LocalDate from ^LocalDate to ^LocalDate date]
   (let [date' (or date (LocalDate/now))]
     (and (or (nil? from) (not (.isBefore date' from)))
          (or (nil? to) (.isBefore date' to))))))

(def zoned-date-time-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSXX"))

(def transit-writers
  {LocalDateTime
   (transit/write-handler (constantly "LocalDateTime")
                          (fn [^LocalDateTime date]
                            (.format date DateTimeFormatter/ISO_DATE_TIME)))

   ZonedDateTime
   (transit/write-handler (constantly "ZonedDateTime")
                          (fn [^ZonedDateTime date]
                            (.format date zoned-date-time-formatter)))

   LocalDate
   (transit/write-handler (constantly "LocalDate")
                          (fn [^LocalDate date]
                            (.format date DateTimeFormatter/ISO_LOCAL_DATE)))})

(def transit-readers
  {"LocalDateTime" (transit/read-handler (fn [^String s]
                                           (LocalDateTime/parse s)))
   "ZonedDateTime" (transit/read-handler (fn [^String s]
                                           (ZonedDateTime/parse s zoned-date-time-formatter)))
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