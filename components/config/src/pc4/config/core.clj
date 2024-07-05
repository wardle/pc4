(ns pc4.config.core
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig]))

(defmethod aero/reader 'ig/ref
  [_ _ x]
  (ig/ref x))

(defmethod aero/reader 'clj/var
  [_ _ x]
  (var-get (requiring-resolve x)))

(defn ^:private config*
  "Reads configuration from the resources directory using the profile specified."
  [profile]
  (if-let [url (io/resource "config/config.edn")]
    (aero/read-config url {:profile profile})
    (throw (ex-info "no config.edn file in resources" {}))))

(defn ^:private remove-non-namespaced-keys [m]
  (reduce-kv
   (fn [acc k v] (if (namespace k) (assoc acc k v) acc))
   {} m))

(defn config
  [profile]
  (remove-non-namespaced-keys (config* profile)))

(comment
  (keys (config :dev))
  (:pc4.snomedct.interface/svc (config :dev)))
