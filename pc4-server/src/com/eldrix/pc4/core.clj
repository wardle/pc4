(ns com.eldrix.pc4.core
  (:require [clojure.tools.logging :as log]
            [com.eldrix.pc4.system :as pc4]))

(defn run [{:keys [profile]}]
  (when-not profile
    (log/error "Missing :profile")
    (System/exit 0))
  (log/info "starting pc4-server with profile" {:profile profile})
  (pc4/load-namespaces profile [:com.eldrix.pc4.server/pedestal])
  (pc4/init profile [:com.eldrix.pc4.server/pedestal]))