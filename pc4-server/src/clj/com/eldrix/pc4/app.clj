(ns com.eldrix.pc4.app
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.pc4.rsdb.auth :as rsdb.auth]
            [com.eldrix.pc4.rsdb.users :as users]
            [com.eldrix.pc4.rsdb.users :as rsdb.users]
            [com.eldrix.pc4.ui.misc :as misc]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.interceptor.chain :as chain]
            [com.eldrix.pc4.rsdb.users]
            [com.eldrix.pc4.ui.misc :as ui.misc]
            [com.eldrix.pc4.ui.patient :as ui.patient]
            [com.eldrix.pc4.ui.project :as ui.project]
            [com.eldrix.pc4.ui.user :as ui.user]
            [reitit.core :as r]))


(defn page [content]
  [:html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:script {:src "/js/htmx.org-v1.9.2_dist_htmx.min.js"}]
    [:link {:href "/css/output.css" :rel "stylesheet"}]]
   [:body.h-full.bg-gray-100
    {:hx-boost "true"}
    content]])


(defn redirect [url]
  {:status  301
   :headers {"Location" url}})


(defn logout-button [{request :request, :as ctx}]
  [:form {:method "post" :action (r/match->path (r/match-by-name (::r/router request) :logout))}
   [:input {:type "hidden" :name "__anti-forgery-token" :value (get-in ctx [:request ::csrf/anti-forgery-token])}]
   [:button "Logout"]])

(defn navigation-bar [ctx]
  (let [router (get-in ctx [:request ::r/router])
        authenticated-user (get-in ctx [:request :session :authenticated-user])
        show-user-menu? (some-> (get-in ctx [:request :params :show-user-menu]) parse-boolean)]
    (ui.user/nav-bar
      (cond-> {:id    "nav-bar"
               :title {:s "PatientCare" :attrs {:href (r/match->path (r/match-by-name! router :home))}}}
              authenticated-user
              (assoc :user {:full-name  (:t_user/full_name authenticated-user)
                            :initials   (:t_user/initials authenticated-user)
                            :attrs      {:hx-get    (r/match->path (r/match-by-name! router :nav-bar) {:show-user-menu (not show-user-menu?)})
                                         :hx-target "#nav-bar" :hx-swap "outerHTML"}
                            :photo      (when (:t_user/has_photo authenticated-user) {:src (r/match->path (r/match-by-name! router :get-user-photo {:system "patientcare.app" :value (:t_user/username authenticated-user)}))})
                            :menu-open? show-user-menu?
                            :menu       [{:id    :about
                                          :title (:t_user/job_title authenticated-user)}
                                         {:id    :view-profile
                                          :title "My profile"
                                          :attrs {:href (r/match->path (r/match-by-name! router :get-user {:user-id (:t_user/id authenticated-user)}))}}
                                         {:id    :logout :title "Logout"
                                          :attrs {:hx-post     (r/match->path (r/match-by-name router :logout))
                                                  :hx-push-url "true"
                                                  :hx-target   "body"
                                                  :hx-vals     (str "{\"__anti-forgery-token\" : \"" (get-in ctx [:request ::csrf/anti-forgery-token]) "\"}")}}]})))))

