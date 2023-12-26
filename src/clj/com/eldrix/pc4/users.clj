(ns com.eldrix.pc4.users
  (:require [buddy.sign.jwt :as jwt]
            [clojure.tools.logging.readable :as log]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [com.eldrix.pc4.rsdb.auth]
            [com.eldrix.pc4.rsdb.users :as rsdb-users]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.fulcrologic.fulcro.server.api-middleware :as api-middleware])
  (:import (java.time Instant LocalDateTime)))

(s/def ::conn any?)

(pco/defmutation ping-operation
  "Return service status."
  [{:keys [uuid]}]
  {::pco/op-name 'pc4.users/ping
   ::pco/params  [:uuid]
   ::pco/output  [:uuid :date-time]}
  (log/trace "received ping " uuid)
  {:uuid      uuid
   :date-time (LocalDateTime/now)})

(pco/defmutation perform-login
  [{session   :session :as env}
   {:keys [system value password]}]
  {::pco/op-name 'pc4.users/perform-login}
  (when-let [user (rsdb-users/perform-login! env value password)]
    (println {:perform-login user})
    (api-middleware/augment-response user
                                     (fn [response]
                                       (assoc response :session (assoc session :authenticated-user
                                                                               (select-keys user [:t_user/id :t_user/active_roles])))))))

(pco/defmutation logout
  [env _]
  {::pco/op-name 'pc4.users/logout}
  (api-middleware/augment-response {:session/authenticated-user nil} #(assoc % :session nil)))

(pco/defresolver authenticated-user
  "Returns the authenticated user based on parameters in the environment"
  [{user :authenticated-user} _]
  {::pco/output [{:session/authenticated-user [:system :value
                                               (pco/? :t_user/id)
                                               (pco/? :t_user/username)]}]}
  {:session/authenticated-user user})

(def regulator->namespace
  {"GMC"  :uk.org.hl7.fhir.id/gmc-number
   "GPhC" :uk.org.hl7.fhir.id/gphc-number
   "NMC"  :uk.org.hl7.fhir.id/nmc-pin})

(pco/defresolver professional-regulators
  "Resolves a professional regulator.
  e.g.
  {:wales.nhs.nadex/professionalRegistration {:regulator \"GMC\" :code \"4624000\"}}

  will result in:
  {:uk.org.hl7.fhir.id/gmc-number \"4624000\"}"
  [{{:keys [regulator code]} :wales.nhs.nadex/professionalRegistration}]
  {::pco/output [:uk.org.hl7.fhir.id/gmc-number
                 :uk.org.hl7.fhir.id/gphc-number
                 :uk.org.hl7.fhir.id/nmc-pin]}
  (if-let [nspace (get regulator->namespace regulator)]
    {nspace code}))

(pco/defresolver x500->common-name
  "Generate an x500 common-name."
  [{:urn:oid:2.5.4/keys [givenName surname]}]
  {::pco/output [:urn:oid:2.5.4/commonName]}                ;; (cn)   common name - first, middle, last
  {:urn:oid:2.5.4/commonName (str givenName " " surname)})

(pco/defresolver fhir-practitioner-identifiers
  [{:wales.nhs.nadex/keys [sAMAccountName]}]
  {::pco/output [{:org.hl7.fhir.Practitioner/identifier [:org.hl7.fhir.Identifier/system
                                                         :org.hl7.fhir.Identifier/value]}]}
  {:org.hl7.fhir.Practitioner/identifier [{:org.hl7.fhir.Identifier/system "cymru.nhs.uk"
                                           :org.hl7.fhir.Identifier/value  sAMAccountName}]})

(pco/defresolver fhir-practitioner-name
  "Generate a FHIR practitioner name from x500 data."
  [{:urn:oid:2.5.4/keys [givenName surname personalTitle]}]
  {::pco/input [:urn:oid:2.5.4/givenName
                :urn:oid:2.5.4/surname
                (pco/? :urn:oid:2.5.4/personalTitle)]}
  {::pco/output [{:org.hl7.fhir.Practitioner/name [:org.hl7.fhir.HumanName/given
                                                   :org.hl7.fhir.HumanName/family
                                                   :org.hl7.fhir.HumanName/use]}]}
  {:org.hl7.fhir.Practitioner/name [(cond-> {:org.hl7.fhir.HumanName/use :org.hl7.fhir.name-use/usual}
                                      (not (str/blank? surname))
                                      (assoc :org.hl7.fhir.HumanName/family surname)
                                      (not (str/blank? givenName))
                                      (assoc :org.hl7.fhir.HumanName/given (str/split givenName #"\s"))
                                      (not (str/blank? personalTitle))
                                      (assoc :org.hl7.fhir.HumanName/prefix [personalTitle]))]})

(pco/defresolver fhir-telecom
  "Generate FHIR telecom (contact points) from x500 data."
  [{email     :urn:oid:0.9.2342.19200300.100.1.3
    telephone :urn:oid:2.5.4/telephoneNumber}]              ;;
  {::pco/input  [:urn:oid:0.9.2342.19200300.100.1.3
                 (pco/? :urn:oid:2.5.4/telephoneNumber)]
   ::pco/output [{:org.hl7.fhir.Practitioner/telecom [:org.hl7.fhir.ContactPoint/system
                                                      :org.hl7.fhir.ContactPoint/value]}]}
  {:org.hl7.fhir.Practitioner/telecom
   (cond-> []
     email
     (conj {:org.hl7.fhir.ContactPoint/system :org.hl7.fhir.contact-point-system/email
            :org.hl7.fhir.ContactPoint/value  email})
     telephone
     (conj {:org.hl7.fhir.ContactPoint/system :org.hl7.fhir.contact-point-system/phone
            :org.hl7.fhir.ContactPoint/value  telephone}))})

(comment
  (fhir-telecom {:urn:oid:2.5.4/telephoneNumber "07786196137" :urn:oid:0.9.2342.19200300.100.1.3 "mark.wardle@wales.nhs.uk"})

  (fhir-practitioner-name {:urn:oid:2.5.4/surname "Wardle" :urn:oid:2.5.4/givenName "Mark" :urn:oid:2.5.4/personalTitle "Dr"}))



(def all-resolvers
  [ping-operation
   login-operation
   perform-login
   logout
   refresh-token-operation
   authenticated-user
   (pbir/equivalence-resolver :urn:oid:2.5.4/telephoneNumber :urn:oid:2.5.4.20)
   (pbir/equivalence-resolver :urn:oid:2.5.4/surname :urn:oid:2.5.4.4)
   (pbir/equivalence-resolver :urn:oid:2.5.4/givenName :urn:oid:2.5.4.42)
   (pbir/equivalence-resolver :urn:oid:2.5.4/sn :urn:oid:2.5.4/surname)
   (pbir/alias-resolver :wales.nhs.nadex/givenName :urn:oid:2.5.4/givenName)
   (pbir/alias-resolver :wales.nhs.nadex/sAMAccountName :urn:oid:1.2.840.113556.1.4/sAMAccountName)
   (pbir/alias-resolver :wales.nhs.nadex/title :urn:oid:2.5.4/title)
   (pbir/alias-resolver :wales.nhs.nadex/personalTitle :urn:oid:2.5.4/personalTitle)
   (pbir/alias-resolver :wales.nhs.nadex/sn :urn:oid:2.5.4/sn)
   (pbir/alias-resolver :wales.nhs.nadex/telephoneNumber :urn:oid:2.5.4/telephoneNumber)
   (pbir/alias-resolver :wales.nhs.nadex/mail :urn:oid:0.9.2342.19200300.100.1.3)
   professional-regulators
   x500->common-name
   fhir-practitioner-identifiers
   fhir-practitioner-name
   fhir-telecom])


(defn make-mutation [m]
  (pco/mutation
    (reduce-kv
      (fn [m k v] (assoc m (if (qualified-keyword? k) k (keyword "com.wsscode.pathom3.connect.operation" (name k))) v))
      {} m)))

(defn make-resolver [m]
  (pco/resolver
    (reduce-kv
      (fn [m k v] (assoc m (if (qualified-keyword? k) k (keyword "com.wsscode.pathom3.connect.operation" (name k))) v))
      {} m)))

(comment
  (macroexpand-1 x500->common-name)
  (macroexpand-1 login-operation)

  (some true? (map qualified-keyword? (keys {:hi 1 :there 2 ::pco/op-name 'wibble})))

  (def r (make-resolver {:input     [:urn:oid:2.5.4/givenName
                                     :urn:oid:2.5.4/surname],
                         :output    [:urn:oid:2.5.4/commonName],
                         :docstring "Generate an x500 common-name.",
                         :op-name   'com.eldrix.pc4.users/x500->common-name
                         :resolve   (fn [env {:urn:oid:2.5.4/keys [givenName surname]}]
                                      {:urn:oid:2.5.4/commonName (str givenName " " surname)})}))
  (pco/resolver? r)
  r
  x500->common-name
  (r {} {:urn:oid:2.5.4/givenName "Mark" :urn:oid:2.5.4/surname "Wardle"})

  (make-mutation {:op-name   'pc4.users/login
                  :params    [:system
                              :value
                              :password],
                  :docstring "This is the documentation"
                  :mutate    (fn [env params] params)})
  (pbir/equivalence-resolver :urn:oid:2.5.4/telephoneNumber :urn:oid:2.5.4.20))

