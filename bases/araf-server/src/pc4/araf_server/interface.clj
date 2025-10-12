(ns pc4.araf-server.interface
  (:require
    [buddy.core.codecs :as codecs]
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [io.pedestal.connector :as conn]
    [io.pedestal.http.body-params :as body-params]
    [io.pedestal.http.jetty :as jetty]
    [io.pedestal.http.ring-middlewares :as ring-middlewares]
    [io.pedestal.http.route :as route]
    [io.pedestal.http.secure-headers :as secure-headers]
    [io.pedestal.service.interceptors :as interceptors]
    [io.pedestal.service.resources :as resources]
    [pc4.araf.interface :as araf]
    [pc4.config.interface :as config]
    [pc4.log.interface :as log]
    [ring.middleware.session.cookie :as cookie]
    [selmer.parser :as selmer]))


(defn create-connector
  [{:keys [host port svc session-key]}]
  (-> (conn/default-connector-map host port)
      #_(conn/with-default-interceptors)
      (conn/optionally-with-dev-mode-interceptors)
      (conn/with-interceptors
        [interceptors/log-request
         interceptors/not-found
         (ring-middlewares/session
           {:store        (cookie/cookie-store (when session-key {:key (codecs/hex->bytes session-key)}))
            :cookie-name  "araf-session"
            :cookie-attrs {:same-site :strict}})
         (ring-middlewares/content-type)
         route/query-params
         (body-params/body-params)
         (secure-headers/secure-headers
           {:content-security-policy-settings "object-src 'none';"})])
      (conn/with-routes
        (araf/server-routes svc)
        (resources/resource-routes {:resource-root "public"}))
      (jetty/create-connector nil)))

(s/def ::host string?)
(s/def ::port (s/int-in 0 65535))
(s/def ::cache? boolean?)
(s/def ::session-key string?)
(s/def ::svc ::araf/svc)
(s/def ::config (s/keys :req-un [::svc ::host ::port]
                        :opt-un [::cache? ::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [cache? session-key] :or {cache? true} :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain ::config config))))
  (log/info "starting araf server" (select-keys config [:host :port :cache?]))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart"))
  (when-not cache?
    (log/info "template cache disabled for development - performance will be degraded")
    (selmer/cache-off!))
  (-> (create-connector config)
      (conn/start!)))

(defmethod ig/halt-key! ::server
  [_ conn]
  (log/info "stopping araf server" conn)
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
  (ig.repl/set-prep! (prep-system :aws))
  (ig.repl/go [::server])
  (keys (system))
  (def svc (:pc4.araf.interface/svc (system)))
  (araf/create-request svc "1111111111" :valproate-f (araf/expiry (java.time.Duration/ofDays 14)))
  (araf/create-request svc "2222222222" :valproate-fna (araf/expiry (java.time.Duration/ofDays 14)))
  (ig.repl/halt)
  (pc4.config.interface/config :dev))

