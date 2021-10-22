(ns com.eldrix.pc4.server.core
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.server.system :as pc4]))

(defn run [{:keys [profile] :or {profile :live}}]
  (log/info "starting pc4-server with profile" {:profile profile})
  (pc4/init profile))