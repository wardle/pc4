(ns com.eldrix.pc4.dev
  (:require
    [clojure.repl :as repl :refer [doc]]
    [clojure.spec.test.alpha :as stest]
    [com.eldrix.pc4.system :as pc4]
    [integrant.core :as ig]
    [com.eldrix.clods.core :as clods]
    [com.eldrix.pc4.rsdb.patients :as patients]
    [com.eldrix.pc4.rsdb.results :as results]
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [cognitect.transit :as transit]
    [com.eldrix.pc4.users :as users]
    [portal.api :as portal]
    [com.eldrix.pc4.rsdb.projects :as projects]))

(stest/instrument)                                          ;; turn on instrumentation for development


(comment
  (def p (portal/open {:launcher :intellij}))               ;; open intellij portal (needs portal inspector)
  (def p (portal/open))                                     ;; or... open browser-based portal
  (add-tap #'portal/submit)

  (:com.eldrix.deprivare/ops (pc4/config :dev))
  ;; start a system without a server  (REPL usage only)
  (def system (pc4/init :dev [:pathom/env]))
  (def system (pc4/init :dev [:pathom/boundary-interface]))
  (tap> system)
  (ig/halt! system)
  (do
    (ig/halt! system)
    (def system (pc4/init :dev [:pathom/env :wales.nhs.cavuhb/pms])))

  ;; start a graph API server
  (pc4/load-namespaces :dev [:com.eldrix.pc4.api/server])
  (def system (pc4/init :dev [:com.eldrix.pc4.api/server]))
  (ig/halt! system)

  ;; start a www server
  (pc4/load-namespaces :dev [:com.eldrix.pc4.www/server])
  (def system (pc4/init :dev [:com.eldrix.pc4.www/server]))
  (ig/halt! system)

  (do (ig/halt! system)
      (pc4/load-namespaces :dev [:com.eldrix.pc4.www/server])
      (def system (pc4/init :dev [:com.eldrix.pc4.www/server])))
  (tap> system)

  (com.eldrix.pc4.rsdb.projects/common-concepts-for-project (:com.eldrix.rsdb/conn system) 5)
  (com.eldrix.pc4.rsdb.users/all-projects-and-children-identifiers (:com.eldrix.rsdb/conn system) "ma090906")
  ;; exercise some of the components...
  (keys system)
  (tap> (com.eldrix.clods.core/fetch-org (:com.eldrix/clods system) nil "7a4"))
  (com.eldrix.hermes.core/search (:com.eldrix/hermes system) {:s "amlodipine" :max-hits 1 :constraint "<10363601000001109"})
  (com.eldrix.hermes.core/get-extended-concept (:com.eldrix/hermes system) 108537001)
  (com.eldrix.dmd.core/fetch-release-date (:com.eldrix/dmd system))
  (com.eldrix.dmd.core/fetch-product (:com.eldrix/dmd system) 108537001)
  (com.eldrix.nhspd.core/fetch-postcode (:com.eldrix/nhspd system) "cf144xw")
  (com.eldrix.deprivare.core/fetch-installed (:com.eldrix/deprivare system))
  (com.eldrix.deprivare.core/fetch-lsoa (:com.eldrix/deprivare system) "W01001770")


  (require '[com.eldrix.pc4.rsdb.patients :as patients])
  (patients/fetch-death-certificate (:com.eldrix.rsdb/conn system) {:t_patient/id 27204})
  (patients/fetch-patient-addresses (:com.eldrix.rsdb/conn system) {:t_patient/id 8})

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
  (def system (pc4/init :dev [:pathom/boundary-interface]))
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
                 [{[:t_patient/patient-identifier 12182]
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
                 [{[:t_patient/patient-identifier 81253]
                   [:t_patient/last_name]}

                  {[:t_patient/patient-identifier 17490]
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



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;   THIS COMMENT BLOCK IS FOR INTERACTIVE USE WITH THE REMOTE SERVER PC4   ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment
  ;; just connect to remote database
  (def system (pc4/init :pc4-dev [:com.eldrix.rsdb/conn :com.eldrix.rsdb/config]))
  (def system (pc4/init :pc4-dev [:pathom/boundary-interface]))
  (ig/halt! system)
  (com.eldrix.pc4.rsdb.users/fetch-user-by-id (:com.eldrix.rsdb/conn system) 1)

  (require '[com.eldrix.pc4.modules.dmt :as dmt])
  (com.eldrix.pc4.modules.dmt/write-data system :plymouth)
  (com.eldrix.pc4.modules.dmt/write-data system :cambridge)
  (com.eldrix.pc4.modules.dmt/merge-matching-data "/Users/mark/lemtrada/centres" "/Users/mark/lemtrada/combined")

  (com.eldrix.pc4.rsdb.users/create-user (:com.eldrix.rsdb/conn system) {:t_user/username     "er528"
                                                                         :t_user/first_names  "Eleanor"
                                                                         :t_user/last_name    "Reffin"
                                                                         :t_user/title        "Ms"
                                                                         :t_user/email        "er528@cam.ac.uk"
                                                                         :t_user/job_title_fk 1})
  (def random-password (org.apache.commons.lang3.RandomStringUtils/randomAlphabetic 32))
  random-password
  (#'com.eldrix.pc4.rsdb.users/save-password! (:com.eldrix.rsdb/conn system) "er528" random-password)
  (com.eldrix.pc4.rsdb.users/set-must-change-password! (:com.eldrix.rsdb/conn system) "er528")
  (next.jdbc/execute! (:com.eldrix.rsdb/conn system) ["select * from t_user"])


  (com.eldrix.pc4.rsdb.projects/fetch-project (:com.eldrix.rsdb/conn system) 1) ;; TEST
  (com.eldrix.pc4.rsdb.projects/fetch-project (:com.eldrix.rsdb/conn system) 126) ;; cambridge
  (com.eldrix.pc4.rsdb.projects/fetch-project (:com.eldrix.rsdb/conn system) 127) ;; plymouth

  (com.eldrix.pc4.rsdb.users/register-user-to-project (:com.eldrix.rsdb/conn system) {:username   "er528"
                                                                                      :project-id 126})

  (com.eldrix.pc4.rsdb.users/set-must-change-password! (:com.eldrix.rsdb/conn system) "cpizot")

  (def global-salt (get-in system [:com.eldrix.rsdb/config :legacy-global-pseudonym-salt]))
  global-salt
  (com.eldrix.pc4.rsdb.projects/update-legacy-pseudonymous-patient! (:com.eldrix.rsdb/conn system)
                                                                    global-salt
                                                                    124064
                                                                    {:nhs-number "4290060692"
                                                                     :date-birth (java.time.LocalDate/of 1983 7 3)
                                                                     :sex        :MALE})
  (com.eldrix.pc4.rsdb.projects/find-legacy-pseudonymous-patient (:com.eldrix.rsdb/conn system)
                                                                 {:salt       global-salt :project-id 126 :nhs-number "4290060692"
                                                                  :date-birth (java.time.LocalDate/of 1983 7 3)})
  (com.eldrix.pc4.rsdb.projects/fetch-project (:com.eldrix.rsdb/conn system) 126)
  (com.eldrix.pc4.rsdb.projects/fetch-by-project-pseudonym (:com.eldrix.rsdb/conn system) 126 "0ef84a958ff85bf9e7ea022b73268952fdccf06f866efe794a7a0c4c5d7a8d8c")

  (com.eldrix.pc4.rsdb.projects/discharge-episode! (:com.eldrix.rsdb/conn system) 1 {:t_episode/id 48256})

  (require '[com.eldrix.pc4.modules.dmt])
  (com.eldrix.pc4.modules.dmt/make-patient-identifiers-table system
                                                             (com.eldrix.pc4.modules.dmt/fetch-study-patient-identifiers system :cambridge))
  (com.eldrix.pc4.modules.dmt/write-table system
                                          com.eldrix.pc4.modules.dmt/patient-identifiers-table
                                          :plymouth
                                          (com.eldrix.pc4.modules.dmt/fetch-study-patient-identifiers system :plymouth))

  (require '[com.eldrix.pc4.modules.dmt :as dmt])
  (com.eldrix.pc4.modules.dmt/all-patient-diagnoses system [124079])
  (com.eldrix.pc4.modules.dmt/write-data system :cambridge))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (def x (com.eldrix.pc4.rsdb.results/parse-count-lesions "5+/-2"))

  (results/lesion-range (com.eldrix.pc4.rsdb.results/parse-count-lesions ">12"))

  (gen/generate (s/gen ::lesion-count))
  (gen/generate (s/gen ::lesion-number))
  (s/valid? ::lesion-count [:plus-minus 4 1])
  (com.eldrix.pc4.rsdb.results/parse-count-lesions "6-3"))
