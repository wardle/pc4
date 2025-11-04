(ns pc4.workbench.controllers.project.araf
  "ARAF patient monitoring controller.
  Displays patients enrolled in ARAF programmes with categorized views
  based on their current outcome status."
  (:require
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.pathom-web.interface :as pw]
    [pc4.ui.interface :as ui]
    [pc4.web.interface :as web]
    [pc4.rsdb.interface :as rsdb])
  (:import (java.time LocalDateTime Period)))

(def project-programme-configs
  "Vector of maps defining project->programme mappings.
  Each map contains :project-id, :programme (keyword), and :title (menu title).
  TODO: Replace with a more robust configuration mechanism."
  [{:project-id 24
    :programme  :valproate-f
    :title      "Valproate (female) ARAF"}])

(def project-programmes
  "Lookup map of project-id to programme configuration."
  (into {} (map (juxt :project-id identity) project-programme-configs)))

(defn project-programme
  "Returns the ARAF programme keyword for the given project-id, or nil if not configured."
  [project-id]
  (:programme (get project-programmes project-id)))

(defn programme-menu-title
  "Returns the menu title for the given project-id, or nil if not configured."
  [project-id]
  (:title (get project-programmes project-id)))

(defn categorize-patient
  "Categorize a patient based on their ARAF outcome.
  Returns a keyword: :excluded-permanently, :excluded-temporarily, :on-hold,
  :completed, :due-to-expire, :outstanding, or :incomplete."
  [{:keys [excluded completed expiry on-hold]} opts]
  (let [now (:now opts (LocalDateTime/now))
        expiry-window (or (:expiry-window opts) (Period/ofMonths 3))
        expiry-threshold (when expiry-window (.plus now expiry-window))]
    (cond
      (= :permanent excluded) :excluded-permanently
      (= :temporary excluded) :excluded-temporarily
      on-hold :on-hold
      (and completed (not expiry)) :completed
      (and completed expiry (.isAfter expiry now))
      (if (and expiry-threshold (.isBefore expiry expiry-threshold))
        :due-to-expire
        :completed)
      ;; Not excluded, not on-hold, not completed = outstanding tasks
      (and (not excluded) (not on-hold) (not completed)) :outstanding
      :else :incomplete)))

