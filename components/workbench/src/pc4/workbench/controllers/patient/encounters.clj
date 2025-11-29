(ns pc4.workbench.controllers.patient.encounters
  (:require
    [clojure.data.json :as json]
    [clojure.edn :as edn]
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
  (:import [java.time LocalDate LocalDateTime]))

(defn safe-parse-long [s]
  (when-not (str/blank? s) (parse-long s)))

(defn safe-parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s)))

(defn safe-parse-local-date-time [s]
  (when-not (str/blank? s)
    (try
      (LocalDateTime/parse s)
      (catch Exception _ nil))))


(defn ui-modal-create-encounter
  "Create encounter modal with project and template selection.
  Returns the complete modal including actions that are dynamically enabled/disabled
  based on whether a valid template is selected."
  [{:keys [csrf-token can-edit patient-identifier rsdb patient-episodes user-projects
           selected-project-id selected-filter selected-template-id hidden?]
    :or   {selected-filter "suitable" hidden? true}}]
  (let [url (route/url-for :encounter/create :path-params {:patient-identifier patient-identifier})
        patient-project-ids (set (map :t_episode/project_fk (remove :t_episode/date_discharge patient-episodes)))
        suitable-projects (filter #(patient-project-ids (:t_project/id %)) user-projects)
        projects (if (= selected-filter "suitable") suitable-projects user-projects)
        project-ids (set (map :t_project/id projects))
        project-id (or (when (and selected-project-id (project-ids selected-project-id)) selected-project-id)
                       (:t_project/id (first projects)))
        project (first (filter #(= project-id (:t_project/id %)) projects))
        project-title (:t_project/title project)
        templates (when project-id
                    (->> (rsdb/project->encounter-templates rsdb project-id)
                         (remove :t_encounter_template/is_deleted)
                         (sort-by :t_encounter_template/title)))
        has-templates? (seq templates)
        can-create? (and can-edit has-templates?)
        ;; Encode template options as EDN vectors: [template-id template-title project-title]
        template-options (map (fn [{:t_encounter_template/keys [id title]}]
                                {:id   (pr-str [id title project-title])
                                 :text title})
                              templates)]
    (ui/ui-modal
      {:id      :create-encounter
       :hidden? hidden?
       :title   "Create encounter"
       :size    :xl
       :actions [{:id         :create
                  :title      "Create encounter »"
                  :role       :primary
                  :disabled?  (not can-create?)
                  :hx-post    url
                  :hx-include "#create-encounter-form"
                  :hx-target  "#create-encounter"
                  :hx-swap    "outerHTML"}
                 {:id          :cancel
                  :title       "Cancel"
                  :hx-on:click "htmx.addClass(htmx.find('#create-encounter'), 'hidden');"}]}
      [:form {:id "create-encounter-form"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:input {:type "hidden" :name "patient-identifier" :value patient-identifier}]

       (ui/ui-simple-form
         (ui/ui-simple-form-item
           {:label "Project"}
           [:div.flex.flex-col.md:flex-row.gap-4
            [:select.form-select.w-full.md:w-auto
             {:name       "project-filter"
              :hx-post    url
              :hx-target  "#create-encounter"
              :hx-swap    "outerHTML"
              :hx-include "#create-encounter-form"
              :hx-vals    "{\"hidden\":\"false\"}"}
             (when (seq suitable-projects)
               [:option {:value "suitable" :selected (= selected-filter "suitable")} "Suitable projects"])
             (when (seq user-projects)
               [:option {:value "all" :selected (= selected-filter "all")} "My projects"])]
            [:select.form-select.flex-1
             {:name       "project-id"
              :required   true
              :hx-post    url
              :hx-target  "#create-encounter"
              :hx-swap    "outerHTML"
              :hx-include "#create-encounter-form"
              :hx-vals    "{\"hidden\":\"false\"}"}
             (for [{:t_project/keys [id title]} (sort-by :t_project/title projects)]
               [:option {:value id :selected (= id project-id)} title])]])

         ;; Encounter template selection - value is EDN [template-id template-title project-title]
         (if has-templates?
           (ui/ui-simple-form-item
             {:label "Encounter Type"}
             (ui/ui-select-button {:id       "encounter-template"
                                   :name     "encounter-template"
                                   :required true
                                   :disabled false
                                   :options  template-options}))
           (ui/ui-simple-form-item
             {:label "Encounter Type"}
             (ui/box-error-message {:title   "Not supported"
                                    :message "This project does not support creating encounters."}))))])))





(defn encounter-sidebar
  [{:keys [csrf-token encounter-id patient-identifier date-time
           title subtitle users is-deleted is-locked lock-date-time
           can-edit? can-unlock?]}]
  [:div.col-span-1.p-2.space-y-2
   [:button.w-full.inline-flex.justify-center.py-2.px-4.border.border-gray-300.shadow-sm.text-sm.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
    {:onclick "history.back()"}
    "Back"]

   (when (and date-time title)
     [:div.shadow.bg-gray-50
      [:div.font-semibold.bg-gray-200.text-center.italic.text-gray-600.pt-2.pb-2
       (ui/format-date-time date-time)]
      [:div.text-sm.p-2.pt-4.text-gray-600.italic.text-center
       subtitle]
      [:div.font-bold.text-lg.min-w-min.pt-0.text-center.pb-4
       title]])

   (when (seq users)
     [:div.shadow.bg-gray-50.p-2
      [:div.text-xs.font-semibold.text-gray-500.uppercase.mb-1 "Users"]
      [:ul.text-sm.text-gray-700
       (for [{:t_user/keys [id full_name initials]} (sort-by (juxt :t_user/last_name :t_user/first_names) users)]
         [:li {:key id}
          [:span.hidden.sm:inline full_name]
          [:span.sm:hidden {:title full_name} initials]])]])

   (when is-deleted
     [:div.mt-4.font-bold.text-center.bg-red-100.p-4.border.border-red-600.rounded
      "Warning: this encounter has been deleted"])

   (when (and (not is-deleted) (not is-locked) can-edit?)
     [:button.w-full.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-blue-600.hover:bg-blue-700
      {:onclick "htmx.removeClass(htmx.find('#edit-encounter'), 'hidden');"}
      "Edit"])

   (when (or is-locked lock-date-time can-edit?)
     [:div.mt-2.italic.text-sm.text-center.bg-gray-100.p-2.border.border-gray-200.shadow.rounded
      (if is-locked
        [:div.grid.grid-cols-1.gap-2
         "This encounter has been locked against editing"
         (when (and (not is-deleted) can-unlock?)
           [:button.w-full.inline-flex.justify-center.py-1.px-2.border.border-gray-300.shadow-sm.text-xs.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
            {:hx-post     (route/url-for :encounter/lock :path-params {:patient-identifier patient-identifier :encounter-id encounter-id})
             :hx-vals     (str "{\"lock\":\"false\",\"__anti-forgery-token\":\"" csrf-token "\"}")
             :hx-push-url "false"
             :hx-target   "body"
             :hx-swap     "innerHTML"}
            "Unlock"])]
        [:div.grid.grid-cols-1.gap-2
         (when lock-date-time
           [:span "This encounter will lock at " [:br] (ui/format-date-time lock-date-time)])
         (when can-unlock?
           [:button.w-full.inline-flex.justify-center.py-1.px-2.border.border-gray-300.shadow-sm.text-xs.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
            {:hx-post     (route/url-for :encounter/lock :path-params {:patient-identifier patient-identifier :encounter-id encounter-id})
             :hx-vals     (str "{\"lock\":\"true\",\"__anti-forgery-token\":\"" csrf-token "\"}")
             :hx-push-url "false"
             :hx-target   "body"
             :hx-swap     "innerHTML"}
            "Lock encounter now"])])])])

(def encounter-handler
  "Inspect an encounter, displaying core data and forms."
  (pw/handler
    [:ui/csrf-token
     :ui/navbar
     {:ui/current-patient [:ui/patient-banner]}
     {:ui/current-encounter
      [:t_encounter/id
       :t_encounter/date_time
       :t_encounter/is_deleted
       :t_encounter/is_locked
       :t_encounter/lock_date_time
       :t_encounter/hospital_crn
       :t_encounter/notes
       {:t_encounter/encounter_template
        [:t_encounter_template/title {:t_encounter_template/project [:t_project/id :t_project/title]}]}
       {:t_encounter/users [:t_user/id :t_user/first_names :t_user/last_name :t_user/full_name :t_user/initials]}
       {:t_encounter/completed_forms
        [:form/id :form/form_type
         :form/summary_result
         {:form/user [:t_user/id :t_user/full_name :t_user/initials]}]}
       {:t_encounter/available_form_types
        [:form_type/id :form_type/nm :form_type/title]}
       {:t_encounter/patient
        [:t_patient/patient_identifier :t_patient/permissions]}]}]
    (fn [request {:ui/keys [csrf-token navbar current-patient current-encounter]}]
      (let [{:t_encounter/keys [id date_time is_deleted is_locked lock_date_time hospital_crn notes
                                encounter_template users completed_forms available_form_types patient]} current-encounter
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
                        [:<>
                         ;; Lazily load the edit-encounter modal (renders hidden, shown by Edit button)
                         (when can-edit-encounter?
                           [:div {:hx-get     (route/url-for :encounter/edit :path-params {:patient-identifier patient_identifier
                                                                                           :encounter-id       id})
                                  :hx-trigger "load"
                                  :hx-swap    "outerHTML"}])
                         [:div.grid.grid-cols-1.sm:grid-cols-6
                          (encounter-sidebar {:patient-identifier patient_identifier
                                              :encounter-id       id
                                              :csrf-token         csrf-token
                                              :date-time          date_time
                                              :is-locked          is_locked
                                              :lock-date-time     lock_date_time
                                              :title              title
                                              :subtitle           project-title
                                              :users              users
                                              :can-edit?          can-edit-encounter?
                                              :can-unlock?        can-edit-patient?})
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
                               {:dangerouslySetInnerHTML {:__html (or notes "")}}]]]]]]])}))))))


