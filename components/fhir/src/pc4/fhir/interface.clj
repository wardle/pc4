(ns pc4.fhir.interface
  "Specifications for pc4 FHIR data structures. Automated conversion from the
  FHIR specifications would be possible, but the FHIR standard is too open
  and needs to be constrained in any particular implementation. As such, these
  specifications tighten the more broad general purpose specifications for use
  within pc4."
  (:require [clojure.spec.alpha :as s])
  (:import (java.time LocalDate LocalDateTime)))

(def administrative-gender #{"male" "female" "other" "unknown"})
(def contact-point-system #{"phone" "fax" "email" "pager" "url" "sms" "other"})
(def contact-point-use #{"home" "work" "temp" "old" "mobile"})

(s/def :org.hl7.fhir.Attachment/contentType string?)
(s/def :org.hl7.fhir.Attachment/data string?)
(s/def :org.hl7.fhir.Attachment/url string?)
(s/def :org.hl7.fhir.Attachment/language string?)
(s/def :org.hl7.fhir.Attachment/size pos-int?)
(s/def :org.hl7.fhir.Attachment/hash string?)
(s/def :org.hl7.fhir.Attachment/title string?)
(s/def :org.hl7.fhir.Attachment/width pos-int?)
(s/def :org.hl7.fhir.Attachment/height pos-int?)
(s/def :org.hl7.fhir/Attachment
  (s/keys :req [:org.hl7.fhir.Attachment/contentType
                (or :org.hl7.fhir.Attachment/data :org.hl7.fhir.Attachment/url)]
          :opt [:org.hl7.fhir.Attachment/language
                :org.hl7.fhir.Attachment/size
                :org.hl7.fhir.Attachment/hash
                :org.hl7.fhir.Attachment/title
                :org.hl7.fhir.Attachment/width
                :org.hl7.fhir.Attachment/height]))

(s/def :org.hl7.fhir.Identifier/system string?)
(s/def :org.hl7.fhir.Identifier/value string?)
(s/def :org.hl7.fhir/Identifier
  (s/keys :req [:org.hl7.fhir.Identifier/system
                :org.hl7.fhir.Identifier/value]))

(s/def :org.hl7.fhir.Period/start (s/nilable #(instance? LocalDate %)))
(s/def :org.hl7.fhir.Period/end (s/nilable #(instance? LocalDate %)))
(s/def :org.hl7.fhir/Period
  (s/keys :req [:org.hl7.fhir.Period/start :org.hl7.fhir.Period/end]))

(s/def :org.hl7.fhir.HumanName/family string?)
(s/def :org.hl7.fhir.HumanName/given (s/coll-of string?))
(s/def :org.hl7.fhir/HumanName
  (s/keys :req [:org.hl7.fhir.HumanName/use
                :org.hl7.fhir.HumanName/family
                :org.hl7.fhir.HumanName/given
                :org.hl7.fhir.HumanName/prefix]
          :opt [:org.hl7.fhir.HumanName/suffix]))

(s/def :org.hl7.fhir.Address/line (s/coll-of string?))
(s/def :org.hl7.fhir.Address/city (s/nilable string?))
(s/def :org.hl7.fhir.Address/district (s/nilable string?))
(s/def :org.hl7.fhir.Address/country (s/nilable string?))
(s/def :org.hl7.fhir.Address/postalCode (s/nilable string?))
(s/def :org.hl7.fhir/Address
  (s/keys :req [:org.hl7.fhir.Address/line
                :org.hl7.fhir.Address/city
                :org.hl7.fhir.Address/district
                :org.hl7.fhir.Address/country
                :org.hl7.fhir.Address/postalCode]
          :opt [:org.hl7.fhir.Address/period]))

(s/def :org.hl7.fhir.ContactPoint/value string?)
(s/def :org.hl7.fhir.ContactPoint/system contact-point-system)
(s/def :org.hl7.fhir.ContactPoint/use contact-point-use)
(s/def :org.hl7.fhir.ContactPoint/rank pos-int?)
(s/def :org.hl7.fhir.ContactPoint/period :org.hl7.fhir/Period)
(s/def :org.hl7.fhir/ContactPoint
  (s/keys :req [:org.hl7.fhir.ContactPoint/use
                :org.hl7.fhir.ContactPoint/system
                :org.hl7.fhir.ContactPoint/value]
          :opt [:org.hl7.fhir.ContactPoint/rank
                :org.hl7.fhir.ContactPoint/period]))

(s/def :org.hl7.fhir.Patient/name (s/coll-of :org.hl7.fhir/HumanName))
(s/def :org.hl7.fhir.Patient/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.Patient/active boolean?)
(s/def :org.hl7.fhir.Patient/birthDate (s/or :date #(instance? LocalDate %) :date-time #(instance? LocalDateTime %)))
(s/def :org.hl7.fhir.Patient/deceased (s/nilable (s/or :boolean boolean? :date #(instance? LocalDate %) :date-time #(instance? LocalDateTime %))))
(s/def :org.hl7.fhir.Patient/gender administrative-gender)
(s/def :org.hl7.fhir.Patient/address (s/coll-of :org.hl7.fhir/Address))
(s/def :org.hl7.fhir.Patient/telecom (s/coll-of :org.hl7.fhir/ContactPoint))
(s/def :org.hl7.fhir/Patient
  (s/keys :req [:org.hl7.fhir.Patient/identifier
                :org.hl7.fhir.Patient/active
                :org.hl7.fhir.Patient/name
                :org.hl7.fhir.Patient/birthDate
                :org.hl7.fhir.Patient/gender
                :org.hl7.fhir.Patient/address
                :org.hl7.fhir.Patient/telecom
                :org.hl7.fhir.Patient/generalPractitioner]
          :opt [:org.hl7.fhir.Patient/deceased]))

(s/def :org.hl7.fhir.Practitioner/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.Practitioner/active boolean?)
(s/def :org.hl7.fhir.Practitioner/name (s/coll-of :org.hl7.fhir/HumanName))
(s/def :org.hl7.fhir.Practitioner/telecom (s/coll-of :org.hl7.fhir/ContactPoint))
(s/def :org.hl7.fhir.Practitioner/gender administrative-gender)
(s/def :org.hl7.fhir.Practitioner/photo (s/coll-of :org.hl7.fhir/Attachment))
(s/def :org.hl7.fhir/Practitioner
  (s/keys :req [:org.hl7.fhir.Practitioner/identifier
                :org.hl7.fhir.Practitioner/active
                :org.hl7.fhir.Practitioner/name
                :org.hl7.fhir.Practitioner/telecom
                :org.hl7.fhir.Practitioner/gender
                :org.hl7.fhir.Practitioner/photo]))

(defn gp-surgery-identifiers
  "Given a FHIR patient, return identifiers for the GP surgeries."
  [pt]
  (->> (:org.hl7.fhir.Patient/generalPractitioner pt)
       (filter #(= "https://fhir.nhs.uk/Id/ods-organization-code"
                   (get-in % [:org.hl7.fhir.Reference/identifier :org.hl7.fhir.Identifier/system])))
       (map :org.hl7.fhir.Reference/identifier)))

(defn general-practitioner-identifiers
  "Given a FHIR patient, return identifiers for the general practitioners"
  [pt]
  (->> (:org.hl7.fhir.Patient/generalPractitioner pt)
       (filter #(= "https://fhir.hl7.org.uk/Id/gmp-number"
                   (get-in % [:org.hl7.fhir.Reference/identifier :org.hl7.fhir.Identifier/system])))
       (map :org.hl7.fhir.Reference/identifier)))
