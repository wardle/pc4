(ns com.eldrix.pc4.pedestal
  (:require [buddy.core.codecs]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [cognitect.transit :as transit]
            [com.eldrix.nhsnumber :as nhsnumber]
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
            [io.pedestal.interceptor.chain :as intc.chain]
            [io.pedestal.interceptor.error :as intc.error]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.http.body-params :as body-params]
            [integrant.core :as ig]
            [malli.core :as m]
            [reitit.core :as r]
            [reitit.http]
            [reitit.pedestal]
            [ring.middleware.session.cookie]
            [rum.core :as rum])
  (:import (clojure.lang ExceptionInfo)
           (java.time LocalDate)))


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
  (intc.error/error-dispatch
    [ctx ex]
    [{:interceptor ::login}]
    (assoc ctx :response {:status 400 :body (ex-message ex)})
    [{:interceptor ::attach-claims}]
    (assoc ctx :response {:status 401 :body "Unauthenticated."})
    [{:interceptor :io.pedestal.http.impl.servlet-interceptor/ring-response}]
    (assoc ctx :response {:status 400 :body {:error (Throwable->map ex)}})
    :else ;; this should not happen
    (do (log/error "service error" (ex-message ex))
        (log/trace "pedestal service error" (Throwable->map ex))
        (assoc ctx :response {:status 200 :body {:error (Throwable->map ex)}}))))


