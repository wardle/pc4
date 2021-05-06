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
  (p.eql/process (:pathom/registry system) [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"] [:uk.gov.ons.nhspd/LSOA11 :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M :uk.gov.ons.nhspd/PCT :uk.nhs.ord/name :uk.nhs.ord.primaryRole/displayName {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}])

  (p.eql/process (:pathom/registry system)
                 [{[:info.snomed.Concept/id 24700007]
                   [:info.snomed.Concept/id
                    :info.snomed.Concept/descriptions]}])
  system

  )
