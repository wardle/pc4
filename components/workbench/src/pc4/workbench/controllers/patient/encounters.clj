(ns pc4.workbench.controllers.patient.encounters
  (:require
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn]
    [pc4.ods-ui.interface :as ods-ui]
    [pc4.pathom-web.interface :as pw]
    [pc4.rsdb.interface :as rsdb]
    [pc4.ui.interface :as ui]
    [pc4.web.interface :as web]
    [pc4.workbench.controllers.select-user :as select-user])
  (:import [java.time LocalDate]))

(defn safe-parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s)))

(defn safe-parse-local-date-time [s]
  (when-not (str/blank? s)
    (try
      (java.time.LocalDateTime/parse s)
      (catch Exception _ nil))))

(defn parse-encounter-form-params
  "Parse encounter form parameters into typed values.
  Returns map with parsed values for encounter fields."
  [form-params]
  {:encounter-template-id (some-> (:encounter-template-id form-params) parse-long)
   :date-time             (safe-parse-local-date-time (:date-time form-params))
   :duration-minutes      (some-> (:duration-minutes form-params) parse-long)
   :consultant-user-fk    (some-> (:consultant-user-fk form-params) parse-long)
   :hospital-fk           (when-not (str/blank? (:hospital-fk form-params)) (:hospital-fk form-params))
   :ward                  (:ward form-params)
   :notes                 (:notes form-params)
   :project-id            (some-> (:project-id form-params) parse-long)
   :project-filter        (:project-filter form-params)
   :user-ids              (when-let [users (:encounter-users form-params)]
                            (try
                              (->> (clojure.edn/read-string users)
                                   (map :user-id)
                                   (filter some?))
                              (catch Exception _ nil)))})

(defn build-encounter-data
  "Build encounter data map for save-encounter!
  For creation: requires patient-identifier
  For update: requires encounter-id"
  [{:keys [encounter-id patient-identifier encounter-template-id date-time notes
           duration-minutes consultant-user-fk hospital-fk ward]}]
  (cond-> {:t_encounter/encounter_template_fk encounter-template-id
           :t_encounter/date_time             date-time
           :t_encounter/notes                 notes
           :t_encounter/duration_minutes      duration-minutes
           :t_encounter/consultant_user_fk    consultant-user-fk
           :t_encounter/hospital_fk           hospital-fk
           :t_encounter/ward                  ward}
    encounter-id (assoc :t_encounter/id encounter-id)
    patient-identifier (assoc :t_patient/patient_identifier patient-identifier)))