(def encounters-handler
  "Patient page showing a list of encounters with user-chosen headings."
  (pw/handler
    {:menu :encounters}
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/permissions :t_patient/episodes]}]
    (fn [request {:ui/keys [csrf-token patient-page current-patient]}]
      (let [rsdb (get-in request [:env :rsdb])
            {:t_patient/keys [patient_identifier episodes permissions]} current-patient
            can-edit? (permissions :PATIENT_EDIT)
            hx-vals (json/write-str {:patient-identifier   patient_identifier
                                     :__anti-forgery-token csrf-token})]
        (web/ok
          (ui/render-file
            "templates/patient/base.html"
            (-> patient-page
                (assoc-in
                  [:menu :submenu :items]
                  [{:content (ui/render [:form {:hx-target  "#list-encounters"
                                                :hx-trigger "change"
                                                :hx-vals    hx-vals
                                                :hx-post    (route/url-for :ui/list-encounters)}
                                         (ui/ui-select-button {:name    "view"
                                                               :options [{:id :notes :text "Notes"}
                                                                         {:id :users :text "Users"}
                                                                         {:id :ninflamm :text "Neuroinflammatory"}
                                                                         {:id :mnd :text "Motor neurone disease"}]})])}
                   {:text    "Add encounter..."
                    :hidden  (not can-edit?)
                    :onClick "htmx.removeClass(htmx.find(\"#create-encounter\"), \"hidden\");"}])
                (assoc :content
                       (ui/render
                         [:<>
                          ;; Lazily load the create-encounter modal (renders hidden, shown by button click)
                          [:div {:hx-post    (route/url-for :encounter/create)
                                 :hx-trigger "load"
                                 :hx-swap    "outerHTML"
                                 :hx-vals    hx-vals}]
                          [:div {:id "list-encounters"}
                           [:div {:hx-trigger "load"
                                  :hx-vals    hx-vals
                                  :hx-post    (route/url-for :ui/list-encounters)
                                  :hx-target  "#list-encounters"}
                            (ui/ui-spinner {})]]])))))))))



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
            {:keys [lock success-url]} (:form-params request)]
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
            (web/redirect-see-other
              (or success-url
                  (route/url-for :patient/encounter
                                 :path-params {:patient-identifier patient_identifier :encounter-id id})))))))))

