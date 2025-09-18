(ns pc4.arafc.impl.core
  (:require
    [buddy.core.codecs :as codecs]
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [io.pedestal.http :as http]
    [pc4.arafc.impl.routes :as routes]
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
                              :cookie-name  "araf-manager-session"
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
(s/def ::config (s/keys :req-un [::host ::port]
                        :opt-un [::cache? ::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [cache? session-key] :or {cache? true} :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain ::config config))))
  (log/info "starting araf-manager server" (select-keys config [:host :port :cache?]))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart"))
  (when-not cache?
    (log/info "template cache disabled for development - performance will be degraded")
    (selmer/cache-off!))
  (start (assoc config :routes (routes/routes))))

(defmethod ig/halt-key! ::server
  [_ service-map]
  (log/info "stopping araf-manager server" (select-keys service-map [::http/port ::http/type]))
  (http/stop service-map))
