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
            [com.eldrix.pc4.server.rsdb :as rsdb]
            [com.eldrix.pc4.server.users :as users]
            [com.eldrix.pc4.server.patients :as patients]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as intc]
            [next.jdbc.connection :as connection]
            [buddy.sign.jwt :as jwt]
            [com.eldrix.pc4.server.dates :as dates]
            [com.wsscode.pathom.viz.ws-connector.pathom3 :as p.connector])
  (:import (com.zaxxer.hikari HikariDataSource)))

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
  (when connection-pool-size
    (-> params
        (assoc :connection-pool (nadex/make-connection-pool connection-pool-size)))))

(defmethod ig/halt-key! :com.eldrix.concierge/nadex [_ {:keys [connection-pool]}]
  (when connection-pool (.close connection-pool)))

(defmethod ig/init-key :com.eldrix/rsdb
  [_ params]
  (log/info "registering PatientCare EPR [rsdb]" params)
  (swap! resolvers into com.eldrix.pc4.server.rsdb/all-resolvers)
  (connection/->pool HikariDataSource params))

(defmethod ig/halt-key! :com.eldrix/rsdb
  [_ conn]
  (.close conn))

(defmethod ig/init-key :com.eldrix.rsdb/config [_ config]
  config)

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

(defmethod ig/init-key :wales.nhs.cavuhb/pms [_ config]
  config)

(defmethod ig/init-key :wales.nhs/empi [_ config]
  config)

(def default-resolvers
  [users/all-resolvers
   patients/all-resolvers])

