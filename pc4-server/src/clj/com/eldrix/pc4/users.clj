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

(s/def ::jwt-secret-key string?)
(s/def ::jwt-expiry-seconds number?)
(s/def ::login-configuration (s/keys :req-un [::jwt-secret-key] :opt-un [::jwt-expiry-seconds]))

(defn make-user-token
  "Create a user token using the claims and configuration supplied.
  Parameters:
  - claims : a map containing arbitrary claims e.g {:username \"ma090906\"}
  - config : a map containing
           |- :jwt-expiry-seconds - expiry in seconds, default 300 seconds
           |- :jwt-secret-key     - JWT secret key
  e.g.
  (make-user-token {:system \"cymru.nhs.uk\" :value \"ma090906\"} {:jwt-secret-key \"secret\"})"
  [claims {:keys [jwt-expiry-seconds jwt-secret-key] :or {jwt-expiry-seconds (* 60 5)}}]
  (jwt/sign (assoc claims :exp (.plusSeconds (Instant/now) jwt-expiry-seconds)) jwt-secret-key))

(defn refresh-user-token
  "Refreshes a user token.
  Essentially, takes out the claims and re-signs. No new token will be issued if
  the token has expired or is invalid for any other reason."
  [existing-token {:keys [jwt-secret-key] :as config}]
  (when-let [claims (try (jwt/unsign existing-token jwt-secret-key)
                         (catch Exception e (log/debug "Attempt to refresh invalid token")))]
    (make-user-token claims config)))

(defn check-user-token
  "Returns the claims from a token, if it is valid."
  [token {:keys [jwt-secret-key]}]
  (try (jwt/unsign token jwt-secret-key)
       (catch Exception e nil)))

(pco/defmutation refresh-token-operation
  "Refresh the user token.
  Parameters:
    |- :token              : the existing token
  Returns a new token."
  [{:com.eldrix.pc4/keys [login] :as env} {:keys [token]}]
  {::pco/op-name 'pc4.users/refresh-token
   ::pco/params  [:token]
   ::pco/output  [:io.jwt.token]}
  (when-not (s/valid? ::login-configuration login)
    (throw (ex-info "invalid login configuration:" (s/explain-data ::login-configuration login))))
  (let [new-token (refresh-user-token token login)]
    (api-middleware/augment-response {:io.jwt/token new-token}
                                     #(assoc-in % [:response :session :authenticated-user :io.jwt/token] new-token))))


(defn is-rsdb-user? [conn system value]
  (rsdb-users/is-rsdb-user? conn system value))

(defn make-authorization-manager [conn namespace username]
  (when (not= namespace "cymru.nhs.uk")
    (throw (ex-info "cannot make authorization manager for user"
                    {:namespace namespace :username username})))
  (rsdb-users/make-authorization-manager conn username))

(s/def ::system string?)
(s/def ::value string?)
(s/def ::claims (s/keys :req-un [::system ::value]
                        :opt [:t_user/id :t_user/username]))

(s/fdef make-authenticated-env
  :args (s/cat :conn ::conn :claims ::claims))
(defn make-authenticated-env
  "Given claims containing at least `:system` and `:value`, create an environment.
  - conn   : rsdb database connection
  - system : namespace
  - value  : username."
  [conn {rsdb-user-id :t_user/id
         :keys [system value] :as claims}]
  (let [rsdb-user? (when claims (or rsdb-user-id (is-rsdb-user? conn system value)))]
    (cond-> {}
            claims
            (assoc :authenticated-user claims)
            rsdb-user?
            (assoc :authorization-manager (make-authorization-manager conn system value)))))

(pco/defmutation login-operation
  "Perform a login.
  Parameters:
    |- :system             : the namespace system to use
    |- :value              : the username.
    |- :password           : the password.
  Returns a user.

  A token is generated with claims containing at least :system and :value,
  but possibly other information as well in order to permit ongoing resolution.

  We could make a LoginProtocol for each provider, but we still need to map to
  the keys specified here so it is simpler to use `cond` and choose the correct
  path based on the namespace and the providers available at runtime.
  In addition, it is more likely than we will blend data from multiple sources
  in order to provide data resolution here; there won't be a 1:1 logical
  mapping between our login abstraction and the backend service. This approach
  is designed to decouple client from this complexity.

  For example, if the user has a registered rsdb account, we defer to that for
  user information. The 'system' parameter is currently a placeholder;
  it should be \"cymru.nhs.uk\" for the time being. The way login-action works
  *will* change but this will not affect clients."
  [{pc4-login :com.eldrix.pc4/login rsdb-conn :com.eldrix.rsdb/conn :as env}
   {:keys [system value password]}]
  {::pco/op-name 'pc4.users/login}
  (when-not (s/valid? ::login-configuration pc4-login)
    (throw (ex-info "invalid login configuration:" (s/explain-data ::login-configuration pc4-login))))
  (let [wales-nadex (get-in pc4-login [:providers :wales.nhs/nadex])
        fake-login (get-in pc4-login [:providers :com.eldrix.pc4/fake-login-provider])
        rsdb-user (when (and rsdb-conn (= system "cymru.nhs.uk")) (rsdb-users/check-password rsdb-conn wales-nadex value password))
        claims (merge rsdb-user {:system system :value value})
        token (make-user-token claims pc4-login)]
    (log/info "login-operation:" claims)
    (when rsdb-user
      (com.eldrix.pc4.rsdb.users/record-login rsdb-conn value))
    #_(log/warn " *** DELIBERATELY PAUSING ***")
    #_(Thread/sleep 2000)
    (cond
      ;; if we have an RSDB service, defer to that; it may update or supplement data from NADEX anyway
      rsdb-user
      (let [user (rsdb-users/fetch-user rsdb-conn value)]
        (log/info "login for " system value ": using rsdb backend")
        (api-middleware/augment-response (assoc user :io.jwt/token token)
                                         (fn [response] (assoc-in response [:session :authenticated-user] user))))

      ;; do we have the NHS Wales' NADEX configured, and is it a namespace it can handle?
      (and wales-nadex (= system "cymru.nhs.uk"))
      (do
        (log/info "login for " system value "; config:" wales-nadex)
        (if-let [user (first (nadex/search (:connection-pool wales-nadex) value password))]
          (-> (reduce-kv (fn [m k v] (assoc m (keyword "wales.nhs.nadex" (name k)) v)) {} user)
              (assoc :io.jwt/token token)
              (api-middleware/augment-response (fn [response] (assoc-in response [:session :authenticated-user] user))))
          (log/info "failed to authenticate user " system "/" value)))

      ;; if nothing else has worked....
      ;; do we have a fake login provider configured?
      fake-login
      (do
        (log/info "attempting fake login for " system value)
        (when (and (= (:username fake-login) value) (= (:password fake-login) password))
          (log/info "successful fake login")
          (let [user {:sAMAccountName           value
                      :sn                       "Wardle"
                      :givenName                "Mark"
                      :personalTitle            "Dr."
                      :mail                     "mark@wardle.org"
                      :telephoneNumber          "02920747747"
                      :professionalRegistration {:regulator "GMC" :code "4624000"}
                      :title                    "Consultant Neurologist"}]
            (-> user
                (update-keys #(keyword "wales.nhs.nadex" (name %)))
                (assoc :io.jwt/token token)
                (api-middleware/augment-response #(assoc-in % [:session :authenticated-user] user))))))

      ;; no login provider found for the namespace provided
      :else
      (log/info "no login provider found for namespace" {:system system :providers (keys pc4-login)}))))

(pco/defresolver authenticated-user
  "Returns the authenticated user based on parameters in the environment"
  [{user    :authenticated-user} _]
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

