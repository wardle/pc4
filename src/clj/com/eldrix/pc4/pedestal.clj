(ns com.eldrix.pc4.pedestal
  (:require [buddy.core.codecs]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [cognitect.transit :as transit]
            [com.eldrix.pc4.dates :as dates]
            [com.eldrix.pc4.rsdb.users :as rsdb.users]      ;; TODO: switch to non-rsdb impl
            [com.eldrix.pc4.users :as users]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.server.api-middleware :as api-middleware]
            [io.pedestal.http :as http]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.interceptor.error :as intc.error]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.http.body-params :as body-params]
            [integrant.core :as ig]
            [reitit.http]
            [reitit.pedestal]
            [ring.middleware.session.cookie]
            [rum.core :as rum])
  (:import (clojure.lang ExceptionInfo)
           (com.fulcrologic.fulcro.algorithms.tempid TempId)
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
    :else                                                   ;; this should not happen
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
    (log/trace "pathom result" result)
    (when (seq errors)
      (log/error "error processing request" params)
      (doseq [err errors]
        (log/error (if (instance? Throwable err) (Throwable->map err) err))))
    (let [response (api-middleware/apply-response-augmentations result)]
      (tap> {:response response})
      (assoc ctx :response (merge {:status 200 :body result} response)))))

(defn landing-page
  "Return a landing page for a front-end.
  - src - filename of the compiled JS (usually obtained from the shadow cljs build).
  The filename changing means that version updates do not require users to forcibly refresh their browser to
  avoid using cached downloads."
  [src {:keys [title csrf-token]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1.0"}]
    [:link {:href "css/output.css" :rel "stylesheet" :type "text/css"}]
    #_[:script {:src "https://cdn.tailwindcss.com"}]
    [:script
     {:dangerouslySetInnerHTML {:__html (str "var pc4_network_csrf_token = '" csrf-token "';")}}]
    [:title (or title "pc4")]]
   [:body
    [:noscript "'PatientCare v4' is a JavaScript app. Please enable JavaScript to continue."]
    [:div#app]
    [:script {:src (str "js/compiled/" src)}]]])

(def landing
  "Interceptor to return the pc4 front-end application."
  {:enter
   (fn [ctx]
     (let [app (get-in ctx [:com.eldrix.pc4/cljs-modules :main :output-name])
           csrf-token (get-in ctx [:request ::csrf/anti-forgery-token])]
       (if (str/blank? csrf-token)
         (log/warn "Missing CSRF token; has CSRF protection been enabled?"))
       (if-not app
         (log/error (str "Missing front-end app. Found modules: " (keys (:com.eldrix.pc4/cljs-modules ctx))) {})
         (assoc ctx :response
                    {:status 200 :headers {"Content-Type" "text/html"}
                     :body   (str "<!DOCTYPE html>\n"
                                  (rum/render-html
                                    (landing-page app {:csrf-token csrf-token})))}))))})

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
       (if-not (= 'pc4.users/perform-login op-name)
         (do
           (log/warn "invalid request at /login-mutation endpoint" params)
           (assoc ctx :response {:status 400 :body {:error "Only a single 'pc4.users/login' operation is permitted at this endpoint."}}))
         (execute-pathom ctx {:session (:session request)} params))))})

(def ping
  "A simple health check. Pass in a uuid to test the resolver backend.
  This means the health check can potentially be extended to include additional
  resolution."
  {:enter (fn [ctx]
            (let [{:keys [uuid] :as params} (get-in ctx [:request :transit-params])]
              (execute-pathom ctx nil [{(list 'pc4.users/ping {:uuid uuid}) [:uuid :date-time]}])))})


(def authorization-manager
  "Add an authorization manager into the context."
  {:enter
   (fn [ctx]
     (log/trace "api request: " (get-in ctx [:request :transit-params]))
     (let [user (get-in ctx [:request :session :authenticated-user])]
       (assoc ctx :authorization-manager (rsdb.users/authorization-manager2 user))))})

(def api
  {:enter
   (fn [ctx]
     (log/trace "api request: " (get-in ctx [:request :transit-params]))
     (let [params (get-in ctx [:request :transit-params])
           authorization-manager (:authorization-manager ctx)
           env {:authorization-manager authorization-manager
                :session               (get-in ctx [:request :session])}]
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


(defn routes []
  (log/info "loading routes")
  [["/"
    {:name :landing
     :get  {:interceptors [landing]}}]
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
     :post {:interceptors [authorization-manager api]}}]
   ["/users/:system/:value/photo"
    {:name :get-user-photo
     :get  {:interceptors [get-user-photo]}}]])


(def transit-read-handlers
  (merge dates/transit-readers
         {com.fulcrologic.fulcro.algorithms.tempid/tag
          (transit/read-handler (fn [uuid]
                                  (com.fulcrologic.fulcro.algorithms.tempid/tempid uuid)))}))
(def transit-write-handlers
  (merge dates/transit-writers
         {ExceptionInfo (transit/write-handler "ex-info" ex-data)
          Throwable     (transit/write-handler "java.lang.Exception" Throwable->map)
          TempId        (transit/write-handler com.fulcrologic.fulcro.algorithms.tempid/tag #(.-id ^TempId %))}))

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
                           :cookie-name "pc4-session"
                           :cookie-attrs {:same-site :strict}}})

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
              (body-params/body-params (body-params/default-parser-map :transit-options [{:handlers transit-read-handlers}]))
              (http/transit-body-interceptor
                ::transit-json-body "application/transit+json;charset=UTF-8" :json {:handlers transit-write-handlers})
              (csrf/anti-forgery) ;; we manually insert interceptor here, as otherwise default-interceptors includes a non-customised body params
              service-error-handler)
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! ::server [_ service-map]
  (http/stop service-map))

