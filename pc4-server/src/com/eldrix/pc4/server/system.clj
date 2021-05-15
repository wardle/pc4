(ns com.eldrix.pc4.server.system
  "Composes building blocks into a system using aero, integrant and pathom.

  We currently manage the system configuration using integrant - stitching
  together discrete services. Each of those are then made available for use,
  but we also build a set of pathom resolvers and operations on top of that
  functionality. Currently, we manage those resolvers using a simple clojure
  atom and then build a pathom environment from that. It is conceivable that
  an integrant component could support defining in-built resolvers itself
  by the implementation of a protocol, for example."
  (:require [aero.core :as aero]
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
            [com.eldrix.pc4.server.users :as users]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as intc]))

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
  [_ {:keys [username password] :as options}]
  options)

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

(defmethod ig/halt-key! :pathom/registry [_ env]
  (reset! resolvers []))

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


(def default-resolvers
  (concat users/all-resolvers))

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
  (do
    (ig/halt! system)
    (def system (init :dev)))

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


