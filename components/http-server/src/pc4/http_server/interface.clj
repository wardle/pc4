(ns pc4.http-server.interface
  (:require
    [buddy.core.codecs :as codecs]
    [clojure.spec.alpha :as s]
    [io.pedestal.http.body-params :as body-params]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.interceptor :as intc]
    [io.pedestal.http :as http]
    [io.pedestal.http.ring-middlewares :as ring]
    [io.pedestal.http.route :as route]
    [integrant.core :as ig]
    [pc4.http-server.web :as web]
    [pc4.ods.interface :as clods]
    [pc4.http-server.controllers.home :as home]
    [pc4.http-server.controllers.login :as login]
    [pc4.http-server.controllers.patient :as patient]
    [pc4.http-server.controllers.patient.charts :as patient-charts]
    [pc4.http-server.controllers.patient.diagnoses :as patient-diagnoses]
    [pc4.http-server.controllers.patient.encounters :as patient-encounters]
    [pc4.http-server.controllers.patient.medications :as patient-medications]
    [pc4.http-server.controllers.patient.ninflamm :as patient-ninflamm]
    [pc4.http-server.controllers.project :as project]
    [pc4.http-server.controllers.snomed :as snomed]
    [pc4.http-server.controllers.user :as user]
    [pc4.log.interface :as log]
    [ring.middleware.session.cookie :as cookie]
    [selmer.parser :as selmer]))

(def tap-ctx
  {:name  ::tap
   :enter (fn [ctx] (tap> {:on-enter ctx}) ctx)
   :leave (fn [ctx] (tap> {:on-leave ctx}) ctx)})

