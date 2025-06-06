(ns pc4.nhs-number.interface
  (:require
   [clojure.spec.gen.alpha :as gen]
   [com.eldrix.nhsnumber :as nnn]))

(defn valid?
  "Is the given string a valid NHS number?"
  [s]
  (nnn/valid? s))

(defn format-nnn
  ([s] (nnn/format-nnn s))
  ([s sep] (nnn/format-nnn s sep)))

(defn normalise
  ([s]
   (nnn/normalise s))
  ([s mode]
   (nnn/normalise s mode)))

(defn gen-nhs-number
  "Return a clojure test.check generator for synthetic NHS numbers."
  ([]
   (gen-nhs-number 9))
  ([prefix]
   (gen/fmap (fn [i]
               (nnn/random i))
             (gen/return prefix))))

(comment
  (gen/sample (gen-nhs-number)))
