(ns pc4.deprivare.interface
  "Deprivation indices, currrently supporting the United Kingdom. 
  Graph resolvers are available and are built dynamically. The graph
  namespace is resolved only if the graph resolvers are requested so
  that this component has no fixed dependency on the pathom library."
  (:require
   [clojure.java.io :as io]
   [com.eldrix.deprivare.core :as deprivare]
   [integrant.core :as ig]
   [pc4.log.interface :as log]))

(defmethod ig/init-key ::svc [_ {:keys [root f path]}]
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening deprivare index: " path')
    (deprivare/open path')))

(defmethod ig/halt-key! ::svc [_ svc]
  (deprivare/close svc))

(defmethod ig/init-key ::ops [_ svc]
  (if-let [make-resolvers (requiring-resolve 'com.eldrix.deprivare.graph/make-all-resolvers)]
    (make-resolvers svc)
    (throw (ex-info "unable to resolve " {}))))

(defn fetch-lsoa
  "Return deprivation data for the LSOA code specified."
  [svc lsoa]
  (deprivare/fetch-lsoa svc lsoa))

(defn graph-resolvers
  "Dynamically generate Pathom graph resolvers for the given service, based on the installed
  deprivation indices for the given service."
  [svc]
  (let [make-all-resolvers (requiring-resolve 'com.eldrix.deprivare.graph/make-all-resolvers)]
    (make-all-resolvers svc)))




