(ns com.eldrix.pc4.server.rsdb.projects
  (:import (java.time LocalDate)))

(defn active?
  "Is this project active?"
  ([project] (active? project (LocalDate/now)))
  ([{:t_project/keys [^LocalDate date_to ^LocalDate date_from]} ^LocalDate date]
   (and (or (nil? date_from)
            (.isEqual date date_from)
            (.isAfter date date_from))
        (or (nil? date_to)
            (.isBefore date date_to)))))

(comment
  )