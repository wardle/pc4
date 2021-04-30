(ns com.eldrix.pc4.server.api
  (:require [clojure.tools.logging.readable :as log]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as intc]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.interface.eql :as p.eql]))

(set! *warn-on-reflection* true)

(defn inject
  "A simple interceptor to inject into the context."
  [m]
  {:name  ::inject
   :enter (fn [context] (merge context m))})

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))

(def pathom-api
  {:name  ::pathom-api
   :enter (fn [context]
            (println "transit params:" (get-in context [:request :transit-params]))
            (let [registry (:pathom-registry context)
                  result (p.eql/process registry (get-in context [:request :transit-params]))]
              (println "result: " result)
              (assoc context :response (ok result))))})

(def routes
  (route/expand-routes
    #{["/api" :post pathom-api]}))


(comment
  (require '[com.eldrix.clods.core :as clods])
  (require '[com.eldrix.clods.graph])
  (def clods (com.eldrix.clods.core/open-index "/var/tmp/ods" "/var/tmp/nhspd"))
  (def registry (-> (pci/register com.eldrix.clods.graph/all-resolvers)
                    (assoc :clods clods)))
  ;;(require '[com.wsscode.pathom.viz.ws-connector.core :as pvc])
  ;;(p.connector/connect-env registry {::pvc/parser-id pc4-server})

  (start-dev {:pathom-registry registry} 8080)
  (stop-dev)

  (p.eql/process registry [{[:uk.gov.ons.nhspd/PCDS "cf14 4xw"] [:uk.gov.ons.nhspd/LSOA11 :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M :uk.gov.ons.nhspd/PCT :uk.nhs.ord/name :uk.nhs.ord.primaryRole/displayName {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}])
  )
;; from command line, send some transit+json data to the API endpoint
;; echo '["^ ","a",1,"b",[1,2.2,200000,null]]' | http -f POST localhost:8080/api Content-Type:application/transit+json;charset=UTF-8