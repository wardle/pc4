(ns com.eldrix.pc4.server.core
  (:require [clojure.tools.logging :as log]
            [com.eldrix.pc4.server.system :as pc4]
            [clojure.string :as str]))

(defn run [{:keys [profile]}]
  (when (str/blank? profile)
    (log/error "Missing :profile")
    (System/exit 0))
  (log/info "starting pc4-server with profile" {:profile profile})
  (pc4/init profile [:http/server]))