(def routes
  #{["/" :get [login/authenticated project/update-session home/home-page] :route-name :home]
    ["/login" :get login/login :route-name :user/login]
    ["/login" :post login/do-login :route-name :user/login!]
    ["/logout" :post login/logout :route-name :user/logout!]
    ["/user/:user-id/photo" :get user/user-photo :route-name :user/photo]
    ["/user/:user-id/profile" :get [login/authenticated user/profile] :route-name :user/profile]
    ["/user/:user-id/impersonate" :get [login/authenticated login/impersonate] :route-name :user/impersonate]
    ["/user/:user-id/messages" :get [login/authenticated user/messages] :route-name :user/messages]
    ["/user/:user-id/send-message" :get [login/authenticated user/send-message] :route-name :user/send-message]
    ["/user/:user-id/send-message" :post [login/authenticated user/send-message] :route-name :user/send-message!]
    ["/user/:user-id/downloads" :get [login/authenticated user/downloads] :route-name :user/downloads]
    ["/user/:user-id/change-password" :get [login/authenticated user/change-password] :route-name :user/change-password]
    ["/user/:user-id/change-password" :post [login/authenticated user/process-change-password] :route-name :user/process-change-password!]
    ["/project/:project-id/home" :get [login/authenticated project/update-session project/home] :route-name :project/home]
    ["/project/:project-id/team" :get [login/authenticated project/update-session project/team] :route-name :project/team]
    ["/project/:project-id/find-patient" :get [login/authenticated project/update-session project/find-patient] :route-name :project/find-patient]
    ["/project/:project-id/find-patient" :post [login/authenticated project/find-patient] :route-name :project/do-find-patient]
    ["/project/:project-id/today" :get [login/authenticated project/update-session project/today-wizard] :route-name :project/today]
    ["/project/:project-id/register-patient" :get [login/authenticated project/update-session project/register-patient] :route-name :project/register-patient]
    ["/project/:project-id/patients" :get [login/authenticated project/update-session project/patients] :route-name :project/patients]
    ["/project/:project-id/encounters" :get [login/authenticated project/update-session project/encounters] :route-name :project/encounters]
    ["/patient/:patient-identifier/home" :get [login/authenticated patient/authorized patient/home] :route-name :patient/home]
    ["/patient/:patient-identifier/banner" :get [login/authenticated patient/authorized patient/expanded-banner] :route-name :patient/banner]
    ["/patient/:patient-identifier/break-glass" :get [login/authenticated patient/break-glass] :route-name :patient/break-glass]
    ["/patient/:patient-identifier/break-glass" :post [login/authenticated patient/do-break-glass] :route-name :patient/do-break-glass]
    ["/patient/:patient-identifier/encounters" :get [login/authenticated patient/authorized patient-encounters/encounters-handler] :route-name :patient/encounters]
    ["/patient/:patient-identifier/encounter/:encounter-id" :get [login/authenticated patient/authorized patient-encounters/encounter-handler] :route-name :patient/encounter]
    ["/patient/:patient-identifier/nhs" :get [login/authenticated patient/authorized patient/nhs] :route-name :patient/nhs]
    ["/patient/:patient-identifier/projects" :get [login/authenticated patient/authorized patient/projects] :route-name :patient/projects]
    ["/patient/:patient-identifier/admissions" :get [login/authenticated patient/authorized patient/admissions] :route-name :patient/admissions]
    ["/patient/:patient-identifier/register" :get [login/authenticated patient/authorized patient/register] :route-name :patient/register]
    ["/patient/:patient-identifier/register-to-project" :post [login/authenticated patient/register-to-project] :route-name :patient/do-register-to-project]
    ["/patient/:patient-identifier/diagnoses" :get [login/authenticated patient/authorized patient-diagnoses/diagnoses-handler] :route-name :patient/diagnoses]
    ["/patient/:patient-identifier/diagnosis/:diagnosis-id" :get [login/authenticated patient/authorized patient-diagnoses/edit-diagnosis-handler] :route-name :patient/edit-diagnosis]
    ["/patient/:patient-identifier/diagnosis/:diagnosis-id" :post [login/authenticated patient/authorized patient-diagnoses/save-diagnosis-handler] :route-name :patient/save-diagnosis]
    ["/patient/:patient-identifier/medication" :get [login/authenticated patient/authorized patient-medications/medications-handler] :route-name :patient/medications]
    ["/patient/:patient-identifier/medication/:medication-id" :get [login/authenticated patient/authorized patient-medications/edit-medication-handler] :route-name :patient/edit-medication]
    ["/patient/:patient-identifier/medication/:medication-id" :post [tap-ctx login/authenticated patient/authorized (ring/nested-params) patient-medications/save-medication-handler] :route-name :patient/save-medication]
    ["/patient/:patient-identifier/medication/:medication-id" :delete [login/authenticated patient/authorized patient-medications/delete-medication-handler] :route-name :patient/delete-medication]
    ["/patient/:patient-identifier/chart" :get [login/authenticated patient/authorized patient-charts/chart-handler] :route-name :patient/chart]
    ["/patient/:patient-identifier/documents" :get [login/authenticated patient/authorized patient/documents] :route-name :patient/documents]
    ["/patient/:patient-identifier/results" :get [login/authenticated patient/authorized patient/results] :route-name :patient/results]
    ["/patient/:patient-identifier/procedures" :get [login/authenticated patient/authorized patient/procedures] :route-name :patient/procedures]
    ["/patient/:patient-identifier/alerts" :get [login/authenticated patient/authorized patient/alerts] :route-name :patient/alerts]
    ["/patient/:patient-identifier/family" :get [login/authenticated patient/authorized patient/family] :route-name :patient/family]
    ["/patient/:patient-identifier/neuroinflammatory" :get [login/authenticated patient/authorized patient-ninflamm/neuroinflammatory-handler] :route-name :patient/neuroinflammatory]
    ["/patient/:patient-identifier/neuroinflammatory" :post [login/authenticated patient/authorized patient-ninflamm/save-ms-diagnosis-handler] :route-name :patient/save-ms-diagnosis]
    ["/patient/:patient-identifier/ms-event/:ms-event-id" :get [login/authenticated patient/authorized patient-ninflamm/edit-ms-event-handler] :route-name :patient/edit-ms-event]
    ["/patient/:patient-identifier/ms-event/:ms-event-id" :post [login/authenticated patient/authorized patient-ninflamm/save-ms-event-handler] :route-name :patient/save-ms-event]
    ["/patient/:patient-identifier/ms-event/:ms-event-id" :delete [login/authenticated patient/authorized patient-ninflamm/delete-ms-event-handler] :route-name :patient/delete-ms-event]
    ["/patient/:patient-identifier/motorneurone" :get [login/authenticated patient/authorized patient/motorneurone] :route-name :patient/motorneurone]
    ["/patient/:patient-identifier/epilepsy" :get [login/authenticated patient/authorized patient/epilepsy] :route-name :patient/epilepsy]
    ["/ui/patient/search" :post [login/authenticated patient/search] :route-name :patient/search]
    ["/ui/snomed/autocomplete" :post [login/authenticated snomed/autocomplete-handler] :route-name :snomed/autocomplete]
    ["/ui/snomed/autocomplete-results" :post [login/authenticated snomed/autocomplete-results-handler] :route-name :snomed/autocomplete-results]
    ["/ui/snomed/autocomplete-selected-result" :post [login/authenticated snomed/autocomplete-selected-result-handler] :route-name :snomed/autocomplete-selected-result]
    ["/ui/user/search" :get [login/authenticated user/search] :route-name :user/search]
    ["/ui/list-encounters" :post [login/authenticated patient-encounters/list-encounters-handler] :route-name :ui/list-encounters]})

