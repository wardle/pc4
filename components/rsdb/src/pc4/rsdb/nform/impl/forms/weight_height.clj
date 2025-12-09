(ns pc4.rsdb.nform.impl.forms.weight-height
  "Weight and height form implementation."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [pc4.rsdb.nform.impl.form :as form]))

;; Weight in kg - realistic range 0.5-500kg covers premature babies to extreme obesity
(s/def ::weight_kilogram
  (s/nilable (s/with-gen
               (s/and number? #(< 0 % 1000) #(Double/isFinite %))
               #(gen/double* {:min 0.5 :max 500.0 :infinite? false :NaN? false}))))

;; Height in metres - realistic range 0.3-2.5m covers newborns to tallest humans
(s/def ::height_metres
  (s/nilable (s/with-gen
               (s/and number? #(< 0 % 10) #(Double/isFinite %))
               #(gen/double* {:min 0.3 :max 2.5 :infinite? false :NaN? false}))))

(defmethod form/spec :weight-height/v1 [_]
  (s/keys :opt-un [::weight_kilogram ::height_metres]))

(defmethod form/summary :weight-height/v1 [{:keys [weight_kilogram height_metres]}]
  (cond
    (and weight_kilogram height_metres)
    (str "Wt: " weight_kilogram " kg, Ht: " height_metres " m")

    weight_kilogram
    (str "Wt: " weight_kilogram " kg")

    height_metres
    (str "Ht: " height_metres " m")

    :else
    "."))
