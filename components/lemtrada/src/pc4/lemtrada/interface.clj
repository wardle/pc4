(ns pc4.lemtrada.interface
  (:require
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [pc4.lemtrada.core :as lemtrada]
   [pc4.log.interface :as log]))

(s/def ::codelists some?)
(s/def ::hermes some?)
(s/def ::clods some?)
(s/def ::deprivare some?)
(s/def ::dmd some?)
(s/def ::pathom fn?)
(s/def ::cavpms some?)
(s/def ::env (s/keys :req-un [::cavpms ::codelists ::hermes ::clods ::deprivare ::dmd ::pathom]))

(defmethod ig/init-key ::env
  [_ {:keys [hermes clods deprivare pathom dmd] :as env}]
  (when-not (s/valid? env ::env)
    (throw (ex-info "invalid lemtrada environment" (s/explain-data ::env env))))
  (log/info "initialising lemtrada research project configuration" (keys env))
  env)

(defn export
  "Export research data.
  Run as:
  ```
  clj -X pc4.lemtrada.interface/export :profile :cvx :centre :cardiff
  clj -X pc4.lemtrada.interface/export :profile :pc4 :centre :plymouth
  clj -X pc4.lemtrada.interface/export :profile :dev :centre :cambridge
  ```
  Profile determines the environment in which to use. There are four running
  pc4 environments currently:
  * dev - development
  * nuc - development
  * pc4 - Amazon AWS infrastructure
  * cvx - NHS Wales infrastructure

  Centre determines the choice of projects from which to analyse patients.
  * :cardiff
  * :plymouth
  * :cambridge

  So, for example, to export the Plymouth dataset from a dev laptop using live
  system:
  ```
  clj -X:dev pc4.lemtrada.interface/export :profile :pc4-dev :centre :plymouth
  ```
  "
  [{:keys [profile centre] :as opts}]
  (lemtrada/export opts))

(defn check-demographics
  "Check demographics report. Run as:
  ```
  clj -X pc4.lemtrada.interface/check-demographics :profile :cvx :centre :cardiff
  ```"
  [{:keys [profile centre] :as opts}]
  (lemtrada/check-demographics opts))

(defn update-demographic-authority
  "Update demographic authorities. Run as:
  ```
  clj -X pc4.lemtrada.core/update-demographic-authority :profile :cvx :centre :cardiff
  ```"
  [opts]
  (lemtrada/update-demographic-authority opts))

(defn update-cav-admissions
  "Update admission data from CAVPMS. Run as:
  ```
  clj -X pc4.lemtrada.interface/update-cav-admissions :profile :cvx :centre :cardiff
  ```"
  [{:keys [profile centre] :as opts}]
  (lemtrada/update-cav-admissions opts))
