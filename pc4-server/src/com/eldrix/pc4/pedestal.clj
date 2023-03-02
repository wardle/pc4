(ns com.eldrix.pc4.pedestal
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [cognitect.transit :as transit]
            [com.eldrix.pc4.app :as app]
            [com.eldrix.pc4.dates :as dates]
            [com.eldrix.pc4.users :as users]
            [com.fulcrologic.fulcro.server.api-middleware :as api-middleware]
            [io.pedestal.http :as http]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor.error :as int-err]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route :as route]
            [integrant.core :as ig]
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
        result (try (pathom env params) (catch Throwable e e))
        mutation-error (if (instance? Throwable result) result (some identity (map :com.wsscode.pathom3.connect.runner/mutation-error (vals result))))]
    (if-not mutation-error
      (do (log/debug "pathom success: " {:request params
                                         :result  result})
          (assoc ctx :response (merge {:status 200 :body result} (api-middleware/apply-response-augmentations result))))
      (let [error (Throwable->map mutation-error)
            error-data (ex-data mutation-error)]
        (log/error "pathom error: " {:request (get-in ctx [:request :transit-params])
                                     :cause   error})
        (tap> {:mutation-error error})
        (when error-data (log/info "error" error-data))
        (assoc ctx :response (ok {:error (:cause error)}))))))

(def login
  "The login endpoint enforces a specific pathom call rather than permitting
  arbitrary requests. "
  {:name  ::login
   :enter (fn [ctx]
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
  {:name  ::login-mutation
   :enter (fn [{:keys [request] :as ctx}]
            (let [params (:transit-params request)          ;; [(pc4.users/login {:username "system", :password "password"})]
                  _ (log/info "login request:" params)
                  op-name (when (s/valid? ::login params) (-> params (get 0) keys first first))]
              (if-not (= 'pc4.users/login op-name)
                (do
                  (log/info "invalid request at /login-mutation endpoint" params)
                  (assoc ctx :response {:status 400 :body {:error "Only a single 'pc4.users/login' operation is permitted at this endpoint."}}))
                (execute-pathom ctx nil params))))})

(def attach-claims
  "Interceptor to check request claims and add them to the context under the key
  :authenticated-claims."
  {:name  ::attach-claims
   :enter (fn [ctx]
            (let [auth-header (get-in ctx [:request :headers "authorization"])
                  _ (log/debug "request auth:" auth-header)
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
  {:name  ::api
   :enter (fn [ctx]
            (log/info "api request: " (get-in ctx [:request :transit-params]))
            (let [params (get-in ctx [:request :transit-params])
                  rsdb-conn (:com.eldrix.rsdb/conn ctx)
                  claims (:authenticated-claims ctx)
                  env (users/make-authenticated-env rsdb-conn claims)]
              (execute-pathom ctx env params)))})

(def get-user-photo
  "Return a user photograph.
  This endpoint is designed to flexibly handle lookup of a user photograph."
  {:name  ::get-user-photo
   :enter (fn [{:com.eldrix.rsdb/keys [conn] :as ctx}]
            (let [system (get-in ctx [:request :path-params :system])
                  value (get-in ctx [:request :path-params :value])]
              (log/info "user photo request" {:system system :value value})
              (if (or (= "patientcare.app" system) (= "cymru.nhs.uk" system))
                (if-let [photo (com.eldrix.pc4.rsdb.users/fetch-user-photo conn value)]
                  (do (println "photo:" photo)
                      (assoc ctx :response {:status  200
                                            :headers {"Content-Type" (:erattachment/mimetype photo)}
                                            :body    (:erattachmentdata/data photo)}))
                  ctx)
                ctx)))})

(def execute-eql
  "An interceptor that will execute the EQL in the context :query adding the
  result to the context in the :result key."
  {:enter (fn [{q :query, pathom :pathom/boundary-interface, :as ctx}]
            (log/info "executing EQL" q)
            (if q (assoc ctx :result (pathom q))
                  (throw (ex-info "Missing query in context" ctx))))})

(def render-component
  "Renders :component from the context as the response."
  {:leave
   (fn [{response :response, component :component, :as ctx}]
     (log/info "rendering component " component)
     (when-not (or response component)
       (log/warn "No content generated" ctx))
     (if (or response (not component))
       ctx
       (assoc ctx :response {:status  200
                             :headers {"Content-Type" "text/html"}
                             :body    (rum/render-html component)})))})


(def check-authenticated
  "An interceptor that checks that for an authenticated user. If none, a login
  page is returned instead."
  {:enter (fn [ctx]
            (log/warn "checking authenticated user" (get-in ctx [:request :session :authenticated-user]))
            (if-let [user (get-in ctx [:request :session :authenticated-user])]
              (do (log/info "authenticated user " user)
                  ctx)
              (do (log/info "no authenticated user for uri:" (get-in ctx [:request :uri]))
                  (-> (assoc ctx :login {:login-url (route/url-for :post-login-page)
                                         :url (get-in ctx [:request :uri])})
                      (chain/terminate)
                      (chain/enqueue [(intc/map->Interceptor app/view-login-page) (intc/map->Interceptor render-component)])))))})

(def routes
  (route/expand-routes
    #{["/login" :post [service-error-handler login]]        ;; for legacy clients (re-frame / pc4-ward)
      ["/login-mutation" :post [login-mutation]]            ;; for fulcro clients (fulcro / pc4-ms)
      ["/ping" :post [ping]]
      ["/api" :post [service-error-handler attach-claims api]]
      ["/users/:system/:value/photo" :get [get-user-photo]]
      ;;
      ["/app/home" :get [check-authenticated render-component app/home-page] :route-name :home]
      ["/app/login" :post [render-component app/login app/view-login-page] :route-name :post-login-page]
      ["/app/login" :get [render-component app/login app/view-login-page] :route-name :get-login-page]
      ["/app/logout" :post [app/logout] :route-name :logout]
      ["/app/patient/:patient-id" :get [check-authenticated render-component app/get-patient execute-eql app/view-patient-page] :route-name :get-patient]
      ["/app/patient/:patient-id/home" :get [check-authenticated render-component app/get-patient execute-eql app/view-patient-demographics] :route-name :patient-demog]
      ["/app/project/:project-id/patient/:pseudonym" :get [check-authenticated render-component app/get-pseudonymous-patient execute-eql app/view-patient-page] :route-name :get-pseudonymous-patient]}))

(defn make-service-map
  [{:keys [port allowed-origins host join? session-key] :or {port 8080, join? false}}]
  {::http/type            :jetty
   ::http/join?           join?
   ::http/log-request     true
   ::http/routes          routes
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
      (update ::http/interceptors conj
              (intc/interceptor (inject env))
              (body-params/body-params (body-params/default-parser-map :transit-options [{:handlers dates/transit-readers}]))
              (csrf/anti-forgery)
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