(defn execute-pathom
  "Executes a pathom query from the body of the request.

  Fulcro response augmentations are applied to the result, so that resolvers can
  modify response headers and other aspects of the response (e.g. to update
  session data).
  See [[com.fulcrologic.fulcro.server.api-middleware/apply-response-augmentations]]"
  [ctx env params]
  (log/debug "executing pathom" params)
  (let [pathom (:pathom/boundary-interface ctx)
        result (pathom env params)
        errors (remove nil? (map :com.wsscode.pathom3.connect.runner/mutation-error (vals result)))]
    (log/debug "pathom result" result)
    (when (seq errors)
      (log/error "error processing request" params)
      (doseq [err errors]
        (log/error (if (instance? Throwable err) (Throwable->map err) err))))
    (assoc ctx :response (merge {:status 200 :body result} #_(api-middleware/apply-response-augmentations result)))))

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
  {:enter
   (fn [ctx]
     (let [app (get-in ctx [:com.eldrix.pc4/cljs-modules :app :output-name])]
       (assoc ctx :response {:status 200 :headers {"Content-Type" "text/html"}
                             :body   (rum/render-html (landing-page app))})))})

(def login
  "The login endpoint enforces a specific pathom call rather than permitting
  arbitrary requests. "
  {:enter
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
  {:enter
   (fn [{:keys [request] :as ctx}]
     (let [params (:transit-params request)                 ;; [{(pc4.users/login {:username "system", :password "password"}) [...]]
           op-name (when (s/valid? ::login params) (-> params (get 0) keys first first))]
       (if-not (= 'pc4.users/login op-name)
         (do
           (log/warn "invalid request at /login-mutation endpoint" params)
           (assoc ctx :response {:status 400 :body {:error "Only a single 'pc4.users/login' operation is permitted at this endpoint."}}))
         (execute-pathom ctx nil params))))})

(def attach-claims
  "Interceptor to check request claims and add them to the context under the key
  :authenticated-claims."
  {:enter (fn [ctx]
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
  {:enter (fn [ctx]
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
  {:enter
   (fn [ctx]
     (log/trace "api request: " (get-in ctx [:request :transit-params]))
     (let [params (get-in ctx [:request :transit-params])
           rsdb-conn (:com.eldrix.rsdb/conn ctx)
           claims (:authenticated-claims ctx)
           env (users/make-authenticated-env rsdb-conn claims)]
       (execute-pathom ctx env params)))})

(def get-user-photo
  "Return a user photograph.
  This endpoint is designed to flexibly handle lookup of a user photograph.
  TODO: fallback to active directory photograph."
  {:enter
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


(defn make-execute-eql
  "Returns an interceptor that executes EQL from either the route data, the last
  interceptor or :query in the context. Each can either be literal EQL, or a
  function that will take the current request and return EQL. Result will be
  stored in context under key :result. Will throw an exception if
  'throw-if-missing' is true and no EQL can be found."
  [{:keys [throw-if-missing]}]
  (fn [{conn :com.eldrix.rsdb/conn, pathom :pathom/boundary-interface, :as ctx}]
    (let [user (get-in ctx [:request :session :authenticated-user])
          env (when user (users/make-authenticated-env conn (assoc user :system "cymru.nhs.uk" :value (:t_user/username user))))
          q (or (get-in ctx [:request ::r/match :data :eql])
                (some-> ctx ::intc.chain/queue last :eql)
                (:query ctx))
          q' (if (fn? q) (q (:request ctx)) q)]
      (if q'
        (try
          (let [result (pathom env q')]
            (log/warn {:result result})
            (assoc ctx :result result))
          (catch Throwable e
            (log/error (str "pathom exception " (Throwable->map e)))
            (assoc ctx :result {:error (Throwable->map e)})))
        (if throw-if-missing
          (throw (ex-info "Missing query in context" ctx))
          ctx)))))

(def execute-eql
  "An interceptor that will execute the EQL in the context using :query or
  from the routing data under key :eql, or from the last interceptor in the
  chain under key :eql. Each can either be EQL directly, or a function that will
  take the current request and return EQL. The result will be added to the
  context in the :result key."
  {:enter (make-execute-eql {:throw-if-missing true})})

(def execute-eql-if-present
  "Executes EQL in context, but does not throw an exception if missing.
  See [[execute-eql]]."
  {:enter (make-execute-eql {:throw-if-missing false})})

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
  "An interceptor that checks for an authenticated user. If none, a login
  page is returned instead. Adds ':authorization-manager' to context if there is
  an authenticated user. Ensures response will not be cached."
  {:enter
   (fn [{:com.eldrix.rsdb/keys [conn] :as ctx}]
     (log/warn "checking authenticated user" (get-in ctx [:request :session :authenticated-user]))
     (if-let [user (get-in ctx [:request :session :authenticated-user])]
       (do (log/info "authenticated user " user)
           (assoc ctx :authorization-manager (rsdb.users/make-authorization-manager conn (:t_user/username user))))
       (do (log/info "no authenticated user for uri:" (get-in ctx [:request :uri]))
           (-> (assoc ctx :login {:login-url (r/match->path (r/match-by-name! (get-in ctx [:request ::r/router]) :login-page))
                                  :url       (get-in ctx [:request :uri])})
               (intc.chain/terminate)
               (intc.chain/enqueue [(intc/map->Interceptor app/view-login-page) (intc/map->Interceptor render-component)])))))
   :leave (fn [ctx]
            (update-in ctx [:response :headers] assoc
                       "Cache-Control" "no-cache, must-revalidate, max-age=0, no-store, private"
                       "Pragma" "no-cache"
                       "Expires" "0"))})

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

(def parse-params
  "Interceptor to coerce and validate request parameters when required."
  {:enter
   (fn [{req :request, :as ctx}]
     (let [params (:params req)
           {:keys [schema coercer]} (get-in ctx [:request ::r/match :data])
           coerced (if coercer (coercer req) params)
           valid? (when schema (m/validate schema coerced))
           error-ks (when (false? valid?) (set (keys (malli.error/error-value (m/explain schema coerced)))))]
       (tap> {:parse-params {:coerced coerced :error-ks error-ks}})
       (assoc-in ctx [:request :parsed-params] (assoc coerced :valid? valid? :error-ks error-ks))))})

(defn routes []
  (log/info "loading routes")
  [["/" {:name :landing :get {:interceptors [landing]}}]
   ["/login"
    {:name :login
     :post {:interceptors [login]}}]                        ;; for legacy clients (re-frame / pc4-ward)
   ["/login-mutation"
    {:name :login-mutation
     :post {:interceptors [login-mutation]}}]               ;; for fulcro clients (fulcro / pc4-ms)
   ["/ping"
    {:name :ping
     :post {:interceptors [ping]}}]
   ["/api"
    {:name :api
     :post {:interceptors [attach-claims api]}}]
   ["/users/:system/:value/photo"
    {:name :get-user-photo
     :get  {:interceptors [get-user-photo]}}]
   ;;
   ["/app/home"
    {:name :home
     :get  {:interceptors [check-authenticated render-component execute-eql context->repl app/home-page]}}]

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
     :permission :PATIENT_VIEW}]

   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; //app/project
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

   ["/app/project/:project-id/home"
    {:name       :get-project
     :get        {:interceptors [check-authenticated add-authorizer check-permission render-component execute-eql context->repl app/view-project-home]}
     :permission :LOGIN}]
   ["/app/project/:project-id/team"
    {:name       :get-project-team
     :get        {:interceptors [check-authenticated add-authorizer check-permission render-component execute-eql context->tap app/view-project-users]}
     :post       {:interceptors [check-authenticated add-authorizer check-permission render-component execute-eql context->tap app/view-project-users]}
     :permission :LOGIN}]
   ["/app/project/:project-id/users/:user-id"
    {:name :get-project-user                                ;; view profile in context of a project or service
     :get  {:interceptors [check-authenticated add-authorizer render-component execute-eql context->repl app/view-user]}}]

   ["/app/project/:project-id/patient/:pseudonym"
    {:name :get-pseudonymous-patient
     :get  {:interceptors [check-authenticated render-component execute-eql app/view-patient-page]}}]

   ["/app/project/:project-id/find-pseudonymous-patient"
    {:name :find-pseudonymous-patient
     :get  {:interceptors [check-authenticated render-component execute-eql app/find-pseudonymous-patient]}
     :post {:interceptors [check-authenticated render-component execute-eql app/find-pseudonymous-patient]}}]

   ["/app/project/:project-id/register-pseudonymous-patient"
    {:name    :register-pseudonymous-patient
     :get     {:interceptors [check-authenticated render-component execute-eql context->repl app/register-pseudonymous-patient]}
     :post    {:interceptors [check-authenticated render-component parse-params execute-eql context->repl app/register-pseudonymous-patient]}
     :coercer (fn [{:keys [path-params params]}]
                {:project-id (some-> (:project-id path-params) parse-long)
                 :nhs-number (get params "nhs-number")
                 :date-birth (some-> (get params "date-birth") dates/safe-parse-local-date)
                 :sex        (let [s (get params "gender")] (when-not (str/blank? s) (keyword s)))})
     :schema  (m/schema [:map [:project-id :int]
                         [:nhs-number [:and :string [:fn nhsnumber/valid*?]]]
                         [:date-birth [:fn #(instance? LocalDate %)]]
                         [:sex [:enum :MALE :FEMALE :UNKNOWN]]])}]

   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; //app/user
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ["/app/user/:user-id"
    {:name :get-user                                        ;; view profile independent on any project or service
     :get  {:interceptors [check-authenticated render-component execute-eql context->tap app/view-user]}}]

   ["/app/user/:user-id/ui/common-concepts"                 ;; TODO: should take language preferences from request as well
    {:name :get-user-common-concepts
     :get  {:interceptors [check-authenticated render-component execute-eql app/user-ui-select-common-concepts]}}]

   ["/app/user/:user-id/ui/search-concept"
    {:name :get-user-search-concept
     :get  {:interceptors [check-authenticated render-component execute-eql app/ui-view-search-concept-results]}
     :post {:interceptors [check-authenticated render-component execute-eql app/ui-view-search-concept-results]}}]

   ["/app/user/:user-id/ui/descriptions/:description-id"
    {:name :get-user-inspect-description
     :get  {:interceptors [check-authenticated render-component execute-eql app/ui-inspect-description]}}]

   ["/app/user/:user-id/impersonate"
    {:name       :impersonate-user
     :post       {:interceptors [render-component check-authenticated add-authorizer check-permission app/impersonate app/view-login-page]}
     :permission :SYSTEM}]])

(defn make-service-map
  [{:keys [port allowed-origins host join? session-key session-timeout-seconds] :or {port 8080, join? false}}]
  {::http/type            :jetty
   ::http/join?           join?
   ::http/log-request     true
   ::http/routes          []
   ::http/resource-path   "/public"
   ::http/port            port
   ::http/allowed-origins (if (= "*" allowed-origins) (constantly true) allowed-origins)
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

(defmethod ig/init-key ::server [_ {:keys [dev? env session-key] :as config}]
  (log/info "running HTTP server" (dissoc config :env :session-key))
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain-data ::config config))))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart or load balancing"))
  (-> (make-service-map config)
      (http/default-interceptors)
      (reitit.pedestal/replace-last-interceptor
        (reitit.pedestal/routing-interceptor
          (reitit.http/router ((if dev? #'routes (constantly (#'routes)))))))
      (http/dev-interceptors)
      (update ::http/interceptors conj
              (intc/interceptor (inject env))
              (body-params/body-params (body-params/default-parser-map :transit-options [{:handlers dates/transit-readers}]))
              #_(csrf/anti-forgery)                         ;; turned off for legacy SPA... but TODO: turn on
              (http/transit-body-interceptor ::transit-json-body
                                             "application/transit+json;charset=UTF-8"
                                             :json
                                             {:handlers (merge dates/transit-writers
                                                               {ExceptionInfo (transit/write-handler "ex-info" ex-data)
                                                                Throwable     (transit/write-handler "java.lang.Exception" Throwable->map)})})
              service-error-handler)
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! ::server [_ service-map]
  (http/stop service-map))