(defn env-interceptor
  "Return an interceptor to inject the given env into the request."
  [env]
  (intc/interceptor
    {:name  ::inject
     :enter (fn [context] (assoc-in context [:request :env] env))}))

(defn csrf-error-handler
  "Log CSRF errors and redirect to home page."
  [ctx]
  (log/error "missing CSRF token in request" (get-in ctx [:request :uri]))
  (web/redirect-see-other "/"))

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
       ::http/secure-headers {:content-security-policy-settings {:object-src "none"}}}
      http/default-interceptors
      http/dev-interceptors
      (update ::http/interceptors conj
              (body-params/body-params)
              (csrf/anti-forgery {:error-handler csrf-error-handler})
              (env-interceptor env))
      http/create-server
      http/start))

(s/def ::hermes any?)
(s/def ::rsdb any?)
(s/def ::ods clods/valid-service?)
(s/def ::pathom ifn?)
(s/def ::host string?)
(s/def ::port (s/int-in 0 65535))
(s/def ::disable-cache boolean?)
(s/def ::session-key string?)
(s/def ::env (s/keys :req-un [::hermes ::rsdb ::ods ::pathom]))
(s/def ::config (s/keys :req-un [::host ::port ::env]
                        :opt-un [::disable-cache ::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [cache? session-key] :or {cache? true} :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain ::config config))))
  (log/info "starting http server" (select-keys config [:host :port]))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart or load balancing"))
  (when-not cache?
    (log/warn "template cache disabled for development - performance will be degraded")
    (selmer.parser/cache-off!))
  (start config))

(defmethod ig/halt-key! ::server
  [_ service-map]
  (log/info "stopping http server" (select-keys service-map [::http/port ::http/type]))
  (http/stop service-map))

(def all-resolvers
  [pc4.http-server.controllers.home/resolvers
   pc4.http-server.controllers.patient/resolvers
   pc4.http-server.controllers.user/resolvers
   pc4.http-server.controllers.project/resolvers])

(defn prep-system []
  (let [get-conf (requiring-resolve 'pc4.config.interface/config)
        conf (get-conf :dev)]
    (ig/load-namespaces conf [::server])
    (ig/expand conf (ig/deprofile :dev))))

(defn system []
  (requiring-resolve 'integrant.repl.state/system))

(comment
  (require '[integrant.repl :as ig.repl])
  (ig.repl/set-prep! prep-system)
  (ig.repl/go [::server])
  (pc4.config.interface/config :dev)
  (require '[edn-query-language.core :as eql])

  (require '[portal.api :as portal])
  (portal/open {:launcher :intellij})
  (portal/open)
  (add-tap #'portal/submit)
  (route/routes-from routes)
  (def srv (start {}))
  (http/stop srv)

  (selmer/render " hi there {{name}} " {:name "Mark"})
  (selmer/render-file "navbar.html" {:name "Mark"})
  (selmer.parser/cache-off!)
  (println (selmer/render-file "home-page.html" {:user {:fullname "Mark Wardle" :initials "MW"}}))
  (system)
  (keys (system))
  (def rsdb (:pc4.rsdb.interface/svc (system)))
  (def conn (:conn rsdb))
  (honey.sql/format (pc4.rsdb.patients/with-current-address {:select :* :from :t_patient
                                                             :where  [:= :patient_identifier 13358]}))
  (next.jdbc/execute! conn (honey.sql/format (pc4.rsdb.patients/with-current-address {:select :* :from :t_patient
                                                                                      :where  [:= :patient_identifier 13358]}))))



