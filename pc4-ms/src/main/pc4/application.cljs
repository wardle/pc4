(ns pc4.application
  (:require [com.fulcrologic.fulcro.application :as app]
            [com.fulcrologic.fulcro.networking.http-remote :as net]))


(defonce app (app/fulcro-app
               {;; This ensures your client can talk to a CSRF-protected server.
                ;; See middleware.clj to see how the token is embedded into the HTML
                :remotes {:remote (net/fulcro-http-remote
                                    {:url                "http://localhost:8080/api"})}}))
