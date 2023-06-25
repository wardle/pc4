(ns com.eldrix.pc4.pedestal
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [cognitect.transit :as transit]
            [com.eldrix.pc4.app :as app]
            [com.eldrix.pc4.dates :as dates]
            [com.eldrix.pc4.rsdb.auth :as rsdb.auth]        ;; TODO: switch to non-rsdb impl
            [com.eldrix.pc4.rsdb.users :as rsdb.users]      ;; TODO: switch to non-rsdb impl
            [com.eldrix.pc4.rsdb.patients :as rsdb.patients]
            [com.eldrix.pc4.system :as pc4]
            [com.eldrix.pc4.users :as users]
            [com.fulcrologic.fulcro.server.api-middleware :as api-middleware]
            [io.pedestal.http :as http]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor.error :as int-err]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.http.body-params :as body-params]
            [integrant.core :as ig]
            [reitit.core :as r]
            [reitit.http]
            [reitit.pedestal]
            [ring.middleware.session.cookie]
            [rum.core :as rum])
  (:import (clojure.lang ExceptionInfo)))


(set! *warn-on-reflection* true)

(defn inject
  "A simple interceptor to inject the map into the context."
  [m]
  {:name  ::inject
   :enter (fn [context] (merge context m))})

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))

(def service-error-handler
  (int-err/error-dispatch [ctx ex]
                          [{:interceptor ::login}]
                          (assoc ctx :response {:status 400 :body (ex-message ex)})
                          [{:interceptor ::attach-claims}]
                          (assoc ctx :response {:status 401 :body "Unauthenticated."})
                          :else
                          (assoc ctx :io.pedestal.interceptor.chain/error ex)))


(defn execute-pathom
  "Executes a pathom query from the body of the request.

  Fulcro response augmentations are applied to the result, so that resolvers can
  modify response headers and other aspects of the response (e.g. to update
  session data).
  See [[com.fulcrologic.fulcro.server.api-middleware/apply-response-augmentations]]"
  [ctx env params]
  (let [pathom (:pathom/boundary-interface ctx)
        result (pathom env params)
        errors (remove nil? (map :com.wsscode.pathom3.connect.runner/mutation-error (vals result)))]
    (when (seq errors)
      (log/error "pathom errors" {:request (get-in ctx [:request :transit-params]) :errors (map Throwable->map errors)}))
    (assoc ctx :response (merge {:status 200 :body result} (api-middleware/apply-response-augmentations result)))))