(defn parse-encounter-form-params
  "Parse encounter form parameters. Parses EDN structures and type conversions."
  [form-params]
  (-> form-params
      (update :consultant-user #(some-> % edn/read-string))
      (update :encounter-users #(some-> % edn/read-string))
      (update :hospital #(some-> % edn/read-string))
      (update :encounter-template-id safe-parse-long)
      (update :date-time safe-parse-local-date-time)
      (update :duration-minutes safe-parse-long)
      (update :project-id safe-parse-long)))

(defn- save-encounter-data!
  "Saves encounter data for either a new or existing encounter.
  Returns the encounter ID."
  [rsdb {:keys [patient-identifier encounter-id encounter-template-id date-time duration-minutes
                consultant-user hospital encounter-users ward notes]}]
  (let [{saved-encounter-id :t_encounter/id}
        (rsdb/save-encounter!
          rsdb
          (cond-> {:t_patient/patient_identifier      patient-identifier
                   :t_encounter/encounter_template_fk encounter-template-id
                   :t_encounter/date_time             date-time
                   :t_encounter/notes                 notes
                   :t_encounter/duration_minutes      duration-minutes
                   :t_encounter/consultant_user_fk    (:user-id consultant-user)
                   :t_encounter/hospital_fk           (-> hospital :org.hl7.fhir.Organization/identifier first :org.hl7.fhir.Identifier/value)
                   :t_encounter/ward                  ward}
            encounter-id (assoc :t_encounter/id encounter-id)))]
    (when-let [user-ids (set (map :user-id encounter-users))]
      (rsdb/set-encounter-users! rsdb saved-encounter-id user-ids))
    saved-encounter-id))

(defn format-user
  "Transform database user entity to display format for select components."
  [{:t_user/keys [id full_name job_title]}]
  {:user-id   id
   :full-name full_name
   :job-title job_title})

(defn ui-modal-edit-encounter
  "Modal dialog for entering or editing encounter details."
  [{:keys [mode hidden? csrf-token can-edit save-url cancel-action
           encounter encounter-template-id project-title encounter-template-title
           consultant-user hospital-org encounter-users common-hospitals]}]
  (let [new? (= mode :create)
        modal-id (if new? :create-encounter :edit-encounter)
        form-id (if new? "new-encounter-form" "edit-encounter-form")
        {:t_encounter/keys [id date_time duration_minutes notes ward]} encounter
        disabled (not can-edit)]
    (ui/ui-modal
      {:id      modal-id
       :hidden? hidden?
       :title   (str (if new? "Create" "Edit") " encounter: " encounter-template-title)
       :size    :xl
       :actions [{:id         :save
                  :title      (if new? "Create" "Save")
                  :role       :primary
                  :hx-post    save-url
                  :hx-include (str "#" form-id)
                  :hx-target  "body"}
                 (merge {:id :cancel :title "Cancel"} cancel-action)]}
      (ui/ui-rich-text-script)
      [:form {:id form-id}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       (when id
         [:input {:type "hidden" :name "encounter-id" :value id}])
       (when (and new? encounter-template-id)
         [:input {:type "hidden" :name "encounter-template-id" :value encounter-template-id}])

       (ui/ui-simple-form
         (ui/ui-simple-form-item
           {:label "Project"}
           [:div.text-gray-900 project-title])

         (ui/ui-simple-form-item
           {:label "Encounter Type"}
           [:div.text-gray-900 encounter-template-title])

         (ui/ui-simple-form-item
           {:label "Date and Time"}
           (ui/ui-local-date-time {:id "date-time" :name "date-time" :required true :disabled disabled}
                                  (or date_time (LocalDateTime/now))))

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
             {:name             "consultant-user"
              :label            "Consultant / responsible person"
              :selected         consultant-user
              :disabled         disabled
              :only-responsible true}))

         (ui/ui-simple-form-item
           {:label "Hospital"}
           (ods-ui/ui-select-org
             {:name        "hospital"
              :label       "Hospital"
              :selected    hospital-org
              :disabled    disabled
              :active      true
              :roles       ["RO148" "RO150" "RO176" "RO198"]
              :fields      #{:name :address}
              :common-orgs (or common-hospitals [])}))

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
           (ui/ui-rich-text {:id "notes" :name "notes" :rows 10 :disabled disabled} notes)))])))

