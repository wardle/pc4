(ns pc4.wales-nadex.interface
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [integrant.core :as ig]
   [pc4.fhir.interface :as fhir]
   [pc4.wales-nadex.core :as core]))

(defn open [config]
  (core/make-service config))

(defn close [svc]
  (core/close svc))

(defmethod ig/init-key ::svc
  [_ config]
  (open config))

(defmethod ig/halt-key! ::svc
  [_ svc]
  (close svc))

(defn valid-service?
  "Is 'svc' a valid NADEX service?"
  [svc]
  (core/valid-service? svc))

(defn can-authenticate?
  "Can the user authenticate with these credentials. Returns a boolean."
  [svc username password]
  (core/can-authenticate? svc username password))

(defn search-by-username
  "Search by username - searching against an exact match on 'sAMAccountName' LDAP field."
  [svc username]
  (core/search-by-username svc username))

(defn search-by-name
  "Search by name; LDAP fields 'sn' and 'givenName' will be searched by prefix."
  [svc s]
  (core/search-by-name svc s))

(defn gen-user
  "Return a generator for synthetic LDAP user data. Any specified data will
  be used in preference to generated data.
  Requires the [test.check](https://github.com/clojure/test.check) library to be on the classpath,
  but this dependency is dynamically loaded only if used at runtime. 

  For example,
  ```
  (require '[clojure.spec.gen.alpha :as gen])
  (gen/generate (gen-ldap-user))
  =>
  {:department      \"06xb1Z2W5xp8QN0Abx9g45\",
   :wwwHomePage     \"Y5Fu5TXJHpr8L8q9V286\",
   :sAMAccountName  \"cx562069\",
   :mail            \"amwsvghw.yezwelhzo@wales.nhs.uk\",
   :streetAddress   \"23A4Bbl07B0OGREZXC3x1\",
   :l               \"OV7HmyiD51\",
   :title           \"NV\",
   :telephoneNumber \"nNlxkwki4K36676T54PRbl052T6uk1\",
   :postOfficeBox   \"yFJHFajg72t7c6Mq4pG15HVajSl\",
   :postalCode      \"C4auzOCrO0ii2lK\",
   :givenName       \"cH85BxY7eJvMI61ohNx\",
   :sn              \"F7u17dUpj4qzMnK\",
   :mobile          \"EN\",
   :company         \"t1zj5e3y554u53S0vuekU753IQ1\",
   :physicalDeliveryOfficeName \"N5m9y4nG10aV18O4Ou9Hn0zZ83nI\"}
  ```"
  ([]
   (core/gen-ldap-user))
  ([m]
   (core/gen-ldap-user m)))

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
  :args (s/cat :user ::core/LdapUser)
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
     :org.hl7.fhir.HumanName/prefix personalTitle}]})

(comment
  (def svc (core/make-service {:users [{:username "ma090906" :password "password" :data {:sn "Wardle"}}]}))
  (can-authenticate? svc "ma090906" "password")
  (search-by-username svc "ma090906")
  (search-by-name svc "ward")
  (require '[clojure.spec.gen.alpha :as gen])
  (gen/generate (gen-user))
  (def user (gen/generate (gen-user)))
  (def user' (user->fhir-r4 user))
  (s/valid? (:ret (s/get-spec `user->fhir-r4)) user')
  (s/explain :org.hl7.fhir/Practitioner user'))