(defn fetch-projects-and-templates
  "Fetch suitable and all projects with templates for a patient, and return selected project's templates.
  Parameters:
  - rsdb: database service
  - patient-episodes: patient's episodes from EQL
  - user-projects: authenticated user's active projects from EQL
  - current-project-id: optional current project ID to use as default
  - selected-project-id: optional explicitly selected project ID
  - selected-filter: 'suitable' or 'all' (defaults to 'suitable')

  Returns map with:
  - :suitable-projects - projects where patient has active episodes
  - :my-projects - all user's projects with active templates
  - :selected-project-id - the project ID to use
  - :encounter-templates - templates for the selected project"
  [{:keys [rsdb patient-episodes user-projects current-project-id selected-project-id selected-filter]
    :or   {selected-filter "suitable"}}]
  (let [patient-project-ids (->> patient-episodes
                                 (remove :t_episode/date_discharge)
                                 (map :t_episode/project_fk)
                                 set)
        has-active-templates? (fn [project]
                                (seq (remove :t_encounter_template/is_deleted
                                             (rsdb/project->encounter-templates rsdb (:t_project/id project)))))
        my-projects (->> user-projects
                         (filter has-active-templates?)
                         (sort-by :t_project/title))
        suitable-projects (->> my-projects
                               (filter #(contains? patient-project-ids (:t_project/id %)))
                               (sort-by :t_project/title))
        default-project-id (when (and current-project-id (contains? patient-project-ids current-project-id))
                             current-project-id)
        ;; Determine which project list to use based on filter
        projects (if (= selected-filter "suitable") suitable-projects my-projects)
        project-ids (set (map :t_project/id projects))
        ;; Only use selected-project-id if it's actually in the filtered list
        valid-selected-id (when (and selected-project-id (contains? project-ids selected-project-id))
                            selected-project-id)
        resolved-project-id (or valid-selected-id
                                default-project-id
                                (:t_project/id (first projects)))
        templates (when resolved-project-id
                    (->> (rsdb/project->encounter-templates rsdb resolved-project-id)
                         (remove :t_encounter_template/is_deleted)
                         (sort-by :t_encounter_template/title)))]
    {:suitable-projects   suitable-projects
     :my-projects         my-projects
     :selected-project-id resolved-project-id
     :encounter-templates templates}))

(defn ui-edit-encounter
  "Edit encounter form matching the diagnosis/medication pattern.
  Parameters:
  - :csrf-token         - CSRF token for form submission
  - :can-edit           - Whether user can edit the encounter
  - :encounter          - The encounter map (may be nil for creation)
  - :project-title      - Project title (shown when editing)
  - :encounter-template-title - Encounter template title (shown when editing)
  - :error              - Error message to display
  - :suitable-projects  - List of suitable projects for selection (creation mode)
  - :my-projects        - List of all user's projects (creation mode)
  - :encounter-templates - List of encounter templates for selected project
  - :selected-project-id - Currently selected project ID
  - :selected-filter    - Current project filter ('suitable' or 'all')
  - :consultant-user    - Currently selected consultant/SRO user map
  - :hospital-org       - Currently selected hospital organization
  - :encounter-users    - List of users associated with the encounter
  - :common-hospitals   - Common hospitals for the user (FHIR Organization structures)"
  [{:keys [csrf-token can-edit encounter project-title encounter-template-title error
           suitable-projects my-projects encounter-templates selected-project-id selected-filter
           patient-identifier consultant-user hospital-org encounter-users common-hospitals]
    :or   {selected-filter "suitable"}}]
  (let [{:t_encounter/keys [id date_time duration_minutes notes consultant_user_fk hospital_fk ward]} encounter
        url (if id
              (route/url-for :encounter/update :path-params {:patient-identifier patient-identifier :encounter-id id})
              (route/url-for :encounter/save-new :path-params {:patient-identifier patient-identifier}))
        term (or encounter-template-title "New encounter")
        now (str (java.time.LocalDateTime/now))
        disabled (not can-edit)
        filter-val selected-filter
        projects (if (= filter-val "suitable") suitable-projects my-projects)
        project-id (or selected-project-id (:t_project/id (first projects)))]
    (ui/active-panel
      {:id    "edit-encounter"
       :title term}
      [:form {:method "post" :action url}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:input {:type "hidden" :name "encounter-id" :value id}]

       (ui/ui-simple-form
         (when error
           (ui/box-error-message {:title "Invalid" :message "You have entered invalid data."}))

         ;; Project field - read-only when editing, selector when creating
         (ui/ui-simple-form-item
           {:label "Project"}
           (if id
             [:div.text-gray-900 project-title]
             [:div.flex.flex-col.md:flex-row.gap-4
              [:select.form-select.w-full.md:w-auto
               {:name       "project-filter"
                :hx-post    url
                :hx-target  "#edit-encounter"
                :hx-swap    "outerHTML"
                :hx-include "#edit-encounter"}
               (when (seq suitable-projects)
                 [:option {:value "suitable" :selected (= filter-val "suitable")} "Suitable projects"])
               (when (seq my-projects)
                 [:option {:value "all" :selected (= filter-val "all")} "My projects"])]
              [:select.form-select.flex-1
               {:name       "project-id"
                :required   true
                :hx-post    url
                :hx-target  "#edit-encounter"
                :hx-swap    "outerHTML"
                :hx-include "#edit-encounter"}
               (for [project projects]
                 [:option {:value    (:t_project/id project)
                           :selected (= (:t_project/id project) project-id)}
                  (:t_project/title project)])]]))

         ;; Encounter template field - read-only when editing, selector when creating
         (ui/ui-simple-form-item
           {:label "Encounter Type"}
           (if id
             [:div.text-gray-900 encounter-template-title]
             (when (seq encounter-templates)
               (ui/ui-select-button {:id          "encounter-template-id"
                                     :name        "encounter-template-id"
                                     :required    true
                                     :disabled    disabled
                                     :selected-id (get-in encounter [:t_encounter/encounter_template :t_encounter_template/id])
                                     :options     (map (fn [{:t_encounter_template/keys [id title]}]
                                                         {:id id :text title})
                                                       encounter-templates)}))))

         (ui/ui-simple-form-item
           {:label "Date and Time"}
           (ui/ui-local-date-time {:id "date-time" :name "date-time" :required true :disabled disabled}
                                  (or date_time (java.time.LocalDateTime/now))))

         (ui/ui-simple-form-item
           {:label "Duration (minutes)"}
           (ui/ui-textfield {:id       "duration-minutes"
                             :name     "duration-minutes"
                             :type     "number"
                             :value    duration_minutes
                             :disabled disabled}))

         (ui/ui-simple-form-item
           {:label "Consultant / responsible person"}
           (select-user/ui-select-user
             {:name             "consultant-user-fk"
              :label            "Consultant / responsible person"
              :selected         consultant-user
              :disabled         disabled
              :only-responsible true}))

         (ui/ui-simple-form-item
           {:label "Hospital"}
           (ods-ui/ui-select-org
             {:name         "hospital-fk"
              :label        "Hospital"
              :selected     hospital-org
              :disabled     disabled
              :active       true
              :roles        ["RO148" "RO150" "RO176" "RO198"]
              :fields       #{:name :address}
              :common-orgs  (or common-hospitals [])}))

         (ui/ui-simple-form-item
           {:label "Ward"}
           (ui/ui-textfield {:name     "ward"
                             :value    ward
                             :disabled disabled}))

         (ui/ui-simple-form-item
           {:label "Users"}
           (select-user/ui-select-users
             {:name     "encounter-users"
              :label    "Users"
              :selected encounter-users
              :disabled disabled}))

         (ui/ui-simple-form-item
           {:label "Notes"}
           (ui/ui-rich-text {:id "notes" :name "notes" :rows 10 :disabled disabled} notes))

         (ui/ui-action-bar
           (ui/ui-submit-button {:disabled disabled} "Save")))])))

