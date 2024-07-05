(ns pc4.nhs-number.interface
  (:require [com.eldrix.nhsnumber :as nnn]))

(defn valid?
  "Is the given string a valid NHS number?"
  [s]
  (nnn/valid? s))

(defn format-nnn [s]
  (nnn/format-nnn s))

(defn normalise [s]
  (nnn/normalise s))
