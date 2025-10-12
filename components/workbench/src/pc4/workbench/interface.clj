(ns pc4.workbench.interface
  (:require
    [buddy.core.codecs :as codecs]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [io.pedestal.connector :as conn]
    [io.pedestal.http.body-params :as body-params]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.jetty :as jetty]
    [io.pedestal.http.ring-middlewares :as ring-middlewares]
    [io.pedestal.interceptor :as intc]
    [io.pedestal.http.ring-middlewares :as ring]
    [io.pedestal.http.route :as route]
    [io.pedestal.http.secure-headers :as secure-headers]
    [io.pedestal.service.interceptors :as interceptors]
    [io.pedestal.service.resources :as resources]
    [pc4.log.interface :as log]
    [pc4.ods.interface :as clods]
    [pc4.ods-ui.interface :as odsui]
    [pc4.snomed-ui.interface :as snomedui]
    [pc4.workbench.controllers.encounters :as encounters]
    [pc4.workbench.controllers.home :as home]
    [pc4.workbench.controllers.login :as login]
    [pc4.workbench.controllers.patient :as patient]
    [pc4.workbench.controllers.patient.charts :as patient-charts]
    [pc4.workbench.controllers.patient.diagnoses :as patient-diagnoses]
    [pc4.workbench.controllers.patient.encounters :as patient-encounters]
    [pc4.workbench.controllers.patient.forms :as patient-forms]
    [pc4.workbench.controllers.patient.medications :as patient-medications]
    [pc4.workbench.controllers.patient.ninflamm :as patient-ninflamm]
    [pc4.workbench.controllers.project :as project]
    [pc4.workbench.controllers.project.araf :as project-araf]
    [pc4.workbench.controllers.project.register-patient :as register-patient]
    [pc4.workbench.controllers.user :as user]
    [pc4.workbench.controllers.select-user :as select-user]
    [pc4.workbench.controllers.test.select-user :as test-select-user]
    [pc4.workbench.controllers.test.select-org :as test-select-org]
    [pc4.web.interface :as web]
    [ring.middleware.session.cookie :as cookie]
    [selmer.parser :as selmer]))

(def tap-ctx
  {:name  ::tap
   :enter (fn [ctx] (tap> {:on-enter ctx}) ctx)
   :leave (fn [ctx] (tap> {:on-leave ctx}) ctx)})

