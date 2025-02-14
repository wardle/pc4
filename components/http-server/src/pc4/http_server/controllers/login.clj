(ns pc4.http-server.controllers.login
  (:require
    [clojure.string :as str]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [lambdaisland.uri :as uri]
    [pc4.http-server.web :as web]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]))

(defn login [request]
  (let [redirect-url (get-in request [:params :redirect-url])]
    (web/ok
      (web/render-file "templates/login-page.html"
                       {:title        "Login"
                        :action       (route/url-for :user/login!)
                        :csrf-token   (csrf/existing-token request)
                        :redirect-url redirect-url}))))

(defn sanitise-redirect-url
  "Sanitise a redirection URL. At the moment, we only permit redirection using
  relative URLs on the same host at the point of login, so strip out additional information 
  such as host and port. This could instead use a regular expression to check for
  approved redirects."
  [url]
  (when-not (str/blank? url)
    (let [uri (uri/parse url)]
      (uri/uri-str (dissoc uri :scheme :user :password :host :port)))))

(defn do-login
  [request]
  (let [session (:session request)
        rsdb (get-in request [:env :rsdb])
        username (get-in request [:form-params :username])
        password (get-in request [:form-params :password])
        redirect-url (or (sanitise-redirect-url (get-in request [:form-params :redirect-url]))
                         (route/url-for :home))
        user (when-not (str/blank? username) (rsdb/perform-login! rsdb username password))]
    (log/info "login attempt by user '" username "' with redirect to" redirect-url)
    (if-not user
      (web/ok
        (web/render-file "templates/login-page.html"
                         {:title        "Login"
                          :action       (route/url-for :user/login!)
                          :csrf-token   (csrf/existing-token request)
                          :username     username
                          :error        "Invalid username or password"
                          :redirect-url redirect-url}))
      (-> (web/redirect-see-other redirect-url)             ;; return redirect response with updated session
          (assoc :session (assoc session :authenticated-user user))))))


(defn impersonate
  "A handler that allows a system user to impersonate another user. This is
  useful during development and testing to evaluate permissions implementation."
  [request]
  (let [rsdb (get-in request [:env :rsdb])
        user-id (some-> (get-in request [:path-params :user-id]) parse-long)
        {:t_user/keys [username]} (rsdb/user-by-id rsdb user-id)
        authenticated-user (get-in request [:session :authenticated-user])
        is-system? (:t_role/is_system authenticated-user)]
    (if-not is-system?
      (do (log/warn "impersonate attempt by non-system user"
                    {:username (:t_user/username authenticated-user), :impersonate-user-id user-id})
          {:status 403 :body "You do not have permission"})
      (let [user (rsdb/perform-login! rsdb username nil {:impersonate true})]
        (log/warn "system user impersonating user" user-id)
        (-> (web/redirect-see-other (route/url-for :home))
            (assoc :session (assoc (:session request) :authenticated-user user)))))))


(def authenticated
  "An interceptor that checks that there is an authenticated user, redirecting
  to the login page if there is not. If there is a valid user, an authorization
  manager is added to the request."
  {:name :authenticated
   :enter
   (fn [{:keys [request] :as ctx}]
     (let [uri (:uri request)
           user (get-in request [:session :authenticated-user])]
       (if user
         (assoc-in ctx [:request :authorization-manager] (rsdb/user->authorization-manager user))
         (let [query-params (when (not= "/" uri) {"redirect-url" uri})]
           (log/info "no authenticated user for uri:" uri)
           (assoc ctx :response (web/redirect-see-other (route/url-for :user/login :query-params query-params)))))))
   :leave
   (fn [ctx]                                                ;; ensure response is not cached
     (update-in ctx [:response :headers] assoc
                "Cache-Control" "no-cache, must-revalidate, max-age=0, no-store, private"
                "Pragma" "no-cache"
                "Expires" "0"))})

(defn logout
  [_request]
  (-> (web/moved-permanently (route/url-for :home))
      (assoc :session nil)))