(defn filter-patients-by-category
  "Filter patients to only those matching the specified category."
  [patients programme category opts]
  (if (= :all category)
    patients
    (filter #(= category (categorize-patient (get % programme) opts)) patients)))

(defn outstanding-tasks
  "Extract outstanding (incomplete) tasks from an outcome."
  [outcome]
  (->> (:tasks outcome)
       (filter (fn [[_task-id completed?]] (not completed?)))
       (map first)))

(defn task-label
  "Convert a task keyword to a display label."
  [task-kw]
  (case task-kw
    :s1 "Step 1: Evaluation"
    :s2 "Step 2: Treatment decision"
    :s3 "Step 3: Risks explained"
    :s4 "Step 4: Acknowledgement"
    (str task-kw)))

(defn patient->display-name
  "Extract display name from patient."
  [{:t_patient/keys [first_names last_name]}]
  (str last_name ", " first_names))

(defn patient->sort-name
  "Extract sort name (surname, firstname) from patient."
  [{:t_patient/keys [first_names last_name]}]
  (str (str/lower-case (or last_name "")) " " (str/lower-case (or first_names ""))))

(defn format-expiry-date
  "Format an expiry LocalDateTime for display."
  [expiry]
  (when expiry
    (str (.format expiry (java.time.format.DateTimeFormatter/ofPattern "dd MMM yyyy")))))

(defn category-label
  "Get display label for a category."
  [category]
  (case category
    :all "All patients"
    :outstanding "Outstanding tasks"
    :excluded-permanently "Excluded (permanent)"
    :excluded-temporarily "Excluded (temporary)"
    :on-hold "On hold"
    :completed "Completed"
    :due-to-expire "Due to expire"
    (name category)))

(defn category-select-button
  "Generate a select button for patient categories."
  [project-id selected-category]
  (ui/ui-select-button
    {:id          "category"
     :name        "category"
     :selected-id (name selected-category)
     :hx-get      (route/url-for :project/araf :path-params {:project-id project-id})
     :hx-target   "body"
     :hx-push-url "true"
     :hx-indicator "#araf-spinner"
     :options     [{:id "outstanding" :text (category-label :outstanding)}
                   {:id "all" :text (category-label :all)}
                   {:id "excluded-permanently" :text (category-label :excluded-permanently)}
                   {:id "excluded-temporarily" :text (category-label :excluded-temporarily)}
                   {:id "on-hold" :text (category-label :on-hold)}
                   {:id "completed" :text (category-label :completed)}
                   {:id "due-to-expire" :text (category-label :due-to-expire)}]}))

(defn patient->list-item
  "Convert a patient with outcome to a list item for display."
  [{:t_patient/keys [patient_identifier nhs_number date_birth] :as patient} programme]
  (let [outcome (get patient programme)
        tasks (outstanding-tasks outcome)
        expiry (:expiry outcome)
        category (categorize-patient outcome {})
        dob (cond
              (instance? LocalDateTime date_birth) (.toLocalDate date_birth)
              (instance? java.time.LocalDate date_birth) date_birth
              :else nil)]
    {:patient-id patient_identifier
     :name (patient->display-name patient)
     :sort-name (patient->sort-name patient)
     :nhs-number nhs_number
     :dob date_birth
     :dob-str (some-> dob ui/format-date)
     :category category
     :category-label (category-label category)
     :expiry expiry
     :expiry-str (format-expiry-date expiry)
     :outstanding-tasks (map (fn [task-kw]
                               {:id (name task-kw)
                                :label (task-label task-kw)})
                             tasks)}))

(defn outcome->summary
  "Transform an ARAF outcome into display data for the modal."
  [outcome]
  (let [category (categorize-patient outcome {})]
    {:category       category
     :category-label (category-label category)
     :excluded       (:excluded outcome)
     :completed      (:completed outcome)
     :expiry-str     (format-expiry-date (:expiry outcome))
     :on-hold-str    (format-expiry-date (:on-hold outcome))
     :tasks          (map (fn [[task-kw completed?]]
                            {:id        (name task-kw)
                             :label     (task-label task-kw)
                             :completed completed?})
                          (:tasks outcome))}))

(defn button-bar
  "Render button bar content for adding ARAF forms."
  [available]
  (when (seq available)
    (let [form-options (map (fn [{:keys [id title]}]
                              {:id (str id)
                               :text title})
                            available)]
      [:div.flex.flex-col.sm:flex-row.gap-3
       [:div.flex-1
        (ui/ui-select-button
          {:id "form-type"
           :name "form-type"
           :selected-id (str (:id (first available)))
           :options form-options})]
       (ui/ui-button
         {:type "button"
          :hx-post "#"
          :hx-include "[name='form-type']"
          :class "mt-2"}
         "Add Form")])))

(defn patient-outcome-modal
  [{:keys [banner patient-url outcome forms programme-title can-edit available]}]
  (ui/render
    (ui/ui-modal
      {:id      "araf-outcome-content"
       :hidden? false
       :title   programme-title
       :size    :full
       :left-content (when can-edit (button-bar available))
       :actions [{:id          :cancel
                  :title       "Close"
                  :role        :secondary
                  :hx-on:click "htmx.find('#araf-outcome-content').setAttribute('hidden', '')"}
                 {:id          :view-patient
                  :title       "View Patient Record »"
                  :role        :secondary
                  :hx-get      patient-url
                  :hx-target   "body"
                  :hx-push-url "true"}]}

      ;; Patient banner section
      [:div.mb-6
       {:dangerouslySetInnerHTML {:__html (ui/render-file "templates/patient/banner.html" {:banner banner})}}]

      ;; Outcome summary section
      (let [summary (outcome->summary outcome)]
        [:div.mb-6
         [:h4.text-md.font-semibold.mb-2 "Current Outcome"]
         [:div.bg-gray-50.p-4.rounded
          [:div.mb-3
           [:span.font-medium "Status: "]
           [:span.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full
            {:class (case (:category summary)
                      :completed "bg-green-100 text-green-800"
                      :due-to-expire "bg-yellow-100 text-yellow-800"
                      :on-hold "bg-blue-100 text-blue-800"
                      :excluded-permanently "bg-gray-100 text-gray-800"
                      :excluded-temporarily "bg-gray-100 text-gray-800"
                      :outstanding "bg-red-100 text-red-800"
                      "bg-gray-100 text-gray-800")}
            (:category-label summary)]]

          (when (:expiry-str summary)
            [:div.mb-2 [:span.font-medium "Expiry: "] (:expiry-str summary)])

          (when (:on-hold-str summary)
            [:div.mb-2 [:span.font-medium "On Hold Until: "] (:on-hold-str summary)])

          (when (:excluded summary)
            [:div.mb-2 [:span.font-medium "Excluded: "]
             (case (:excluded summary)
               :permanent "Permanent"
               :temporary "Temporary"
               (name (:excluded summary)))])

          [:div.mt-4
           [:span.font-medium "Tasks:"]
           [:ul.list-disc.list-inside.mt-2
            (for [{:keys [label completed]} (:tasks summary)]
              [:li {:key label}
               [:span {:class (if completed "text-green-600" "text-red-600")}
                (if completed "✓ " "✗ ")]
               label])]]]])

      ;; Forms list section
      [:div
       [:h4.text-md.font-semibold.mb-2 "Completed Forms"]
       (if (seq forms)
         [:div.overflow-hidden
          [:table.min-w-full.divide-y.divide-gray-200
           [:thead.bg-gray-50
            [:tr
             [:th.px-4.py-2.text-left.text-xs.font-medium.text-gray-500.uppercase "Date"]
             [:th.px-4.py-2.text-left.text-xs.font-medium.text-gray-500.uppercase "Form Type"]]]
           [:tbody.bg-white.divide-y.divide-gray-200
            (for [form forms]
              [:tr {:key (:t_form/id form)}
               [:td.px-4.py-2.text-sm (ui/format-date (:date-time form))]
               [:td.px-4.py-2.text-sm (name (:t_form/form_type form))]])]]]
         [:p.text-gray-500.text-sm "No forms recorded."])])))

(defn sort-patients
  "Sort patient items by the specified column and direction."
  [patients sort-col sort-dir]
  (let [sort-key (case sort-col
                   "name" :sort-name
                   "dob" :dob
                   "status" :category
                   "expiry" :expiry
                   :sort-name)
        comparator (if (= "desc" sort-dir) #(compare %2 %1) compare)]
    (sort-by sort-key comparator patients)))

(defn toggle-sort-dir
  "Toggle sort direction between asc and desc."
  [current-sort-by column-name current-sort-dir]
  (if (= current-sort-by column-name)
    (if (= "asc" current-sort-dir) "desc" "asc")
    "asc"))

(defn sort-column-headers
  "Generate sortable column header data."
  [project-id category current-sort-by current-sort-dir]
  (let [columns [{:id "name" :label "Patient"}
                 {:id "dob" :label "Date of Birth"}
                 {:id "status" :label "Status"}
                 {:id "expiry" :label "Expiry"}]]
    (map (fn [{:keys [id label]}]
           (let [new-dir (toggle-sort-dir current-sort-by id current-sort-dir)]
             {:label label
              :sortable true
              :active (= id current-sort-by)
              :direction (when (= id current-sort-by) current-sort-dir)
              :url (route/url-for :project/araf
                                  :path-params {:project-id project-id}
                                  :query-params {:category (name category)
                                                 :sort-by id
                                                 :sort-dir new-dir})}))
         columns)))

(def patient-outcome
  "Handler for patient ARAF outcome modal."
  (pw/handler
    [{:ui/current-patient [:t_patient/id :t_patient/patient_identifier :ui/patient-banner]}
     {:ui/current-project [:t_project/id :t_project/permissions]}]
    (fn [request {:ui/keys [current-patient current-project] :as result}]
      (let [project-id (:t_project/id current-project)
            patient-pk (or (:t_patient/id current-patient) (throw (ex-info "no patient id for current patient" result)))
            patient-identifier (:t_patient/patient_identifier current-patient)
            rsdb (get-in request [:env :rsdb])
            programme (project-programme project-id)
            programme-title (programme-menu-title project-id)
            can-edit (:PATIENT_EDIT (:t_project/permissions current-project))
            {:keys [outcome forms available]} (rsdb/araf-outcome-with-forms rsdb programme patient-pk)
            sorted-forms (sort-by :date-time #(compare %2 %1) forms)]

        (web/ok
          (patient-outcome-modal
            {:banner          (:ui/patient-banner current-patient)
             :patient-url     (route/url-for :patient/home :path-params {:patient-identifier patient-identifier})
             :outcome         outcome
             :forms           sorted-forms
             :programme-title programme-title
             :can-edit        can-edit
             :available       available}))))))

(def araf-patients
  "ARAF patient monitoring controller."
  (pw/handler
    (fn [request]
      [{:ui/current-project
        [:t_project/id
         (list :ui/project-menu {:selected :araf})]}
       :ui/navbar])
    (fn [request {:ui/keys [navbar current-project]}]
      (let [project-id (:t_project/id current-project)
            rsdb (get-in request [:env :rsdb])
            programme (project-programme project-id)
            programme-title (programme-menu-title project-id)
            category (keyword (get-in request [:params :category] "outstanding"))
            sort-by (get-in request [:params :sort-by] "name")
            sort-dir (get-in request [:params :sort-dir] "asc")
            patients (rsdb/araf-programme-outcome rsdb programme project-id)
            filtered-patients (filter-patients-by-category patients programme category {})
            patient-items (map #(patient->list-item % programme) filtered-patients)
            sorted-patients (sort-patients patient-items sort-by sort-dir)]

        (web/ok
          (ui/render-file
            "templates/project/araf-patients.html"
            {:navbar navbar
             :menu (assoc (:ui/project-menu current-project)
                     :submenu {:items [{:content (ui/render (category-select-button project-id category))}]})
             :title (str programme-title ": " (category-label category))
             :category category
             :project-id project-id
             :sort-headers (sort-column-headers project-id category sort-by sort-dir)
             :patient-count (count filtered-patients)
             :patients sorted-patients}))))))

(comment
  ;; Test categorization
  (require '[pc4.config.interface :as config]
           '[integrant.core :as ig])
  (def system (ig/init (config/config :dev)))
  (def rsdb (:pc4.rsdb.interface/conn system))

  (let [patients (rsdb/araf-programme-outcome rsdb :valproate-f 24)]
    (group-by #(categorize-patient (:valproate-f %) {}) patients)))