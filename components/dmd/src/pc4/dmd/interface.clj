(ns pc4.dmd.interface
  (:require
   [clojure.java.io :as io]
   [com.eldrix.dmd.core :as dmd]
   [integrant.core :as ig]
   [pc4.log.interface :as log]))

(defmethod ig/init-key ::svc
  [_ {:keys [root f path]}]
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening UK NHS dm+d index: " path')
    (dmd/open-store path')))

(defmethod ig/halt-key! ::svc [_ dmd]
  (dmd/close dmd))

(defn graph-resolvers
  "Lazily return the graph resolvers for dmd. Expect an environment that 
  contains a key :com.eldrix.dmd.graph/store with a dmd svc."
  []
  (requiring-resolve 'com.eldrix.dmd.graph/all-resolvers))


(def atc-for-product dmd/atc-for-product)
(def fetch-release-date dmd/fetch-release-date)
(def fetch-product dmd/fetch-product)
(def fetch-lookup dmd/fetch-lookup)
(def vpids-from-atc dmd/vpids-from-atc)
(def atc->products-for-ecl dmd/atc->products-for-ecl)
(def vmps-for-product dmd/vmps-for-product)
(def amps-for-product dmd/amps-for-product)
