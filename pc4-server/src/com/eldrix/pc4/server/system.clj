(ns com.eldrix.pc4.server.system
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.clods.core :as clods]
            [com.eldrix.clods.graph :as clods-g]
            [com.eldrix.hermes.terminology :as hermes]
            [com.eldrix.hermes.graph :as hermes-g]
            [com.eldrix.pc4.server.api :as api]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [io.pedestal.interceptor :as intc]))


(def config
  {:eldrix/clods    {:ods-path   "/var/tmp/ods"
                     :nhspd-path "/var/tmp/nhspd"}

   :eldrix/hermes   {:path "/Users/mark/Dev/hermes/snomed.db"}

   :pathom/registry {:resolvers (concat com.eldrix.clods.graph/all-resolvers
                                        com.eldrix.hermes.graph/all-resolvers)
                     :env       {::clods-g/svc  (ig/ref :eldrix/clods)
                                 ::hermes-g/svc (ig/ref :eldrix/hermes)}}

   :http/server     {:service-map {::http/type            :jetty
                                   ::http/join?           true
                                   ::http/routes          api/routes
                                   ::http/port            8080
                                   ::http/allowed-origins (constantly true)
                                   ::http/host            "0.0.0.0"}
                     :env         {:pathom-registry (ig/ref :pathom/registry)}}})


(defmethod ig/init-key :eldrix/clods [_ {:keys [ods-path nhspd-path]}]
  (log/info "opening nhspd index from " nhspd-path)
  (log/info "opening clods index from " ods-path)
  (clods/open-index ods-path nhspd-path))

(defmethod ig/halt-key! :eldrix/clods [_ clods]
  (.close clods))

(defmethod ig/init-key :eldrix/hermes [_ {:keys [path]}]
  (log/info "opening hermes from " path)
  (hermes/open path))

(defmethod ig/halt-key! :eldrix/hermes [_ svc]
  (.close svc))

(defmethod ig/init-key :pathom/registry [_ {:keys [resolvers env]}]
  (merge env (pci/register resolvers)))

(defmethod ig/init-key :http/server [_ {:keys [service-map env]}]
  (-> service-map
      (http/default-interceptors)
      (update ::http/interceptors conj
              (intc/interceptor (api/inject env)) (io.pedestal.http.body-params/body-params) http/transit-body)
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! :http/server [_ service-map]
  (http/stop service-map))

(comment
  (def system (ig/init (assoc-in config [:http/server :service-map ::http/join?] false)))
  (require '[com.wsscode.pathom3.interface.eql :as p.eql])
  (keys system)
  (:pathom/registry system)
  (p.eql/process (:pathom/registry system) [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"] [:uk.gov.ons.nhspd/LSOA11 :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M :uk.gov.ons.nhspd/PCT :uk.nhs.ord/name :uk.nhs.ord.primaryRole/displayName {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}])

  (p.eql/process (:pathom/registry system)
                 [{[:info.snomed.Concept/id 24700007]
                   [:info.snomed.Concept/id
                    :info.snomed.Concept/descriptions]}])
  (ig/halt! system))