(ns pc4.lemtrada.spec
  (:require [clojure.spec.alpha :as s]
            [pc4.rsdb.interface :as rsdb]))

(s/def ::codelists some?)
(s/def ::hermes some?)
(s/def ::clods some?)
(s/def ::deprivare some?)
(s/def ::dmd some?)
(s/def ::pathom fn?)
(s/def ::cavpms any?)
(s/def ::rsdb rsdb/valid-service?)
(s/def ::env (s/keys :req-un [::cavpms ::codelists ::hermes ::clods ::deprivare ::dmd ::pathom ::rsdb]))
