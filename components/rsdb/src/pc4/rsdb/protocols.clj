(ns pc4.rsdb.protocols
  (:require [clojure.spec.alpha :as s]))

(s/def ::system string?)
(s/def ::code string?)
(s/def ::id (s/tuple ::system ::code))

(s/def ::params
  (s/keys :opt-un [:s :id]))

(defprotocol PatientSearch
  (search [this params]
    "Performs a search for a patient using the parameters specified."))
