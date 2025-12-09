(ns pc4.rsdb.nform.impl.forms.timed-walk
  "Timed walk test form implementation."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [pc4.rsdb.nform.impl.form :as form]))

(def ambulatory-aids
  #{"NONE" "ONE_STICK" "ONE_CRUTCH" "TWO_STICKS" "TWO_CRUTCHES"
    "ROLLATOR" "AID_OF_ONE" "AID_OF_TWO"})

(def ambulatory-aid->title
  {"NONE"         "No support"
   "ONE_STICK"    "One stick"
   "ONE_CRUTCH"   "One crutch"
   "TWO_STICKS"   "Two sticks"
   "TWO_CRUTCHES" "Two crutches"
   "ROLLATOR"     "Rollator"
   "AID_OF_ONE"   "Aid of one"
   "AID_OF_TWO"   "Aid of two"})

(s/def ::achieved_distance_metres
  (s/with-gen
    (s/and number? #(< 0 % 1000) #(Double/isFinite %))
    #(gen/double* {:min 1.0 :max 500.0 :infinite? false :NaN? false})))

(s/def ::intended_distance_metres
  (s/with-gen
    (s/and number? #(< 0 % 1000) #(Double/isFinite %))
    #(gen/double* {:min 1.0 :max 500.0 :infinite? false :NaN? false})))

(s/def ::time_seconds
  (s/with-gen
    (s/and number? #(< 0 % 3600) #(Double/isFinite %))
    #(gen/double* {:min 1.0 :max 600.0 :infinite? false :NaN? false})))

(s/def ::ambulatory_aids ambulatory-aids)
(s/def ::functional_electrical_stimulation boolean?)
(s/def ::orthosis boolean?)
(s/def ::notes (s/nilable string?))
(s/def ::number_of_steps (s/nilable (s/int-in 0 10000)))

(defmethod form/spec :timed-walk/v1 [_]
  (s/keys :req-un [::achieved_distance_metres
                   ::intended_distance_metres
                   ::time_seconds
                   ::ambulatory_aids
                   ::functional_electrical_stimulation
                   ::orthosis]
          :opt-un [::notes ::number_of_steps]))

(defmethod form/hydrate :timed-walk/v1 [form]
  (-> form
      (update :functional_electrical_stimulation parse-boolean)
      (update :orthosis parse-boolean)))

(defmethod form/dehydrate :timed-walk/v1 [form]
  (-> form
      (update :functional_electrical_stimulation str)
      (update :orthosis str)))

(defmethod form/summary :timed-walk/v1
  [{:keys [achieved_distance_metres time_seconds number_of_steps ambulatory_aids]}]
  (if (and achieved_distance_metres time_seconds)
    (str achieved_distance_metres "m in " time_seconds "s ("
         (when number_of_steps (str number_of_steps " steps, "))
         (ambulatory-aid->title ambulatory_aids) ")")
    "."))
