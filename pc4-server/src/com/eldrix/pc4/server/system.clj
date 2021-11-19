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
            [com.eldrix.comprehend.core :as comprehend]
            [com.eldrix.deprivare.core :as deprivare]
            [com.eldrix.deprivare.graph]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.dmd.graph]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.graph]
            [com.eldrix.pc4.server.api :as api]
            [com.eldrix.pc4.server.dates :as dates]
            [com.eldrix.pc4.server.rsdb :as rsdb]
            [com.eldrix.pc4.server.users :as users]
            [com.eldrix.pc4.server.patients :as patients]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.error :as p.error]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params]
            [io.pedestal.interceptor :as intc]
            [next.jdbc.connection :as connection]
            [buddy.sign.jwt :as jwt]
            [cognitect.transit :as transit]
            [io.pedestal.http.body-params :as body-params]

            [com.fulcrologic.fulcro.server.api-middleware :as server]
            [org.httpkit.server]
            [ring.middleware.content-type]
            [ring.middleware.cors]
            [ring.middleware.defaults])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time LocalDate)))

(def resolvers (atom []))

(defmethod ig/init-key :com.eldrix/clods [_ {:keys [ods-path nhspd-path]}]
  (log/info "opening nhspd index from " nhspd-path)
  (log/info "opening clods index from " ods-path)
  (log/info "registering UK ODS and NHSPD graph resolvers")
  (swap! resolvers into com.eldrix.clods.graph/all-resolvers)
  (clods/open-index ods-path nhspd-path))

(defmethod ig/halt-key! :com.eldrix/clods [_ clods]
  (.close clods))

(defmethod ig/init-key :com.eldrix/deprivare [_ {:keys [path]}]
  (log/info "opening deprivare index: " path)
  (let [svc (deprivare/open path)]
    (swap! resolvers into (com.eldrix.deprivare.graph/make-all-resolvers svc))
    svc))

(defmethod ig/halt-key! :com.eldrix/deprivare [_ svc]
  (com.eldrix.deprivare.core/close svc))

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

(defmethod ig/init-key :com.eldrix.rsdb/conn
  [_ params]
  (log/info "registering PatientCare EPR [rsdb] connection" params)
  (swap! resolvers into com.eldrix.pc4.server.rsdb/all-resolvers)
  (connection/->pool HikariDataSource params))

(defmethod ig/halt-key! :com.eldrix.rsdb/conn
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

(defmethod ig/init-key :com.eldrix/comprehend [_ config]
  (log/info "registering comprehend service")
  (com.eldrix.comprehend.core/open config))

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
           (-> (pci/register {::p.error/lenient-mode? true}
                             resolvers)))))

(defmethod ig/halt-key! :pathom/env [_ env]
  (reset! resolvers []))

(defmethod ig/init-key :pathom/boundary-interface [_ {:keys [env] :as config}]
  (p.eql/boundary-interface env))

(defmethod ig/init-key :http/server [_ {:keys [port allowed-origins host env join?] :or {port 8080 join? false} :as config}]
  (log/info "Running HTTP server" (dissoc config :env))
  (-> {::http/type            :jetty
       ::http/join?           join?
       ::http/routes          api/routes
       ::http/port            port
       ::http/allowed-origins (cond
                                (= "*" allowed-origins)
                                (constantly true)
                                :else
                                allowed-origins)
       ::http/host            (or host "127.0.0.1")}
      (http/default-interceptors)
      (update ::http/interceptors conj
              (intc/interceptor (api/inject env))
              (body-params/body-params (body-params/default-parser-map :transit-options [{:handlers dates/transit-readers}]))
              (http/transit-body-interceptor ::transit-json-body
                                             "application/transit+json;charset=UTF-8"
                                             :json
                                             {:handlers (merge dates/transit-writers
                                                               {clojure.lang.ExceptionInfo (transit/write-handler "ex-info" ex-data)
                                                                Throwable                  (transit/write-handler "java.lang.Exception" Throwable->map)})}))
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! :http/server [_ service-map]
  (http/stop service-map))

(def ^:private not-found-handler
  (fn [req]
    {:status  404
     :headers {"Content-Type" "text/plain"}
     :body    "Not Found"}))

(defn wrap-claims
  "Middleware that attaches valid claims to the request ':authenticated-claims'
  and returns 401 if no valid bearer token found."
  [handler login-config]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"])
          _ (log/info "request auth:" auth-header)
          [_ token] (when auth-header (re-matches #"(?i)Bearer (.*)" auth-header))
          claims (when (and token login-config) (users/check-user-token token login-config))]
      (when (and token (not login-config))
        (log/error "no valid login configuration available"))
      (if claims
        (handler (assoc request :authenticated-claims claims))
        #_(handler request)
        {:status  401
         :headers {"Content-Type" "text/plain"}
         :body    "Unauthorized. Request missing valid Bearer token."}))))

