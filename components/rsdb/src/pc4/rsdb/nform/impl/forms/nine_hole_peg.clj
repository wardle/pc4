(ns pc4.rsdb.nform.impl.forms.nine-hole-peg
  "Nine-hole peg test form implementation."
  (:require
    [clojure.spec.alpha :as s]
    [pc4.rsdb.nform.impl.form :as form]))

(s/def ::time_seconds_left (s/nilable (s/int-in 0 600)))
(s/def ::time_seconds_right (s/nilable (s/int-in 0 600)))

(defmethod form/spec :nine-hole-peg/v1 [_]
  (s/keys :req-un [::time_seconds_left ::time_seconds_right]))

(defmethod form/summary :nine-hole-peg/v1
  [{:keys [time_seconds_left time_seconds_right]}]
  (if (and time_seconds_left time_seconds_right)
    (str "R: " time_seconds_right "s L: " time_seconds_left "s")
    "."))
