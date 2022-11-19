(ns com.eldrix.pc4.www
  "A web server providing server-side rendering."
  (:require [buddy.core.codecs]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.server.web :as web]
            [integrant.core :as ig]
            [io.pedestal.http.route :as route]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.interceptor.chain]
            [io.pedestal.http.body-params]
            [rum.core :as rum]
            [ring.middleware.session.cookie]
            [com.eldrix.pc4.server.config :as config]
            [com.eldrix.pc4.server.home :as home]))



(def home-page-query
  [:t_user/username
   :t_user/must_change_password
   :urn:oid:1.2.840.113556.1.4/sAMAccountName
   :io.jwt/token
   :urn:oid:2.5.4/givenName
   :urn:oid:2.5.4/surname
   :urn:oid:0.9.2342.19200300.100.1.3
   :urn:oid:2.5.4/commonName
   :urn:oid:2.5.4/title
   :urn:oid:2.5.4/telephoneNumber
   :org.hl7.fhir.Practitioner/telecom
   :org.hl7.fhir.Practitioner/identifier
   {:t_user/active_projects                                 ;;; iff the user has an rsdb account, this will be populated
    [:t_project/id :t_project/name :t_project/title :t_project/slug
     :t_project/is_private
     :t_project/long_description :t_project/type
     :t_project/virtual]}
   {:org.hl7.fhir.Practitioner/name
    [:org.hl7.fhir.HumanName/use
     :org.hl7.fhir.HumanName/prefix
     :org.hl7.fhir.HumanName/family
     :org.hl7.fhir.HumanName/given
     :org.hl7.fhir.HumanName/suffix]}
   :t_user/latest_news])

(defn home-page
  [{{:keys [authenticated-user] :as session}    :session
    {:pathom/keys [boundary-interface] :as env} :env
    url-for                                     :url-for
    :as                                         req}]
  (let [{{:t_user/keys [active_projects recent_news] :as user} :session/authenticated-user}
        (boundary-interface {:authenticated-user authenticated-user}
                            [{:session/authenticated-user home-page-query}])]
    (println "authenticated user" authenticated-user)
    (println "user query result:" user)
    (println "url-for" url-for)
    (println "active projects:" active_projects)
    (web/html-response
      (web/page {:page :index}
                [:div
                 [:h1 "Hello " (:urn:oid:2.5.4/givenName authenticated-user)]
                 (home/home-panel (map #(assoc % :t_project/url (@url-for ::inspect-project :params {:id (:t_project/id %)})) active_projects) recent_news)
                 #_(when (seq active_projects) (home/project-panel (map #(assoc % :t_project/url (@url-for ::inspect-project :params {:id (:t_project/id %)})) active_projects)))
                 [:a {:href "/logout"} "Logout"]
                 [:h1 "Hi there"]
                 [:ul
                  (for [i (range 5)]
                    [:li i])]
                 [:pre "request:" req]]))))

(defn inject
  "A simple interceptor to associate a given value into the request under key `k`"
  [k v]
  {:name  ::inject
   :enter (fn [context] (update-in context [:request] assoc k v))})

(def authenticated-user
  "An interceptor to ensure only authenticated users can access the route,
  redirecting to login page if there is no authenticated user."
  {:name  ::authenticated-user
   :enter (fn [context]
            (log/info "Checking have authenticated user" (:request context))
            (if (get-in context [:request :session :authenticated-user])
              context
              (io.pedestal.interceptor.chain/terminate context)))
   :leave (fn [context]
            (if (get-in context [:request :session :authenticated-user])
              context
              (let [path (get-in context [:request :uri])
                    query (get-in context [:request :query-string])
                    url (str path (when query (str "?" query)))]
                (tap> context)
                (log/info "Redirecting to login page with " {:url url})
                (assoc context :response (web/redirect (cond-> {:path "/login"}
                                                               url (assoc :query {:url url})))))))})

(defn login-page
  [req]
  (web/html-response {:session nil}
                     (web/page {:page :login :session nil}
                               (home/main-login-panel {:url "/"}))))

(defn login-handler
  [{{:pathom/keys [boundary-interface]} :env
    {:strs [username password url]}     :params}]
  (log/info "Attempting login for user " {:username username :password password})
  (let [{:pc4.users/syms [login]} (boundary-interface [{(list 'pc4.users/login
                                                              {:system "cymru.nhs.uk" :value username :password password})
                                                        [:t_user/id :t_user/username :urn:oid:2.5.4/givenName :urn:oid:2.5.4/surname]}])]
    (if login
      (do (log/info "Successful login for user" login)
          (web/redirect {:path (or url "/") :session {:authenticated-user login}}))
      (do (log/debug "Failed login for user" username)
          (web/html-response {:session nil}
                             (web/page {:page :login :session nil}
                                       (home/main-login-panel {:username username
                                                               :url      url
                                                               :error    "Incorrect username or password"})))))))
(defn logout-page
  [req]
  (log/info "Performing logout" (get-in req [:session :authenticated-user]))
  (web/redirect {:path "/" :logout? true}))

(defn inspect-project [req] (web/html-response (web/page {} [:h1 "Inspect project"])))

(def routes
  (route/expand-routes
    #{["/" :get [`authenticated-user `home-page]]
      ["/login" :get [`login-page]]
      ["/logout" :get ['logout-page]]
      ["/login" :post [(io.pedestal.http.body-params/body-params) `login-handler]]
      ["/project/:id" :get ['inspect-project]]}))
(comment
  (io.pedestal.http.route/print-routes routes))

(def url-for (route/url-for-routes routes))

(defn make-service-map
  [{:keys [port allowed-origins host join? session-key] :or {port 8080 join? false}}]
  {::http/type            :jetty
   ::http/join?           join?
   ::http/log-request     true
   ::http/routes          routes
   ::http/resource-path   "/public"
   ::http/port            port
   ::http/allowed-origins (cond (= "*" allowed-origins) (constantly true)
                                :else allowed-origins)
   ::http/host            (or host "127.0.0.1")
   ::http/enable-session  {:store        (ring.middleware.session.cookie/cookie-store
                                           (when session-key {:key (buddy.core.codecs/hex->bytes session-key)}))
                           :cookie-attrs {:same-site :lax}}})
(s/def ::env (s/keys :req [:pathom/boundary-interface
                           :com.eldrix.rsdb/conn
                           :com.eldrix.pc4/login]))

(s/def ::config (s/keys :req-un [::env]
                        :opt-un [::port ::host ::allowed-origins ::join? ::session-key]))

(defmethod ig/init-key ::server [_ {:keys [env session-key dev?] :as config}]
  (log/info "Running www HTTP server" (dissoc config :env :session-key))
  (when-not (s/valid? ::config config)
    (throw (ex-info "Invalid server configuration" (s/explain-data ::config config))))
  (when-not session-key
    (log/warn "No explicit session key in configuration; using randomly generated key which will cause problems on server restart or load balancing"))
  (when dev?
    (log/warn "Running in development mode")
    (reset! config/dev? true))
  (-> (make-service-map config)
      (http/default-interceptors)
      (update ::http/interceptors conj
              (intc/interceptor (inject :env env)))
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! ::server [_ service-map]
  (http/stop service-map))