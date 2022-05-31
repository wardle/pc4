(ns com.eldrix.pc4.fulcro
  (:require [clojure.tools.logging.readable :as log]
            [clojure.spec.alpha :as s]
            [com.eldrix.pc4.dates :as dates]
            [com.eldrix.pc4.users :as users]
            [com.fulcrologic.fulcro.server.api-middleware]
            [org.httpkit.server]
            [ring.middleware.content-type]
            [ring.middleware.cors]
            [ring.middleware.defaults]
            [integrant.core :as ig]))

(def ^:private not-found-handler
  (fn [req]
    {:status  404
     :headers {"Content-Type" "text/plain"}
     :body    "Not Found"}))

(defn wrap-claims
  "Middleware that attaches valid claims to the request ':authenticated-claims'
  and returns 401 if no valid bearer token found."
  [handler login-config]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"])
          _ (log/info "request auth:" auth-header)
          [_ token] (when auth-header (re-matches #"(?i)Bearer (.*)" auth-header))
          claims (when (and token login-config) (users/check-user-token token login-config))]
      (when (and token (not login-config))
        (log/error "no valid login configuration available"))
      (if claims
        (handler (assoc request :authenticated-claims claims))
        #_(handler request)
        {:status 401
         :body   {:error "Unauthorized. Request missing valid Bearer token."}}))))


(s/def ::operation symbol?)
(s/def ::params map?)
(s/def ::login-op (s/cat :operation ::operation :params ::params))
(s/def ::login-mutation (s/map-of ::login-op vector?))
(s/def ::login (s/coll-of ::login-mutation :kind vector? :count 1))

(comment
  (def example-login [{(list 'pc4.users/login {:username "system", :password "password"}) [:t_user/id :t_user/first_names :t_user/last_name]}])
  (s/valid? ::login example-login)
  (s/conform ::login example-login))

(defn wrap-login
  "The login endpoint does not have an authenticated user, so we need to take
  special measures to prevent arbitrary pathom queries at this endpoint.
  The alternative here would be to use a different pathom environment that
  contains only a single resolver, login."
  [handler {uri :uri pathom :pathom}]
  (fn [req]
    (if (= uri (:uri req))
      (let [_ (log/info "login request:" (:transit-params req))
            params (:transit-params req)                    ;; [(pc4.users/login {:username "system", :password "password"})]
            op-name (when (s/valid? ::login params) (-> params (get 0) keys first first))]
        (if-not (= 'pc4.users/login op-name)
          (do
            (log/info "invalid request at /login endpoint" params)
            {:status 400 :body {:error "Only a single 'pc4.users/login' operation is permitted at this endpoint."}})
          (let [resp (com.fulcrologic.fulcro.server.api-middleware/handle-api-request params pathom)
                user (get-in resp [:body 'pc4.users/login])]
            (if user resp {}))))                            ;; return a nil response if no
      (handler req))))

(defn wrap-authenticated-pathom
  "Attach an authenticated environment into the request, if possible."
  [handler {conn :com.eldrix.rsdb/conn pathom-boundary-interface :pathom-boundary-interface}]
  (fn [{claims :authenticated-claims :as req}]
    (let [{:keys [system value]} claims
          rsdb-user? (when claims (users/is-rsdb-user? conn system value))
          env (cond-> {}
                      claims
                      (assoc :authenticated-user (select-keys claims [:system :value]))
                      rsdb-user?
                      (assoc :authorization-manager (users/make-authorization-manager conn system value)))]
      (handler (assoc req :pathom (partial pathom-boundary-interface env))))))

(defn wrap-api
  [handler {:keys [uri]}]
  (when-not (string? uri)
    (throw (ex-info "Invalid parameters to `wrap-api`. :uri required." {})))
  (fn [req]
    (if (= uri (:uri req))
      (do
        (log/info "processing API call " req)
        (com.fulcrologic.fulcro.server.api-middleware/handle-api-request (:transit-params req) (:pathom req)))
      (handler req))))

(defn wrap-log-response [handler]
  (fn [req]
    (log/info "request:" req)
    (let [response (handler req)]
      (log/info "response:" response)
      response)))

(defn wrap-hello [handler uri]
  (fn [req]
    (if (= uri (:uri req))
      {:status  200
       :body    "Hello World"
       :headers {"Content-Type" "text/plain"}}
      (handler req))))

(defmethod ig/init-key ::handler [_ {:keys [ring-defaults login-config pathom-boundary-interface] :as config}]
  (-> not-found-handler
      (wrap-api {:uri "/api"})
      (wrap-authenticated-pathom config)
      (wrap-claims login-config)
      (wrap-login {:pathom pathom-boundary-interface :uri "/login"})
      (com.fulcrologic.fulcro.server.api-middleware/wrap-transit-params {:opts {:handlers dates/transit-readers}})
      (com.fulcrologic.fulcro.server.api-middleware/wrap-transit-response {:opts {:handlers dates/transit-writers}})
      ; (wrap-hello "/hello")
      (ring.middleware.defaults/wrap-defaults ring-defaults)
      (ring.middleware.cors/wrap-cors
        :access-control-allow-origin [#".*"]
        :access-control-allow-headers ["Content-Type"]
        :access-control-allow-methods [:post]
        :access-control-allow-headers #{"accept"
                                        "accept-encoding"
                                        "accept-language"
                                        "authorization"
                                        "content-type"
                                        "origin"})))
;(wrap-log-response)


(defmethod ig/init-key ::server [_ {:keys [port allowed-origins host handler] :as config}]
  (log/info "running HTTP server" (dissoc config :handler))
  (when-not handler
    (throw (ex-info "invalid http server configuration: expected 'handler' key" config)))
  (org.httpkit.server/run-server handler (cond-> config
                                                 (= "*" allowed-origins) (assoc :legal-origins #".*")
                                                 (seq allowed-origins) (assoc :legal-origins allowed-origins)
                                                 host (assoc :ip host))))

(defmethod ig/halt-key! ::server [_ stop-server-fn]
  (stop-server-fn))

