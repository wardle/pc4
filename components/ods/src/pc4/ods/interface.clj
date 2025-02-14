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

(defn valid-service?
  "Is 'svc' a valid ODS service?"
  [svc]
  (satisfies? clods/ODS svc))

(def fetch-org clods/fetch-org)
(def fetch-postcode clods/fetch-postcode)
(def parse-org-id clods/parse-org-id)

(def related? clods/related?)

(defn make-related?-fn
  "Returns a function that can check whether two organisations are related.  
 
  This is optimised for the use-case in which one needs to make repeated
  checks from a single target organisation to many others. As each check 
  requires a lookup of the organisation and its relationships, this will 
  memoize that call to prevent duplicate lookups.

  Example:
    ((make-related?-fn ods-svc \"7A4\") \"RWMBV\")  
  => true
  
  This is because RWMBV is the old code for UHW, which was replaced by 7A4BV, which is
  part of Cardiff and Vale University Health Board (7A4)."
  [ods-svc org-code]
  (let [fetch-fn (memoize clods/fetch-org)
        org (fetch-fn ods-svc nil org-code)]
    (if org
      (fn [other-org-code]
        (when-let [other-org (fetch-fn ods-svc nil other-org-code)]
          (boolean (clods/related? ods-svc other-org org))))
      (constantly false))))

(defn equivalent-and-child-org-ids
  "Return a set of tuples of roots and extensions for organisations equivalent
  to, and 'children of' the specified organisation."
  [ods-svc root extension]
  (clods/equivalent-and-child-org-ids ods-svc root extension))

(defn graph-resolvers
  "Dynamically return the graph resolvers for 'ods'."
  []
  (requiring-resolve 'com.eldrix.clods.graph/all-resolvers))

