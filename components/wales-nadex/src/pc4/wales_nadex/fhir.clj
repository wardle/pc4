(ns pc4.wales-nadex.fhir
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [pc4.fhir.interface]
   [pc4.wales-nadex.spec :as nspec]))

(def regulator->fhir-systems
  {"GMC" "https://fhir.hl7.org.uk/Id/gmc-number"
   "NMC" "https://fhir.hl7.org.uk/Id/nmc-number"
   "HCPC" "https://fhir.hl7.org.uk/Id/hcpc-number"})

(defn make-fhir-r4-regulator-identifier
  "Turn an LDAP professional registration entry into a FHIR identifier."
  [{:keys [regulator code]}]
  (when-let [system (regulator->fhir-systems regulator)]
    {:org.hl7.fhir.Identifier/system  system
     :org.hl7.fhir.Identifier/value code}))

(s/fdef user->fhir-r4
  :args (s/cat :user ::nspec/LdapUser)
  :ret :org.hl7.fhir/Practitioner)
(defn user->fhir-r4
  [{:keys [sAMAccountName sn givenName personalTitle mail telephoneNumber wwwHomePage
           mobile professionalRegistration thumbnailPhoto
           physicalDeliveryOfficeName streetAddress l postalCode company]}]
  {:org.hl7.fhir.Practitioner/active true
   :org.hl7.fhir.Practitioner/gender "unknown"  ;; TODO: does LDAP have anything about gender?

   :org.hl7.fhir.Practitioner/identifier
   (cond-> [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.wales/Id/nadex-identifier"
             :org.hl7.fhir.Identifier/value sAMAccountName}]
     professionalRegistration
     (conj (make-fhir-r4-regulator-identifier professionalRegistration)))

   :org.hl7.fhir.Practitioner/telecom
   (cond-> []
     (not (str/blank? mail))
     (conj {:org.hl7.fhir.ContactPoint/system "email"
            :org.hl7.fhir.ContactPoint/value mail
            :org.hl7.fhir.ContactPoint/use "work"})
     (not (str/blank? wwwHomePage))
     (conj {:org.hl7.fhir.ContactPoint/system "url"
            :org.hl7.fhir.ContactPoint/value wwwHomePage
            :org.hl7.fhir.ContactPoint/use "work"})
     (not (str/blank? telephoneNumber))
     (conj {:org.hl7.fhir.ContactPoint/system "phone"
            :org.hl7.fhir.ContactPoint/value telephoneNumber
            :org.hl7.fhir.ContactPoint/use "work"})
     (not (str/blank? mobile))
     (conj {:org.hl7.fhir.ContactPoint/system "phone"
            :org.hl7.fhir.ContactPoint/value mobile
            :org.hl7.fhir.ContactPoint/use "mobile"}))

   :org.hl7.fhir.Practitioner/address
   [{:org.hl7.fhir.Address/use "work"
     :org.hl7.fhir.Address/type "physical"
     :org.hl7.fhir.Address/text (str/join "\n" (remove str/blank? [physicalDeliveryOfficeName streetAddress l postalCode]))
     :org.hl7.fhir.Address/line [physicalDeliveryOfficeName streetAddress]
     :org.hl7.fhir.Address/district ""
     :org.hl7.fhir.Address/state ""
     :org.hl7.fhir.Address/city l
     :org.hl7.fhir.Address/country ""
     :org.hl7.fhir.Address/postalCode postalCode}]

   :org.hl7.fhir.Practitioner/photo
   (cond-> []
     thumbnailPhoto   ; TODO: check mimetype? 
     (conj {:org.hl7.fhir.Attachment/data (.encodeToString (java.util.Base64/getEncoder) thumbnailPhoto)
            :org.hl7.fhir.Attachment/contentType "image/jpeg"}))

   :org.hl7.fhir.Practitioner/name
   [{:org.hl7.fhir.HumanName/use "official"
     :org.hl7.fhir.HumanName/text (str/join " " (remove str/blank? [personalTitle givenName sn]))
     :org.hl7.fhir.HumanName/family sn
     :org.hl7.fhir.HumanName/given (str/split givenName #"\s")
     :org.hl7.fhir.HumanName/prefix (if (str/blank? personalTitle) [] [personalTitle])}]})