(defn wrap-login [handler {uri :uri pathom :pathom}]
  (fn [req]
    (if (= uri (:uri req))
      (let [{:keys [system value password query] :as params} (:transit-params req)]
        (clojure.pprint/pprint req)
        (log/info "login endpoint; parameters" (dissoc params :password))
        (com.fulcrologic.fulcro.server.api-middleware/handle-api-request
          [{(list 'pc4.users/login {:system system :value value :password password}) query}] pathom))
      (handler req))))

(defn wrap-authenticated-pathom
  "Attach an authenticated environment into the request, if possible."
  [handler {conn :com.eldrix.rsdb/conn pathom-boundary-interface :pathom-boundary-interface}]
  (fn [{claims :authenticated-claims :as req}]
    (let [{:keys [system value]} claims
          rsdb-user? (when claims (users/is-rsdb-user? conn system value))
          env (cond-> {}
                      claims
                      (assoc :authenticated-user (select-keys claims [:system :value]))
                      rsdb-user?
                      (assoc :authorization-manager (users/make-authorization-manager conn system value)))]
      (handler (assoc req :pathom (partial pathom-boundary-interface env))))))

(defn wrap-api
  [handler {:keys [uri]}]
  (when-not (string? uri)
    (throw (ex-info "Invalid parameters to `wrap-api`. :uri required." {})))
  (fn [req]
    (if (= uri (:uri req))
      (do
        (log/info "processing API call " req)
        (com.fulcrologic.fulcro.server.api-middleware/handle-api-request (:transit-params req) (:pathom req)))
      (handler req))))

(defn wrap-log-response [handler]
  (fn [req]
    (log/info "request:" req)
    (let [response (handler req)]
      (log/info "response:" response)
      response)))

(defn wrap-hello [handler uri]
  (fn [req]
    (if (= uri (:uri req))
      {:status  200
       :body    "Hello World"
       :headers {"Content-Type" "text/plain"}}
      (handler req))))

(defmethod ig/init-key :http/handler [_ {:keys [ring-defaults login-config pathom-boundary-interface] :as config}]
  #_(com.fulcrologic.fulcro.algorithms.transit/install-type-handler!
      (com.fulcrologic.fulcro.algorithms.transit/type-handler
        java.time.LocalDate "LocalDate"
        (fn [^java.time.LocalDate date]
          (.format date java.time.format.DateTimeFormatter/ISO_LOCAL_DATE))
        (fn [s]
          (java.time.LocalDate/parse s))))
  #_(com.fulcrologic.fulcro.algorithms.transit/install-type-handler!
      (com.fulcrologic.fulcro.algorithms.transit/type-handler
        java.time.LocalDateTime "LocalDateTime"
        (fn [^java.time.LocalDateTime date-time]
          (.format date-time java.time.format.DateTimeFormatter/ISO_LOCAL_DATE_TIME))
        (fn [s]
          (java.time.LocalDateTime/parse s))))
  (-> not-found-handler
      (wrap-api {:uri "/api"})
      (wrap-authenticated-pathom config)
      (wrap-claims login-config)
      (wrap-login {:pathom pathom-boundary-interface :uri "/login"})
      (com.fulcrologic.fulcro.server.api-middleware/wrap-transit-params {:opts {:handlers dates/transit-readers}})
      (com.fulcrologic.fulcro.server.api-middleware/wrap-transit-response {:opts {:handlers dates/transit-writers}})
      ; (wrap-hello "/hello")
      (ring.middleware.defaults/wrap-defaults ring-defaults)
      (ring.middleware.cors/wrap-cors
        :access-control-allow-origin [#".*"]
        :access-control-allow-headers ["Content-Type"]
        :access-control-allow-methods [:post]
        :access-control-allow-headers #{"accept"
                                        "accept-encoding"
                                        "accept-language"
                                        "authorization"
                                        "content-type"
                                        "origin"})
      (wrap-log-response)
      ))

(defmethod ig/init-key :http/server2 [_ {:keys [port allowed-origins host handler] :as config}]
  (log/info "running HTTP server" (dissoc config :handler))
  (when-not handler
    (throw (ex-info "invalid http server configuration: expected 'handler' key" config)))
  (org.httpkit.server/run-server handler (cond-> config     ;; TODO: support allowed-origins
                                                 (= "*" allowed-origins) (assoc :legal-origins #".*")
                                                 (seq allowed-origins) (assoc :legal-origins allowed-origins)
                                                 host (assoc :ip host))))

(defmethod ig/halt-key! :http/server2 [_ stop-server-fn]
  (stop-server-fn))



(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(defn config
  "Reads configuration from the resources directory using the profile specified."
  [profile]
  (-> (aero/read-config (io/resource "config.edn") {:profile profile})
      (dissoc :secrets)))

(defn prep [profile]
  (ig/load-namespaces (config profile)))

(defn init
  ([profile]
   (reset! resolvers [])
   (ig/init (config profile)))
  ([profile keys]
   (reset! resolvers [])
   (ig/init (config profile) keys)))

(comment
  (config :dev)
  (config :live)

  (prep :dev)

  ;; start a server for re-frame front-end
  (def system (init :dev [:http/server]))
  (ig/halt! system)

  ;; start a server for fulcro front-end
  (def system (init :dev [:http/server2]))
  (ig/halt! system)


  (transit/reader (:body response))

  ;; this creates a fake authenticated environment and injects it into our system
  (def authenticated-env (com.eldrix.pc4.server.api/make-authenticated-env (:com.eldrix.rsdb/conn system) {:system "cymru.nhs.uk" :value "ma090906"}))
  (def authenticated-env (com.eldrix.pc4.server.api/make-authenticated-env (:com.eldrix.rsdb/conn system) {:system "cymru.nhs.uk" :value "system"}))

  (rsdb/delete-ms-event! (merge (:pathom/env system) authenticated-env) {:t_ms_event/id 1381})
  (reset! resolvers [])
  (ig/halt! system)

  (defn reload []
    (ig/halt! system)
    (def system (init :dev [:http/server])))
  (reload)
  (count @resolvers)
  (sort (map str (map #(get-in % [:config :com.wsscode.pathom3.connect.operation/op-name]) (flatten default-resolvers))))
  (sort (map #(get-in % [:config :com.wsscode.pathom3.connect.operation/op-name]) (flatten [@resolvers default-resolvers])))

  (:pathom/env system)
  (rsdb/save-pseudonymous-patient-postal-code! (:pathom/env
                                                 system) {:t_patient/patient_identifier 124018
                                                          :uk.gov.ons.nhspd/PCD2 "CF14 4XW"})
  (clods/fetch-postcode (:com.eldrix/clods system) "CF14 4XW")
  (save)
  (keys system)
  ((:pathom/boundary-interface system) [{[:uk.gov.ons.nhspd/PCDS "b30 1hl"]
                                         [:uk.gov.ons.nhspd/LSOA11
                                          :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M
                                          :urn.ogc.def.crs.EPSG.4326/latitude
                                          :urn.ogc.def.crs.EPSG.4326/longitude
                                          {:uk.gov.ons.nhspd/LSOA-2011 [:uk.gov.ons/lsoa
                                                                        :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
                                          :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile
                                          :uk-composite-imd-2020-mysoc/UK_IMD_E_rank
                                          {:uk.gov.ons.nhspd/PCT_ORG [:uk.nhs.ord/name :uk.nhs.ord/active :uk.nhs.ord/orgId]}]}])

  ((:pathom/boundary-interface system) [{[:info.snomed.Concept/id 24700007] [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/lowercaseTerm]}]}])
  (time (second (first ((:pathom/boundary-interface system) [{[:info.snomed.Concept/id 80146002]
                                                              [:info.snomed.Concept/id
                                                               :info.snomed.Concept/active
                                                               '(:info.snomed.Concept/preferredDescription {:accept-language "en-GB"})
                                                               {:info.snomed.Concept/descriptions
                                                                [:info.snomed.Description/active :info.snomed.Description/lowercaseTerm]}]}]))))

  ((:pathom/boundary-interface system) [{'(info.snomed.Search/search
                                            {:s          "mult scl"
                                             :constraint "<404684003"
                                             :max-hits   10})
                                         [:info.snomed.Concept/id
                                          :info.snomed.Description/id
                                          :info.snomed.Description/term
                                          {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
                                          :info.snomed.Concept/active]}])

  ((:pathom/boundary-interface system) [{'(info.snomed/parse
                                            {:s "He has multiple sclerosis."})
                                         [:info.snomed.Concept/id
                                          :info.snomed.Description/id
                                          :info.snomed.Description/term
                                          {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
                                          :info.snomed.Concept/active]}])

  ((:pathom/boundary-interface system) [{'(pc4.users/login
                                            {:system "cymru.nhs.uk" :value "ma090906'" :password "password"})
                                         [:urn:oid:1.2.840.113556.1.4/sAMAccountName
                                          :io.jwt/token
                                          :urn:oid:2.5.4/givenName
                                          :urn:oid:2.5.4/surname
                                          :urn:oid:0.9.2342.19200300.100.1.3
                                          :urn:oid:2.5.4/commonName
                                          :urn:oid:2.5.4/title
                                          :urn:oid:2.5.4/telephoneNumber
                                          :org.hl7.fhir.Practitioner/telecom
                                          :org.hl7.fhir.Practitioner/identifier
                                          {:t_user/active_projects ;;; iff the user has an rsdb account, this will be populated
                                           [:t_project/id :t_project/name :t_project/title :t_project/slug
                                            :t_project/is_private
                                            :t_project/long_description :t_project/type :t_project/virtual]}
                                          {:org.hl7.fhir.Practitioner/name
                                           [:org.hl7.fhir.HumanName/use
                                            :org.hl7.fhir.HumanName/prefix
                                            :org.hl7.fhir.HumanName/family
                                            :org.hl7.fhir.HumanName/given
                                            :org.hl7.fhir.HumanName/suffix]}]}])

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
  (reload)

  ((:pathom/boundary-interface system)                      ;here we have to include an authenticated environment in order to do this action
   authenticated-env
   [{(list 'pc4.rsdb/register-patient-by-pseudonym
           {:user-id    1
            :project-id 124
            :nhs-number "1111111111"
            :sex        :MALE
            :date-birth (LocalDate/of 1973 10 5)})
     [:t_patient/id
      :t_patient/patient_identifier
      :t_patient/first_names
      :t_patient/last_name
      :t_patient/date_birth]}])

  ((:pathom/boundary-interface system) [{(list 'pc4.rsdb/search-patient-by-pseudonym
                                               {:project-id 124
                                                :pseudonym  "686"})
                                         [:t_patient/id
                                          :t_patient/patient_identifier
                                          :t_patient/first_names
                                          :t_patient/last_name
                                          :t_patient/date_birth
                                          :t_patient/status
                                          :t_patient/date_death]}])



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

  (com.eldrix.pc4.server.rsdb.projects/find-legacy-pseudonymous-patient (:com.eldrix.rsdb/conn system)
                                                                        {:salt       (:legacy-global-pseudonym-salt (:com.eldrix.rsdb/config system))
                                                                         :project-id 124
                                                                         :nhs-number "3333333333"
                                                                         :date-birth (LocalDate/of 1975 5 1)})
  )