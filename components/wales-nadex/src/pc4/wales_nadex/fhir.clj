(ns pc4.wales-nadex.fhir
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [pc4.fhir.interface]
   [pc4.wales-nadex.spec :as nspec]
   [pc4.snomedct.interface :as hermes]))

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
             :org.hl7.fhir.Identifier/value sAMAccountName
             :org.hl7.fhir.Identifier/use "official"}]
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

(def known-fhir-practitioner-roles
  "See https://hl7.org/fhir/r4/valueset-practitioner-role.html - "
  [{:ecl "<158965000" :role "doctor"     :display "Doctor"}
   {:ecl "<769038007" :role "researcher" :display "Researcher"}
   {:ecl "<265937000" :role "nurse"      :display "Nurse"}
   {:ecl "<307972006" :role "teacher"    :display "Teacher"}
   {:ecl "<46255001"  :role "pharmacist" :display "Pharmacist"}])

(defn concept-id->roles
  [hermes concept-id]
  (->> known-fhir-practitioner-roles
       (filter (fn [{:keys [ecl]}]
                 (seq (hermes/intersect-ecl hermes #{concept-id} ecl))))
       (map (fn [{:keys [role display]}]
              (hash-map
               :org.hl7.fhir.CodeableConcept/coding [{:org.hl7.fhir.Coding/system  "http://terminology.hl7.org/CodeSystem/practitioner-role"
                                                      :org.hl7.fhir.Coding/code    (str role)
                                                      :org.hl7.fhir.Coding/display display}]
               :org.hl7.fhir.CodeableConcept/text display)))))

(defn user->fhir-r4-practitioner-role
  [hermes {:keys [title]}]
  (when-not (str/blank? title)
    (when-let [{:keys [conceptId preferredTerm]} (first (hermes/ranked-search hermes {:s title :constraint "<14679004" :max-hits 1}))]
      {;:org.hl7.fhir.PractitionerRole/identifier nil
       :org.hl7.fhir.PractitionerRole/active true
     ;:org.hl7.fhir.PractitionerRole/period nil
     ;:org.hl7.fhir.PractitionerRole/specialty nil   ;; unfortunately, we cannot easily derive speciality from occupation
       :org.hl7.fhir.PractitionerRole/code
       (into [{:org.hl7.fhir.CodeableConcept/coding [{:org.hl7.fhir.Coding/system  "http://snomed.info/sct"
                                                      :org.hl7.fhir.Coding/code    (str conceptId)
                                                      :org.hl7.fhir.Coding/display preferredTerm}]
               :org.hl7.fhir.CodeableConcept/text title}]
             (concept-id->roles hermes conceptId))})))

(comment
  (def hermes (#'hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (time (hermes/intersect-ecl hermes (into #{} (map :id) known-fhir-practitioner-roles) ">>56397003"))
  (time (hermes/intersect-ecl hermes #{56397003} "<158965000"))
  (hermes/ranked-search hermes {:s "physiotherapist" :constraint "<223366009|Healthcare professional|" :max-hits 1})
  (hermes/ranked-search hermes {:s "consultant neurologist" :constraint "<394658006"})
  (concept-id->roles hermes 56397003)
  (concept-id->roles hermes 36682004)
  (hermes/all-parents-ids hermes 36682004)

  (user->fhir-r4-practitioner-role hermes {:title "Consultant Neurologist"}))
