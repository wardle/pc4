(ns pc4.workbench.controllers.patient.encounters
  (:require
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.nhs-number.interface :as nnn]
    [pc4.pathom-web.interface :as pw]
    [pc4.rsdb.interface :as rsdb]
    [pc4.ui-core.interface :as ui]
    [pc4.web.interface :as web])
  (:import [java.time LocalDate]))

(defn safe-parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s)))

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
            can-edit? (get-in permissions [:PATIENT_EDIT])
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
                    {:class "cursor-pointer hover:bg-gray-50"
                     :hx-get (route/url-for :patient/encounter 
                                            :path-params {:patient-identifier (or patient-identifier# patient_identifier)
                                                          :encounter-id id})
                     :hx-target "body"
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
              (assoc patient-page
                :content
                (ui/render [:div {:id "list-encounters"} response])))))))))

(def encounter-lock-handler
  (pw/handler
    {}
    [:ui/csrf-token
     {:ui/current-encounter
      [:t_encounter/id
       {:t_encounter/patient [:t_patient/patient_identifier
                              :t_patient/permissions]}]}]
    (fn [request {:ui/keys [current-encounter]}]
      (let [{:t_encounter/keys [id patient]} current-encounter
            {:t_patient/keys [patient_identifier permissions]} patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            {:keys [lock success-url] :or {success-url (route/url-for
                                                         :patient/encounter
                                                         :path-params {:patient-identifier patient_identifier
                                                                       :encounter-id       id})}} (:form-params request)
            should-lock? (= "true" lock)
            rsdb (get-in request [:env :rsdb])]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          (nil? lock)
          (web/bad-request "Missing 'lock' parameter")

          :else
          (do
            (if should-lock?
              (rsdb/lock-encounter! rsdb id)
              (rsdb/unlock-encounter! rsdb id))
            (web/redirect-see-other success-url)))))))

(def add-encounter-handler
  (pw/handler
    {:menu :encounters}
    [:ui/csrf-token
     :ui/navbar
     {:ui/current-patient
      [:ui/patient-banner
       :t_patient/permissions]}
     {:ui/current-project
      [:t_project/id
       :t_project/title]}]
    (fn [request {:ui/keys [csrf-token navbar current-patient current-project]}]
      (let [{:t_patient/keys [permissions]} current-patient
            can-edit-patient? (get permissions :PATIENT_EDIT)]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")
          
          :else
          (web/ok
            (ui/render-file
              "templates/patient/base.html"
              {:navbar  navbar
               :banner  (:ui/patient-banner current-patient)
               :content (ui/render
                          [:div
                           [:h1 "Add New Encounter"]
                           [:p "Project: " (:t_project/title current-project)]
                           [:p "Add encounter form will be implemented here"]])})))))))

