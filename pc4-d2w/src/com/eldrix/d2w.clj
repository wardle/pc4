(ns com.eldrix.d2w
  (:require [odoyle.rules :as o]))

(def rules
  (o/ruleset
    {::list-patients


     ::print-time
     [:what
      [::time ::total tt]
      :then
      (println tt)]}))

;; create session and add rule
(def *session
  (atom (reduce o/add-rule (o/->session) rules)))

(swap! *session
       (fn [session]
         (-> session
             (o/insert ::time ::total 110)
             o/fire-rules)))

(comment
  (println (o/query-all @*session ::print-time))
  (println "Hello World")
  (+ 1 2 )

  )