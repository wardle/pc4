(ns com.eldrix.pc4.commons.dates
  (:require [cognitect.transit :as transit])
  (:import [goog.date Date DateTime]))

(def months-en
  {0  "Jan"
   1  "Feb"
   2  "Mar"
   3  "Apr"
   4  "May"
   5  "Jun"
   6  "Jul"
   7  "Aug"
   8  "Sep"
   9  "Oct"
   10 "Nov"
   11 "Dec"})

(defn format-date [^Date date]
  (when date (str (.getDate date)
                  "-"
                  (get months-en (.getMonth date))
                  "-"
                  (.getYear date))))

(def transit-writers
  {goog.date.Date
   (transit/write-handler (constantly "LocalDate")
                          (fn [^goog.date.Date d] (.toIsoString d)))
   goog.date.DateTime
   (transit/write-handler (constantly "LocalDateTime")
                          (fn [^goog.date.DateTime dt] (.toIsoString dt)))})

(def transit-readers
  {"LocalDate"     (transit/read-handler #(Date/fromIsoString %))
   "LocalDateTime" (transit/read-handler #(DateTime/fromIsoString %))})

(comment
  (js/alert "hi there")
  (js/console.log "hello")
  (def w (transit/writer :json {:handlers transit-writers}))
  (transit/write w (js/Date))
  (transit/write w (new Date))
  (transit/write w (new DateTime))
  (def r (transit/reader :json {:handlers transit-readers}))
  (transit/read r (transit/write w (js/Date)))
  (transit/write w (Date.))
  (.equals (Date.) (transit/read r (transit/write w (Date.))))
  (let [d (DateTime.)]
    (.equals d (transit/read r (transit/write w d))))
  (DateTime.)
  (transit/read r (transit/write w (DateTime.)))
  (def d (transit/read r "[\"~#LocalDate\",\"2021-06-07\"]"))
  (println d)
  (transit/write w d)
  (.equals (Date/fromIsoString "20200101") (Date/fromIsoString "2020-01-01"))
  )