(ns pc4.araf-manager.interface
  (:require [buddy.core.codecs :as codecs]
            [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.secure-headers :as secure-headers]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.log :as log]
            [io.pedestal.service.interceptors :as interceptors]
            [io.pedestal.service.resources :as resources]
            [pc4.arafc.impl.routes :as routes]
            [pc4.config.interface :as config]
            [ring.middleware.session.cookie :as cookie]))

(defn inject-env
  "Interceptor that injects 'env' into the request."
  [env]
  (intc/interceptor
    {:name ::inject-svc
     :enter (fn [ctx]
              (assoc-in ctx [:request :env] env))}))


(s/def ::host string?)
(s/def ::port (s/int-in 0 65535))
(s/def ::session-key string?)
(s/def ::config
  (s/keys :req-un [::host ::port ::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [env host port session-key] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid configuration" (s/explain-data ::config config))))
  (log/info "starting araf manager server" (select-keys config [:host :port]))
  (-> (conn/default-connector-map host port)
      #_(conn/with-default-interceptors)
      (conn/optionally-with-dev-mode-interceptors)
      (conn/with-interceptors
        [interceptors/log-request
         interceptors/not-found
         (ring-middlewares/session
           {:store        (cookie/cookie-store (when session-key {:key (codecs/hex->bytes session-key)}))
            :cookie-name  "pc4-session"
            :cookie-attrs {:same-site :strict}})
         (ring-middlewares/content-type)
         route/query-params
         (secure-headers/secure-headers
           {:content-security-policy-settings "object-src 'none';"})
         (inject-env env)])
      (conn/with-routes
        (routes/routes)
        (resources/resource-routes {:resource-root "public"}))
      (jetty/create-connector nil)
      (conn/start!)))

(defmethod ig/halt-key! ::server
  [_ conn]
  (log/info "stopping araf manager server" conn)
  (conn/stop! conn))


(defn prep-system
  ([]
   (prep-system :dev))
  ([profile]
   (fn []
     (let [conf (config/config profile)]
       (ig/load-namespaces conf [::server])
       (ig/expand conf (ig/deprofile profile))))))

(defn system []
  (var-get (requiring-resolve 'integrant.repl.state/system)))

(comment
  (require '[integrant.repl :as ig.repl])
  (ig.repl/set-prep! (prep-system :dev))
  (config/config :dev)
  (ig.repl/go [::server])
  (keys (system))
  (def svc (::server (system))))