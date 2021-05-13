(ns com.eldrix.pc4.server.system
  "Composes building blocks into a system using aero, integrant and pathom."
  (:require [aero.core :as aero]
            [buddy.sign.jwt :as jwt]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [com.eldrix.clods.core :as clods]
            [com.eldrix.clods.graph]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.dmd.graph]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.graph]
            [com.eldrix.pc4.server.api :as api]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as intc])
  (:import (java.time Instant)))

(def resolvers (atom []))

(defmethod ig/init-key :com.eldrix/clods [_ {:keys [ods-path nhspd-path]}]
  (log/info "opening nhspd index from " nhspd-path)
  (log/info "opening clods index from " ods-path)
  (log/info "registering UK ODS and NHSPD graph resolvers")
  (swap! resolvers into com.eldrix.clods.graph/all-resolvers)
  (clods/open-index ods-path nhspd-path))

(defmethod ig/halt-key! :com.eldrix/clods [_ clods]
  (.close clods))

(defmethod ig/init-key :com.eldrix/dmd [_ {:keys [path]}]
  (log/info "opening UK NHS dm+d index: " path)
  (swap! resolvers into com.eldrix.dmd.graph/all-resolvers)
  (dmd/open-store path))

(defmethod ig/halt-key! :com.eldrix/dmd [_ dmd]
  (.close dmd))

(defmethod ig/init-key :com.eldrix.concierge/nadex
  [_ {:keys [connection-pool-size _default-bind-username _default-bind-password] :as params}]
  (if connection-pool-size
    (-> params
        (assoc :connection-pool (nadex/make-connection-pool connection-pool-size)))))

(defmethod ig/halt-key! :com.eldrix.concierge/nadex [_ {:keys [connection-pool]}]
  (when connection-pool (.close connection-pool)))

(defmethod ig/init-key :com.eldrix.pc4/fake-login-provider
  [_ {:keys [password]}]
  {:password password})

(defmethod ig/init-key :com.eldrix.pc4/login
  [_ config]
  (log/info "registering login providers:" (keys (:providers config)))
  config)

(defmethod ig/init-key :com.eldrix/hermes [_ {:keys [path]}]
  (log/info "registering SNOMED graph resolvers")
  (swap! resolvers into com.eldrix.hermes.graph/all-resolvers)
  (log/info "opening hermes from " path)
  (hermes/open path))

(defmethod ig/halt-key! :com.eldrix/hermes [_ svc]
  (.close svc))

(defmethod ig/init-key :pathom/registry [_ {:keys [env]}]
  (log/info "creating pathom registry " env " resolvers:" (count @resolvers))
  (dorun (->> @resolvers
              (map (fn [r] (get-in r [:config :com.wsscode.pathom3.connect.operation/op-name])))
              (map #(log/info "resolver: " %))))
  (merge env (-> (pci/register (seq @resolvers))
                 (com.wsscode.pathom3.plugin/register [pbip/remove-stats-plugin
                                                       (pbip/attribute-errors-plugin)]))))


(defmethod ig/init-key :http/server [_ {:keys [port allowed-origins host env]}]
  (-> {::http/type            :jetty
       ::http/join?           false
       ::http/routes          api/routes
       ::http/port            (or port 8080)
       ::http/allowed-origins (cond
                                (= "*" allowed-origins)
                                (constantly true)
                                :else
                                allowed-origins)
       ::http/host            (or host "127.0.0.1")}
      (http/default-interceptors)
      (update ::http/interceptors conj
              (intc/interceptor (api/inject env))
              (io.pedestal.http.body-params/body-params)
              http/transit-body)
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! :http/server [_ service-map]
  (http/stop service-map))

;;;;;;;;;;;;
;; Graph resolvers


(defn make-user-token
  [creds {:keys [jwt-expiry-seconds jwt-secret-key]}]
  (jwt/sign (assoc creds :exp (.plusSeconds (Instant/now) (or jwt-expiry-seconds 120))) jwt-secret-key))

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
    (make-user-token current-user login)))

(pco/defmutation login-operation
  "Perform a login.
  Parameters:
    |- :system             : the namespace system to use
    |- :value              : the username.
    |- :password           : the password.
  Returns a user.

  It would seem sensible to make a LoginProtocol for each provider, but
  we still need to map to the keys specified here so it is simpler to use
  `cond` and choose the correct path based on the namespace and the providers
  available at runtime."
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

      ;; do we have a fake login provider configured?
      fake-login
      (do
        (log/info "performing fake login for " system value)
        (when (= (:password fake-login) password)
          {:urn.oid.1.2.840.113556.1.4/sAMAccountName value
           :io.jwt/token                              token
           :urn.oid.2.5.4/surname                     "Duck"
           :urn.oid.2.5.4/givenName                   "Donald"
           :urn.oid.2.5.4/title                       "Consultant Neurologist"}))
      ;; no login provider found for the namespace provided
      :else
      (log/info "no login provider found for namespace" {:system system :providers (keys login)}))))