(defn routes [{:keys [odsui snomedui]}]
  (set/union
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
      ["/project/:project-id/araf" :get [login/authenticated project/update-session project-araf/araf-patients] :route-name :project/araf]
      ["/project/:project-id/araf/:patient-identifier/outcome" :get [login/authenticated project/update-session patient/authorized project-araf/patient-outcome] :route-name :project/araf-patient-outcome]
      ["/project/:project-id/find-patient" :get [login/authenticated project/update-session project/find-patient] :route-name :project/find-patient]
      ["/project/:project-id/find-patient" :post [login/authenticated project/find-patient] :route-name :project/do-find-patient]
      ["/project/:project-id/today" :get [login/authenticated project/update-session project/today-wizard] :route-name :project/today]
      ["/project/:project-id/register-patient" :get [login/authenticated project/update-session register-patient/register-patient-form] :route-name :project/register-patient]
      ["/project/:project-id/register-patient/search" :post [login/authenticated register-patient/search-patient] :route-name :project/register-patient-search]
      ["/project/:project-id/register-patient/action" :post [login/authenticated register-patient/register-patient-action] :route-name :project/register-patient-action]
      ["/project/:project-id/patients" :get [login/authenticated project/update-session project/patients] :route-name :project/patients]
      ["/project/:project-id/encounters" :get [login/authenticated project/update-session project/encounters] :route-name :project/encounters]
      ["/patient/:patient-identifier/home" :get [login/authenticated patient/authorized patient/home] :route-name :patient/home]
      ["/patient/:patient-identifier/banner" :get [login/authenticated patient/authorized patient/expanded-banner] :route-name :patient/banner]
      ["/patient/:patient-identifier/break-glass" :get [login/authenticated patient/break-glass] :route-name :patient/break-glass]
      ["/patient/:patient-identifier/break-glass" :post [login/authenticated patient/do-break-glass] :route-name :patient/do-break-glass]
      ["/patient/:patient-identifier/encounters" :get [login/authenticated patient/authorized patient-encounters/encounters-handler] :route-name :patient/encounters]
      ["/patient/:patient-identifier/add-encounter" :get [login/authenticated patient/authorized patient-encounters/add-encounter-handler] :route-name :patient/add-encounter]
      ["/patient/:patient-identifier/encounter/:encounter-id" :get [login/authenticated patient/authorized patient-encounters/encounter-handler] :route-name :patient/encounter]
      ["/patient/:patient-identifier/encounter/:encounter-id/lock" :post [login/authenticated patient/authorized patient-encounters/encounter-lock-handler] :route-name :encounter/lock]
      ["/patient/:patient-identifier/encounter/:encounter-id/form/:form-type/:form-id" :get [login/authenticated patient/authorized patient-forms/form-handler] :route-name :patient/form]
      ["/patient/:patient-identifier/encounter/:encounter-id/form/:form-type/:form-id" :post [login/authenticated patient/authorized patient-forms/form-save-handler] :route-name :patient/form-save]
      ["/patient/:patient-identifier/encounter/:encounter-id/form/:form-type/:form-id" :delete [login/authenticated patient/authorized patient-forms/form-delete-handler] :route-name :patient/form-delete]
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
      ["/ui/list-encounters" :post [login/authenticated encounters/list-encounters-handler] :route-name :ui/list-encounters]
      ["/ui/user/select" :post [login/authenticated select-user/user-select-handler] :route-name :user/select]
      ["/ui/user/search" :post [login/authenticated select-user/user-search-handler] :route-name :user/search]
      ["/test/user-select" :get [login/authenticated test-select-user/test-user-select-handler] :route-name :test/user-select]
      ["/test/org-select" :get [login/authenticated test-select-org/test-org-select-handler] :route-name :test/org-select]}
    (snomedui/routes snomedui {:interceptors [login/authenticated]})
    (odsui/routes odsui {:interceptors [login/authenticated]})))

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
  [{:keys [env session-key host port join? routes]}]
  (-> (conn/default-connector-map host port)
      (conn/optionally-with-dev-mode-interceptors)
      (conn/with-interceptors
        [interceptors/log-request
         interceptors/not-found
         (ring-middlewares/session
           {:store        (cookie/cookie-store (when session-key {:key (codecs/hex->bytes session-key)}))
            :cookie-name  "pc4-session"
            :cookie-attrs {:same-site :strict}})
         (ring-middlewares/flash)
         (ring-middlewares/content-type)
         route/query-params
         (body-params/body-params)
         (csrf/anti-forgery {:error-handler csrf-error-handler})
         (secure-headers/secure-headers
           {:content-security-policy-settings "object-src 'none';"})
         (env-interceptor env)])
      (conn/with-routes
        routes
        (resources/resource-routes {:resource-root "public"}))
      (jetty/create-connector nil)
      (conn/start!)))

(s/def ::hermes any?)
(s/def ::rsdb any?)
(s/def ::ods clods/valid-service?)
(s/def ::odsui some?)
(s/def ::snomedui some?)
(s/def ::pathom ifn?)
(s/def ::host string?)
(s/def ::port (s/int-in 0 65535))
(s/def ::disable-cache boolean?)
(s/def ::session-key string?)
(s/def ::env (s/keys :req-un [::hermes ::rsdb ::ods ::odsui ::pathom ::snomedui]))
(s/def ::config (s/keys :req-un [::host ::port ::env]
                        :opt-un [::disable-cache ::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [env cache? session-key] :or {cache? true} :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain ::config config))))
  (log/info "starting http server" (select-keys config [:host :port :cache?]))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart or load balancing"))
  (when-not cache?
    (log/warn "template cache disabled for development - performance will be degraded")
    (selmer.parser/cache-off!))
  (start (assoc config :routes (routes env))))

(defmethod ig/halt-key! ::server
  [_ conn]
  (log/info "stopping http server" conn)
  (conn/stop! conn))

(def all-resolvers
  [pc4.pathom-web.interface/all-resolvers
   pc4.workbench.controllers.home/resolvers
   pc4.workbench.controllers.patient/resolvers
   pc4.workbench.controllers.user/resolvers
   pc4.workbench.controllers.project/resolvers])

(defn prep-system []
  (let [get-conf (requiring-resolve 'pc4.config.interface/config)
        conf (get-conf :dev)]
    (ig/load-namespaces conf [::server])
    (ig/expand conf (ig/deprofile [:dev]))))

(defn system []
  (var-get (requiring-resolve 'integrant.repl.state/system)))

(comment
  (require '[integrant.repl :as ig.repl])
  (ig.repl/set-prep! prep-system)
  (ig.repl/go [::server])
  (ig.repl/halt)
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



