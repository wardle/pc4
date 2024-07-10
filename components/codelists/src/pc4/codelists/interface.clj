(ns pc4.codelists.interface
  (:require
   [clojure.spec.alpha :as s]
   [com.eldrix.codelists.core :as cl]
   [integrant.core :as ig]))

(s/def ::hermes some?)
(s/def ::dmd some?)
(s/def ::config (s/keys :req-un [::hermes ::dmd]))

(defmethod ig/init-key ::svc [_ {:keys [hermes dmd] :as conf}]
  (when-not (s/valid? ::config conf)
    (throw (ex-info "invalid configuration for codelists" (s/explain-data ::config conf))))
  ;; create an environment as expected by codelists; namespaced keys for dependent services:
  {:com.eldrix/hermes hermes
   :com.eldrix/dmd    dmd})

(defn realize-concepts
  "Realize concepts from the codelist specification `x`. "
  [env x]
  (cl/realize-concepts env x))

(def to-icd10 cl/to-icd10)
(def to-atc cl/to-atc)

(def disjoint? cl/disjoint?)






