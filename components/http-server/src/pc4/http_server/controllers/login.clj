(ns pc4.http-server.controllers.login
  (:require
    [clojure.string :as str]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [lambdaisland.uri :as uri]
    [pc4.http-server.html :as html]
    [pc4.http-server.ui :as ui]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]
    [rum.core :as rum]))

(rum/defc login-panel
  "Login panel with hero title, and parameters:
  - form      : properties for HTML form
  - username  :
  - password  :
  - disabled  : if login form should be disabled
  - error     : an error message to be shown"
  [{:keys [form username password hidden disabled error]}]
  [:div.flex.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
   [:div.max-w-md.w-full.space-y-8
    [:form form
     (for [[k v] hidden
           :when v]
       [:input {:type "hidden" :name (name k) :value (str v)}])
     [:div
      [:h1.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter "PatientCare " [:span.font-bold "v4"]]]
     [:div.rounded-md.shadow-sm
      [:div.mt-8
       [:label.sr-only {:for "username"} "username"]
       [:input#email-address.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
        (merge {:name "username" :type "text" :autoComplete "username" :required true :placeholder "Username" :autofocus true :disabled disabled}
               username)]]
      [:div.mt-2.mb-4
       [:label.sr-only {:for "password"} "Password"]
       [:input#password.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
        (merge {:name "password" :type "password" :autoComplete "current-password" :required true :placeholder "Password" :disabled disabled}
               password)]]]
     (when error
       (ui/box-error-message {:message error}))
     [:div.mt-2
      [:button.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500
       {:type "" :disabled disabled :class (if disabled "bg-gray-400 animate-pulse" "bg-indigo-600 hover:bg-indigo-700")}
       [:span.absolute.left-0.inset-y-0.flex.items-center.pl-3
        [:svg.h-5.w-5.text-indigo-500.group-hover:text-indigo-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true" :class (when disabled "animate-bounce")}
         [:path {:fill-rule "evenodd" :d "M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" :clip-rule "evenodd"}]]] (if disabled "Signing in" "Sign in")]]]]])

(defn login [request]
  (let [redirect-url (get-in request [:params :redirect-url])]
    (html/html5
      {:title "pc4: login"}
      (login-panel {:form   {:method "POST"}
                    :hidden (cond-> {:__anti-forgery-token (csrf/existing-token request)}
                              redirect-url (assoc :redirect-url redirect-url))}))))

(defn sanitise-redirect-url
  "Sanitise a redirection URL. At the moment, we only permit redirection using
  relative URLs on the same host at the point of login, so strip out additional information 
  such as host and port. This could insted use a regular expression to check for
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
        user (rsdb/user->display-names (rsdb/perform-login! rsdb username password))]
    (log/info "login attempt by user '" username "' with redirect to" redirect-url)
    (if-not user
      (html/html5
        {:title "pc4: login"}
        (login-panel {:form   {:method "POST"}
                      :error  "Invalid username or password"
                      :hidden (cond-> {:__anti-forgery-token (csrf/existing-token request)}
                                redirect-url (assoc :redirect-url redirect-url))}))
      {:status  303
       :headers {"Location" redirect-url}        ;; take care to preserve existing session
       :session (assoc session :authenticated-user user)})))

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
           (assoc ctx :response {:status  303
                                 :headers {"Location" (route/url-for :user/login :query-params query-params)}})))))
   :leave                                                   ;; ensure response is not cached
   (fn [ctx]
     (update-in ctx [:response :headers] assoc
                "Cache-Control" "no-cache, must-revalidate, max-age=0, no-store, private"
                "Pragma" "no-cache"
                "Expires" "0"))})

(defn logout
  [_request]
  {:status  301
   :headers {"Location" (route/url-for :home)}
   :session nil})
