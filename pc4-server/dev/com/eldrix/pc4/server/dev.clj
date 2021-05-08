(ns com.eldrix.pc4.server.dev
  (:require
    [com.eldrix.pc4.server.system :as pc4-system]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [com.wsscode.pathom.viz.ws-connector.core :as pvc]
    [com.wsscode.pathom.viz.ws-connector.pathom3 :as p.connector]
    [integrant.core :as ig]))


(defn connect-viz [registry]
  (p.connector/connect-env registry {:com.wsscode.pathom.viz.ws-connector.core/parser-id 'pc4}))

(comment

  (def system (pc4-system/init :dev))
  (ig/halt! system)
  (connect-viz (:pathom/registry system))

  (keys system)
  (keys (:pathom/registry system))
  (p.eql/process (:pathom/registry system) [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"] [:uk.gov.ons.nhspd/LSOA11 :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M :uk.gov.ons.nhspd/PCT :uk.nhs.ord/name :uk.nhs.ord.primaryRole/displayName {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}])

  (p.eql/process (:pathom/registry system)
                 [{[:info.snomed.Concept/id 108537001]
                   [:info.snomed.Concept/id
                    :info.snomed.Concept/preferredDescription
                    :uk.nhs.dmd/NM]}])
  (p.eql/process (:pathom/registry system)
                 [{[:uk.nhs.dmd/VTMID 108537001]
                   [:uk.nhs.dmd/VTMID
                    :uk.nhs.dmd/NM
                    {:uk.nhs.dmd/VMPS [:uk.nhs.dmd/VPID :uk.nhs.dmd/NM]}
                    :info.snomed.Concept/id
                    :info.snomed.Concept/parentRelationshipIds
                    {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}])
  (com.eldrix.dmd.store/fetch-product (get-in system [:pathom/registry :com.eldrix.dmd.graph/store]) 108537001)

  )
