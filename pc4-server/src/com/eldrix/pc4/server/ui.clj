(ns com.eldrix.pc4.server.ui
  (:import (java.time.format DateTimeFormatter)
           (java.time LocalDate)))

(def formatter (DateTimeFormatter/ofPattern "dd MMM yyyy"))

(defn format-date [^LocalDate date]
  (.format formatter date))

(defn box-error-message
  [{:keys [title message]}]
  (when message
    [:div.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.relative.shadow-md {:role "alert"}
     (when title [:span.strong.font-bold.mr-4 title])
     [:span.block.sm:inline message]]))