(def create-encounter-handler
  "Handler for the create encounter modal.
  - On initial load or filter/project change: returns the first step modal form
  - On form submission (triggered by 'create' button): returns the second step modal form."
  (pw/handler
    [:ui/csrf-token
     {:ui/current-patient [:t_patient/patient_identifier
                           :t_patient/permissions
                           {:t_patient/episodes [:t_episode/project_fk :t_episode/date_discharge]}]}
     {:ui/authenticated-user [{:t_user/active_projects [:t_project/id :t_project/title]}
                              {:t_user/hospitals [:org.hl7.fhir.Organization/identifier
                                                  :org.hl7.fhir.Organization/name
                                                  :org.hl7.fhir.Organization/active
                                                  :org.hl7.fhir.Organization/address]}]}
     {:ui/current-project [:t_project/id]}]
    (fn [{:keys [form-params] :as request} {:ui/keys [csrf-token current-patient authenticated-user current-project]}]
      (let [{:t_patient/keys [patient_identifier permissions episodes]} current-patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            rsdb (get-in request [:env :rsdb])
            trigger (web/hx-trigger request)
            submitting? (= "create" trigger)
            ;; Modal is hidden by default; selects pass hidden=false to keep it visible on refresh
            hidden? (not= "false" (:hidden form-params))]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          ;; render page 1 of the modal choosing project/encounter template
          (not submitting?)
          (web/ok
            (ui/render
              (ui-modal-create-encounter
                {:csrf-token          csrf-token
                 :can-edit            can-edit-patient?
                 :patient-identifier  patient_identifier
                 :rsdb                rsdb
                 :patient-episodes    episodes
                 :user-projects       (:t_user/active_projects authenticated-user)
                 :selected-project-id (or (some-> (:project-id form-params) parse-long)
                                          (:t_project/id current-project))
                 :selected-filter     (get form-params :project-filter "suitable")
                 :hidden?             hidden?})))

          ;; render step 2: enter encounter details
          :else
          (let [[encounter-template-id template-title project-title] (edn/read-string (:encounter-template form-params))
                create-url (route/url-for :encounter/create :path-params {:patient-identifier patient_identifier})
                save-url (route/url-for :encounter/save :path-params {:patient-identifier patient_identifier})]
            (web/ok
              (ui/render
                (ui-modal-edit-encounter
                  {:mode                     :create
                   :hidden?                  false
                   :csrf-token               csrf-token
                   :can-edit                 can-edit-patient?
                   :save-url                 save-url
                   :cancel-action            {:hx-post   create-url
                                              :hx-vals   (str "{\"__anti-forgery-token\":\"" csrf-token "\"}")
                                              :hx-target "#create-encounter"
                                              :hx-swap   "outerHTML"}
                   :encounter-template-id    encounter-template-id
                   :project-title            project-title
                   :encounter-template-title template-title
                   :common-hospitals         (:t_user/hospitals authenticated-user)})))))))))


