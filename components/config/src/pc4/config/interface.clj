(ns pc4.config.interface
  (:require
   [pc4.config.core :as config]))

(defn config
  [profile]
  (config/config profile))

(comment
  (require '[com.stuartsierra.component :as component])
  (require '[pc4.rsdb.interface :as rsdb])
  (require '[pc4.hermes.interface :as hermes]))
