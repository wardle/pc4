(ns com.eldrix.pc4.core
  (:require [clojure.tools.logging :as log]
            [com.eldrix.pc4.rsdb.migrations :as migrations]
            [com.eldrix.pc4.system :as pc4])
  (:gen-class))

(defn run [{:keys [profile]}]
  (when-not profile
    (log/error "Missing :profile")
    (System/exit 1))
  (log/info "starting pc4-server with profile" {:profile profile})
  (pc4/load-namespaces profile [:com.eldrix.pc4.pedestal/server])
  (pc4/init profile [:com.eldrix.pc4.pedestal/server :repl/server]))

(defn migrate [{:keys [profile]}]
  (when-not profile
    (log/error "Missing :profile")
    (System/exit 1))
  (log/info "Running database migrations with profile" {:profile profile})
  (pc4/init profile [:com.eldrix.rsdb/run-migrations]))

(defn -main [& args]
  (if-let [profile (first args)]
    (run {:profile (keyword profile)})
    (println "Missing parameter 'profile' e.g. cvx, pc4 or dev")))