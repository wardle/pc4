(ns com.eldrix.pc4.system
  "Composes building blocks into a system using aero, integrant and pathom."
  (:require [aero.core :as aero]
            [buddy.core.codecs :as codecs]
            [buddy.core.hash :as hash]
            [clojure.core.server]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.wales.cav-pms :as cav-pms]
            [com.eldrix.concierge.wales.empi :as empi]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [com.eldrix.clods.core :as clods]
            [com.eldrix.deprivare.core :as deprivare]
            [com.eldrix.deprivare.graph]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.nhspd.core :as nhspd]
            [com.eldrix.odsweekly.core :as ods-weekly]
            [com.eldrix.pc4.filestorage :as fstore]
            [com.eldrix.pc4.rsdb :as rsdb]
            [com.eldrix.pc4.rsdb.migrations :as migrations]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.error :as p.error]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [com.wsscode.pathom3.plugin :as p.plugin]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [edn-query-language.core :as eql]
            [integrant.core :as ig]
            [next.jdbc.connection :as connection])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.io File)
           (java.time Duration LocalDate)))

(defmethod ig/init-key :com.eldrix/nhspd [_ {:keys [root f path]}]
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening nhspd index " path')
    (nhspd/open-index path')))

(defmethod ig/halt-key! :com.eldrix/nhspd [_ nhspd]
  (.close nhspd))

(defmethod ig/init-key :com.eldrix/clods [_ {:keys [root f path nhspd]}]
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening clods index from " path')
    (clods/open-index {:ods-dir path' :nhspd nhspd})))

(defmethod ig/halt-key! :com.eldrix/clods [_ clods]
  (.close clods))

(defmethod ig/init-key :com.eldrix/ods-weekly [_ {:keys [root f path]}]
  (if-let [path' (or path (when (and root f) (.getCanonicalPath (io/file root f))))]
    (do (log/info "opening ods-weekly from " path')
        (ods-weekly/open-index path'))
    (log/info "skipping ods-weekly; no path specified")))

(defmethod ig/init-key :com.eldrix/deprivare [_ {:keys [root f path]}]
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening deprivare index: " path')
    (deprivare/open path')))

(defmethod ig/halt-key! :com.eldrix/deprivare [_ svc]
  (deprivare/close svc))

(defmethod ig/init-key :com.eldrix.deprivare/ops [_ svc]
  (com.eldrix.deprivare.graph/make-all-resolvers svc))

(defmethod ig/init-key :com.eldrix/dmd [_ {:keys [root f path]}]
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening UK NHS dm+d index: " path')
    (dmd/open-store path')))

(defmethod ig/halt-key! :com.eldrix/dmd [_ dmd]
  (.close dmd))

(defmethod ig/init-key :com.eldrix/hermes [_ {:keys [root f path]}]
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening hermes from " path')
    (hermes/open path')))

(defmethod ig/halt-key! :com.eldrix/hermes [_ svc]
  (.close svc))

(defmethod ig/init-key :wales.nhs/nadex
  [_ params]
  (if (:pool-size params)
    (do (log/info "configuring NADEX:" (select-keys params [:host :hosts :pool-size]))
        (-> params
            (assoc :connection-pool (nadex/make-connection-pool params))))
    (log/info "skipping NADEX as no configuration")))

(defmethod ig/halt-key! :wales.nhs/nadex [_ {:keys [connection-pool]}]
  (when connection-pool (.close connection-pool)))

(defmethod ig/init-key :com.eldrix.rsdb/conn
  [_ params]
  (log/info "registering PatientCare EPR [rsdb] connection" (dissoc params :password))
  (connection/->pool HikariDataSource params))

(defmethod ig/halt-key! :com.eldrix.rsdb/conn
  [_ conn]
  (.close conn))

(defmethod ig/init-key :com.eldrix.rsdb/migration-config
  [_ config]
  config)

(defmethod ig/init-key :com.eldrix.rsdb/check-migrations
  [_ config]
  (if-let [migrations (seq (migrations/pending-list config))]
    (throw (ex-info (str "Error: " (count migrations) " pending database migration(s): " migrations) {:pending migrations}))
    config))

(defmethod ig/init-key :com.eldrix.rsdb/run-migrations
  [_ config]
  (if-let [migrations (seq (migrations/pending-list config))]
    (do (log/info "running migrations:" migrations)
        (if (migrations/migrate config)
          (throw (ex-info "failed to perform migrations" {:config  config
                                                          :pending migrations}))
          (log/info "migrations finished successfully")))
    (log/info "no migrations pending")))

(defmethod ig/init-key :com.eldrix.rsdb/config [_ config]
  config)

(defmethod ig/init-key :com.eldrix.pc4/fake-login-provider
  [_ {:keys [username password] :as options}]
  options)

(defmethod ig/init-key :com.eldrix.pc4/login
  [_ config]
  (log/info "registering login providers:" (keys (:providers config)))
  config)

(defmethod ig/init-key :wales.nhs.cavuhb/pms [_ config]
  config)

(defmethod ig/init-key :wales.nhs/empi [_ config]
  config)

(defmethod ig/init-key :com.eldrix.pc4/demographic-service
  [_ {:keys [empi cavuhb]}]
  ;; create a demographic service that can resolve a identifier against a
  ;; a given authority returning patient data in a FHIR representation
  (fn demographic-service [authority {:org.hl7.fhir.Identifier/keys [system value]}]
    (case authority                                         ;; we double-check that we have an active configuration before resolving
      :EMPI (when empi (empi/resolve! empi system value))
      :CAVUHB (when cavuhb (cav-pms/fetch-patient cavuhb system value))
      (constantly nil))))

(defmethod ig/init-key :pathom/ops [_ ops]
  (flatten ops))

(defmethod ig/init-key :pathom/env [_ {:pathom/keys [ops] :as env}]
  (log/info "creating pathom registry" {:n-operations (count ops)})
  (run! #(log/trace "op: " %)
        (sort (map (fn [r] (str (get-in r [:config :com.wsscode.pathom3.connect.operation/op-name]))) ops)))
  (-> env
      (dissoc :pathom/ops)
      (assoc ::p.error/lenient-mode? true
             :com.wsscode.pathom3.format.eql/map-select-include #{:tempids}) ;; always include request for tempids
      (pci/register ops)
      (p.plugin/register
       {::p.plugin/id `handle-resolver-err
        ::pcr/wrap-resolver-error
        (fn [_]
          (fn [env node error]
            (when (instance? Throwable error)
              (.printStackTrace ^Throwable error))
            (log/error "pathom resolver error" {:node node :error error})))})
      (p.plugin/register
       {::p.plugin/id `handle-mutation-err
        ::pcr/wrap-mutate
        (fn [mutate]
          (fn [env params]
            (try
              (mutate env params)
              (catch Throwable err
                (.printStackTrace err)
                {::pcr/mutation-error (ex-message err)}))))})
      (p.plugin/register
       {::p.plugin/id `query-params->env
        ::p.eql/wrap-process-ast
        (fn [process]
          (fn [env ast]
            (let [children     (:children ast)
                  query-params (reduce
                                (fn [qps {:keys [type params] :as x}]
                                  (cond-> qps
                                    (and (not= :call type) (seq params)) (merge params)))
                                {}
                                children)
                  env          (assoc env :query-params query-params)]
              (process env ast))))})))

(defmethod ig/init-key :pathom/boundary-interface [_ {:keys [env config]}]
  (when (:connect-viz config)
    (log/info "connecting pathom-viz" config)
    (try
      (let [connect-env (requiring-resolve 'com.wsscode.pathom.viz.ws-connector.pathom3/connect-env)]
        (connect-env env (merge {:com.wsscode.pathom.viz.ws-connector.core/parser-id 'pc4} config)))
      (catch Exception _ (log/warn "unable to connect to pathom-viz as dependency not available in this build"))))
  (p.eql/boundary-interface env))

(defmethod ig/init-key :com.eldrix.pc4/filestorage [_ {:keys [link-duration retention-duration] :as config}]
  (let [config' (cond-> config
                  link-duration (assoc :link-duration (Duration/parse link-duration))
                  retention-duration (assoc :retention-duration (Duration/parse retention-duration)))]
    (log/info "registering filestore service" (select-keys config' [:kind :region :bucket-name :dir :link-duration :retention-duration]))

    (com.eldrix.pc4.filestorage/make-file-store config')))

(defmethod ig/halt-key! :com.eldrix.pc4/filestorage [_ fs]
  (.close fs))

(defmethod ig/init-key :com.eldrix.pc4/cljs-modules [_ {path :manifest-path}]
  (log/debug "looking for cljs modules in " path)
  (if-let [manifest-url (io/resource path)]
    (let [manifest (edn/read-string (slurp manifest-url))]
      (log/info "found cljs modules" (reduce (fn [acc {:keys [module-id output-name]}] (assoc acc module-id output-name)) {} manifest))
      (reduce (fn [acc {:keys [module-id] :as module}]      ;; return a map of module-id to module information
                (assoc acc module-id module)) {} manifest))
    (log/info "no shadow cljs manifest file found")))

(defmethod ig/init-key :repl/server [_ {server-name :name, :as opts}]
  (if server-name
    (do (log/info "starting repl server" (select-keys opts [:name :port]))
        {:server-name server-name
         :server      (clojure.core.server/start-server opts)})
    (log/info "skipping repl server: not configured")))

(defmethod ig/halt-key! :repl/server [_ {:keys [server-name]}]
  (clojure.core.server/stop-server server-name))

(defmethod aero/reader 'ig/ref [_ _ x]
  (ig/ref x))

(defmethod aero/reader 'clj/var [_ _ x]
  (var-get (requiring-resolve x)))

(defn config
  "Reads configuration from the resources directory using the profile specified.
  Removes any non-namespaced keys from the configuration."
  [profile]
  (let [conf (aero/read-config (io/resource "config.edn") {:profile profile})
        kws-without-ns (seq (remove namespace (keys conf)))] ;; get any non-namespaced keys
    (apply dissoc conf kws-without-ns)))

(defn load-namespaces
  "Load any required namespaces for the profile and, optionally, services
  specified.
  Parameters:
  profile   : a defined profile
  keys      : a vector of keys in a configuration to be loaded."
  ([profile]
   (ig/load-namespaces (config profile)))
  ([profile keys]
   (ig/load-namespaces (config profile) keys)))

(defn init
  "Create a pc4 'system' using the profile and keys specified."
  ([profile]
   (ig/init (config profile)))
  ([profile keys]
   (ig/init (config profile) keys)))

(defn halt! [system]
  (ig/halt! system))

(comment
  (config :dev)
  (config :live)

  (load-namespaces :dev)

  (config :pc4)
  ;; start a server using pedestal/jetty
  (def system (init :dev [:http/server]))
  (ig/halt! system)

  ;; start a server using http-kit/ring
  (def system (init :dev [:http/server2]))
  (ig/halt! system)

  ;; this creates a fake authenticated environment and injects it into our system
  (def authenticated-env (com.eldrix.pc4.pedestal/make-authenticated-env (:com.eldrix.rsdb/conn system) {:system "cymru.nhs.uk" :value "ma090906"}))
  (def authenticated-env (com.eldrix.pc4.pedestal/make-authenticated-env (:com.eldrix.rsdb/conn system) {:system "cymru.nhs.uk" :value "system"}))
  (rsdb/delete-ms-event! (merge (:pathom/env system) authenticated-env) {:t_ms_event/id 1381})
  (ig/halt! system)

  (defn reload []
    (ig/halt! system)
    (def system (init :dev [:http/server])))
  (reload)
  (:pathom/env system)
  (rsdb/save-pseudonymous-patient-postal-code! (:pathom/env
                                                system) {:t_patient/patient_identifier 124018
                                                         :uk.gov.ons.nhspd/PCD2        "CF14 4XW"})
  (clods/fetch-postcode (:com.eldrix/clods system) "CF14 4XW")
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

  (com.eldrix.pc4.rsdb.projects/find-legacy-pseudonymous-patient (:com.eldrix.rsdb/conn system)
                                                                 {:salt       (:legacy-global-pseudonym-salt (:com.eldrix.rsdb/config system))
                                                                  :project-id 124
                                                                  :nhs-number "3333333333"
                                                                  :date-birth (LocalDate/of 1975 5 1)})
  (#'com.eldrix.pc4.rsdb.users/save-password! (:com.eldrix.rsdb/conn system) "system" "password")
  (com.eldrix.pc4.rsdb.users/check-password (:com.eldrix.rsdb/conn system) nil "system" "password")

  (comment
    (config :dev)))
