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
    [pc4.http-server.controllers.patient :as patient]
    [pc4.http-server.controllers.project :as project]
    [pc4.http-server.controllers.snomed :as snomed]
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
    ["/login" :get login/login :route-name :user/login]
    ["/login" :post login/do-login :route-name :user/login!]
    ["/logout" :post login/logout :route-name :user/logout!]
    ["/user/:user-id/photo" :get user/user-photo :route-name :user/photo]
    ["/user/:user-id/profile" :get [login/authenticated user/profile] :route-name :user/profile]
    ["/user/:user-id/messages" :get [login/authenticated user/messages] :route-name :user/messages]
    ["/user/:user-id/send-message" :get [login/authenticated user/send-message] :route-name :user/send-message]
    ["/user/:user-id/send-message" :post [login/authenticated user/send-message] :route-name :user/send-message!]
    ["/user/:user-id/downloads" :get [login/authenticated user/downloads] :route-name :user/downloads]
    ["/project/:project-id/home" :get [login/authenticated project/home] :route-name :project/home]
    ["/project/:project-id/team" :get [login/authenticated project/team] :route-name :project/team]
    ["/project/:project-id/find-patient" :get [login/authenticated project/find-patient] :route-name :project/find-patient]
    ["/project/:project-id/find-patient" :post [login/authenticated project/find-patient] :route-name :project/do-find-patient]
    ["/project/:project-id/today" :get [login/authenticated project/today-wizard] :route-name :project/today]
    ["/project/:project-id/register-patient" :get [login/authenticated project/register-patient] :route-name :project/register-patient]
    ["/project/:project-id/patients" :get [login/authenticated project/patients] :route-name :project/patients]
    ["/patient/:patient-identifier/home" :get [login/authenticated patient/home] :route-name :patient/home]
    ["/patient/:patient-identifier/encounters" :get [login/authenticated patient/encounters] :route-name :patient/encounters]
    ["/patient/:patient-identifier/nhs" :get [login/authenticated patient/nhs] :route-name :patient/nhs]
    ["/patient/:patient-identifier/projects" :get [login/authenticated patient/projects] :route-name :patient/projects]
    ["/patient/:patient-identifier/admissions" :get [login/authenticated patient/admissions] :route-name :patient/admissions]
    ["/patient/:patient-identifier/register" :get [login/authenticated patient/register] :route-name :patient/register]
    ["/patient/:patient-identifier/diagnoses" :get [login/authenticated patient/diagnoses] :route-name :patient/diagnoses]
    ["/patient/:patient-identifier/medication" :get [login/authenticated patient/medication] :route-name :patient/medication]
    ["/patient/:patient-identifier/documents" :get [login/authenticated patient/documents] :route-name :patient/documents]
    ["/patient/:patient-identifier/results" :get [login/authenticated patient/results] :route-name :patient/results]
    ["/patient/:patient-identifier/procedures" :get [login/authenticated patient/procedures] :route-name :patient/procedures]
    ["/patient/:patient-identifier/alerts" :get [login/authenticated patient/alerts] :route-name :patient/alerts]
    ["/patient/:patient-identifier/family" :get [login/authenticated patient/family] :route-name :patient/family]
    ["/patient/:patient-identifier/neuroinflammatory" :get [login/authenticated patient/neuroinflammatory] :route-name :patient/neuroinflammatory]
    ["/patient/:patient-identifier/motorneurone" :get [login/authenticated patient/motorneurone] :route-name :patient/motorneurone]
    ["/patient/:patient-identifier/epilepsy" :get [login/authenticated patient/epilepsy] :route-name :patient/epilepsy]
    ["/ui/snomed/autocomplete" :get [login/authenticated snomed/autocomplete] :route-name :snomed/autocomplete]
    ["/ui/user/search" :get [login/authenticated user/search] :route-name :user/search]})

(defn env-interceptor
  "Add an interceptor to the service map that will inject the given env into the request."
  [service-map env]
  (update service-map ::http/interceptors
          conj (intc/interceptor {:name  ::inject
                                  :enter (fn [context] (assoc-in context [:request :env] env))})))

(defn csrf-error-handler
  [ctx]
  (log/error "missing CSRF token in request" (get-in ctx [:request :uri]))
  (assoc ctx :response {:status  303
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



