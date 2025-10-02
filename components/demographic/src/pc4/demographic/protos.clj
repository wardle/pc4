(ns pc4.demographic.protos)

(defprotocol PatientsByIdentifier
  (fetch [_ system value]
    "Fetch patients using the specified patient data encoded as a FHIR representation. Returns a sequence of matches which
    may be nil."))