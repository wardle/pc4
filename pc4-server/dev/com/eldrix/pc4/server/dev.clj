(ns com.eldrix.pc4.server.dev
  (:require
    [com.eldrix.pc4.server.system :as pc4-system]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [com.wsscode.pathom.viz.ws-connector.core :as pvc]
    [com.wsscode.pathom.viz.ws-connector.pathom3 :as p.connector]
    [integrant.core :as ig]
    [next.jdbc :as jdbc]))


(defn connect-viz [registry]
  (p.connector/connect-env registry {:com.wsscode.pathom.viz.ws-connector.core/parser-id 'pc4}))

(comment

  (def system (pc4-system/init :dev))
  (ig/halt! system)
  (do
    (ig/halt! system)
    (def system (pc4-system/init :dev)))
  (connect-viz (:pathom/registry system))

  (keys system)
  (keys (:pathom/registry system))
  (p.eql/process (:pathom/registry system)
                 [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"]
                   [:uk.gov.ons.nhspd/LSOA11
                    :uk.gov.ons.nhspd/OSNRTH1M
                    :uk.gov.ons.nhspd/OSEAST1M
                    {:uk.gov.ons.nhspd/PCT_ORG [:uk.nhs.ord/name :uk.nhs.ord.primaryRole/displayName {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}]}])

  (p.eql/process (:pathom/registry system)
                 [{[:info.snomed.Concept/id 108537001]
                   [:info.snomed.Concept/id
                    :info.snomed.Concept/preferredDescription
                    :uk.nhs.dmd/NM]}])

  (p.eql/process (:pathom/registry system)
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

  (def result (p.eql/process (:pathom/registry system)
                             [{(list 'pc4.users/login
                                     {:system "wales.nhs.uk" :value "ma090906" :password "password"})
                               [:urn.oid.1.2.840.113556.1.4/sAMAccountName
                                :urn.oid.2.5.4/givenName
                                :urn.oid.2.5.4/surname
                                :urn.oid.2.5.4/title
                                :urn.oid.2.5.4/commonName
                                :urn.oid.2.5.4/telephoneNumber
                                :urn.oid.0.9.2342.19200300.100.1.3
                                :org.hl7.fhir.Practitioner/telecom
                                :org.hl7.fhir.Practitioner/identifier
                                {:org.hl7.fhir.Practitioner/name
                                 [:org.hl7.fhir.HumanName/use
                                  :org.hl7.fhir.HumanName/family
                                  :org.hl7.fhir.HumanName/given]}]}]))
  result

  (p.eql/process (:pathom/registry system)
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
  (p.eql/process (:pathom/registry system)
                 [{[:t_patient/patient-identifier 81253]
                   [:t_patient/last_name
                    ]}
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
                                                                          :org.w3.2004.02.skos.core/prefLabel]}]}]}])

  )


