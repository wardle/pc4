(ns pc4.araf-server.interface
  (:require
    [buddy.core.codecs :as codecs]
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [io.pedestal.http :as http]
    [io.pedestal.http.route :as route]
    [pc4.araf.interface :as araf]
    [pc4.config.interface :as config]
    [pc4.log.interface :as log]
    [ring.middleware.session.cookie :as cookie]
    [selmer.parser :as selmer]))

(defn start
  [{:keys [host port routes join? session-key]}]
  (-> {::http/host           host
       ::http/port           (or port 8080)
       ::http/routes         routes
       ::http/type           :jetty
       ::http/join?          join?
       ::http/resource-path  "/public"
       ::http/enable-session {:store        (cookie/cookie-store (when session-key {:key (codecs/hex->bytes session-key)}))
                              :cookie-name  "araf-session"
                              :cookie-attrs {:same-site :strict}}
       ::http/secure-headers {:content-security-policy-settings {:object-src "none"}}}
      http/default-interceptors
      http/dev-interceptors
      http/create-server
      http/start))

(s/def ::host string?)
(s/def ::port (s/int-in 0 65535))
(s/def ::cache? boolean?)
(s/def ::session-key string?)
(s/def ::svc ::araf/patient-svc)
(s/def ::config (s/keys :req-un [::svc ::host ::port]
                        :opt-un [::cache? ::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [cache? session-key svc] :or {cache? true} :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain ::config config))))
  (log/info "starting araf server" (select-keys config [:host :port :cache?]))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart"))
  (when-not cache?
    (log/warn "template cache disabled for development - performance will be degraded")
    (selmer/cache-off!))
  (start (assoc config :routes (route/routes-from (araf/server-routes svc)))))

(defmethod ig/halt-key! ::server
  [_ service-map]
  (log/info "stopping araf server" (select-keys service-map [::http/port ::http/type]))
  (http/stop service-map))

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
  (araf/create-request (:pc4.araf.interface/patient (system)) "1111111111" :valproate-f
                       (java.time.Instant/.plus (java.time.Instant/now) (java.time.Duration/ofDays 14)))
  (araf/create-request (:pc4.araf.interface/patient (system)) "2222222222" :valproate-fna
                       (java.time.Instant/.plus (java.time.Instant/now) (java.time.Duration/ofDays 14)))
  (ig.repl/halt)
  (pc4.config.interface/config :dev)
  (route/routes-from routes)
  (def srv (start {:host "localhost" :port 8081}))
  (http/stop srv))

