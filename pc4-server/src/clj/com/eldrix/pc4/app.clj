(ns com.eldrix.pc4.app
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
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
            [reitit.core :as r])
  (:import (java.util Locale)))


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
  {:eql
   (fn [req]
     (let [user (get-in req [:session :authenticated-user])]
       {:pathom/entity user
        :pathom/eql    [{:t_user/active_projects
                         [:t_project/id :t_project_user/id :t_project_user/active? :t_project/active?
                          :t_project/name :t_project/title :t_project/type]}
                        :t_user/latest_news]}))
   :enter
   (fn [ctx]
     (let [router (get-in ctx [:request ::r/router])
           active-projects (->> (get-in ctx [:result :t_user/active_projects])
                                (map (fn [{id :t_project/id :as p}] (assoc p :attrs {:href (r/match->path (r/match-by-name router :get-project {:project-id id}))}))))
           latest-news (get-in ctx [:result :t_user/latest_news])]
       (assoc ctx
         :component
         (page [:<>
                (navigation-bar ctx)
                [:div.grid.grid-cols-1.md:grid-cols-4.md:gap-4.m-4
                 [:div.md:mr-2
                  (ui.user/project-panel {:projects active-projects})]
                 [:div.col-span-3
                  (ui.user/list-news {:news-items latest-news})]]]))))})

(def nav-bar
  {:enter (fn [ctx] (assoc ctx :component (navigation-bar ctx)))})

(def view-patient-page
  {:eql
   (fn [req]                                                ;; we view patient using :patient-id or  a combination of :project-id and :pseudonym
     {:pathom/entity (if-let [patient-id (some-> (get-in req [:path-params :patient-id]) parse-long)]
                       {:t_patient/patient_identifier patient-id}
                       {:t_patient/project_pseudonym [(some-> (get-in req [:path-params :project-id]) parse-long)
                                                      (get-in req [:path-params :pseudonym])]})
      :pathom/eql    [:t_patient/id :t_patient/patient_identifier
                      :t_patient/title :t_patient/first_names :t_patient/last_name
                      :t_patient/date_birth :t_patient/date_death :t_patient/current_age
                      :t_patient/status
                      {:t_patient/address [:t_address/address1 :t_address/address2 :t_address/address3 :t_address/postcode]}
                      :t_patient/surgery
                      {:t_patient/hospitals [:t_patient_hospital/patient_identifier :t_patient_hospital/hospital]}]})
   :enter
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
    [:<>
     [:div.px-2.py-1.font-bold title]
     (ui.misc/vertical-navigation
       {:selected-id selected-id
        :items       [{:id      :home
                       :icon    (ui.misc/icon-home)
                       :content (content "Home")
                       :attrs   {:href (r/match->path (r/match-by-name! router :get-project {:project-id project-id}))}}
                      {:id      :find-patient
                       :icon    (ui.misc/icon-magnifying-glass)
                       :content (content "Find patient")
                       :attrs   {:href (r/match->path (r/match-by-name! router :find-patient {:project-id project-id}))}}
                      {:id      :register-patient
                       :icon    (ui.misc/icon-plus-circle)
                       :content (content "Register patient")
                       :attrs   {:href (r/match->path (r/match-by-name! router :register-patient {:project-id project-id}))}}
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
          {})})]))


(def view-project-home
  {:eql
   (fn [req] {:pathom/entity {:t_project/id (some-> (get-in req [:path-params :project-id]) parse-long)}
              :pathom/eql    [:t_project/id :t_project/title :t_project/name :t_project/inclusion_criteria :t_project/exclusion_criteria
                              :t_project/date_from :t_project/date_to :t_project/address1 :t_project/address2
                              :t_project/address3 :t_project/address4 :t_project/postcode
                              {:t_project/administrator_user [:t_user/full_name :t_user/username]}
                              :t_project/active? :t_project/long_description :t_project/type
                              :t_project/count_registered_patients :t_project/count_discharged_episodes :t_project/count_pending_referrals
                              {:t_project/specialty [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                              {:t_project/parent_project [:t_project/id :t_project/title]}]})
   :enter
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
                               (project-menu ctx {:project-id id :title (:t_project/title project) :selected-id :home})]
                              [:div.col-span-5.p-6
                               (ui.project/project-home project*)]]]))))))})
