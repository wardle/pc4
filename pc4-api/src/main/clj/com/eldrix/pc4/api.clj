(ns com.eldrix.pc4.api
  (:require
    [com.eldrix.hermes.core :as hermes]
    [com.eldrix.hermes.snomed :as snomed])
  (:import (com.eldrix.hermes.snomed ExtendedConcept)))



(defn ^String term)



(defprotocol Description
  (^long conceptId [this])
  (^String term [this]))

(defprotocol Concept
  (^long id [this])
  (^Description fullySpecifiedName [this])
  (^Description preferredDescription [this]))

(extend-type com.eldrix.hermes.snomed.Description
  Description
  (term [this] (.-term this))
  (conceptId [this] (.-conceptId this)))

(extend-type com.eldrix.hermes.snomed.Concept
  Concept
  (id [this] (.-id this))
  (fullySpecifiedName [this] (com.eldrix.hermes.core/get-fully-specified-name svc (.-id this)))
  (preferredSynonym [this] (com.eldrix.hermes.core/get-preferred-synonym svc (.-id this) "en-GB")))


(comment
  (def svc (com.eldrix.hermes.core/open "/Users/mark/Dev/hermes/snomed.db"))
  (def ms (hermes/get-extended-concept svc 24700007))
  (def ms (hermes/get-concept svc 24700007))
  (type ms)
  (keys ms)
  (type (assoc ms :preferred-synonym (hermes/get-preferred-synonym svc 24700007 "en-GB")))
  (:descriptions ms)
  (map type (:descriptions ms))
  (fullySpecifiedName ms svc)
  (id ms)
  (reify)
  )