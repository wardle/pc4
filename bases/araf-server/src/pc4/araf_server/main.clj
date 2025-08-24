(ns pc4.araf-server.main
  (:require [integrant.core :as ig]
            [pc4.araf-server.interface :as server]
            [pc4.config.interface :as config]
            [pc4.log.interface :as log])
  (:gen-class))

(defn serve [{:keys [profile] :or {profile :dev}}]
  (let [conf (config/config (keyword profile))]
    (log/info "starting araf patient server with profile:" profile)
    (ig/load-namespaces conf [::server/server])
    (ig/init conf [::server/server])))

(defn -main [& args]
  (let [profile (or (first args) "dev")]
    (serve {:profile profile})))