(def edit-encounter-handler
  "GET /patient/:patient-identifier/encounter/:encounter-id/edit - Returns edit modal for an existing encounter"
  (pw/handler
    [:ui/csrf-token
     {:ui/current-patient [:t_patient/patient_identifier
                           :t_patient/permissions]}
     {:ui/authenticated-user [{:t_user/hospitals [:org.hl7.fhir.Organization/identifier
                                                  :org.hl7.fhir.Organization/name
                                                  :org.hl7.fhir.Organization/active
                                                  :org.hl7.fhir.Organization/address]}]}
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
    (fn [request {:ui/keys [csrf-token current-patient authenticated-user current-encounter]}]
      (let [{:t_patient/keys [patient_identifier permissions]} current-patient
            {:t_encounter/keys [id is_locked encounter_template consultant_user hospital users
                                date_time duration_minutes notes ward]} current-encounter
            can-edit-patient? (get permissions :PATIENT_EDIT)
            project-title (get-in encounter_template [:t_encounter_template/project :t_project/title])
            encounter-template-title (get-in encounter_template [:t_encounter_template/title])
            edit-url (route/url-for :encounter/edit :path-params {:patient-identifier patient_identifier
                                                                  :encounter-id       id})
            save-url (route/url-for :encounter/save :path-params {:patient-identifier patient_identifier})]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          is_locked
          (web/forbidden "This encounter is locked and cannot be edited")

          :else
          (web/ok
            (ui/render
              (ui-modal-edit-encounter
                {:mode                     :edit
                 :hidden?                  true
                 :csrf-token               csrf-token
                 :can-edit                 can-edit-patient?
                 :save-url                 save-url
                 :cancel-action            {:hx-get    edit-url
                                            :hx-target "#edit-encounter"
                                            :hx-swap   "outerHTML"}
                 :encounter                current-encounter
                 :project-title            project-title
                 :encounter-template-title encounter-template-title
                 :consultant-user          (some-> consultant_user format-user)
                 :hospital-org             hospital
                 :encounter-users          (mapv format-user users)
                 :common-hospitals         (:t_user/hospitals authenticated-user)}))))))))

