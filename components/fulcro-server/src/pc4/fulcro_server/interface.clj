(ns pc4.fulcro-server.interface
  (:require
   [buddy.core.codecs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [cognitect.transit :as transit]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.fulcro.server.api-middleware :as api-middleware]
   [io.pedestal.http :as http]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.csrf :as csrf]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor.error :as intc.error]
   [io.pedestal.interceptor :as intc]
   [integrant.core :as ig]
   [pc4.log.interface :as log]
   [pc4.rsdb.interface :as rsdb]      ;; TODO: switch to non-rsdb impl for business logic
   [ring.middleware.session.cookie]
   [rum.core :as rum])
  (:import
   (com.fulcrologic.fulcro.algorithms.tempid TempId)
   (java.time LocalDate LocalDateTime Period ZonedDateTime)
   (java.time.format DateTimeFormatter)))

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
   [{:interceptor ::add-authorization-manager}]
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
  [{:keys [pathom] :as ctx} env params]
  (log/trace "executing pathom" params)
  (let [result (pathom env params)
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
  [src {:keys [title csrf-token use-tailwind-cdn]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1.0"}]
    (if use-tailwind-cdn
      (do
        (log/warn "Using tailwind CDN instead of local CSS")
        [:script {:src "https://cdn.tailwindcss.com"}])
      [:link {:href "/css/output.css" :rel "stylesheet" :type "text/css"}])
    [:script
     {:dangerouslySetInnerHTML {:__html (str "var pc4_network_csrf_token = '" csrf-token "';")}}]
    [:title (or title "pc4")]]
   [:body
    [:noscript "'PatientCare v4' is a JavaScript app. Please enable JavaScript to continue."]
    [:div#app]
    [:script {:src (str "/js/compiled/" src)}]]])

(defn landing*
  [{:keys [use-tailwind-cdn] :as ctx}]
  (let [app (get-in ctx [:cljs-modules :main :output-name])
        csrf-token (get-in ctx [:request ::csrf/anti-forgery-token])]
    (when (str/blank? csrf-token)
      (log/warn "Missing CSRF token; has CSRF protection been enabled?"))
    (if-not app
      (log/error (str "Missing front-end app. Found modules: " (keys (:cljs-modules ctx))) {})
      (assoc ctx :response
             {:status 200 :headers {"Content-Type" "text/html"}
              :body   (str "<!DOCTYPE html>\n"
                           (rum/render-html
                            (landing-page app {:csrf-token csrf-token :use-tailwind-cdn use-tailwind-cdn})))}))))

(def landing
  "Interceptor to return the pc4 front-end application."
  {:name  ::landing
   :enter landing*})

(def not-found
  {:name ::not-found
   :leave
   (fn [ctx]
     (if (and (not (http/response? (:response ctx)))
              (= :get (:request-method (:request ctx))))
       (landing* ctx)
       ctx))})

(s/def ::operation symbol?)
(s/def ::params map?)
(s/def ::login-op (s/cat :operation ::operation :params ::params))
(s/def ::login-mutation (s/map-of ::login-op vector?))
(s/def ::login (s/coll-of ::login-mutation :kind vector? :count 1))

(def login
  "A login endpoint designed for fulcro clients in which login is simply a mutation. We need to take special
  measures to prevent arbitrary pathom queries at this endpoint. The alternative here would be to use a different pathom
   environment that contains only a single resolver, login."
  {:name ::login
   :enter
   (fn [{:keys [request] :as ctx}]
     (let [params (:transit-params request)                 ;; [{(pc4.users/login {:username "system", :password "password"}) [...]]
           op-name (when (s/valid? ::login params) (-> params (get 0) keys first first))]
       (if-not (= 'pc4.users/perform-login op-name)   ;; TODO: parse using EQL and examine the AST instead
         (do
           (log/warn "invalid request at /login-mutation endpoint" params)
           (assoc ctx :response {:status 400 :body {:error "Only a single 'pc4.users/login' operation is permitted at this endpoint."}}))
         (execute-pathom ctx {:session (:session request)} params))))})

(def ping
  "A simple health check. Pass in a uuid to test the resolver backend.
  This means the health check can potentially be extended to include additional
  resolution."
  {:name  ::ping
   :enter (fn [ctx]
            (let [{:keys [uuid] :as params} (get-in ctx [:request :transit-params])]
              (execute-pathom ctx nil [{(list 'pc4.users/ping {:uuid uuid}) [:uuid :date-time]}])))})

(def authorization-manager
  "Add an authorization manager into the context."
  {:name ::add-authorization-manager
   :enter
   (fn [ctx]
     (log/trace "authorization-manager; api request: " (get-in ctx [:request :transit-params]))
     (if-let [user (get-in ctx [:request :session :authenticated-user])]
       (assoc ctx :authorization-manager (rsdb/authorization-manager user))
       (do
         (log/info "Unauthenticated request" (:request ctx))
         (throw (ex-info "Unauthenticated" {})))))})

(def api
  {:name ::api
   :enter
   (fn [ctx]
     (tap> {:api ctx})
     (log/trace "api request: "  (get-in ctx [:request :transit-params]))
     (let [params                (get-in ctx [:request :transit-params])
           authorization-manager (:authorization-manager ctx)
           session               (get-in ctx [:request :session])
           authenticated-user    (:authenticated-user session)
           env                   {:session                       session
                                  :session/authorization-manager authorization-manager
                                  :session/authenticated-user    authenticated-user}]
       (execute-pathom ctx env params)))})

(def get-user-photo
  "Return a user photograph.
  This endpoint is designed to flexibly handle lookup of a user photograph.
  TODO: fallback to active directory photograph."
  {:name ::get-user-photo
   :enter
   (fn [{:keys [conn] :as ctx}]
     (let [system (get-in ctx [:request :path-params :system])
           value (get-in ctx [:request :path-params :value])]
       (log/debug "user photo request" {:system system :value value})
       (if (or (= "patientcare.app" system) (= "cymru.nhs.uk" system))
         (if-let [photo (rsdb/fetch-user-photo conn value)]
           (assoc ctx :response {:status  200
                                 :headers {"Content-Type" (:erattachment/mimetype photo)}
                                 :body    (:erattachmentdata/data photo)})
           ctx)
         ctx)))})

(def tap-ctx
  {:name  ::tap
   :enter (fn [ctx] (tap> {:on-enter ctx}) ctx)
   :leave (fn [ctx] (tap> {:on-leave ctx}) ctx)})

(defn- current-time []
  (quot (System/currentTimeMillis) 1000))

(defn- session-expired?
  [{:keys [idle-timeout]}]
  (let [now (current-time)
        expired? (and idle-timeout (< idle-timeout now))]
    (log/info {:idle-timeout idle-timeout
               :now          now
               :expired?     expired?})
    expired?))

(def session-timeout
  "If we have a logged-in user, check the idle timeout."
  {:name  ::session-timeout
   :enter (fn [ctx]
            (let [session (get-in ctx [:request :session])
                  user (:authenticated-user session)]
              (if (and user (session-expired? session))
                (assoc ctx :response {:status  200
                                      :headers {}           ;; have to return headers to stop interceptor chain
                                      :body    {:error "Your session timed out"}
                                      :session (dissoc session :authenticated-user :idle-timeout)})
                ctx)))})

(def session-keep-alive
  {:name ::session-keep-alive
   :leave
   (fn [{:keys [request response] :as ctx}]
     (let [response-session? (contains? response :session)
           request-session (:session request)
           user (or (:authenticated-user request-session) (get-in response [:session :authenticated-user]))]
       (cond
         ;; no logged in user => do nothing
         (not user)
         ctx
         ;; we have a user, but no session in the response so far => add an idle timeout
         (not response-session?)
         (assoc-in ctx [:response :session]
                   (assoc request-session :idle-timeout (+ (current-time) 10)))
         ;; we have a user, and a response session => update the idle timeout
         :else
         (update-in ctx [:response :session] assoc :idle-timeout (+ (current-time) 10)))))})

(def routes
  (route/expand-routes
   #{["/" :get [landing]]
     ["/login" :post [login]]
     ["/api" :post [authorization-manager api]]
     ["/users/:system/:value/photo" :get [authorization-manager get-user-photo]]}))

(def transit-read-handlers
  {"LocalDateTime"
   (transit/read-handler (fn [^String s] (LocalDateTime/parse s)))

   "ZonedDateTime"
   (transit/read-handler (fn [^String s] (ZonedDateTime/parse s)))

   "LocalDate"
   (transit/read-handler (fn [^String s] (LocalDate/parse s)))

   "Period"
   (transit/read-handler (fn [^String s] (Period/parse s)))

   com.fulcrologic.fulcro.algorithms.tempid/tag
   (transit/read-handler (fn [uuid] (tempid/tempid uuid)))})

(def transit-write-handlers
  {LocalDateTime
   (transit/write-handler (constantly "LocalDateTime")
                          (fn [^LocalDateTime date]
                            (.format date DateTimeFormatter/ISO_LOCAL_DATE_TIME)))

   ZonedDateTime
   (transit/write-handler (constantly "ZonedDateTime")
                          (fn [^ZonedDateTime date]
                            (.format date DateTimeFormatter/ISO_ZONED_DATE_TIME)))

   LocalDate
   (transit/write-handler (constantly "LocalDate")
                          (fn [^LocalDate date]
                            (.format date DateTimeFormatter/ISO_LOCAL_DATE)))

   Period
   (transit/write-handler (constantly "Period")
                          (fn [^Period period]   ;; write a period in ISO-8601 format
                            (.toString period)))

   Throwable
   (transit/write-handler "java.lang.Exception" Throwable->map)

   TempId
   (transit/write-handler tempid/tag #(.-id ^TempId %))})

(defn make-service-map
  [{:keys [port allowed-origins host join? session-key session-timeout-seconds] :or {port 8080, join? false}}]
  {::http/type                  :jetty
   ::http/join?                 join?
   ::http/log-request           true
   ::http/routes                routes
   ::http/resource-path         "/public"
   ::http/port                  port
   ::http/allowed-origins       (if (= "*" allowed-origins) (constantly true) allowed-origins)
   ::http/secure-headers        {:content-security-policy-settings {:object-src "none"}}
   ::http/host                  (or host "127.0.0.1")
   ::http/enable-session        {:store        (ring.middleware.session.cookie/cookie-store
                                                (when session-key {:key (buddy.core.codecs/hex->bytes session-key)}))
                                 :cookie-name  "pc4-session"
                                 :cookie-attrs {:same-site :strict}}
   ::http/not-found-interceptor not-found})

;;;;;
;;;;;
;;;;;

(defn find-cljs-modules
  [path]
  (log/debug "looking for cljs modules in " path)
  (if-let [manifest-url (io/resource path)]
    (let [manifest (edn/read-string (slurp manifest-url))]
      (log/info "found cljs modules"
                (reduce (fn [acc {:keys [module-id output-name]}] (assoc acc module-id output-name)) {} manifest))
      (reduce (fn [acc {:keys [module-id] :as module}]      ;; return a map of module-id to module information
                (assoc acc module-id module)) {} manifest))
    (log/info "no shadow cljs manifest file found")))

;;;;;
;;;;;
;;;;;

(s/def ::pathom fn?)
(s/def ::conn some?)
(s/def ::env (s/keys :req-un [::conn ::pathom]))
(s/def ::cljs-manifest string?)
(s/def ::port int?)
(s/def ::host string?)
(s/def ::join? boolean?)
(s/def ::session-key string?)
(s/def ::config (s/keys :req-un [::env ::cljs-manifest]
                        :opt-un [::port ::host ::allowed-origins ::join? ::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [env session-key cljs-manifest] :as config}]
  (log/info "starting HTTP server" (dissoc config :env :session-key))
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain-data ::config config))))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart or load balancing"))
  (let [cljs-modules (find-cljs-modules cljs-manifest)]
    (if (empty? cljs-modules)
      (log/error "no cljs modules found; this server is designed to serve cljs application(s) but none found" {:path cljs-manifest})
      (-> (make-service-map config)
          (http/default-interceptors)
          (http/dev-interceptors)
          (update ::http/interceptors conj
                  (intc/interceptor (inject (assoc env :cljs-modules cljs-modules)))
                  (body-params/body-params (body-params/default-parser-map :transit-options [{:handlers transit-read-handlers}]))
                  (http/transit-body-interceptor ::transit-json-body "application/transit+json;charset=UTF-8" :json {:handlers transit-write-handlers})
                  (csrf/anti-forgery)                           ;; we manually insert interceptor here, as otherwise default-interceptors includes a non-customised body params
                  service-error-handler)
          (http/create-server)
          (http/start)))))

(defmethod ig/halt-key! ::server [_ service-map]
  (http/stop service-map))

