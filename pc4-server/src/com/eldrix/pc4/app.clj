(ns com.eldrix.pc4.app
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http.route :as route]
            [com.eldrix.pc4.ui.patient :as ui-patient]
            [com.eldrix.pc4.ui.user :as ui-user]))


(defn page [content]                                        ;; TODO: use locally installed CSS and scripts
  [:html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:script {:src "https://unpkg.com/htmx.org@1.8.5"}]
    [:script {:src "https://cdn.tailwindcss.com"}]]
   [:body
    content]])


(defn redirect [url]
  {:status  301
   :headers {"Location" url}})


(defn logout-button [ctx]
  [:form {:method "post" :action (route/url-for :logout)}
   [:input {:type "hidden" :name "__anti-forgery-token" :value (get-in ctx [:request ::csrf/anti-forgery-token])}]
   [:button "Logout"]])


(def home-page
  {:enter (fn [ctx]
            (assoc ctx :component (page [:div [:h1 "Hello, World"]
                                         (logout-button ctx)])))})




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

(def get-pseudonymous-patient
  {:enter (fn [ctx]
            (let [project-id (get-in ctx [:request :path-params :project-id])
                  pseudonym (get-in ctx [:request :path-params :pseudonym])]
              (when (and project-id pseudonym)
                (log/info {:name       :get-pseudonymous-patient
                           :project-id project-id :pseudonym pseudonym})
                (assoc ctx :query [{[:t_patient/project_pseudonym [(parse-long project-id) pseudonym]]
                                    patient-properties}]))))})

(def get-patient
  {:enter (fn [ctx]
            (let [patient-identifier (get-in ctx [:request :path-params :patient-id])]
              (assoc ctx :query [{[:t_patient/patient_identifier (parse-long patient-identifier)]
                                  patient-properties}])))})

(def view-patient-page
  "Takes first result from :result in context and generates a component"
  {:enter
   (fn [{result :result, :as ctx}]
     (let [{:t_patient/keys [patient_identifier status nhs_number last_name first_names title current_age date_birth date_death address] :as patient} (first (vals result))]
       (log/info {:name :view-patient-page :patient patient})
       (if-not patient_identifier
         ctx
         (assoc ctx :component
                    (page [:div (ui-patient/patient-banner
                                  {:patient-name (str last_name ", " (str/join " " [title first_names]))
                                   :born         date_birth
                                   :approximate  (= :PSEUDONYMOUS status)
                                   :age          current_age
                                   :nhs-number   nhs_number
                                   :address      (str/join ", " (remove str/blank? [(:t_address/address1 address) (:t_address/address2 address)
                                                                                    (:t_address/address3 address) (:t_address/postcode address)]))
                                   :deceased     date_death})])))))})

(def view-patient-demographics
  {:enter
   (fn [{result :result, :as ctx}]
     (let [{:t_patient/keys [id patient_identifier status last_name first_names title current_age date_birth date_death] :as patient} (first (vals result))]
       (if-not patient_identifier
         ctx
         (assoc ctx :component
                    (ui-patient/patient-banner
                      {:patient-name (str last_name ", " (str/join " " [title first_names]))
                       :born         date_birth
                       :approximate  (= :PSEUDONYMOUS status)
                       :age          current_age
                       :address      (get-in patient [:address :t_address/address1])
                       :deceased     date_death})))))})

(def login
  "Logic for application login. This is currently only designed for users
   registered on rsdb, rather than handling multiple user types."
  {:enter
   (fn [{request :request, pathom :pathom/boundary-interface, :as ctx}]
     (let [username (get-in request [:params "username"])
           password (get-in request [:params "password"])
           url (get-in request [:params "url"])
           user (when (and username password)
                  (-> (pathom [{(list 'pc4.users/login {:system "cymru.nhs.uk" :value username :password password})
                                [:t_user/username :t_user/id]}])
                      (get 'pc4.users/login)))]
       (if user                                             ;; if we have logged in, route to the requested URL, or to home
         (assoc ctx :login {:user user :url (or url (route/url-for :home))})
         (assoc ctx :login {:username  username :url (or url (route/url-for :com.eldrix.pc4.pedestal/home))
                            :error     (when (and (= :post (:request-method request)) (not (str/blank? username))) "Invalid username or password")
                            :login-url (route/url-for :post-login-page)}))))})

(def logout
  {:enter
   (fn [ctx]
     (log/info "performing logout" (get-in ctx [:request :session :authenticated-user]))
     (-> ctx
         (assoc :response (-> (redirect (route/url-for :home))
                              (assoc :session nil)))))})

(def view-login-page
  {:enter
   (fn [{{:keys [url login-url user username error] :as login} :login, :as ctx}]
     (log/info "view-login-page" login)
     (if user
       (-> ctx (chain/terminate)
           (assoc :response (-> (redirect url) (assoc-in [:session :authenticated-user] user))))
       (assoc ctx :component (page (ui-user/login-panel {:form     {:method "post" :action login-url}
                                                         :hidden   {:url url :__anti-forgery-token (get-in ctx [:request ::csrf/anti-forgery-token])}
                                                         :username {:value username}
                                                         :error    error})))))})
