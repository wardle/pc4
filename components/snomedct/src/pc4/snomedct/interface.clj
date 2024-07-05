(ns pc4.snomedct.interface
  "Component to provide SNOMED terminology API. 
  This is a very simple wrapper around Hermes but this could be replaced 
  with a version that calls an external service instead."
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [com.eldrix.hermes.core :as hermes]
   [integrant.core :as ig]
   [pc4.log.interface :as log]))

(s/def ::root string?)
(s/def ::f string?)
(s/def ::default-locale (s/nilable string?))

(s/def ::config
  (s/keys :req-un [::root ::f]
          :opt-un [::default-locale]))

(defmethod ig/init-key ::svc
  [_ {:keys [root f default-locale] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid config" (s/explain-data ::config config))))
  (log/info "opening hermes using configuration" config)
  (hermes/open (.getCanonicalPath (if root (io/file root f) (io/file f)))
               {:default-locale default-locale}))

(defmethod ig/halt-key! ::svc
  [_ svc]
  (log/info "closing hermes service")
  (hermes/close svc))

(defn graph-resolvers []
  (requiring-resolve 'com.eldrix.hermes.graph/all-resolvers))

;;
;; API
;; 
;;

(def expand-ecl-historic hermes/expand-ecl-historic)

(def intersect-ecl hermes/intersect-ecl)
(def child-relationships-of-type hermes/child-relationships-of-type)
(def parent-relationships-of-type hermes/parent-relationships-of-type)
(def fully-specified-name hermes/fully-specified-name)
(def preferred-synonym hermes/preferred-synonym)
(def release-information hermes/release-information)
(defn search [svc params]
  (hermes/search svc params))

(def search-concept-ids hermes/search-concept-ids)

(def subsumed-by? hermes/subsumed-by?)
(def with-historical hermes/with-historical)


