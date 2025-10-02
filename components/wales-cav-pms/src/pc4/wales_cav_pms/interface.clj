(ns pc4.wales-cav-pms.interface
  (:require [integrant.core :as ig]
            [com.eldrix.concierge.wales.cav-pms :as cavpms]))

(defmethod ig/init-key ::svc [_ config]
  config)

(defn fetch-patient-by-crn [svc crn]
  (cavpms/fetch-patient-by-crn svc crn))

(defn fetch-patient-by-nnn [svc nnn]
  (cavpms/fetch-patient-by-nnn svc nnn))

(defn patient->fhir
  "Returns a FHIR representation of CAV PMS patient data"
  [cavpt]
  (cavpms/patient->fhir cavpt))

(defn fetch-admissions [svc patient]
  (cavpms/fetch-admissions svc patient))
