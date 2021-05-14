(ns eldrix.pc4-ward.core
    (:require
     [reagent.core :as reagent]
     [re-frame.core :as re-frame]
     [eldrix.pc4-ward.events :as events]
     [eldrix.pc4-ward.views :as views]
     [eldrix.pc4-ward.config :as config]
     ))


(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [views/main-page]
                  (.getElementById js/document "app")))

(defn init []
  (re-frame/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (mount-root))
