(ns pc4.workbench.controllers.project
  (:require
    [clojure.string :as str]
    [com.wsscode.pathom3.connect.operation :as pco]
    [io.pedestal.http.route :as route]
    [pc4.pathom-web.interface :as pw]
    [pc4.common-ui.interface :as ui]
    [pc4.web.interface :as web]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]
    [pc4.workbench.controllers.project.araf :as project-araf]))



(pco/defresolver current-project
  [{:keys [request] :as env} _]
  {:pco/output [{:ui/current-project [:t_project/id :t_project/title]}]}
  {:ui/current-project
   (when-let [project-id (get-in request [:session :project :id])]
     {:t_project/id    project-id
      :t_project/title (get-in request [:session :project :title])})}) ;; save a refetch for the title as it is in session

(pco/defresolver project-menu
  [env {:t_project/keys [id name title home_page pseudonymous active?] :as project}]
  {::pco/input  [:t_project/id :t_project/title :t_project/pseudonymous :t_project/active?]
   ::pco/output [:ui/project-menu]}
  (let [selected (get (pco/params env) :selected :home)]
    {:ui/project-menu
     {:title    title
      :selected selected
      :items    [{:id   :home
                  :url  (route/url-for :project/home :path-params {:project-id id})
                  :text "Home"}
                 {:id     :araf
                  :url    (route/url-for :project/araf :path-params {:project-id id})
                  :text   "Today"
                  :hidden (nil? (project-araf/project-programme id))}
                 {:id     :today
                  :url    (route/url-for :project/today :path-params {:project-id id})
                  :text   "Today"
                  :hidden (or (not active?) (some? (project-araf/project-programme id)))}
                 {:id   :patients
                  :url  (route/url-for :project/patients :path-params {:project-id id})
                  :text "Patients"}
                 {:id   :find-patient
                  :url  (route/url-for :project/find-patient :path-params {:project-id id})
                  :text "Find patient"}
                 {:id     :register-patient
                  :url    (route/url-for :project/register-patient :path-params {:project-id id})
                  :text   "Register patient"
                  :hidden (not active?)}
                 {:id   :encounters
                  :url  (route/url-for :project/encounters :path-params {:project-id id})
                  :text "Encounters"}
                 {:id   :team
                  :url  (route/url-for :project/team :path-params {:project-id id})
                  :text "Team"}]}}))



(def resolvers [current-project project-menu])


;;
;;
;;

(defn search-by-patient-identifier-panel
  [{:keys [project user csrf-token]}]
  (let [{project-id :t_project/id pseudonymous? :t_project/pseudonymous} project
        {is-system? :t_role/is_system} user
        disabled (and pseudonymous? (not is-system?))]
    (log/debug {:project project :user user :disabled disabled})
    (ui/active-panel
      {:title    "Search by patient identifier"
       :subtitle (when disabled "For pseudonymous projects, this functionality is only available to system administrators")}
      [:form {:method "post" :action (when-not disabled (route/url-for :project/do-find-patient {:params {:project-id project-id}}))}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:div.space-y-2
        (ui/ui-textfield {:id "patient-identifier" :placeholder "Enter patient identifier" :disabled disabled})
        (ui/ui-submit-button {:disabled disabled} "Search »")]])))

(def find-patient
  (pw/handler
    [:ui/navbar
     {:ui/current-project [:t_project/id :t_project/pseudonymous (list :ui/project-menu {:selected :find-patient})]}
     :ui/csrf-token
     {:ui/authenticated-user [:t_role/is_system]} ]
    (fn [request {:ui/keys [navbar current-project csrf-token authenticated-user]}]
      (let [rsdb (get-in request [:env :rsdb])
            patient-identifier (some-> (get-in request [:params "patient-identifier"]) parse-long)
            found-patient (when patient-identifier (rsdb/fetch-patient rsdb {:t_patient/patient_identifier patient-identifier}))]
        (cond
          found-patient                                     ;; we have a found single patient -> redirect to open patient record
          (web/redirect-see-other (route/url-for :patient/home :params {:patient-identifier patient-identifier}))
          :else
          (web/ok
            (ui/render-file
              "templates/project/find-patient-page.html"
              {:title      "Find patient"
               :navbar     navbar
               :menu       (:ui/project-menu current-project)
               :find-by-id (ui/render
                             (search-by-patient-identifier-panel
                               {:project current-project :csrf-token csrf-token
                                :user authenticated-user}))})))))))

(comment
  (ui/render (ui/active-panel {:title "hi there" :class "wibble woobble"})))



(defn today-wizard [request])

(defn patients [request])

(defn encounters [request])



(defn user-filter-select-button []
  (ui/ui-select-button
    {:id          "user-filter"
     :selected-id "active"
     :hx-get      (route/url-for :project/team)
     :hx-target   "#team-list"
     :hx-swap     "outerHTML"
     :options     [{:id "active" :text "Active"}
                   {:id "inactive" :text "Inactive"}
                   {:id "all" :text "All users"}]}))

