(ns dev
  (:require
    [clojure.spec.test.alpha :as stest]
    [integrant.core :as ig]
    [integrant.repl :as ig.repl]
    [integrant.repl.state]))

(stest/instrument)                                          ;; turn on instrumentation for development


(defn prep-system [profile keys]
  (fn []
    (let [get-conf (requiring-resolve 'pc4.config.interface/config)
          conf (get-conf profile)]
      (ig/load-namespaces conf keys)
      (ig/expand conf (ig/deprofile profile)))))

(defn system []
  (var-get (requiring-resolve 'integrant.repl.state/system)))

(comment
  (require '[integrant.repl :as ig.repl])
  (ig.repl/set-prep! (prep-system :dev [:pc4.workbench.interface/server]))
  (ig.repl/go [:pc4.workbench.interface/server])
  (ig.repl/set-prep! (prep-system :dev [:pc4.rsdb.interface/svc]))
  (ig.repl/go [:pc4.rsdb.interface/svc])
  (keys (system))
  (def rsdb (:pc4.rsdb.interface/svc (system)))
  (ig.repl/halt)
  (pc4.config.interface/config :dev))