(defn landing-page
  "Return a landing page for a front-end.
  - src - filename of the compiled JS (usually obtained from the shadow cljs build).
  The filename changing means that version updates do not require users to forcibly refresh their browser to
  avoid using cached downloads."
  [src & {:keys [title]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1.0"}]
    [:link {:href "css/output.css" :rel "stylesheet" :type "text/css"}]
    [:title (or title "pc4")]]
   [:body
    [:noscript "'PatientCare v4' is a JavaScript app. Please enable JavaScript to continue."]
    [:div#app]
    [:script {:src (str "js/compiled/" src)}]]])

(def landing
  "Interceptor to return the pc4-ward front-end application."
  {:name ::landing
   :enter
   (fn [ctx]
     (let [app (get-in ctx [:com.eldrix.pc4/cljs-modules :app :output-name])]
       (assoc ctx :response {:status 200 :headers {"Content-Type" "text/html"}
                             :body   (rum/render-html (landing-page app))})))})

(def login
  "The login endpoint enforces a specific pathom call rather than permitting
  arbitrary requests. "
  {:name ::login
   :enter
   (fn [ctx]
     (let [{:keys [system value password query] :as params} (get-in ctx [:request :transit-params])]
       (log/info "login endpoint; parameters" (dissoc params :password))
       (execute-pathom ctx nil [{(list 'pc4.users/login
                                       {:system system :value value :password password})
                                 query}])))})

(s/def ::operation symbol?)
(s/def ::params map?)
(s/def ::login-op (s/cat :operation ::operation :params ::params))
(s/def ::login-mutation (s/map-of ::login-op vector?))
(s/def ::login (s/coll-of ::login-mutation :kind vector? :count 1))

(def login-mutation
  "A login endpoint designed for fulcro clients in which login is simply a mutation. We need to take special
  measures to prevent arbitrary pathom queries at this endpoint. The alternative here would be to use a different pathom
   environment that contains only a single resolver, login."
  {:name ::login-mutation
   :enter
   (fn [{:keys [request] :as ctx}]
     (let [params (:transit-params request)                 ;; [(pc4.users/login {:username "system", :password "password"})]
           _ (log/info "login request:" (dissoc params :password))
           op-name (when (s/valid? ::login params) (-> params (get 0) keys first first))]
       (if-not (= 'pc4.users/login op-name)
         (do
           (log/warn "invalid request at /login-mutation endpoint" params)
           (assoc ctx :response {:status 400 :body {:error "Only a single 'pc4.users/login' operation is permitted at this endpoint."}}))
         (execute-pathom ctx nil params))))})

(def attach-claims
  "Interceptor to check request claims and add them to the context under the key
  :authenticated-claims."
  {:name  ::attach-claims
   :enter (fn [ctx]
            (let [auth-header (get-in ctx [:request :headers "authorization"])
                  _ (log/trace "request auth:" auth-header)
                  [_ token] (when auth-header (re-matches #"(?i)Bearer (.*)" auth-header))
                  login-config (:com.eldrix.pc4/login ctx)
                  claims (when (and token login-config) (users/check-user-token token login-config))]
              (when (and token (not login-config))
                (log/error "no valid login configuration available in context; looked for [:com.eldrix.pc4/login :jwt-secret-key]"))
              (if claims
                (assoc ctx :authenticated-claims claims)
                (throw (ex-info "Unauthorized." {:status 401})))))})

(def ping
  "A simple health check. Pass in a uuid to test the resolver backend.
  This means the health check can potentially be extended to include additional
  resolution."
  {:name  :ping
   :enter (fn [ctx]
            (let [{:keys [uuid] :as params} (get-in ctx [:request :transit-params])]
              (execute-pathom ctx nil [{(list 'pc4.users/ping {:uuid uuid}) [:uuid :date-time]}])))})

(def api
  "Interceptor that pulls out EQL from the request and responds with
  the result of its processing.
  The pathom boundary interface provides resolution of EQL in context as per
  https://pathom3.wsscode.com/docs/eql/#boundary-interface
  This is injected into the environment by integrant - under the key
  :pathom/boundary-interface.

  Authenticated claims are merged into the pathom environment under the key
  :authenticated-user.

  An authorization manager is merged into the pathom environment under the key
  :authorization-manager, if the user is valid *and* an rsdb user. In the future
  an authorization manager may be injected even if not an rsdb user, because
  authorization information may be sourced from another system of record."
  {:name ::api
   :enter
   (fn [ctx]
     (log/info "api request: " (get-in ctx [:request :transit-params]))
     (let [params (get-in ctx [:request :transit-params])
           rsdb-conn (:com.eldrix.rsdb/conn ctx)
           claims (:authenticated-claims ctx)
           env (users/make-authenticated-env rsdb-conn claims)]
       (execute-pathom ctx env params)))})

(def get-user-photo
  "Return a user photograph.
  This endpoint is designed to flexibly handle lookup of a user photograph.
  TODO: fallback to active directory photograph."
  {:name ::get-user-photo
   :enter
   (fn [{:com.eldrix.rsdb/keys [conn] :as ctx}]
     (let [system (get-in ctx [:request :path-params :system])
           value (get-in ctx [:request :path-params :value])]
       (log/debug "user photo request" {:system system :value value})
       (if (or (= "patientcare.app" system) (= "cymru.nhs.uk" system))
         (if-let [photo (com.eldrix.pc4.rsdb.users/fetch-user-photo conn value)]
           (assoc ctx :response {:status  200
                                 :headers {"Content-Type" (:erattachment/mimetype photo)}
                                 :body    (:erattachmentdata/data photo)})
           ctx)
         ctx)))})

(def execute-eql
  "An interceptor that will execute the EQL in the context using :query or
  from the routing data under key :eql. Both can either be EQL directly, or
  a function that will take the current request and return EQL. The result
  will be added to the context in the :result key."
  {:enter
   (fn [{conn :com.eldrix.rsdb/conn, pathom :pathom/boundary-interface, :as ctx}]
     (let [user (get-in ctx [:request :session :authenticated-user])
           env (when user (users/make-authenticated-env conn (assoc user :system "cymru.nhs.uk" :value (:t_user/username user))))
           q (or (get-in ctx [:request ::r/match :data :eql]) (:query ctx))
           q' (if (fn? q) (q (:request ctx)) q)]
       (if q' (do
                (tap> {:execute-eql q'})
                (assoc ctx :result (pathom env q')))
              (throw (ex-info "Missing query in context" ctx)))))})

(def context->tap
  "Interceptor that will simply `tap>` the context."
  {:enter (fn [ctx] (tap> ctx) ctx)})

(def local-ctx (atom nil))

(def context->repl
  "Interceptor that pushes context into an atom for inspection at a REPL during development."
  {:enter (fn [ctx]
            (reset! local-ctx ctx)
            ctx)})

(def render-component
  "Renders :component from the context as the response."
  {:leave
   (fn [{response :response, component :component, :as ctx}]
     (when-not (or response component)
       (log/warn "No content generated" ctx))
     (if (or response (not component))
       ctx
       (assoc ctx :response {:status  200
                             :headers {"Content-Type" "text/html"}
                             :body    (rum/render-html component)})))})


(def check-authenticated
  "An interceptor that checks that for an authenticated user. If none, a login
  page is returned instead. Adds ':authorization-manager' to context if there is
  an authenticated user."
  {:enter
   (fn [{:com.eldrix.rsdb/keys [conn] :as ctx}]
     (log/warn "checking authenticated user" (get-in ctx [:request :session :authenticated-user]))
     (if-let [user (get-in ctx [:request :session :authenticated-user])]
       (do (log/info "authenticated user " user)
           (assoc ctx :authorization-manager (rsdb.users/make-authorization-manager conn (:t_user/username user))))
       (do (log/info "no authenticated user for uri:" (get-in ctx [:request :uri]))
           (-> (assoc ctx :login {:login-url (r/match->path (r/match-by-name! (get-in ctx [:request ::r/router]) :login-page))
                                  :url       (get-in ctx [:request :uri])})
               (chain/terminate)
               (chain/enqueue [(intc/map->Interceptor app/view-login-page) (intc/map->Interceptor render-component)])))))})

(def add-authorizer
  "Interceptor to add an authorizer for the session. An authorizer is a 1-arity
   function that checks a permission."
  {:enter
   (fn [{conn :com.eldrix.rsdb/conn, manager :authorization-manager, :as ctx}]
     (let [project-id (some-> (get-in ctx [:request :path-params :project-id]) parse-long)
           patient-id (some-> (get-in ctx [:request :path-params :patient-id]) parse-long)]
       (assoc ctx :authorizer
                  (cond
                    (not manager)
                    (do (log/warn "No authorization manager in session, defaulting")
                        (constantly false))
                    project-id
                    (fn [permission] (rsdb.auth/authorized? manager [project-id] permission))
                    patient-id
                    (fn [permission] (rsdb.auth/authorized? manager (rsdb.patients/active-project-identifiers conn patient-id) permission))
                    :else
                    (fn [permission] (rsdb.auth/authorized-any? manager permission))))))})

(def check-permission
  "Interceptor to check permission. Uses an authorizer and a permission in the
  current route's data."
  {:enter
   (fn [{:keys [authorizer] :as ctx}]
     (let [permission (get-in ctx [:request ::r/match :data :permission])]
       (if (and authorizer permission (authorizer permission))
         ctx
         (assoc ctx :response {:status 401 :body "Unauthorized"}))))})


(def routes*
  [["/" {:name :landing :get {:interceptors [landing]}}]
   ["/login" {:name :login :post {:interceptors [login]}}]  ;; for legacy clients (re-frame / pc4-ward)
   ["/login-mutation" {:name :login-mutation :post {:interceptors [login-mutation]}}] ;; for fulcro clients (fulcro / pc4-ms)
   ["/ping" {:name :ping :post {:interceptors [ping]}}]
   ["/api" {:name :api :post {:interceptors [attach-claims api]}}]
   ["/users/:system/:value/photo" {:name :get-user-photo :get {:interceptors [get-user-photo]}}]
   ;;
   ["/app/home"
    {:name :home
     :get  {:interceptors [check-authenticated render-component execute-eql context->tap app/home-page]}
     :eql  (fn [req]
             (let [user (get-in req [:session :authenticated-user])]
               {:pathom/entity user
                :pathom/eql    [{(list :t_user/roles {:active-roles true})
                                 [:t_project/id :t_project_user/id :t_project_user/active? :t_project/active?
                                  :t_project/name :t_project/title :t_project/type]}
                                :t_user/latest_news]}))}]

   ["/app/login"
    {:name :login-page
     :post {:interceptors [render-component app/login app/view-login-page]}}]
   ["/app/logout"
    {:name :logout
     :get  {:interceptors [app/logout]}
     :post {:interceptors [app/logout]}}]
   ["/app/nav-bar"
    {:name :nav-bar :get {:interceptors [render-component app/nav-bar]}}]

   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; //app/patient
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


   ["/app/patient/:patient-id"
    {:name       :get-patient
     :get        {:interceptors [check-authenticated add-authorizer check-permission render-component execute-eql context->tap app/view-patient-page]}
     :permission :PATIENT_VIEW
     :eql        (fn [req] {:pathom/entity {:t_patient/patient_identifier (some-> (get-in req [:path-params :patient-id]) parse-long)}
                            :pathom/eql    app/patient-properties})}]

   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; //app/project
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

   ["/app/project/:project-id/home"
    {:name       :get-project
     :get        {:interceptors [check-authenticated add-authorizer check-permission render-component execute-eql context->repl app/view-project-home]}
     :permission :LOGIN
     :eql        (fn [req] {:pathom/entity {:t_project/id (some-> (get-in req [:path-params :project-id]) parse-long)}
                            :pathom/eql    [:t_project/id :t_project/title :t_project/name :t_project/inclusion_criteria :t_project/exclusion_criteria
                                            :t_project/date_from :t_project/date_to :t_project/address1 :t_project/address2
                                            :t_project/address3 :t_project/address4 :t_project/postcode
                                            {:t_project/administrator_user [:t_user/full_name :t_user/username]}
                                            :t_project/active? :t_project/long_description :t_project/type
                                            :t_project/count_registered_patients :t_project/count_discharged_episodes :t_project/count_pending_referrals
                                            {:t_project/specialty [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                                            {:t_project/parent_project [:t_project/id :t_project/title]}]})}]
   ["/app/project/:project-id/team"
    {:name       :get-project-team
     :get        {:interceptors [check-authenticated add-authorizer check-permission render-component execute-eql context->tap app/view-project-users]}
     :post       {:interceptors [check-authenticated add-authorizer check-permission render-component execute-eql context->tap app/view-project-users]}
     :permission :LOGIN
     :eql        (fn [req]
                   (let [project-id (some-> (get-in req [:path-params :project-id]) parse-long)
                         active (case (get-in req [:form-params :active]) "active" true "inactive" false "all" nil true)] ;; default to true
                     {:pathom/entity {:t_project/id project-id}
                      :pathom/eql    [:t_project/id :t_project/title
                                      {(list :t_project/users {:group-by :user :active active})
                                       [:t_user/id :t_user/username :t_user/has_photo :t_user/email :t_user/full_name :t_user/active?
                                        :t_user/first_names :t_user/last_name :t_user/job_title
                                        {:t_user/roles [:t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?]}]}]}))}]
   ["/app/project/:project-id/users/:user-id"
    {:name :get-project-user                                ;; view profile in context of a project or service
     :get  {:interceptors [check-authenticated add-authorizer render-component execute-eql context->tap context->repl app/view-user]}
     :eql  (fn [req]
             {:pathom/entity {:t_user/id (some-> (get-in req [:path-params :user-id]) parse-long)}
              :pathom/eql    [:t_user/id :t_user/username :t_user/title :t_user/full_name :t_user/first_names :t_user/last_name :t_user/job_title :t_user/authentication_method
                              :t_user/postnomial :t_user/professional_registration :t_user/professional_registration_url
                              :t_professional_registration_authority/abbreviation
                              :t_user/roles]})}]

   ["/app/project/:project-id/patient/:pseudonym"
    {:name :get-pseudonymous-patient
     :get  {:interceptors [check-authenticated render-component execute-eql app/view-patient-page]}
     :eql  (fn [req] {:pathom/entity {:t_patient/project_pseudonym [(some-> (get-in req [:path-params :project-id]) parse-long) (get-in req [:path-params :pseudonym])]}
                      :pathom/eql    app/patient-properties})}]

   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; //app/user
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ["/app/user/:user-id"
    {:name :get-user                                        ;; view profile independent on any project or service
     :get  {:interceptors [check-authenticated render-component execute-eql context->tap app/view-user]}
     :eql  (fn [req]
             {:pathom/entity {:t_user/id (some-> (get-in req [:path-params :user-id]) parse-long)}
              :pathom/eql    [:t_user/id :t_user/username :t_user/title :t_user/full_name
                              :t_user/first_names :t_user/last_name :t_user/job_title :t_user/authentication_method
                              :t_user/postnomial :t_user/professional_registration :t_user/professional_registration_url
                              :t_professional_registration_authority/abbreviation
                              :t_user/roles]})}]

   ["/app/user/:user-id/ui/common-concepts"
    {:name :get-user-common-concepts
     :get  {:interceptors [check-authenticated render-component app/user-ui-common-concepts]}}]

   ["/app/user/:user-id/impersonate"
    {:name       :impersonate-user
     :post       {:interceptors [render-component check-authenticated add-authorizer check-permission app/impersonate app/view-login-page]}
     :permission :SYSTEM}]])


(defn make-service-map
  [{:keys [port allowed-origins host join? session-key] :or {port 8080, join? false}}]
  {::http/type            :jetty
   ::http/join?           join?
   ::http/log-request     true
   ::http/routes          []
   ::http/resource-path   "/public"
   ::http/port            port
   ::http/allowed-origins (cond (= "*" allowed-origins) (constantly true)
                                :else allowed-origins)
   ::http/secure-headers  {:content-security-policy-settings {:object-src "none"}}
   ::http/host            (or host "127.0.0.1")
   ::http/enable-session  {:store        (ring.middleware.session.cookie/cookie-store
                                           (when session-key {:key (buddy.core.codecs/hex->bytes session-key)}))
                           :cookie-attrs {:same-site :lax}}})

(s/def ::env (s/keys :req [:pathom/boundary-interface
                           :com.eldrix.rsdb/conn
                           :com.eldrix.pc4/login]))

(s/def ::config (s/keys :req-un [::env]
                        :opt-un [::port ::host ::allowed-origins ::join? ::session-key]))

(defmethod ig/init-key ::server [_ {:keys [env session-key] :as config}]
  (log/info "Running HTTP server" (dissoc config :env :session-key))
  (when-not (s/valid? ::config config)
    (throw (ex-info "Invalid server configuration" (s/explain-data ::config config))))
  (when-not session-key
    (log/warn "No explicit session key in configuration; using randomly generated key which will cause problems on server restart or load balancing"))
  (-> (make-service-map config)
      (http/default-interceptors)
      (reitit.pedestal/replace-last-interceptor
        (reitit.pedestal/routing-interceptor (reitit.http/router routes*)))
      (http/dev-interceptors)
      (update ::http/interceptors conj
              (intc/interceptor (inject env))
              (body-params/body-params (body-params/default-parser-map :transit-options [{:handlers dates/transit-readers}]))
              #_(csrf/anti-forgery)
              (http/transit-body-interceptor ::transit-json-body
                                             "application/transit+json;charset=UTF-8"
                                             :json
                                             {:handlers (merge dates/transit-writers
                                                               {ExceptionInfo (transit/write-handler "ex-info" ex-data)
                                                                Throwable     (transit/write-handler "java.lang.Exception" Throwable->map)})}))
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! ::server [_ service-map]
  (http/stop service-map))


(comment
  (require '[com.eldrix.pc4.system :as pc4])
  (require '[dev.nu.morse :as morse])
  (morse/launch-in-proc)
  (pc4/load-namespaces :dev [:com.eldrix.pc4.pedestal/server])
  (morse/inspect (pc4/config :dev))
  (def system (pc4/init :dev [:com.eldrix.pc4.pedestal/server]))
  (pc4/halt! system)
  (morse/inspect system)
  (do (pc4/halt! system)
      (def system (pc4/init :dev [:com.eldrix.pc4.pedestal/server]))))
