(ns pc4.cli.core
  (:require
   [pc4.log.interface :as log])
  (:gen-class))

(defn -main [& args]
  (log/info "pc4: hello"))

