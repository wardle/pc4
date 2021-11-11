(ns pc4.client
  (:require
    [pc4.application :refer [app]]
    [pc4.ui :as ui]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]))

(defn ^:export init []
  (app/mount! app ui/Root "app")
  (df/load! app [:info.snomed.Concept/id 37340000] ui/Concept {:target [:concept]})
  (js/console.log "Loaded"))

(defn ^:export refresh []
  ;; re-mounting will cause forced UI refresh
  (app/mount! app ui/Root "app")
  ;; 3.3.0+ Make sure dynamic queries are refreshed
  (comp/refresh-dynamic-queries! app)
  (js/console.log "Hot reload"))


(comment
  ;; get current state of application
  (app/current-state app)
  (tap> (app/current-state app)))