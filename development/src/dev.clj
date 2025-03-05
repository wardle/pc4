(ns dev
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.spec.test.alpha :as stest]
   [cognitect.transit :as transit]
   [integrant.core :as ig]
   [integrant.repl :as ig.repl]
   [integrant.repl.state]
   [pc4.config.interface :as config]
   [pc4.deprivare.interface :as deprivare]
   [pc4.dmd.interface :as dmd]
   [pc4.fulcro-server.interface]
   [pc4.nhspd.interface :as nhspd]
   [pc4.ods.interface :as clods]
   [pc4.rsdb.interface :as rsdb]
   [pc4.snomedct.interface :as hermes]
   [portal.api :as portal]))

(stest/instrument)                                          ;; turn on instrumentation for development

(ig/load-namespaces (config/config :dev) [:pc4.fulcro-server.interface/server])

(defn prep-dev-system []
  (let [conf (config/config :dev)]
    (ig/load-namespaces conf)
    conf))

(ig.repl/set-prep! prep-dev-system)

(defn reset-system []
  (ig.repl/halt)
  (ig.repl/prep)
  (ig/load-namespaces (config/config :dev) [:pc4.fulcro-server.interface/server])
  (ig.repl/init [:pc4.fulcro-server.interface/server]))

(defn inspect-system []
  (tap> #'integrant.repl.state/system))

(defn system [] integrant.repl.state/system)

(comment
  (portal/open {:launcher :intellij})
  (portal/open)
  (add-tap #'portal/submit)
  (ig/load-namespaces (config/config :dev) [:pc4.fulcro-server.interface/server])
  (tap> (config/config :dev))
  (tap> integrant.repl.state/system)

  (ig.repl/go [:pc4.fulcro-server.interface/server])

  (ig/halt! (system))
  (ig.repl/halt)
  (ig/halt! integrant.repl.state/system)

  ;; in vim, mark this form using mm  - then run anytime, using ,emm
  (reset-system)

  (def system integrant.repl.state/system)
  (def pathom (:pc4.graph.interface/boundary-interface integrant.repl.state/system))

  (:com.eldrix.deprivare/ops (config/config :dev))
  ;; start a system without a server  (REPL usage only)
  (def system (ig/init (config/config :dev) [:pc4.graph.interface/env]))
  (def system (ig/init (config/config :dev) [:pc4.graph.interface/boundary-interface]))
  (tap> system)
  (ig/halt! system)
  (do
    (ig/halt! system)
    (def system (ig/init (config/config :dev) [:pc4.graph.interface/boundary-interface])))

  ;; start a server using pedestal/jetty
  (ig/load-namespaces (config/config :dev) [:pc4.fulcro-server.interface/server])
  (def system (ig/init (config/config :dev) [:pc4.fulcro-server.interface/server]))
  (ig/halt! system)
  (do (ig/halt! system)
      (ig/load-namespaces (config/config :dev) [:pc4.fulcro-server.interface/server])
      (def system (ig/init (config/config :dev) [:pc4.fulcro-server.interface/server])))

  ;; exercise some of the components...
  (keys system)
  (clods/fetch-org (:com.eldrix/clods system) nil "7a4")
  (hermes/search (:com.eldrix/hermes system) {:s "amlodipine" :max-hits 1 :constraint "<10363601000001109"})
  (hermes/get-extended-concept (:com.eldrix/hermes system) 108537001)
  (dmd/fetch-release-date (:com.eldrix/dmd system))
  (dmd/fetch-product (:com.eldrix/dmd system) 108537001)
  (nhspd/fetch-postcode (:com.eldrix/nhspd system) "cf144xw")
  (deprivare/fetch-installed (:com.eldrix/deprivare system))
  (deprivare/fetch-lsoa (:com.eldrix/deprivare system) "W01001770")

  (require '[com.eldrix.pc4.rsdb.patients :as patients])
  (com.eldrix.pc4.rsdb.patients/fetch-patient (:com.eldrix.rsdb/conn system) {:t_patient/id 8})
  (rsdb/fetch-death-certificate (:com.eldrix.rsdb/conn system) {:t_patient/id 27204})
  (rsdb/fetch-patient-addresses (:com.eldrix.rsdb/conn system) {:t_patient/id 8})

  (:pathom/ops system)
  ;; for experimentation, we can create an authenticated environment in which we have a valid user:
  (def env (com.eldrix.pc4.users/make-authenticated-env (:com.eldrix.rsdb/conn system) {:system "cymru.nhs.uk" :value "system"}))
  env
  (def resp ((:pathom/boundary-interface system) env [(list 'pc4.rsdb/register-patient-by-pseudonym
                                                            {:project-id 84, :nhs-number "111", :date-birth nil, :gender nil})]))
  (def out (java.io.ByteArrayOutputStream. 4096))
  (def writer (cognitect.transit/writer out :json))
  (cognitect.transit/write writer resp)
  (str out)
  resp
  (type resp)
  (keys (get resp 'pc4.rsdb/register-patient-by-pseudonym)))

(comment
  (def system (pc4/init :dev [:pathom/boundary-interface]))
  (pc4/halt! system)

  (:wales.nhs.cav/pms system)
  (keys system)
  (def process (:pathom/boundary-interface system))
  (process [{[:t_patient/patient_identifier 12182]
             [:t_patient/id
              :t_patient/first_names
              :t_patient/last_name
              :t_patient/status
              :t_patient/nhs_number
              {:t_patient/hospitals [:uk.nhs.ord/name
                                     :uk.nhs.ord/orgId
                                     :t_patient_hospital/id
                                     :t_patient_hospital/hospital_fk
                                     :wales.nhs.cavuhb.Patient/HOSPITAL_ID
                                     :wales.nhs.cavuhb.Patient/NHS_NUMBER
                                     :wales.nhs.cavuhb.Patient/LAST_NAME
                                     :wales.nhs.cavuhb.Patient/FIRST_NAMES
                                     :wales.nhs.cavuhb.Patient/DATE_BIRTH
                                     :wales.nhs.cavuhb.Patient/DATE_DEATH
                                     :wales.nhs.cavuhb.Patient/TITLE
                                     :t_patient_hospital/patient_identifier]}
              {:t_patient/surgery [:uk.nhs.ord/name
                                   :uk.nhs.ord/orgId]}]}]))

(comment
  (def system (pc4/init :dev))
  (pc4/load-namespaces :dev/dell)
  (def system (pc4/init :dev/dell [:pathom/boundary-interface]))
  (pc4/halt! system)
  (def process (:pathom/boundary-interface system))

  (process
   [{'(wales.nhs.empi/fetch-patient
       {:system "https://fhir.nhs.uk/Id/nhs-number" :value "1234567890"})
     [:org.hl7.fhir.Patient/identifier
      :org.hl7.fhir.Patient/name
      :org.hl7.fhir.Patient/gender]}])

  (process [{[:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id "W93036"]
             [:uk.nhs.ord/orgId
              :org.hl7.fhir.Organization/name
              :org.hl7.fhir.Organization/address
              :uk.nhs.ord/generalPractitioners]}])

  (keys system)
  (process [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"]
             [:uk.gov.ons.nhspd/LSOA11
              :uk.gov.ons.nhspd/OSNRTH1M
              :uk.gov.ons.nhspd/OSEAST1M
              {:uk.gov.ons.nhspd/PCT_ORG [:uk.nhs.ord/name :uk.nhs.ord.primaryRole/displayName {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}]}])
  (p.eql/process (:pathom/env system)
                 [{[:info.snomed.Concept/id 108537001]
                   [:info.snomed.Concept/id
                    :info.snomed.Concept/preferredDescription
                    :uk.nhs.dmd/NM]}])

  (p.eql/process (:pathom/env system)
                 [{[:info.snomed.Concept/id 108537001]
                   [:uk.nhs.dmd/VTMID
                    :uk.nhs.dmd/NM
                    {:uk.nhs.dmd/VMPS [:uk.nhs.dmd/VPID :uk.nhs.dmd/NM
                                       {:uk.nhs.dmd/VIRTUAL_PRODUCT_INGREDIENT [:uk.nhs.dmd/STRNT_NMRTR_VAL
                                                                                :uk.nhs.dmd/STRNT_DNMTR_VAL
                                                                                {:uk.nhs.dmd/STRNT_NMRTR_UOM [:uk.nhs.dmd/DESC]}
                                                                                {:uk.nhs.dmd/STRNT_DNMTR_UOM [:uk.nhs.dmd/DESC]}
                                                                                '(:info.snomed.Concept/parentRelationshipIds {:type 116680003})]}]}
                    :info.snomed.Concept/id
                    {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}])

  (com.eldrix.dmd.store/fetch-product (get-in system [:pathom/registry :com.eldrix.dmd.graph/store]) 108537001)

  (p.eql/process (:pathom/env system)
                 [{(list 'pc4.users/login
                         {:system "wales.nhs.uk" :value "ma090906'" :password "password"})
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
                    {:org.hl7.fhir.Practitioner/name
                     [:org.hl7.fhir.HumanName/use
                      :org.hl7.fhir.HumanName/family
                      :org.hl7.fhir.HumanName/given]}]}])

  (p.eql/process (:pathom/env system)
                 [{(list 'pc4.users/ping
                         {:uuid "hi there"})
                   [:uuid]}])

  (p.eql/process (:pathom/env system)
                 [{[:t_patient/patient_identifier 12182]
                   [:t_patient/id
                    :t_patient/first_names
                    :t_patient/last_name
                    :t_patient/status
                    {:t_patient/surgery [:uk.nhs.ord/name]}
                    {:t_patient/encounters [:t_encounter/id
                                            :t_encounter/date_time
                                            :t_encounter/is_deleted
                                            {:t_encounter/hospital [:uk.nhs.ord/name]}
                                            {:t_encounter/users [:t_user/id
                                                                 :t_user/initials
                                                                 :t_user/full_name]}]}]}])
  (require '[com.eldrix.hermes.snomed :as snomed])
  (p.eql/process (:pathom/env system)
                 [{[:t_patient/patient_identifier 81253]
                   [:t_patient/last_name]}

                  {[:t_patient/patient_identifier 17490]
                   [:t_patient/last_name
                    :org.hl7.fhir.Patient/gender
                    {:t_patient/ethnic_origin [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/lowercaseTerm]}
                                               `(:info.snomed.Concept/parentRelationshipIds {:type ~snomed/IsA})]}
                    :t_patient/status
                    {:t_patient/address [:t_address/address1 :t_address/address2 :uk.gov.ons.nhspd/LSOA11 :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M]}
                    {:t_patient/surgery [:org.w3.2004.02.skos.core/prefLabel]}
                    {:t_patient/hospitals [:t_patient_hospital/patient_identifier
                                           {:t_patient_hospital/hospital [:uk.nhs.ord/name
                                                                          :org.w3.2004.02.skos.core/prefLabel]}]}]}]))

(comment
  (def x (com.eldrix.pc4.rsdb.results/parse-count-lesions "5+/-2"))

  (results/lesion-range (com.eldrix.pc4.rsdb.results/parse-count-lesions ">12"))

  (gen/generate (s/gen ::lesion-count))
  (gen/generate (s/gen ::lesion-number))
  (s/valid? ::lesion-count [:plus-minus 4 1])
  (com.eldrix.pc4.rsdb.results/parse-count-lesions "6-3")
  x)
