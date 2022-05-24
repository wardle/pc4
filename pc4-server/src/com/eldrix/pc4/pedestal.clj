(ns com.eldrix.pc4.pedestal
  (:require [clojure.tools.logging.readable :as log]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor.error :as int-err]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.http.body-params :as body-params]
            [cognitect.transit :as transit]
            [com.eldrix.pc4.dates :as dates]
            [com.eldrix.pc4.users :as users]
            [integrant.core :as ig]
            [io.pedestal.http.route :as route])
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
                          :else (assoc ctx :io.pedestal.interceptor.chain/error ex)))


(defn execute-pathom [ctx env params]
  (let [pathom (:pathom-boundary-interface ctx)
        result (pathom env params)
        mutation-error (some identity (map :com.wsscode.pathom3.connect.runner/mutation-error (vals result)))]
    (if-not mutation-error
      (do (log/debug "mutation success: " {:request params
                                           :result  result})
          (assoc ctx :response (ok result)))
      (let [error (Throwable->map mutation-error)
            error-data (ex-data mutation-error)]
        (log/info "mutation error: " {:request (get-in ctx [:request :transit-params])
                                      :cause   (:cause error)})
        (tap> error)
        (when error-data (log/info "error" error-data))
        (assoc ctx :response {:status 400
                              :body   {:message (:cause error)}})))))

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

(defn make-authenticated-env
  "Given claims containing `system` and `value`, create an environment.
  - conn   : rsdb database connection
  - system : namespace
  - value  : username."
  [conn {:keys [system value] :as claims}]
  (let [rsdb-user? (when claims (users/is-rsdb-user? conn system value))]
    (cond-> {}
            claims
            (assoc :authenticated-user (select-keys claims [:system :value]))
            rsdb-user?
            (assoc :authorization-manager (users/make-authorization-manager conn system value)))))


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
  :pathom-boundary-interface.

  Authenticated claims are merged into the pathom environment under the key
  :authenticated-user.

  An authorization manager is merged into the pathom environment under the key
  :authorization-manager, if the user is valid *and* an rsdb user. In the future
  an authorization manager may be injected even if not an rsdb user, because
  authorization information may be sourced from another system of record."
  {:name  ::api
   :enter (fn [ctx]
            (log/debug "api request: " (get-in ctx [:request :transit-params]))
            (let [params (get-in ctx [:request :transit-params])
                  rsdb-conn (:com.eldrix.rsdb/conn ctx)
                  claims (:authenticated-claims ctx)
                  env (make-authenticated-env rsdb-conn claims)]
              (execute-pathom ctx env params)))})

(def routes
  (route/expand-routes
    #{["/login" :post [service-error-handler
                       login]]
      ["/ping" :post [ping]]
      ["/api" :post [service-error-handler
                     attach-claims
                     api]]}))

(defmethod ig/init-key ::server [_ {:keys [port allowed-origins host env join?] :or {port 8080 join? false} :as config}]
  (log/info "Running HTTP server" (dissoc config :env))
  (-> {::http/type            :jetty
       ::http/join?           join?
       ::http/routes          routes
       ::http/port            port
       ::http/allowed-origins (cond
                                (= "*" allowed-origins)
                                (constantly true)
                                :else
                                allowed-origins)
       ::http/host            (or host "127.0.0.1")}
      (http/default-interceptors)
      (update ::http/interceptors conj
              (intc/interceptor (inject env))
              (body-params/body-params (body-params/default-parser-map :transit-options [{:handlers dates/transit-readers}]))
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
