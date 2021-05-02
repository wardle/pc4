(ns com.eldrix.pc4.server.system
  "Composes building blocks into a system using aero, integrant and pathom."
  (:require [aero.core :as aero]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.clods.core :as clods]
            [com.eldrix.clods.graph :as clods-g]
            [com.eldrix.hermes.terminology :as hermes]
            [com.eldrix.hermes.graph :as hermes-g]
            [com.eldrix.pc4.server.api :as api]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [io.pedestal.interceptor :as intc]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [clojure.java.io :as io]))

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(defn config
  "Reads configuration from the resources directory using the profile specified."
  [profile]
  (-> (aero/read-config (io/resource "config.edn") {:profile profile})
      (dissoc :secrets)))

(defn- prep [profile]
  (ig/load-namespaces (config profile)))

(comment
  (config :dev)
  (prep :dev))

(def resolvers (atom #{}))

(defmethod ig/init-key :com.eldrix/clods [_ {:keys [ods-path nhspd-path]}]
  (log/info "opening nhspd index from " nhspd-path)
  (log/info "opening clods index from " ods-path)
  (log/info "registering UK ODS and NHSPD graph resolvers")
  (swap! resolvers into com.eldrix.clods.graph/all-resolvers)
  (clods/open-index ods-path nhspd-path))

(defmethod ig/halt-key! :com.eldrix/clods [_ clods]
  (.close clods))

(defmethod ig/init-key :com.eldrix/concierge-nadex [_ {:keys [pool-size]}]
  (when pool-size (nadex/make-connection-pool pool-size)))

(defmethod ig/halt-key! :com.eldrix/concierge-nadex [_ pool]
  (when pool (.close pool)))

(defmethod ig/init-key :com.eldrix/hermes [_ {:keys [path]}]
  (log/info "registering SNOMED graph resolvers")
  (swap! resolvers into com.eldrix.hermes.graph/all-resolvers)
  (log/info "opening hermes from " path)
  (hermes/open path))

(defmethod ig/halt-key! :com.eldrix/hermes [_ svc]
  (.close svc))

(defmethod ig/init-key :pathom/registry [_ {:keys [env]}]
  (log/info "creating pathom registry " env " resolvers:" (count @resolvers))
  (merge env (pci/register (seq @resolvers))))

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

(comment
  (def system (ig/init (assoc-in config [:http/server :service-map ::http/join?] false)))
  (def system (ig/init (config :dev)))
  (require '[com.wsscode.pathom3.interface.eql :as p.eql])
  (keys system)
  (:pathom/registry system)
  (p.eql/process (:pathom/registry system) [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"] [:uk.gov.ons.nhspd/LSOA11 :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M :uk.gov.ons.nhspd/PCT :uk.nhs.ord/name :uk.nhs.ord.primaryRole/displayName {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}])

  (p.eql/process (:pathom/registry system)
                 [{[:info.snomed.Concept/id 24700007]
                   [:info.snomed.Concept/id
                    :info.snomed.Concept/descriptions]}])
  system
  (ig/halt! system))