(defn user->team-item [{:t_user/keys [id username photo_fk roles] :as user}]
  (let [user' (rsdb/user->display-names user)]
    {:title     (:t_user/full_name user')
     :url       (route/url-for :user/profile {:params {:user-id id}})
     :badges    (into #{} (comp
                            (filter :t_project_user/active?)
                            (map (comp str/upper-case name :t_project_user/role))) roles)
     :subtitle  (rsdb/user->job-title user)
     :image-url (when photo_fk (route/url-for :user/photo {:params {:user-id id}}))}))


(def team
  (pw/handler
    (fn [request]
      (let [user-filter (str/lower-case (get-in request [:params :user-filter] "active"))]
        [{:ui/current-project
          [:t_project/id
           (list :ui/project-menu {:selected :team})
           (list :t_project/users {:group-by :user :active (case user-filter "active" true "inactive" false "all" nil true)})]}
         :ui/navbar]))
    (fn [request {:ui/keys [navbar current-project]}]
      (let [target (get-in request [:headers "hx-target"])
            users (->> (:t_project/users current-project)
                       (sort-by (juxt :t_user/last_name :t_user/first_names))
                       (map user->team-item))]
        (case target
          ;; if we are only updating team list, just render that fragment
          "team-list"
          (web/ok
            (ui/render-file "templates/project/team-list.html" {:team users}))
          ;; otherwise, render whole page...
          (web/ok
            (ui/render-file
              "templates/project/team-page.html"
              {:navbar  navbar
               :menu    (assoc (:ui/project-menu current-project)
                          :submenu {:items [{:content (ui/render (user-filter-select-button))}]})
               :title   "Team"
               :team    users})))))))




(def update-session
  "An interceptor that ensures that our sensibility of 'current project' exists
  in the session. If used in a route that has no project-id in the path, the
  'current project' value will be removed from the session."
  {:enter
   (fn [ctx]
     (let [rsdb (get-in ctx [:request :env :rsdb])
           current-project-id (get-in ctx [:request :session :project :id])
           path-project-id (some-> (get-in ctx [:request :path-params :project-id]) parse-long)]
       (if (and (some? path-project-id) (= current-project-id path-project-id))
         ctx                                                ;; avoid repeated fetches if already current
         (if path-project-id
           ;; we have a current project -> so add into session in the request
           (let [{:t_project/keys [id title]} (rsdb/project-by-id rsdb path-project-id)]
             (-> ctx
                 (assoc ::updated-current-project? true)    ;; signal to our 'leave' phase to update session
                 (assoc-in [:request :session :project] {:id id, :title title})))
           ;; we have no current project -> so remove from the session in the request
           (-> ctx
               (assoc ::updated-current-project? true)
               (update-in [:request :session] dissoc :project))))))
   :leave
   (fn [ctx]
     (if (::updated-current-project? ctx)                   ;; do we need to update session?
       (assoc-in ctx [:response :session] (get-in ctx [:request :session]))
       ctx))})


(defn ^:private project->display-type
  [{:t_project/keys [type pseudonymous]}]
  (str (case type
         :NHS "Clinical"
         :RESEARCH "Research"
         :ALL_PATIENTS "All patients"
         "Unknown")
       (when pseudonymous
         " (pseudonymous)")))
(def home
  (pw/handler
    [:ui/navbar
     {:ui/current-project
      [(list :ui/project-menu {:selected :home})
       :t_project/type :t_project/pseudonymous
       :t_project/title :t_project/long_description
       {:t_project/administrator_user [:t_user/full_name]}
       :t_project/date_from :t_project/date_to
       {:t_project/specialty [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
       {:t_project/parent [:t_project/id :t_project/title]}
       :t_project/address1 :t_project/address2 :t_project/address3 :t_project/address4 :t_project/postcode
       :t_project/inclusion_criteria :t_project/exclusion_criteria
       :t_project/count_registered_patients :t_project/count_pending_referrals :t_project/count_discharged_episodes]}]
    (fn [_ {:ui/keys [navbar current-project]}]
      (let [{:t_project/keys [title long_description administrator_user date_from date_to
                              specialty parent address1 address2 address3 address4 postcode
                              inclusion_criteria exclusion_criteria count_registered_patients
                              count_pending_referrals count_discharged_episodes]} current-project]
        (web/ok
          (ui/render-file
            "templates/project/home-page.html"
            {:navbar  navbar
             :menu    (assoc (:ui/project-menu current-project)
                        :submenu {:items [{:text "Edit project"}]})
             :title   title
             :project {:title       title
                       :tint-class  (case (:t_project/type current-project) :NHS "bg-yellow-100" :RESEARCH "bg-pink-100" "bg-gray-100")
                       :description long_description
                       :items       [{:title "Date from"
                                      :body  date_from}
                                     {:title "Date to"
                                      :body  date_to}
                                     {:title "Administrator"
                                      :body  (:t_user/full_name administrator_user)}
                                     {:title "Registered patients"
                                      :body  count_registered_patients}
                                     {:title "Pending referrals"
                                      :body  count_pending_referrals}
                                     {:title "Discharged (closed) episodes"
                                      :body  count_discharged_episodes}
                                     {:title "Type"
                                      :body  (project->display-type current-project)}
                                     {:title "Specialty"
                                      :body  (get-in specialty [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])}
                                     {:title   "Parent"
                                      :content (str "<a href=\"" (route/url-for :project/home :path-params {:project-id (:t_project/id parent)}) "\">" (:t_project/title parent) "</a>")}]
                       :long-items  [{:title "Address"
                                      :body  (str/join ", " (remove str/blank? [address1 address2 address3 address4 postcode]))}
                                     {:title   "Inclusion criteria"
                                      :content inclusion_criteria}
                                     {:title   "Exclusion criteria"
                                      :content exclusion_criteria}]}}))))))




