(ns pc4.http-server.interface
  (:require
    [buddy.core.codecs :as codecs]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [io.pedestal.http :as http]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [integrant.core :as ig]
    [pc4.http-server.controllers.home :as home]
    [pc4.http-server.controllers.login :as login]
    [pc4.http-server.controllers.project :as project]
    [pc4.http-server.controllers.user :as user]
    [pc4.http-server.html :as html]
    [pc4.log.interface :as log]
    [ring.middleware.session.cookie :as cookie]
    [selmer.parser :as selmer]
    [io.pedestal.interceptor :as intc]
    [pc4.rsdb.interface :as rsdb]))

(selmer/set-resource-path! (clojure.java.io/resource "templates"))

(def routes
  #{["/" :get [login/authenticated home/home-page] :route-name :home]
    ["/login" :get login/view-login]
    ["/login" :post login/perform-login]
    ["/logout" :post login/logout]
    ["/user/:system/:value/photo" :get [login/authenticated user/get-user-photo]]
    ["/project/:project-id" :get [login/authenticated project/home] :route-name :project/home]})

(defn rsdb
  [env]
  (get-in env [:request :env :rsdb]))

(defn hermes
  [env]
  (get-in env [:request :env :hermes]))

(defn env-interceptor
  "Add an interceptor to the service map that will inject the given env into the request."
  [service-map env]
  (update service-map ::http/interceptors
          conj (intc/interceptor {:name  ::inject
                                  :enter (fn [context] (assoc-in context [:request :env] env))})))

(defn csrf-error-handler
  [ctx]
  (log/error "missing CSRF token in request" (get-in ctx [:request :uri]))
  (assoc ctx :response {:status 301
                        :headers {"Location" "/"}}))

(defn start
  [{:keys [env session-key host port join?]}]
  (-> {::http/host           host
       ::http/port           (or port 8080)
       ::http/routes         (route/routes-from routes)
       ::http/type           :jetty
       ::http/join?          join?
       ::http/resource-path  "/public"
       ::http/enable-session {:store        (cookie/cookie-store (when session-key {:key (codecs/hex->bytes session-key)}))
                              :cookie-name  "pc4-session"
                              :cookie-attrs {:same-site :strict}}
       ::http/enable-csrf    {:error-handler csrf-error-handler}
       ::http/secure-headers {:content-security-policy-settings {:object-src "none"}}}
      http/default-interceptors
      http/dev-interceptors
      (env-interceptor env)
      http/create-server
      http/start))

(s/def ::hermes any?)
(s/def ::rsdb any?)
(s/def ::host string?)
(s/def ::port (s/int-in 0 65535))
(s/def ::session-key string?)
(s/def ::env (s/keys :req-un [::hermes ::rsdb]))
(s/def ::config (s/keys :req-un [::host ::port ::env]
                        :opt-un [::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [session-key] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain ::config config))))
  (log/info "starting http server" (select-keys config [:host :port]))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart or load balancing"))
  (start config))

(defmethod ig/halt-key! ::server
  [_ service-map]
  (log/info "stopping http server" (select-keys service-map [::http/port ::http/type]))
  (http/stop service-map))

(comment
  (require '[pc4.config.interface :as config])
  (config/config :dev)
  (route/routes-from routes)
  (def srv (start {}))
  (http/stop srv)
  (def system (ig/init (config/config :dev) [::server]))
  (keys system)

  (selmer/render " hi there {{name}} " {:name "Mark"})
  (selmer/set-resource-path! (io/resource "templates"))
  (selmer/render-file "navbar.html" {:name "Mark"})
  (selmer.parser/cache-off!)
  (println (selmer/render-file "home-page.html" {:user {:fullname "Mark Wardle" :initials "MW"}}))
  (do
    (when system (ig/halt! system))
    (def system (ig/init (config/config :dev) [::server]))))



