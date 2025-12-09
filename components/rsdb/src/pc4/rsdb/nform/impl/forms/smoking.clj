(ns pc4.rsdb.nform.impl.forms.smoking
  "Smoking history form implementation."
  (:require
    [clojure.spec.alpha :as s]
    [pc4.rsdb.nform.impl.form :as form]))

(def smoking-statuses
  #{"NEVER_SMOKED" "CURRENT_SMOKER" "EX_SMOKER"})

(def status->display
  {"NEVER_SMOKED"   "Never smoked"
   "CURRENT_SMOKER" "Current smoker"
   "EX_SMOKER"      "Ex-smoker"})

(s/def ::status smoking-statuses)
(s/def ::current_cigarettes_per_day (s/int-in 0 201))
(s/def ::duration_years (s/nilable (s/int-in 0 101)))
(s/def ::previous_cigarettes_per_day (s/nilable (s/int-in 0 201)))
(s/def ::previous_duration_years (s/nilable (s/int-in 0 101)))
(s/def ::year_gave_up (s/nilable (s/int-in 1900 2101)))

(defmethod form/spec :smoking-history/v1 [_]
  (s/keys :req-un [::status ::current_cigarettes_per_day]
          :opt-un [::duration_years
                   ::previous_cigarettes_per_day
                   ::previous_duration_years
                   ::year_gave_up]))

(defmethod form/summary :smoking-history/v1 [{:keys [status current_cigarettes_per_day]}]
  (if-let [display (status->display status)]
    (if (and (= status "CURRENT_SMOKER") current_cigarettes_per_day)
      (str display " (" current_cigarettes_per_day "/day)")
      display)
    "."))