(def save-encounter-handler
  "POST handler for saving encounters - handles both create and update.
  - If encounter-id is present: updates existing encounter
  - If encounter-template-id is present without encounter-id: creates new encounter"
  (pw/handler
    [:ui/csrf-token
     {:ui/current-patient [:t_patient/patient_identifier
                           :t_patient/permissions]}]
    (fn [{:keys [form-params] :as request} {:ui/keys [current-patient]}]
      (let [{:t_patient/keys [patient_identifier permissions]} current-patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            rsdb (get-in request [:env :rsdb])
            parsed (parse-encounter-form-params form-params)
            encounter-id (some-> (:encounter-id form-params) parse-long)
            encounter-template-id (:encounter-template-id parsed)]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          ;; For updates, check the encounter isn't locked
          (and encounter-id
               (:t_encounter/is_locked (rsdb/encounter-by-id rsdb encounter-id)))
          (web/forbidden "This encounter is locked and cannot be edited")

          ;; Must have either encounter-id (update) or encounter-template-id (create)
          (and (nil? encounter-id) (nil? encounter-template-id))
          (web/bad-request "Missing encounter-id or encounter-template-id")

          :else
          (let [saved-encounter-id
                (save-encounter-data!
                  rsdb
                  {:patient-identifier    patient_identifier
                   :encounter-id          encounter-id
                   :encounter-template-id encounter-template-id
                   :date-time             (:date-time parsed)
                   :duration-minutes      (:duration-minutes parsed)
                   :consultant-user       (:consultant-user parsed)
                   :hospital              (:hospital parsed)
                   :encounter-users       (:encounter-users parsed)
                   :ward                  (:ward parsed)
                   :notes                 (:notes parsed)})]
            (web/redirect-see-other
              (route/url-for :patient/encounter
                             :path-params {:patient-identifier patient_identifier
                                           :encounter-id       saved-encounter-id}))))))))

