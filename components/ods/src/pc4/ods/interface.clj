(ns pc4.ods.interface
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [pc4.log.interface :as log]
   [com.eldrix.clods.core :as clods]
   [integrant.core :as ig]
   [pc4.nhspd.interface :as nhspd]))

(s/def ::path string?)
(s/def ::f string?)
(s/def ::root string?)
(s/def ::nhspd some?)
(s/def ::config (s/keys :req-un [(or ::path (and ::root ::f)) ::nhspd]))

(defmethod ig/init-key ::svc
  [_ {:keys [root f path nhspd] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid ods configuration" (s/explain-data ::config config))))
  (let [path' (or path (.getCanonicalPath (io/file root f)))]
    (log/info "opening ODS index (clods) from " path')
    (clods/open-index {:ods-dir path' :nhspd nhspd})))

(defmethod ig/halt-key! ::svc
  [_ clods]
  (log/info "closing ODS index (clods)")
  (.close clods))

(def fetch-org clods/fetch-org)
(def fetch-postcode clods/fetch-postcode)
(def parse-org-id clods/parse-org-id)

(def related? clods/related?)

(defn graph-resolvers
  "Dynamically return the graph resolvers for 'ods'."
  []
  (requiring-resolve 'com.eldrix.clods.graph/all-resolvers))

