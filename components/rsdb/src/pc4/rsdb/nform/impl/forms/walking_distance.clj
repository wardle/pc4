(ns pc4.rsdb.nform.impl.forms.walking-distance
  "Walking distance form implementation."
  (:require
    [clojure.spec.alpha :as s]
    [pc4.rsdb.nform.impl.form :as form]))

(def ambulation-values
  #{"NOT_RECORDED"
    "FULLY_AMBULATORY"
    "WALK_WITHOUT_AID_OR_REST_300_METRES"
    "WALK_WITHOUT_AID_OR_REST_200_METRES"
    "WALK_WITHOUT_AID_100_METRES"
    "INTERMITTENT_OR_UNILATERAL_CONSTANT_ASSISTANCE_100_METRES"
    "CONSTANT_BILATERAL_ASSISTANCE_WITHOUT_REST_20_METRES"
    "UNABLE_TO_WALK_BEYOND_5_METRES_EVEN_WITH_AID"
    "FEW_STEPS_ONLY_RESTRICTED_TO_WHEELCHAIR"
    "RESTRICTED_BED_CHAIR"
    "DEAD"})

(def ambulatory-aids
  #{"NONE" "ONE_STICK" "ONE_CRUTCH" "TWO_STICKS" "TWO_CRUTCHES"
    "ROLLATOR" "AID_OF_ONE" "AID_OF_TWO"})

(s/def ::ambulation ambulation-values)
(s/def ::ambulatory_aids ambulatory-aids)
(s/def ::functional_electrical_stimulation boolean?)
(s/def ::limited_walking_distance boolean?)
(s/def ::maximum_walking_distance_metres (s/nilable (s/int-in 0 100000)))
(s/def ::orthosis boolean?)

(defmethod form/spec :walking-distance/v1 [_]
  (s/keys :req-un [::ambulation
                   ::ambulatory_aids
                   ::functional_electrical_stimulation
                   ::limited_walking_distance
                   ::orthosis]
          :opt-un [::maximum_walking_distance_metres]))

(defmethod form/hydrate :walking-distance/v1 [form]
  (-> form
      (update :functional_electrical_stimulation parse-boolean)
      (update :limited_walking_distance parse-boolean)
      (update :orthosis parse-boolean)))

(defmethod form/dehydrate :walking-distance/v1 [form]
  (-> form
      (update :functional_electrical_stimulation str)
      (update :limited_walking_distance str)
      (update :orthosis str)))

(defmethod form/summary :walking-distance/v1 [{:keys [ambulation]}]
  (or ambulation "."))
