(ns pc4.msbase.interface
  (:require
   [pc4.msbase.core :as msbase]))

(def all-resolvers
  msbase/all-resolvers)

(defn patient->msbase
  [pathom-boundary-interface patient-identifier]
  (msbase/fetch-patient pathom-boundary-interface patient-identifier))