(def find-patient
  {:eql
   (fn [req]
     (let [project-id (some-> (get-in req [:path-params :project-id]) parse-long)
           pseudonym (or (get-in req [:params :pseudonym]) (get-in req [:params "pseudonym"]))]
       (log/debug {:find-patient--eql {:project-id project-id :pseudonym pseudonym}})
       (into [{[:t_project/id project-id]                   ;; query current project
               [:t_project/id :t_project/title :t_project/type :t_project/pseudonymous]}]
             (when pseudonym                                ;; but when we have a pseudonym, also
               [{(list 'pc4.rsdb/search-patient-by-pseudonym ;; ... perform pseudonymous search
                       {:project-id project-id, :pseudonym pseudonym})
                 [:t_patient/patient_identifier :t_patient/sex :t_patient/date_birth
                  :t_patient/date_death :t_episode/stored_pseudonym :t_episode/project_fk]}]))))
   :enter
   (fn [{:keys [result request] :as ctx}]
     (let [router (get-in ctx [:request ::r/router])
           full-page? (str/blank? (get-in request [:headers "hx-target"]))
           project-id (some-> (get-in request [:path-params :project-id]) parse-long)
           pseudonym (get-in ctx [:request :params "pseudonym"])
           project (get result [:t_project/id project-id])
           patient (get-in result ['pc4.rsdb/search-patient-by-pseudonym])
           view-patient-url (when patient (r/match->path (r/match-by-name! router :get-pseudonymous-patient
                                                                           {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)})))]
       (cond
         ;; redirect to patient record when submitted, matching patient and no target
         (and (not= "search-result" (get-in request [:headers "hx-target"])) patient view-patient-url)
         (assoc ctx :response (redirect view-patient-url))
         full-page?
         (assoc ctx :component
                    (page [:<> (navigation-bar ctx)
                           [:div.grid.grid-cols-1.md:grid-cols-6
                            [:div.col-span-1.pt-6
                             (project-menu ctx {:project-id  project-id
                                                :title       (:t_project/title project)
                                                :selected-id :find-patient})]
                            [:div.col-span-5.p-6
                             (if (:t_project/pseudonymous project)
                               [:form {:method "post" :url (r/match->path (r/match-by-name! router :find-patient {:project-id project-id}))}
                                [:input {:type "hidden" :name "__anti-forgery-token" :value (get-in ctx [:request ::csrf/anti-forgery-token])}]
                                (ui.project/project-search-pseudonymous
                                  {:hx-post       (r/match->path (r/match-by-name! router :find-patient {:project-id project-id}))
                                   :hx-trigger    "keyup changed delay:200ms, search"
                                   :hx-target     "#search-result"
                                   :name          "pseudonym"
                                   :autofocus     true
                                   :auto-complete "off"
                                   :value         pseudonym}
                                  [:div.#search-result])]
                               (ui.misc/box-error-message
                                 {:title "Not yet supported" :message "Only pseudonymous patient search is currently supported but this project uses non-pseudonymous data."}))]]]))
         ;; show patient result as a fragment; this will replace the #search-result div element
         :else
         (assoc ctx :component
                    [:div.bg-white.sm:rounded-lg.mt-4
                     (when patient
                       [:div.px-4.py-5.sm:p-6.border.border-gray-200.shadow.bg-gray-50
                        [:h3.text-lg.leading-6.font-medium.text-gray-900
                         (str (name (:t_patient/sex patient)) " " "born: " (.getYear (:t_patient/date_birth patient)))]
                        [:div.mt-2.sm:flex.sm:items-start.sm:justify-between
                         [:div.max-w-xl.text-sm.text-gray-500
                          [:p (:t_episode/stored_pseudonym patient)]]
                         [:div.mt-5.sm:mt-0.sm:ml-6.sm:flex-shrink-0.sm:flex.sm:items-center
                          [:a {:href view-patient-url}
                           (ui.misc/action-button {} "View patient record")]]]])]))))})




(def register-patient
  {:eql (fn [req]
          {:pathom/entity {:t_project/id (some-> (get-in req [:path-params :project-id]) parse-long)}
           :pathom/eql    [:t_project/id :t_project/title :t_project/inclusion_criteria :t_project/exclusion_criteria
                           :t_project/date_from :t_project/date_to :t_project/active? :t_project/type :t_project/pseudonymous]})
   :enter
   (fn [{{:t_project/keys [id] :as project} :result, :as ctx}]
     (let [router (get-in ctx [:request ::r/router])]
       (if-not project
         ctx
         (assoc ctx :component
                    (page [:<> (navigation-bar ctx)
                           [:div.grid.grid-cols-1.md:grid-cols-6
                            [:div.col-span-1.pt-6
                             (project-menu ctx {:project-id id :title (:t_project/title project) :selected-id :register-patient})]
                            [:div.col-span-5.p-6
                             (if-not (:t_project/pseudonymous project) ;; at the moment, we only support pseudonymous registration
                               (ui.misc/box-error-message {:title   "Not yet supported"
                                                           :message "Only pseudonymous patient registration is currently supported but this project uses non-pseudonymous data."})
                               [:h1 "Register patient"])]]])))))})


(def view-project-users
  {:eql
   (fn [req]
     (let [project-id (some-> (get-in req [:path-params :project-id]) parse-long)
           active (case (get-in req [:form-params :active]) "active" true "inactive" false "all" nil true)] ;; default to true
       {:pathom/entity {:t_project/id project-id}
        :pathom/eql    [:t_project/id :t_project/title
                        {(list :t_project/users {:group-by :user :active active})
                         [:t_user/id :t_user/username :t_user/has_photo :t_user/email :t_user/full_name :t_user/active?
                          :t_user/first_names :t_user/last_name :t_user/job_title
                          {:t_user/roles [:t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?]}]}]}))
   :enter
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
                               (project-menu ctx (merge (:params request) {:project-id id :title (:t_project/title project) :selected-id :team}))]
                              [:div#list-users.col-span-5.p-6
                               (ui.project/project-users users')]]]))))))})

(def view-user
  {:eql
   (fn [req]
     {:pathom/entity {:t_user/id (some-> (get-in req [:path-params :user-id]) parse-long)}
      :pathom/eql    [:t_user/id :t_user/username :t_user/title :t_user/full_name :t_user/first_names :t_user/last_name :t_user/job_title :t_user/authentication_method
                      :t_user/postnomial :t_user/professional_registration :t_user/professional_registration_url
                      :t_professional_registration_authority/abbreviation
                      :t_user/roles]})
   :enter
   (fn [{user :result, request :request, authorizer :authorizer, :as ctx}]
     (if-not user
       ctx
       (let [is-system (when authorizer (authorizer :SYSTEM))
             project-id (some-> (get-in request [:path-params :project-id]) parse-long)
             router (::r/router request)
             user' (cond-> user
                     is-system
                     (assoc :impersonate-user-url (r/match->path (r/match-by-name! router :impersonate-user {:user-id (:t_user/id user)}))))]
         (assoc ctx :component
                    (page [:<> (navigation-bar ctx)
                           [:div.container.mx-auto.px-4.sm:px-6.lg:px-8
                            (ui.user/view-user user')]])))))})

(def user-ui-select-common-concepts
  "Interceptor to return a HTML select list of common concepts constrained by
  ECL. Any parameters, such as name, hx-get, or hx-post, or hx-target are passed
  to the HTML SELECT component."
  {:eql
   (fn [req]
     (let [ecl (get-in req [:params :ecl])]
       {:pathom/entity {:t_user/id (some-> (get-in req [:path-params :user-id]) parse-long)}
        :pathom/eql    [{(list :t_user/common_concepts {:ecl ecl})
                         [:info.snomed.Concept/id
                          {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}))
   :enter
   (fn [{result :result, request :request, :as ctx}]
     (let [concepts (->> (:t_user/common_concepts result)
                         (map #(hash-map :id (:info.snomed.Concept/id %) :term (get-in % [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
                         (sort-by :term))]
       (println (:params request))
       (assoc ctx :component
                  [:select (:params request)
                   (for [{:keys [id term]} concepts]
                     [:option {:value id} term])])))})

(def ui-view-search-concept-results
  "Presents search for concept results as a configurable select box."
  {:eql
   (fn [req]
     (let [s (get-in req [:params :s])
           ecl (get-in req [:params :ecl])
           max-hits (or (get-in req [:params :max-hits]) 512)]
       (if (and s ecl)
         [{(list 'info.snomed.Search/search
                 {:s s, :constraint ecl, :max-hits max-hits})
           [:info.snomed.Description/id
            :info.snomed.Description/term
            :info.snomed.Concept/id
            {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]
         (throw (ex-info "invalid parameters" (:params req))))))
   :enter
   (fn [{result :result, request :request, :as ctx}]
     (let [results (get result 'info.snomed.Search/search)]
       (assoc ctx :component
                  [:select (dissoc (:params request) :s :ecl :max-hits)
                   (for [{id        :info.snomed.Description/id
                          term      :info.snomed.Description/term
                          preferred :info.snomed.Concept/preferredDescription} results]
                     [:option {:value id}
                      (let [preferred-term (:info.snomed.Description/term preferred)]
                        (if (= term preferred-term)
                          term
                          (str term " (" preferred-term ")")))])])))})

(def ui-inspect-description
  {:eql
   (fn [req]
     (let [langs (or (get-in req [:headers "accept-language"]) (.toLanguageTag (Locale/getDefault)))]
       {:pathom/entity {:info.snomed.Description/id (some-> (get-in req [:path-params :description-id]) parse-long)}
        :pathom/eql    [:info.snomed.Description/term
                        {:info.snomed.Description/concept
                         [{(list :info.snomed.Concept/preferredDescription {:accept-language langs})
                           [:info.snomed.Description/term]}
                          {(list :info.snomed.Concept/synonyms {:accept-language langs})
                           [:info.snomed.Description/term]}]}]}))
   :enter
   (fn [{result :result, :as ctx}]
     (let [term (:info.snomed.Description/term result)
           preferred (get-in result [:info.snomed.Description/concept :info.snomed.Concept/preferredDescription :info.snomed.Description/term])
           synonyms (map :info.snomed.Description/term (get-in result [:info.snomed.Description/concept :info.snomed.Concept/synonyms]))]
       (assoc ctx :component
                  [:div preferred
                   [:ul (for [synonym (sort (distinct synonyms))]
                          [:li synonym])]])))})

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

