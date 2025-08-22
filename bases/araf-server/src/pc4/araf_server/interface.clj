(ns pc4.araf-server.interface
  (:require
    [buddy.core.codecs :as codecs]
    [clojure.data.json :as json]
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [io.pedestal.http :as http]
    [io.pedestal.http.body-params :as body-params]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [io.pedestal.http.content-negotiation :as content-negotiation]
    [pc4.araf-server.handlers :as h]
    [pc4.araf.interface :as araf]
    [pc4.log.interface :as log]
    [ring.middleware.session.cookie :as cookie]
    [selmer.parser :as selmer]))

(def supported-types ["application/edn" "application/json"])

(def content-neg
  (content-negotiation/negotiate-content supported-types))

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "application/edn"))

(defn transform-content
  [body content-type]
  (case content-type
    "application/edn" (pr-str body)
    "application/json" (json/write-str body)))

(defn coerce-to
  [response content-type]
  (-> response
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-body
  {:name ::coerce-body
   :leave
   (fn [context]
     (if (nil? (get-in context [:response :headers "Content-Type"]))
       (update context :response coerce-to (accepted-type context))
       context))})

(def entity-render
  {:name :entity-render
   :leave
   (fn [context]
     (if-let [item (:result context)]
       (assoc context :response (h/ok item))
       context))})

(defn csrf-error-handler
  [ctx]
  (log/error "missing CSRF token in request" (get-in ctx [:request :uri]))
  (assoc-in ctx [:response] {:status 403 :body "Forbidden; missing CSRF token in submission"}))

(def routes
  #{["/" :get h/home-handler :route-name :home]
    ["/araf/welcome" :get h/welcome-handler :route-name :welcome]
    ["/araf/welcome" :post h/search-handler :route-name :search]
    ["/araf/form/:long-access-key" :get h/intro-handler :route-name :introduction]
    ["/araf/form/:long-access-key/question/:step" :post [(csrf/anti-forgery) h/question-handler] :route-name :question]
    ["/araf/form/:long-access-key/signature" :post [(csrf/anti-forgery) h/signature-handler] :route-name :signature]
    ["/araf/api/request/:nhs-number/:access-key" :get [h/authenticate coerce-body content-neg entity-render h/api-get-request] :route-name :api-get-request]
    ["/araf/api/request" :post [h/authenticate coerce-body content-neg entity-render h/api-create-request] :route-name :api-create-request]
    ["/araf/api/responses" :get [h/authenticate coerce-body content-neg entity-render h/api-get-responses] :route-name :api-get-responses]})

(defn start
  [{:keys [host port env join? session-key]}]
  (-> {::http/host           host
       ::http/port           (or port 8080)
       ::http/routes         (route/routes-from routes)
       ::http/type           :jetty
       ::http/join?          join?
       ::http/resource-path  "/public"
       ::http/enable-session {:store        (cookie/cookie-store (when session-key {:key (codecs/hex->bytes session-key)}))
                              :cookie-name  "araf-session"
                              :cookie-attrs {:same-site :strict}}
       ::http/secure-headers {:content-security-policy-settings {:object-src "none"}}}
      http/default-interceptors
      http/dev-interceptors
      (update ::http/interceptors conj
              (body-params/body-params)
              (csrf/anti-forgery {:error-handler csrf-error-handler})
              (h/env-interceptor env))
      http/create-server
      http/start))

(s/def ::host string?)
(s/def ::port (s/int-in 0 65535))
(s/def ::cache? boolean?)
(s/def ::session-key string?)
(s/def ::svc ::araf/patient-svc)
(s/def ::env (s/keys :req-un [::svc]))
(s/def ::config (s/keys :req-un [::env ::host ::port]
                        :opt-un [::cache? ::session-key]))

(defmethod ig/init-key ::server
  [_ {:keys [cache? session-key env] :or {cache? true} :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid server configuration" (s/explain ::config config))))
  (log/info "starting araf server" (select-keys config [:host :port :cache?]))
  (when-not session-key
    (log/warn "no explicit session key in configuration; using randomly generated key which will cause problems on server restart"))
  (when-not cache?
    (log/warn "template cache disabled for development - performance will be degraded")
    (selmer/cache-off!))
  (start config))

(defmethod ig/halt-key! ::server
  [_ service-map]
  (log/info "stopping araf server" (select-keys service-map [::http/port ::http/type]))
  (http/stop service-map))

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
  (ig.repl/halt)
  (pc4.config.interface/config :dev)
  (route/routes-from routes)
  (def srv (start {:host "localhost" :port 8081}))
  (http/stop srv))

