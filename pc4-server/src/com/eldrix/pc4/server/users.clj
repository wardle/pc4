(ns com.eldrix.pc4.server.users
  (:require [buddy.sign.jwt :as jwt]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.operation :as pco])
  (:import (java.time Instant)))

(defn make-user-token
  "Create a user token using the credentials and configuration supplied.
  e.g.
  (make-user-token {:system system :value value} {:jwt-secret-key \"secret\"})"
  [creds {:keys [jwt-expiry-seconds jwt-secret-key] :or {jwt-expiry-seconds (* 60 5)}}]
  (jwt/sign (assoc creds :exp (.plusSeconds (Instant/now) jwt-expiry-seconds)) jwt-secret-key))

(pco/defmutation refresh-token-operation
  "Refresh the user token.
  Parameters:
    |- :token              : the existing token

  Returns a new token."
  [{:com.eldrix.pc4/keys [login] :as env} {:keys [token]}]
  {::pco/op-name 'pc4.users/refresh-token
   ::pco/params  [:token]
   ::pco/output  [:io.jwt.token]}
  (when-let [current-user (try (jwt/unsign token (:jwt-secret-key login))
                               (catch Exception e (log/debug "Attempt to refresh invalid token")))]
    (log/info "Issuing refreshed token for user " current-user)
    (make-user-token current-user login)))

(pco/defmutation login-operation
  "Perform a login.
  Parameters:
    |- :system             : the namespace system to use
    |- :value              : the username.
    |- :password           : the password.
  Returns a user.

  We could make a LoginProtocol for each provider, but we still need to map to
  the keys specified here so it is simpler to use `cond` and choose the correct
  path based on the namespace and the providers available at runtime."
  [{:com.eldrix.pc4/keys [login] :as env} {:keys [system value password]}]
  {::pco/op-name 'pc4.users/login
   ::pco/params  [:system :value :password]
   ::pco/output  [:urn.oid.1.2.840.113556.1.4/sAMAccountName ;; sAMAccountName
                  :io.jwt/token
                  :urn.oid.1.2.840.113556.1.4.221
                  :urn.oid.0.9.2342.19200300.100.1.3        ;; (email)
                  :urn.oid.0.9.2342.19200300.100.1.1        ;; (uid)
                  :urn.oid:2.5.4/givenName                  ;; (givenName)
                  :urn.oid.2.5.4/surname                    ;; (sn)
                  :urn.oid.2.5.4/title                      ;; job title, not prefix
                  ]}
  (let [wales-nadex (get-in login [:providers :com.eldrix.concierge/nadex])
        fake-login (get-in login [:providers :com.eldrix.pc4/fake-login-provider])
        token (make-user-token {:system system :value value} login)]
    (cond
      ;; do we have the NHS Wales' NADEX configured, and is it a namespace it can handle?
      (and wales-nadex (= system "cymru.nhs.uk"))
      (do
        (log/info "login for " system value "; config:" wales-nadex)
        (if-let [user (nadex/search (:connection-pool wales-nadex) value password)]
          ;;(reduce-kv (fn [m k v] (assoc m (keyword "uk.nhs.cymru" (name k)) v)) {} user)
          {:urn.oid.1.2.840.113556.1.4/sAMAccountName (:sAMAccountName user)
           :io.jwt/token                              token
           :urn.oid.2.5.4/surname                     (:sn user)
           :urn.oid.2.5.4/givenName                   (:givenName user)
           :urn.oid.2.5.4/title                       (:title user)}
          (log/info "failed to authenticate user " system "/" value)))

      ;; finally, if nothing else has worked....
      ;; do we have a fake login provider configured?
      fake-login
      (do
        (log/info "performing fake login for " system value password "expecting " (:username fake-login) (:password fake-login))
        (when (and (= (:username fake-login) value) (= (:password fake-login) password))
          (log/info "successful fake login")
          {:urn.oid.1.2.840.113556.1.4.221          value
           :io.jwt/token                            token
           :urn.oid.2.5.4/surname                   "Wardle"
           :urn.oid.2.5.4/givenName                 "Mark"
           :urn.oid.2.5.4/title                     "Consultant Neurologist"}))

      ;; no login provider found for the namespace provided
      :else
      (log/info "no login provider found for namespace" {:system system :providers (keys login)}))))

(pco/defresolver x500->common-name
  "Generates an x500 common-name."
  "Generate an x500 common-name."
  [{:urn.oid.2.5.4/keys [givenName surname]}]
  {::pco/output [:urn.oid.2.5.4/commonName]}                ;; (cn)   common name - first, middle, last
  {:urn.oid.2.5.4/commonName (str givenName " " surname)})

(pco/defresolver fhir-practitioner-name
  "Generate a FHIR practitioner name from x500 data."
  [{:urn.oid.2.5.4/keys [givenName surname]}]
  {::pco/output [{:org.hl7.fhir.Practitioner/name [:org.hl7.fhir.HumanName/given
                                                   :org.hl7.fhir.HumanName/family
                                                   :org.hl7.fhir.HumanName/use]}]}
  {:org.hl7.fhir.Practitioner/name {:org.hl7.fhir.HumanName/family givenName
                                    :org.hl7.fhir.HumanName/given  surname
                                    :org.hl7.fhir.HumanName/use    :org.hl7.fhir.name-use/usual}})

(pco/defresolver fhir-contact-points
  "Generate FHIR contact points from x500 data."
  [{email :urn.oid.0.9.2342.19200300.100.1.3
    telephone}]
  {::pco/output [{:org.hl7.fhir.Practitioner/contactPoints [:org.hl7.fhir.ContactPoint/system
                                                            :org.hl7.fhir.ContactPoint/value]}]}
  {:org.hl7.fhir.Practitioner/contactPoints [{:org.hl7.fhir.ContactPoint/system :org.hl7.fhir.contact-point-system/email
                                              :org.hl7.fhir.ContactPoint/value  email}]})

(def all-resolvers
  [login-operation
   refresh-token-operation
   (pbir/equivalence-resolver)
   x500->common-name
   fhir-practitioner-name
   fhir-contact-points
   ])

