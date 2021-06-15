(ns eldrix.pc4-ward.refer.core
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]))

;; these could be automatically generated from the FHIR specs, except that the
;; specifications often have everything as optional (e.g. cardinality 0..1).
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def :org.hl7.fhir.Identifier/system ::non-blank-string)
(s/def :org.hl7.fhir.Identifier/value ::non-blank-string)
(s/def :org.hl7.fhir/Identifier (s/keys :req [:org.hl7.fhir.Identifier/system :org.hl7.fhir.Identifier/value]
                                        :opt [:org.hl7.fhir.Identifier/use :org.hl7.fhir.Identifier/type
                                              :org.hl7.fhir.Identifier/period :org.hl7.fhir.Identifier/assigner]))
(s/def :org.hl7.fhir.Coding/system ::non-blank-string)
(s/def :org.hl7.fhir.Coding/code ::non-blank-string)
(s/def :org.hl7.fhir/Coding (s/keys :req [:org.hl7.fhir.Coding/system :org.hl7.fhir.Coding/code]
                                    :opt [:org.hl7.fhir.Coding/version :org.hl7.fhir.Coding/display]))
(s/def :org.hl7.fhir.CodeableConcept/coding :org.hl7.fhir/Coding)
(s/def :org.hl7.fhir.CodeableConcept/text ::non-blank-string)
(s/def :org.hl7.fhir/CodeableConcept (s/keys :req [:org.hl7.fhir.CodeableConcept/coding]
                                             :opt [:org.hl7.fhir.CodeableConcept/text]))

(s/def :org.hl7.fhir.HumanName/given (s/coll-of ::non-blank-string))
(s/def :org.hl7.fhir.HumanName/family ::non-blank-string)
(s/def :org.hl7.fhir/HumanName (s/keys :req [:org.hl7.fhir.HumanName/family :org.hl7.fhir.HumanName/given]))
(s/def :org.hl7.fhir.Practitioner/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.Practitioner/name (s/coll-of :org.hl7.fhir/HumanName))
(s/def :org.hl7.fhir/Practitioner (s/keys :req [:org.hl7.fhir.Practitioner/identifier
                                                :org.hl7.fhir.Practitioner/name]))
(s/def :org.hl7.fhir.Patient/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.Patient/name (s/coll-of :org.hl7.fhir/HumanName))
(s/def :org.hl7.fhir/Patient (s/keys :req [:org.hl7.fhir.Patient/name :org.hl7.fhir.Patient/identifier]))

(s/def :org.hl7.fhir.ServiceRequest/patientInstruction string?)
(s/def :org.hl7.fhir.ServiceRequest/priority #{:org.hl7.fhir.request-priority/routine
                                               :org.hl7.fhir.request-priority/urgent
                                               :org.hl7.fhir.request-priority/asap
                                               :org.hl7.fhir.request-priority/stat})
(s/def :org.hl7.fhir.ServiceRequest/intent #{:org.hl7.fhir.request-intent/proposal
                                             :org.hl7.fhir.request-intent/plan
                                             :org.hl7.fhir.request-intent/directive
                                             :org.hl7.fhir.request-intent/order
                                             :org.hl7.fhir.request-intent/original-order
                                             :org.hl7.fhir.request-intent/reflex-order
                                             :org.hl7.fhir.request-intent/filler-order
                                             :org.hl7.fhir.request-intent/instance-order})
(s/def :org.hl7.fhir.ServiceRequest/code :org.hl7.fhir/CodeableConcept)
(s/def :org.hl7.fhir.ServiceRequest/subject (s/or :org.hl7.fhir/Patient :org.hl7.fhir/Reference))
(s/def :org.hl7.fhir.ServiceRequest/reasonCode (s/coll-of :org.hl7.fhir/CodeableConcept))
(s/def :org.hl7.fhir.ServiceRequest/performerType :org.hl7.fhir/CodeableConcept)
(s/def :org.hl7.fhir/ServiceRequest (s/keys :req [:org.hl7.fhir.ServiceRequest/status
                                                  :org.hl7.fhir.ServiceRequest/code
                                                  :org.hl7.fhir.ServiceRequest/requester
                                                  :org.hl7.fhir.ServiceRequest/intent
                                                  :org.hl7.fhir.ServiceRequest/authoredOn
                                                  :org.hl7.fhir.ServiceRequest/subject
                                                  :org.hl7.fhir.ServiceRequest/performerType
                                                  (or :org.hl7.fhir.ServiceRequest/locationCode
                                                      :org.hl7.fhir.ServiceRequest/locationReference)
                                                  :org.hl7.fhir.ServiceRequest/reasonCode]
                                            :opt [:org.hl7.fhir.ServiceRequest/patientInstruction]))


(s/def ::practitioner :org.hl7.fhir/Practitioner)
(s/def ::job-title ::non-blank-string)
(s/def ::contact-details ::non-blank-string)
(s/def ::referrer (s/keys :req [::practitioner
                                ::job-title
                                ::contact-details]
                          :opt [::team-contact-details]))
(s/def ::patient :org.hl7.fhir/Patient)
(s/def ::mode #{:inpatient :outpatient :advice})
(s/def ::location (s/keys :req [::mode]
                          :opt [::hospital ::ward]))
(s/def ::service string?)
(s/def ::question string?)
(s/def ::valid-referrer? (s/keys :req [::referrer]))
(s/def ::valid-patient? (s/keys :req [::referrer ::patient]))
(s/def ::valid-service? (s/keys :req [::referrer ::patient ::service]))
(s/def ::valid-question? (s/keys :req [::referrer ::patient ::service ::question]))
(s/def ::valid-referral? (s/keys :req [::date-time ::referrer ::patient ::question ::service]))

(defn prepare-referral [referral authenticated-user patient]
  (cond-> referral
          authenticated-user
          (-> (assoc-in [::referrer ::practitioner] authenticated-user)
              (update-in [::referrer ::job-title] #(or % (:urn.oid.2.5.4/title authenticated-user)))
              (update-in [::referrer ::contact-details] #(or % (:urn.oid.2.5.4/telephoneNumber authenticated-user))))
          patient
          (assoc ::patient patient)))

(defn completed-stages [referral]
  (cond-> #{}
          (s/valid? ::referrer (::referrer referral))
          (conj :clinician)
          (s/valid? ::patient (::patient referral))
          (conj :patient)
          (s/valid? ::service (::service referral))
          (conj :service)
          (s/valid? ::question (::question referral))
          (conj :question)))

(defn available-stages [referral]
  (let [completed (completed-stages referral)]
    (cond
      (not (contains? completed :clinician))
      #{:clinician}
      (not (contains? completed :patient))
      #{:clinician :patient}
      (not (contains? completed :service))
      #{:clinician :patient :service}
      (not (contains? completed :question))
      #{:clinician :patient :service :question}
      :else
      #{:clinician :patient :service :send})))

(comment
  (def ex1 {::referrer {::practitioner    {:org.hl7.fhir.Practitioner/identifier []
                                           :org.hl7.fhir.Practitioner/name       {}}
                        :job-title        "Consultant Neurologist"
                        ::contact-details "02920747747"}
            ::patient  {:name "Smith"}})
  (s/explain ::valid-referrer? ex1)
  (available-stages ex1))
