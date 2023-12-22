(ns pc4.dev-preload
  (:require [taoensso.timbre :as log]))

(js/console.log "Setting logging to :debug. Check browser log levels")
(log/set-min-level! :debug)
(log/set-config! taoensso.timbre/default-config)