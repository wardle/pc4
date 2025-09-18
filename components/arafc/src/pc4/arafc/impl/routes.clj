(ns pc4.arafc.impl.routes
  (:require [io.pedestal.http.body-params :as body-params]
            [pc4.web.interface :as web]))

(defn- hello-world
  [request]
  (web/ok "Hello World!"))

(defn routes
  []
  #{["/" :get [hello-world]]})