(def home-page
  {:enter
   (fn [ctx]
     (let [router (get-in ctx [:request ::r/router])
           active-projects (get-in ctx [:result :t_user/roles])
           latest-news (get-in ctx [:result :t_user/latest_news])]
       (assoc ctx
         :component
         (page [:<>
                (navigation-bar ctx)
                [:div.grid.grid-cols-1.md:grid-cols-4.md:gap-4.m-4
                 [:div.md:mr-2
                  (ui.user/project-panel {:projects   active-projects
                                          :make-attrs #(hash-map :href (r/match->path (r/match-by-name router :get-project {:project-id (:t_project/id %)})))})]
                 [:div.col-span-3
                  (ui.user/list-news {:news-items latest-news})]]]))))})

(def nav-bar
  {:enter (fn [ctx] (assoc ctx :component (navigation-bar ctx)))})

(def patient-properties
  [:t_patient/id
   :t_patient/patient_identifier
   :t_patient/title
   :t_patient/first_names
   :t_patient/last_name
   :t_patient/date_birth
   :t_patient/date_death
   :t_patient/current_age
   :t_patient/status
   {:t_patient/address [:t_address/address1 :t_address/address2 :t_address/address3 :t_address/postcode]}
   :t_patient/surgery
   {:t_patient/hospitals [:t_patient_hospital/patient_identifier
                          :t_patient_hospital/hospital]}])

(def view-patient-page
  "Takes result from :result in context and generates a component"
  {:enter
   (fn [{{:t_patient/keys [patient_identifier status nhs_number last_name first_names title current_age date_birth date_death address] :as patient} :result, :as ctx}]
     (log/info {:name :view-patient-page :patient patient})
     (if-not patient_identifier
       ctx
       (assoc ctx :component
                  (page [:<>
                         (navigation-bar ctx)
                         (ui.patient/patient-banner
                           {:patient-name (str last_name ", " (str/join " " [title first_names]))
                            :born         date_birth
                            :approximate  (= :PSEUDONYMOUS status)
                            :age          current_age
                            :nhs-number   nhs_number
                            :address      (str/join ", " (remove str/blank? [(:t_address/address1 address) (:t_address/address2 address)
                                                                             (:t_address/address3 address) (:t_address/postcode address)]))
                            :deceased     date_death})]))))})

(def view-patient-demographics
  {:enter
   (fn [{{:t_patient/keys [id patient_identifier status last_name first_names title current_age date_birth date_death] :as patient} :result, :as ctx}]
     (if-not patient_identifier
       ctx
       (assoc ctx :component
                  (ui.patient/patient-banner
                    {:patient-name (str last_name ", " (str/join " " [title first_names]))
                     :born         date_birth
                     :approximate  (= :PSEUDONYMOUS status)
                     :age          current_age
                     :address      (get-in patient [:address :t_address/address1])
                     :deceased     date_death}))))})


(defn project-menu [ctx {:keys [project-id title selected-id]}]
  (let [router (get-in ctx [:request ::r/router])
        content (fn [s] (vector :span.truncate s))]
    (ui.misc/vertical-navigation
      {:selected-id selected-id
       :items       [{:id      :home
                      :icon    (ui.misc/icon-home)
                      :content (content (or title "Home"))
                      :attrs   {:href (r/match->path (r/match-by-name! router :get-project {:project-id project-id}))}}
                     {:id      :team
                      :icon    (ui.misc/icon-team)
                      :content (content "Team")
                      :attrs   {:href (r/match->path (r/match-by-name! router :get-project-team {:project-id project-id}))}}
                     {:id      :reports
                      :icon    (ui.misc/icon-reports)
                      :content (content "Downloads")}]
       :sub-menu
       (case selected-id
         :team {:title "Team"
                :items [{:id      :filter
                         :content (let [url (r/match->path (r/match-by-name! router :get-project-team {:project-id project-id}))]
                                    [:form {:method "post" :url url :hx-post url :hx-trigger "change" :hx-target "#list-users"}
                                     [:input {:type "hidden" :name "__anti-forgery-token" :value (get-in ctx [:request ::csrf/anti-forgery-token])}]
                                     [:select.w-full.p-2.border
                                      {:name "active"}
                                      [:option {:value "active"} "Active"] [:option {:value "inactive"} "Inactive"] [:option {:value "all"} "All users"]]
                                     [:input.border.p-2.w-full
                                      {:type    "search" :name "search" :placeholder "Search..."
                                       :hx-post url :hx-trigger "keyup changed delay:500ms"}]])}]}
         {})})))


(def view-project-home
  {:enter
   (fn [{{:t_project/keys [id] :as project} :result, :as ctx}]
     (let [router (get-in ctx [:request ::r/router])]
       (log/info {:name :view-project-page :project project})
       (if-not project
         ctx
         (let [project* (cond-> project
                                (:t_project/parent_project project) ;; add a URL for parent project based on project-id
                                (assoc-in [:t_project/parent_project :t_project/url] (r/match->path (r/match-by-name! router :get-project {:project-id (get-in project [:t_project/parent_project :t_project/id])}))))]
           (assoc ctx :component
                      (page [:<> (navigation-bar ctx)
                             [:div.grid.grid-cols-1.md:grid-cols-6
                              [:div.col-span-1.pt-6
                               [:<> [:div.px-2.py-1.font-bold (:t_project/title project)]
                                (project-menu ctx {:project-id id :title "Home" :selected-id :home})]]
                              [:div.col-span-5.p-6
                               (ui.project/project-home project*)]]]))))))})

(def view-project-users
  {:enter
   (fn [{{:t_project/keys [id title users] :as project} :result, request :request, :as ctx}]
     (if-not project
       ctx
       (let [hx-request? (parse-boolean (or (get-in request [:headers "hx-request"]) "false"))
             hx-boosted? (parse-boolean (or (get-in request [:headers "hx-boosted"]) "false"))
             router (::r/router request)
             s (some-> (get-in ctx [:request :form-params :search]) str/trim str/lower-case)
             users' (->> users
                         (map #(assoc % :user-url (r/match->path (r/match-by-name! router :get-project-user {:project-id id :user-id (:t_user/id %)}))
                                        :photo-url (when (:t_user/has_photo %) (r/match->path (r/match-by-name router :get-user-photo {:system "patientcare.app" :value (:t_user/username %)})))))
                         (filter #(or (nil? s) (str/includes? (str/lower-case (:t_user/full_name %)) s) (str/includes? (str/lower-case (:t_user/job_title %)) s))))]
         (assoc ctx :component
                    (if (and hx-request? (not hx-boosted?)) ;; if we have a htmx request, just return content... otherwise, build whole page
                      (ui.project/project-users users')
                      (page [:<> (navigation-bar ctx)
                             #_(misc/breadcrumbs
                                 (misc/breadcrumb-home {:href "#"})
                                 (misc/breadcrumb-item {:href "#"} "Project")
                                 (misc/breadcrumb-item {:href "#"} "Team"))
                             [:div.grid.grid-cols-1.md:grid-cols-6
                              [:div.col-span-1.pt-6
                               [:<> [:div.px-2.py-1.font-bold (:t_project/title project)]
                                (project-menu ctx (merge (:params request) {:project-id id :title "Home" :selected-id :team}))]]
                              [:div#list-users.col-span-5.p-6
                               (ui.project/project-users users')]]]))))))})

(def view-user
  {:enter
   (fn [{user :result, request :request, authorizer :authorizer, :as ctx}]
     (if-not user
       ctx
       (let [is-system (when authorizer (authorizer :SYSTEM))
             project-id (some-> (get-in request [:path-params :project-id]) parse-long)
             router (::r/router request)
             user' (cond-> user
                           is-system
                           (assoc :impersonate-user-url (r/match->path (r/match-by-name! router :impersonate-user {:user-id (:t_user/id user)}))))]
         (println "view user: " user')
         (assoc ctx :component
                    (page [:<> (navigation-bar ctx)
                           [:div.container.mx-auto.px-4.sm:px-6.lg:px-8
                            (ui.user/view-user user')]])))))})

(def user-ui-select-common-concepts
  "Interceptor to return a HTML select list of common concepts constrained by
  ECL. Any parameters, such as name, hx-get, or hx-post, or hx-target are passed
  to the HTML SELECT component."
  {:enter
   (fn [{result :result, request :request, :as ctx}]
     (let [concepts (->> (:t_user/common_concepts result)
                         (map #(hash-map :id (:info.snomed.Concept/id %) :term (get-in % [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
                         (sort-by :term))]
       (println (:params request))
       (assoc ctx :component
                  [:select (:params request)
                   (for [{:keys [id term]} concepts]
                     [:option {:value id} term])])))})



(def login-user-props
  [:t_user/username :t_user/id :t_user/full_name :t_user/first_names
   :t_user/last_name :t_user/initials :t_user/has_photo :t_user/job_title])

(def login
  "Logic for application login. This is currently only designed for users
   registered on rsdb, rather than handling multiple user types. Adds :login
   to the context with a result indicating success or failure by the presence
   of a value for :user."
  {:enter
   (fn [{request :request,
         pathom  :pathom/boundary-interface,
         conn    :com.eldrix.rsdb/conn, :as ctx}]
     (let [router (::r/router request)
           username (get-in request [:params "username"])
           password (get-in request [:params "password"])
           url (get-in request [:params "url"])
           user (when (and username password)
                  (-> (pathom nil [{(list 'pc4.users/login {:system "cymru.nhs.uk" :value username :password password})
                                    login-user-props}])
                      (get 'pc4.users/login)))
           can-login? (when user (rsdb.auth/authorized-any? (rsdb.users/make-authorization-manager conn username) :LOGIN))]
       (if user                                             ;; if we have logged in, route to the requested URL, or to home
         (assoc ctx :login {:user user :url (or url (r/match->path (r/match-by-name! router :home)))})
         (assoc ctx :login {:username  username :url (or url (r/match->path (r/match-by-name! router :home)))
                            :error     (if can-login? (when (and (= :post (:request-method request)) (not (str/blank? username))) "Invalid username or password")
                                                      "You are not registered to any active services or research projects.")
                            :login-url (r/match->path (r/match-by-name! router :login-page))}))))})

(def impersonate
  "A logged-in system user can choose to impersonate another user for testing
  purposes."
  {:enter
   (fn [{request :request, authorizer :authorizer,
         pathom  :pathom/boundary-interface,
         :as     ctx}]
     (let [router (::r/router request)
           user-id (some-> (get-in request [:path-params :user-id]) parse-long)]
       (if-not (when authorizer (authorizer :SYSTEM))       ;; TODO: consider switching to a dedicated permission
         (-> ctx (chain/terminate) (assoc :response {:status 401 :body "Unauthorized"}))
         (let [user (pathom {:pathom/entity {:t_user/id user-id}
                             :pathom/eql    login-user-props})]
           (log/warn "system user impersonating user" user)
           (-> ctx
               (dissoc :authorization-manager :authorizer)
               (assoc :login {:user user :url (r/match->path (r/match-by-name! router :home))}))))))})

(def logout
  {:enter
   (fn [{request :request, :as ctx}]
     (log/info "performing logout" (get-in ctx [:request :session :authenticated-user]))
     (-> ctx
         (assoc :response (-> (redirect (r/match->path (r/match-by-name! (::r/router request) :home)))
                              (assoc :session nil)))))})

(def view-login-page
  "Takes :login from the context and either performs the redirect for a logged-
  in user, or shows the login page."
  {:enter
   (fn [{{:keys [url login-url user username error] :as login} :login, :as ctx}]
     (log/info "view-login-page" login)
     (if user
       (-> ctx (chain/terminate)
           (assoc :response (-> (redirect url) (assoc-in [:session :authenticated-user] user))))
       (assoc ctx :component (page
                               [:<>
                                (navigation-bar ctx)
                                (ui.user/login-panel {:form     {:method "post" :action login-url}
                                                      :hidden   {:url url :__anti-forgery-token (get-in ctx [:request ::csrf/anti-forgery-token])}
                                                      :username {:value username}
                                                      :error    error})]))))})

