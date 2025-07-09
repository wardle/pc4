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
    (clods/open-index {:f path' :nhspd nhspd})))

(defmethod ig/halt-key! ::svc
  [_ clods]
  (log/info "closing ODS index (clods)")
  (.close clods))

(defn valid-service?
  "Is 'svc' a valid ODS service?"
  [svc]
  (clods/valid-service? svc))

(def fetch-org clods/fetch-org)

(defn search-org
  "Search for an organisation
  Parameters :
  - svc      : ODS service
  - params   : Search parameters; a map containing:
    |- :s             : search for name or address of organisation
    |- :n             : search for name of organisation
    |- :address       : search within address
    |- :fuzzy         : fuzziness factor (0-2)
    |- :active        : only include active organisations (default, true)
    |- :roles         : a string or vector of roles
    |- :from-location : a map containing:
    |  |- :postcode : UK postal code, or
    |  |- :lat      : latitude (WGS84)
    |  |- :lon      : longitude (WGS84), and
    |  |- :range    : range in metres (optional)
    |- :limit      : limit on number of search results."
  [svc params]
  (clods/search-org svc params))

(def fetch-postcode clods/fetch-postcode)
(def parse-org-id clods/parse-org-id)
(def equivalent-org-codes clods/equivalent-org-codes)
(def related-org-codes clods/related-org-codes)

(defn graph-resolvers
  "Dynamically return the graph resolvers for 'ods'."
  []
  (requiring-resolve 'com.eldrix.clods.graph/all-resolvers))