(def encounter-param-parsers
  {:patient-identifier    parse-long
   :patient-pk            parse-long
   :with-project          parse-boolean
   :with-patient          parse-boolean
   :with-address          parse-boolean
   :with-crns             parse-boolean
   :user-id               parse-long
   :project-id            parse-long
   :episode-id            parse-long
   :encounter-template-id parse-long
   :deleted               parse-boolean
   :in-person             parse-boolean
   :from                  safe-parse-local-date
   :to                    safe-parse-local-date
   :limit                 parse-long
   :offset                parse-long
   :view                  (fn [x] (or (keyword x) :notes))})

(defn parse-list-encounter-params
  "Parse HTTP request parameters into a map suitable for q-encounters.
  Returns a map that conforms to pc4.rsdb.encounters/::query spec."
  [{:keys [form-params] :as _request}]
  (reduce-kv
    (fn [acc k v]
      (assoc acc k ((get encounter-param-parsers k identity) v)))
    {}
    form-params))

(def all-headings
  [{:id    :date-time
    :title "Date/time"
    :f2    (fn [{:keys [patient-identifier]} {:t_encounter/keys [id date_time] patient-identifier# :t_patient/patient-identifier}]
             [:a {:href (route/url-for :patient/encounter :path-params {:patient-identifier (or patient-identifier# patient-identifier)
                                                                        :encounter-id       id})} (ui/format-date-time date_time)])}
   {:id    :project
    :title "Project"
    :f     :t_project/title}
   {:id    :encounter-template
    :title "Type"
    :f     :t_encounter_template/title}
   {:id    :notes
    :title "Notes"
    :f     (fn [{:t_encounter/keys [notes]}] (ui/html->text notes))}
   {:id    :patient
    :title "Patient"
    :f     (fn [{:t_patient/keys [title first_names last_name]}] (str last_name ", " first_names (when-not (str/blank? title) (str " (" title ")"))))}
   {:id    :nhs-number
    :title "NHS number"
    :f     (fn [{:t_patient/keys [nhs_number]}] (nnn/format-nnn nhs_number))}
   {:id    :crns
    :title "CRN(s)"
    :f     :crns}
   {:id    :address
    :title "Address"
    :f     (fn [{:t_address/keys [address1 address2 postcode_raw]}] (str/join ", " (remove nil? [address1 address2 postcode_raw])))}
   {:id    :sro
    :title "Responsible"
    :f     :sro}
   {:id    :users
    :title "Users"
    :f     :users}
   {:id    :edss
    :title "EDSS"
    :f     :t_form_edss/edss_score}
   {:id    :alsfrs
    :title "ALSFRS"
    :f     (constantly "")}
   {:id    :in-relapse
    :title "In relapse"
    :f     :t_form_ms_relapse/in_relapse}
   {:id    :weight
    :title "Weight/height"
    :f     :t_form_weight_height/weight_kilogram}
   {:id    :lung-function
    :title "Lung function"
    :f     :t_form_lung_function/fvc_sitting}])

(def heading-by-id
  (reduce
    (fn [acc {:keys [id] :as heading}]
      (assoc acc id heading))
    {} all-headings))

(defn core-headings
  [{:keys [with-patient with-crns with-address with-project]}]
  (-> (cond-> [:date-time]
        with-patient
        (conj :patient :nhs-number)
        with-crns
        (conj :crns)
        with-address
        (conj :address)
        with-project
        (conj :project))
      (conj :encounter-template)))

(def extra-headings
  {:notes    [:notes]
   :users    [:sro :users]
   :ninflamm [:edss :in-relapse]
   :mnd      [:weight :alsfrs]})

(defn headings [{:keys [view] :or {view :notes} :as params}]
  (let [core (core-headings params)]
    (->> (into core (get extra-headings view []))
         (map #(or (heading-by-id %) (throw (ex-info (str "no heading found with id: " %) {:id %})))))))

(comment
  (headings {:with-patient true}))


(def default-params
  {:view         :notes
   :with-project true
   :with-patient false})


(def encounter-handler
  (pw/handler
    {:menu :encounters}
    [:ui/csrf-token
     :ui/navbar
     {:ui/current-patient
      [:ui/patient-banner]}
     {:ui/current-encounter
      [:t_encounter/id
       :t_encounter/date_time
       :t_encounter/is_deleted
       :t_encounter/is_locked
       :t_encounter/lock_date_time
       :t_encounter/hospital_crn
       :t_encounter/notes
       {:t_encounter/encounter_template [:t_encounter_template/title
                                         {:t_encounter_template/project [:t_project/id
                                                                         :t_project/title]}]}
       {:t_encounter/completed_forms [:form/id
                                      :form/form_type
                                      :form/summary_result
                                      {:form/user [:t_user/id
                                                   :t_user/full_name
                                                   :t_user/initials]}]}
       {:t_encounter/available_form_types [:form_type/id
                                           :form_type/nm
                                           :form_type/title]}
       {:t_encounter/patient [:t_patient/patient_identifier
                              :t_patient/permissions]}]}]
    (fn [request {:ui/keys [csrf-token navbar current-patient current-encounter]}]
      (let [{:t_encounter/keys [id date_time is_deleted is_locked lock_date_time hospital_crn notes
                                encounter_template completed_forms available_form_types patient]} current-encounter
            {:t_encounter_template/keys [title project]} encounter_template
            project-title (:t_project/title project)
            {:t_patient/keys [patient_identifier permissions]} patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            can-edit-encounter? (and can-edit-patient? (not is_locked))]
        (web/ok
          (ui/render-file
            "templates/patient/base.html"
            {:navbar  navbar
             :banner  (:ui/patient-banner current-patient)
             :content (ui/render
                        [:div.grid.grid-cols-1.sm:grid-cols-6
                         ;; Left sidebar with encounter info and actions
                         [:div.col-span-1.p-2.space-y-2
                          ;; Back button
                          [:button.w-full.inline-flex.justify-center.py-2.px-4.border.border-gray-300.shadow-sm.text-sm.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
                           {:onclick "history.back()"}
                           "Back"]

                          ;; Encounter info panel
                          (when (and date_time encounter_template)
                            [:div.shadow.bg-gray-50
                             [:div.font-semibold.bg-gray-200.text-center.italic.text-gray-600.pt-2.pb-2
                              (ui/format-date-time date_time)]
                             [:div.text-sm.p-2.pt-4.text-gray-600.italic.text-center
                              project-title]
                             [:div.font-bold.text-lg.min-w-min.pt-0.text-center.pb-4
                              title]])

                          ;; Warning if deleted
                          (when is_deleted
                            [:div.mt-4.font-bold.text-center.bg-red-100.p-4.border.border-red-600.rounded
                             "Warning: this encounter has been deleted"])

                          ;; Edit button (if allowed)
                          (when (and (not is_deleted) (not is_locked) can-edit-patient?)
                            [:button.w-full.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-blue-600.hover:bg-blue-700
                             {:hx-get      (route/url-for :encounter/edit :path-params {:patient-identifier patient_identifier :encounter-id id})
                              :hx-target   "body"
                              :hx-push-url "true"}
                             "Edit"])

                          ;; Lock/unlock info and buttons
                          (when (or is_locked lock_date_time can-edit-patient?)
                            [:div.mt-2.italic.text-sm.text-center.bg-gray-100.p-2.border.border-gray-200.shadow.rounded
                             (if is_locked
                               [:div.grid.grid-cols-1.gap-2
                                "This encounter has been locked against editing"
                                (when (and (not is_deleted) can-edit-patient?)
                                  [:button.w-full.inline-flex.justify-center.py-1.px-2.border.border-gray-300.shadow-sm.text-xs.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
                                   {:hx-post     (route/url-for :encounter/lock :path-params {:patient-identifier patient_identifier :encounter-id id})
                                    :hx-vals     (str "{\"lock\":\"false\",\"__anti-forgery-token\":\"" csrf-token "\"}")
                                    :hx-push-url "false"
                                    :hx-target   "body"
                                    :hx-swap     "innerHTML"}
                                   "Unlock"])]
                               [:div.grid.grid-cols-1.gap-2
                                (when lock_date_time
                                  [:span "This encounter will lock at " [:br] (ui/format-date-time lock_date_time)])
                                (when can-edit-patient?
                                  [:button.w-full.inline-flex.justify-center.py-1.px-2.border.border-gray-300.shadow-sm.text-xs.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
                                   {:hx-post     (route/url-for :encounter/lock :path-params {:patient-identifier patient_identifier :encounter-id id})
                                    :hx-vals     (str "{\"lock\":\"true\",\"__anti-forgery-token\":\"" csrf-token "\"}")
                                    :hx-push-url "false"
                                    :hx-target   "body"
                                    :hx-swap     "innerHTML"}
                                   "Lock encounter now"])])])]

                         ;; Main content area
                         [:div.col-span-1.lg:col-span-5.pt-2
                          {:id "main-content"}
                          ;; Forms table
                          (ui/ui-table
                            (ui/ui-table-head
                              (ui/ui-table-row {}
                                               (ui/ui-table-heading {} "Form")
                                               (ui/ui-table-heading {} "Result")
                                               (ui/ui-table-heading {} "User")))
                            (ui/ui-table-body
                              ;; Completed forms
                              (for [{form-id :form/id :form/keys [form_type summary_result user]} completed_forms
                                    :let [{:form_type/keys [nm title]} form_type]]
                                (ui/ui-table-row
                                  {:class "cursor-pointer hover:bg-gray-200"}
                                  (ui/ui-table-cell
                                    [:a {:href (route/url-for :patient/form :path-params {:patient-identifier patient_identifier
                                                                                          :encounter-id       id
                                                                                          :form-type          nm
                                                                                          :form-id            form-id})}
                                     [:span.text-blue-500.underline title]])
                                  (ui/ui-table-cell {} summary_result)
                                  (ui/ui-table-cell {}
                                                    [:span.hidden.lg:block (:t_user/full_name user)]
                                                    [:span.block.lg:hidden {:title (:t_user/full_name user)} (:t_user/initials user)])))

                              ;; Available forms to add (if can edit)
                              (when can-edit-encounter?
                                (for [{:form_type/keys [id nm title]} available_form_types]
                                  (ui/ui-table-row
                                    {:class "italic cursor-pointer hover:bg-gray-200"}
                                    (ui/ui-table-cell {} [:span title])
                                    (ui/ui-table-cell {} [:span "Pending"])
                                    (ui/ui-table-cell {} ""))))))

                          ;; Notes panel
                          [:div.mt-4.bg-white.shadow.rounded-lg
                           [:div.px-4.py-5.sm:p-6
                            [:h3.text-lg.leading-6.font-medium.text-gray-900
                             (if can-edit-encounter?
                               [:a.cursor-pointer.text-blue-500.underline "Notes"]
                               "Notes")]
                            [:div.mt-2.text-sm.text-gray-500
                             {:id "notes-content"}
                             [:div.shadow-inner.p-4.text-sm
                              {:dangerouslySetInnerHTML {:__html (or notes "")}}]]]]]])}))))))

(def encounters-handler
  (pw/handler
    {:menu :encounters}
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/permissions]}]
    (fn [request {:ui/keys [csrf-token patient-page current-patient]}]
      (let [{:t_patient/keys [patient_identifier permissions]} current-patient
            can-edit? (permissions :PATIENT_EDIT)
            view (keyword (get-in request [:query-params :view] "notes"))
            rsdb (get-in request [:env :rsdb])
            encounters-params {:patient-identifier patient_identifier
                               :view               view
                               :with-project       true
                               :with-patient       false}
            encounters (rsdb/list-encounters rsdb encounters-params)
            headings# (headings encounters-params)
            response
            (ui/ui-table
              (ui/ui-table-head
                (for [{:keys [title]} headings#]
                  (ui/ui-table-heading {} title)))
              (ui/ui-table-body
                (for [encounter encounters
                      :let [{:t_encounter/keys [id] patient-identifier# :t_patient/patient_identifier} encounter]]
                  (ui/ui-table-row
                    {:class       "cursor-pointer hover:bg-gray-50"
                     :hx-get      (route/url-for :patient/encounter
                                                 :path-params {:patient-identifier (or patient-identifier# patient_identifier)
                                                               :encounter-id       id})
                     :hx-target   "body"
                     :hx-push-url "true"}
                    (for [{:keys [f f2] :or {f (constantly "")}} headings#]
                      (ui/ui-table-cell {} (cond
                                             f2 (f2 encounters-params encounter)
                                             f (f encounter)
                                             :else "")))))))]
        (web/ok
          (if (= (web/hx-target request) "list-encounters")
            (ui/render response)
            (ui/render-file
              "templates/patient/base.html"
              (-> patient-page
                  (assoc-in
                    [:menu :submenu :items]
                    [{:content (ui/render [:form {:hx-target   "#list-encounters"
                                                  :hx-trigger  "change"
                                                  :hx-get      (route/url-for :patient/encounters :path-params {:patient-identifier patient_identifier})
                                                  :hx-push-url "true"}
                                           (ui/ui-select-button {:name        "view"
                                                                 :selected-id view
                                                                 :options     [{:id :notes :text "Notes"}
                                                                               {:id :users :text "Users"}
                                                                               {:id :ninflamm :text "Neuroinflammatory"}
                                                                               {:id :mnd :text "Motor neurone disease"}]})])}
                     {:text        "Add encounter..."
                      :hidden      (not can-edit?)
                      :hx-get      (route/url-for :encounter/create :path-params {:patient-identifier patient_identifier})
                      :hx-target   "body"
                      :hx-push-url "true"}])
                  (assoc :content
                         (ui/render [:div {:id "list-encounters"} response]))))))))))



(def encounter-lock-handler
  (pw/handler
    {}
    [:ui/csrf-token
     {:ui/current-encounter
      [:t_encounter/id
       {:t_encounter/patient [:t_patient/patient_identifier
                              :t_patient/permissions]}]}]
    (fn [request {:ui/keys [current-encounter]}]
      (let [rsdb (get-in request [:env :rsdb])
            {:t_encounter/keys [id patient]} current-encounter
            {:t_patient/keys [patient_identifier permissions]} patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            {:keys [lock success-url] :or {success-url (route/url-for
                                                         :patient/encounter
                                                         :path-params {:patient-identifier patient_identifier
                                                                       :encounter-id       id})}} (:form-params request)]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          (str/blank? lock)
          (web/bad-request "Missing 'lock' parameter")

          :else
          (do
            (if (parse-boolean lock)
              (rsdb/lock-encounter! rsdb id)
              (rsdb/unlock-encounter! rsdb id))
            (web/redirect-see-other success-url)))))))

(def encounter-edit-handler
  "GET /patient/:patient-identifier/encounter/:encounter-id/edit - Edit an existing encounter"
  (pw/handler
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient [:t_patient/patient_identifier
                           :t_patient/permissions]}
     {:ui/authenticated-user [{:t_user/hospitals [:org.hl7.fhir.Organization/identifier
                                                   :org.hl7.fhir.Organization/name
                                                   :org.hl7.fhir.Organization/active
                                                   :org.hl7.fhir.Organization/address]}]}
     {:ui/current-project [:t_project/title]}
     {:ui/current-encounter [:t_encounter/id
                             {:t_encounter/encounter_template [:t_encounter_template/id
                                                               :t_encounter_template/title
                                                               {:t_encounter_template/project [:t_project/title]}]}
                             :t_encounter/date_time
                             :t_encounter/duration_minutes
                             :t_encounter/notes
                             :t_encounter/consultant_user_fk
                             {:t_encounter/consultant_user [:t_user/id
                                                            :t_user/full_name
                                                            :t_user/job_title]}
                             :t_encounter/hospital_fk
                             {:t_encounter/hospital [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id
                                                     :org.hl7.fhir.Organization/name
                                                     :org.hl7.fhir.Organization/active
                                                     :org.hl7.fhir.Organization/identifier
                                                     :org.hl7.fhir.Organization/address]}
                             :t_encounter/ward
                             {:t_encounter/users [:t_user/id
                                                  :t_user/full_name
                                                  :t_user/job_title]}
                             :t_encounter/is_locked]}]
    (fn [request {:ui/keys [csrf-token patient-page current-patient authenticated-user current-encounter]}]
      (let [{:t_patient/keys [patient_identifier permissions]} current-patient
            {:t_encounter/keys [id is_locked encounter_template consultant_user hospital users]} current-encounter
            can-edit-patient? (get permissions :PATIENT_EDIT)
            project-title (get-in encounter_template [:t_encounter_template/project :t_project/title])
            encounter-template-title (get-in encounter_template [:t_encounter_template/title])
            consultant-user (when consultant_user
                              {:user-id   (:t_user/id consultant_user)
                               :full-name (:t_user/full_name consultant_user)
                               :job-title (:t_user/job_title consultant_user)})
            encounter-users (mapv (fn [user]
                                    {:user-id   (:t_user/id user)
                                     :full-name (:t_user/full_name user)
                                     :job-title (:t_user/job_title user)})
                                  users)]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          is_locked
          (web/forbidden "This encounter is locked and cannot be edited")

          :else
          (web/ok
            (ui/render-file
              "templates/patient/base.html"
              (assoc patient-page
                :content
                (ui/render
                  (ui-edit-encounter
                    {:csrf-token               csrf-token
                     :can-edit                 can-edit-patient?
                     :encounter                current-encounter
                     :patient-identifier       patient_identifier
                     :project-title            project-title
                     :encounter-template-title encounter-template-title
                     :consultant-user          consultant-user
                     :hospital-org             hospital
                     :encounter-users          encounter-users
                     :common-hospitals         (:t_user/hospitals authenticated-user)}))))))))))

(def create-encounter-handler
  "GET /patient/:patient-identifier/encounter/create - Show form to create new encounter"
  (pw/handler
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient [:t_patient/patient_identifier
                           :t_patient/permissions
                           {:t_patient/episodes [:t_episode/project_fk
                                                 :t_episode/date_discharge]}]}
     {:ui/authenticated-user [{:t_user/active_projects [:t_project/id
                                                        :t_project/title]}
                             {:t_user/hospitals [:org.hl7.fhir.Organization/identifier
                                                 :org.hl7.fhir.Organization/name
                                                 :org.hl7.fhir.Organization/active
                                                 :org.hl7.fhir.Organization/address]}]}
     {:ui/current-project [:t_project/id]}]
    (fn [request {:ui/keys [csrf-token patient-page current-patient authenticated-user current-project]}]
      (let [{:t_patient/keys [patient_identifier permissions episodes]} current-patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            rsdb (get-in request [:env :rsdb])
            {:keys [suitable-projects my-projects selected-project-id encounter-templates]}
            (fetch-projects-and-templates
              {:rsdb               rsdb
               :patient-episodes   episodes
               :user-projects      (:t_user/active_projects authenticated-user)
               :current-project-id (:t_project/id current-project)})]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          :else
          (web/ok
            (ui/render-file
              "templates/patient/base.html"
              (assoc patient-page
                :content
                (ui/render
                  (ui-edit-encounter
                    {:csrf-token          csrf-token
                     :can-edit            can-edit-patient?
                     :patient-identifier  patient_identifier
                     :suitable-projects   suitable-projects
                     :my-projects         my-projects
                     :encounter-templates encounter-templates
                     :selected-project-id selected-project-id
                     :selected-filter     "suitable"
                     :common-hospitals    (:t_user/hospitals authenticated-user)}))))))))))

(def post-create-encounter-handler
  "POST /patient/:patient-identifier/encounter/create - Handle encounter creation (partial and full submissions)"
  (pw/handler
    [:ui/csrf-token
     {:ui/current-patient [:t_patient/patient_identifier
                           :t_patient/permissions
                           {:t_patient/episodes [:t_episode/project_fk
                                                 :t_episode/date_discharge]}]}
     {:ui/authenticated-user [{:t_user/active_projects [:t_project/id
                                                        :t_project/title]}
                             {:t_user/hospitals [:org.hl7.fhir.Organization/identifier
                                                 :org.hl7.fhir.Organization/name
                                                 :org.hl7.fhir.Organization/active
                                                 :org.hl7.fhir.Organization/address]}]}]
    (fn [request {:ui/keys [csrf-token current-patient authenticated-user]}]
      (let [{:t_patient/keys [patient_identifier permissions episodes]} current-patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            {:keys [project-filter project-id encounter-template-id date-time duration-minutes notes
                    consultant-user-fk hospital-fk ward encounter-users]} (:form-params request)
            rsdb (get-in request [:env :rsdb])
            is-partial-submission? (web/htmx-request? request)]

        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          ;; Partial submission: re-render form with updated selections
          is-partial-submission?
          (let [{:keys [suitable-projects my-projects selected-project-id encounter-templates]}
                (fetch-projects-and-templates
                  {:rsdb                rsdb
                   :patient-episodes    episodes
                   :user-projects       (:t_user/active_projects authenticated-user)
                   :selected-project-id (some-> project-id parse-long)
                   :selected-filter     project-filter})]
            (web/ok
              (ui/render
                (ui-edit-encounter
                  {:csrf-token          csrf-token
                   :can-edit            can-edit-patient?
                   :patient-identifier  patient_identifier
                   :suitable-projects   suitable-projects
                   :my-projects         my-projects
                   :encounter-templates encounter-templates
                   :selected-project-id selected-project-id
                   :selected-filter     project-filter
                   :common-hospitals    (:t_user/hospitals authenticated-user)
                   :encounter           {:t_encounter/date_time        (safe-parse-local-date-time date-time)
                                         :t_encounter/duration_minutes duration-minutes
                                         :t_encounter/notes            notes
                                         :t_encounter/ward             ward}}))))

          ;; Full submission: create the encounter
          :else
          (let [parsed-encounter-template-id (some-> encounter-template-id parse-long)
                parsed-date-time (safe-parse-local-date-time date-time)
                parsed-duration (some-> duration-minutes parse-long)
                parsed-consultant-fk (some-> consultant-user-fk parse-long)
                parsed-hospital-fk (when-not (str/blank? hospital-fk) hospital-fk)
                parsed-project-id (some-> project-id parse-long)
                parsed-user-ids (when encounter-users
                                  (try
                                    (->> (clojure.edn/read-string encounter-users)
                                         (map :user-id)
                                         (filter some?))
                                    (catch Exception _ nil)))]
            (cond
              (or (nil? parsed-encounter-template-id) (nil? parsed-date-time) (nil? parsed-project-id))
              (web/bad-request "Missing required fields: project, encounter template, and date-time")

              :else
              (try
                (let [new-encounter (rsdb/save-encounter! rsdb
                                                          {:t_patient/patient_identifier      patient_identifier
                                                           :t_encounter/encounter_template_fk parsed-encounter-template-id
                                                           :t_encounter/date_time             parsed-date-time
                                                           :t_encounter/notes                 notes
                                                           :t_encounter/duration_minutes      parsed-duration
                                                           :t_encounter/consultant_user_fk    parsed-consultant-fk
                                                           :t_encounter/hospital_fk           parsed-hospital-fk
                                                           :t_encounter/ward                  ward})
                      encounter-id (:t_encounter/id new-encounter)]
                  (when parsed-user-ids
                    (rsdb/set-encounter-users! rsdb encounter-id parsed-user-ids))
                  (log/info "Created encounter" {:encounter-id encounter-id :patient-identifier patient_identifier})
                  (web/redirect-see-other (route/url-for :patient/encounter
                                                         :path-params {:patient-identifier patient_identifier
                                                                       :encounter-id       encounter-id})))
                (catch Exception e
                  (log/error "Error creating encounter" {:patient-identifier patient_identifier :error (.getMessage e)})
                  (web/bad-request (str "Error creating encounter: " (.getMessage e))))))))))))

(def save-encounter-handler
  "POST /patient/:patient-identifier/encounter/:encounter-id - Update an existing encounter"
  (pw/handler
    [{:ui/current-encounter [:t_encounter/id
                             :t_encounter/is_locked
                             {:t_encounter/patient [:t_patient/patient_identifier
                                                    :t_patient/permissions]}]}]
    (fn [request {:ui/keys [current-encounter]}]
      (let [{{:t_patient/keys [patient_identifier permissions]} :t_encounter/patient
             encounter-id                                       :t_encounter/id
             is-locked                                          :t_encounter/is_locked} current-encounter
            can-edit-patient? (get permissions :PATIENT_EDIT)
            {:keys [encounter-template-id date-time duration-minutes notes consultant-user-fk hospital-fk ward encounter-users]} (:form-params request)
            rsdb (get-in request [:env :rsdb])
            parsed-encounter-template-id (some-> encounter-template-id parse-long)
            parsed-date-time (safe-parse-local-date-time date-time)
            parsed-duration (some-> duration-minutes parse-long)
            parsed-consultant-fk (some-> consultant-user-fk parse-long)
            parsed-hospital-fk (when-not (str/blank? hospital-fk) hospital-fk)
            _ (log/debug "Encounter users from form:" {:encounter-users encounter-users})
            parsed-user-ids (when encounter-users
                              (try
                                (->> (clojure.edn/read-string encounter-users)
                                     (map :user-id)
                                     (filter some?))
                                (catch Exception _ nil)))]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          is-locked
          (web/forbidden "This encounter is locked and cannot be edited")

          (or (nil? parsed-encounter-template-id) (nil? parsed-date-time))
          (web/bad-request "Missing required fields: encounter template and date-time")

          :else
          (try
            (rsdb/save-encounter! rsdb
                                  {:t_encounter/id                    encounter-id
                                   :t_encounter/encounter_template_fk parsed-encounter-template-id
                                   :t_encounter/date_time             parsed-date-time
                                   :t_encounter/notes                 notes
                                   :t_encounter/duration_minutes      parsed-duration
                                   :t_encounter/consultant_user_fk    parsed-consultant-fk
                                   :t_encounter/hospital_fk           parsed-hospital-fk
                                   :t_encounter/ward                  ward})
            (when parsed-user-ids
              (rsdb/set-encounter-users! rsdb encounter-id parsed-user-ids))
            (log/info "Updated encounter" {:encounter-id encounter-id :patient-identifier patient_identifier})
            (web/redirect-see-other (route/url-for :patient/encounter
                                                   :path-params {:patient-identifier patient_identifier
                                                                 :encounter-id       encounter-id}))
            (catch Exception e
              (log/error "Error updating encounter" {:encounter-id encounter-id :patient-identifier patient_identifier :error (.getMessage e)})
              (web/bad-request (str "Error updating encounter: " (.getMessage e))))))))))