(pco/defresolver x500->common-name
  "Generates an x500 common-name."
  [{:urn.oid.2.5.4/keys [givenName surname]}]
  {::pco/output [:urn.oid.2.5.4/commonName]}                ;; (cn)   common name - first, middle, last
  {:urn.oid.2.5.4/commonName (str givenName " " surname)})

(pco/defresolver fhir-practitioner-name
  "Generates a FHIR practitioner name from x500 data."
  [{:urn.oid.2.5.4/keys [givenName surname]}]
  {::pco/output [{:org.hl7.fhir.Practitioner/name [:org.hl7.fhir.HumanName/given
                                                   :org.hl7.fhir.HumanName/family
                                                   :org.hl7.fhir.HumanName/use]}]}
  {:org.hl7.fhir.Practitioner/name {:org.hl7.fhir.HumanName/family givenName
                                    :org.hl7.fhir.HumanName/given  surname
                                    :org.hl7.fhir.HumanName/use    :org.hl7.fhir.name-use/usual}})

(def default-resolvers
  [login-operation
   refresh-token-operation
   x500->common-name
   fhir-practitioner-name])

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(defn config
  "Reads configuration from the resources directory using the profile specified."
  [profile]
  (-> (aero/read-config (io/resource "config.edn") {:profile profile})
      (dissoc :secrets)))

(defn- prep [profile]
  (ig/load-namespaces (config profile)))

(defn init [profile]
  ;; start with a default set of resolvers
  (reset! resolvers default-resolvers)
  ;; configuration can add further resolvers, depending on what is configured
  (ig/init (config profile)))


(comment
  (config :dev)
  (config :live)

  (prep :dev)

  (def system (init :dev))
  (ig/halt! system)

  (keys system)

  (p.eql/process (:pathom/registry system) [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"]
                                             [:uk.gov.ons.nhspd/LSOA11
                                              :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M
                                              :urn.ogc.def.crs.EPSG.4326/latitude
                                              :urn.ogc.def.crs.EPSG.4326/longitude
                                              :uk.gov.ons.nhspd/PCT :uk.nhs.ord/name
                                              :uk.nhs.ord.primaryRole/displayName
                                              {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}])
  (p.eql/process (:pathom/registry system) [{[:info.snomed.Concept/id 24700007] [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/lowercaseTerm]}]}])

  (p.eql/process (:pathom/registry system) [{'(pc4.users/login
                                                {:system :uk.nhs.cymru :value "ma090906" :password "password"})
                                             [:urn.oid.1.2.840.113556.1.4/sAMAccountName
                                              :io.jwt/token
                                              :urn.oid.2.5.4/givenName
                                              :urn.oid.2.5.4/surname
                                              :urn.oid.2.5.4/commonName
                                              {:org.hl7.fhir.Practitioner/name
                                               [:org.hl7.fhir.HumanName/use
                                                :org.hl7.fhir.HumanName/family
                                                :org.hl7.fhir.HumanName/given]}]}])

  (p.eql/process (:pathom/registry system) [{'(pc4.users/refresh-token
                                                {:token "eyJhbGciOiJIUzI1NiJ9.eyJzeXN0ZW0iOiJ1ay5uaHMuY3ltcnUiLCJ2YWx1ZSI6Im1hMDkwOTA2IiwiZXhwIjoxNjIwOTEwNTkzfQ.7PXGgYZYeXNy4qLbCDeKdA_LGQaWbD9AHu1FFWar1os"})
                                             [:io.jwt/token]}])
  )