(defmethod ig/init-key :pathom/env [_ env]
  (log/info "creating pathom registry " env " resolvers:" (count @resolvers))
  (let [resolvers (flatten [@resolvers default-resolvers])]
    (dorun (->> resolvers
                (map (fn [r] (str (get-in r [:config :com.wsscode.pathom3.connect.operation/op-name]))))
                sort
                (map #(log/info "resolver: " %))))
    (merge env
           (-> (pci/register resolvers)
               (com.wsscode.pathom3.plugin/register
                 [(pbip/attribute-errors-plugin)])))))

(defmethod ig/halt-key! :pathom/env [_ env]
  (reset! resolvers []))

(defmethod ig/init-key :pathom/boundary-interface [_ {:keys [env] :as config}]
  (p.eql/boundary-interface env))

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
              (http/transit-body-interceptor ::transit-json-body
                                             "application/transit+json;charset=UTF-8"
                                             :json
                                             {:handlers dates/transit-writers}))
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! :http/server [_ service-map]
  (http/stop service-map))

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(defn config
  "Reads configuration from the resources directory using the profile specified."
  [profile]
  (-> (aero/read-config (io/resource "config.edn") {:profile profile})
      (dissoc :secrets)))

(defn prep [profile]
  (ig/load-namespaces (config profile)))

(defn init [profile]
  ;; start with a default set of resolvers
  (reset! resolvers [])
  ;; configuration can add further resolvers, depending on what is configured
  (ig/init (config profile)))

(comment
  (config :dev)
  (config :live)

  (prep :dev)

  (def system (init :dev))
  (reset! resolvers [])
  (ig/halt! system)

  (do
    (ig/halt! system)
    (def system (init :dev)))

  (count @resolvers)
  (sort (map str (map #(get-in % [:config :com.wsscode.pathom3.connect.operation/op-name]) (flatten default-resolvers))))
  (sort (map #(get-in % [:config :com.wsscode.pathom3.connect.operation/op-name]) (flatten [@resolvers default-resolvers])))

  (:pathom/env system)

  (keys system)
  ((:pathom/boundary-interface system) [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"]
                                         [:uk.gov.ons.nhspd/LSOA11
                                          :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M
                                          :urn.ogc.def.crs.EPSG.4326/latitude
                                          :urn.ogc.def.crs.EPSG.4326/longitude
                                          {:uk.gov.ons.nhspd/PCT_ORG [:uk.nhs.ord/name :uk.nhs.ord/active :uk.nhs.ord/orgId]}]}])

  ((:pathom/boundary-interface system) [{[:info.snomed.Concept/id 24700007] [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/lowercaseTerm]}]}])

  ((:pathom/boundary-interface system) [{'(pc4.users/login
                                            {:system "cymru.nhs.uk" :value "ma090906'" :password "password"})
                                         [:io.jwt/token
                                          :wales.nhs.nadex/sAMAccountName
                                          :urn:oid:2.5.4/sn
                                          :wales.nhs.nadex/givenName
                                          :urn:oid:2.5.4/commonName
                                          :wales.nhs.nadex/personalTitle
                                          :wales.nhs.nadex/mail
                                          :t_user/active_projects
                                          :org.hl7.fhir.Practitioner/identifier
                                          :org.hl7.fhir.Practitioner/telecom
                                          {:org.hl7.fhir.Practitioner/name [:org.hl7.fhir.HumanName/family
                                                                            :org.hl7.fhir.HumanName/given]}
                                          :wales.nhs.nadex/professionalRegistration
                                          :uk.org.hl7.fhir.id/gmc-number]}])

  ((:pathom/boundary-interface system) [{'(pc4.users/refresh-token
                                            {:token "eyJhbGciOiJIUzI1NiJ9.eyJzeXN0ZW0iOiJ1ay5uaHMuY3ltcnUiLCJ2YWx1ZSI6Im1hMDkwOTA2IiwiZXhwIjoxNjIzOTYxMzc1fQ.q3O6NcIuNexVU268C2l8KoIjGQ2AT19sSn77FbwD03o"})
                                         [:io.jwt/token]}])

  ((:pathom/boundary-interface system) {:system "cymru.nhs.uk" :value "ma090906" :password "password"})

  ((:pathom/boundary-interface system) [{'(wales.nhs.cavuhb/fetch-patient
                                            {:system "http://fhir.cavuhb.nhs.wales/Id/pas-identifier" :value "A999998"})
                                         [:org.hl7.fhir.Patient/birthDate
                                          :wales.nhs.cavuhb.Patient/DATE_DEATH
                                          :uk.nhs.cfh.isb1505/display-age
                                          :wales.nhs.cavuhb.Patient/IS_DECEASED
                                          :wales.nhs.cavuhb.Patient/ADDRESSES
                                          :wales.nhs.cavuhb.Patient/HOSPITAL_ID
                                          :uk.nhs.cfh.isb1504/nhs-number
                                          :uk.nhs.cfh.isb1506/patient-name
                                          :org.hl7.fhir.Patient/identifier
                                          :org.hl7.fhir.Patient/name
                                          :wales.nhs.cavuhb.Patient/SEX
                                          :org.hl7.fhir.Patient/gender
                                          :org.hl7.fhir.Patient/deceased
                                          :org.hl7.fhir.Patient/currentAddress]}])


  ((:pathom/boundary-interface system)
   [{[:info.snomed.Concept/id 24700007] [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/lowercaseTerm]}]}])

  ;; updating legacy RF1-based SNOMED in real-time based on new RF2 data:
  ;; t_concept : concept_id
  ;;             concept_status_code
  ;;             ctv_id     ;; not null- but we never use it. can be ""
  ;;             fully_specified_name
  ;;             is_primitive ;; integer 1 or 0
  ;;             snomed_id  ;; not null - but we never use it. can be ""
  ;; t_cached_parent_concepts
  ;;             child_concept_id
  ;;             parent_concept_id
  ;; t_description
  ;;             concept_id
  ;;             description_id
  ;;             description_status_code
  ;;             description_type_code
  ;;             initial_capital_status
  ;;             language_code
  ;;             term
  ;; t_relationship
  ;;   characteristic_type
  ;;   refinability
  ;;   relationship_group
  ;;   relationship_id
  ;;   relationship_type_concept_id
  ;;   source_concept_id
  ;;   target_concept_id
  ;;   date_updated

  )