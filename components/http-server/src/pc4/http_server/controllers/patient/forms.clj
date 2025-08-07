(ns pc4.http-server.controllers.patient.forms
  "pc4 forms implementation.

  Each 'form' is a multi-arity function:
  - 'data'   : 0-arity returning data requirements for the form (EQL)
  - 'render' : 2-arity fn returning rendered form as Clojure data (hiccup/rum).

  This means the data query and rendering are co-located."
  (:require
    [edn-query-language.core :as eql]
    [pc4.log.interface :as log]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.ui :as ui]
    [pc4.http-server.web :as web])
  (:import (java.time LocalDateTime)))

(defn ui-form-user
  ([]
   [{:form/user [:t_user/id :t_user/full_name :t_user/job_title]}])
  ([{:form/keys [user]}]
   (let [selected-user (when user
                         {:user-id   (:t_user/id user) :full-name (:t_user/full_name user)
                          :job-title (or (:t_user/job_title user) "")})]
     (pc4.http-server.controllers.select-user/ui-select-user {:name "user-id" :selected selected-user}))))

(def edss-scores
  "EDSS score options with descriptions"
  [{:id "SCORE0_0" :score "0.0" :description "Normal neurological exam, no disability in any FS"}
   {:id "SCORE1_0" :score "1.0" :description "No disability, minimal signs in one FS"}
   {:id "SCORE1_5" :score "1.5" :description "No disability, minimal signs in more than one FS"}
   {:id "SCORE2_0" :score "2.0" :description "Minimal disability in one FS"}
   {:id "SCORE2_5" :score "2.5" :description "Mild disability in one FS or minimal disability in two FS"}
   {:id "SCORE3_0" :score "3.0" :description "Moderate disability in one FS, or mild disability in three or four FS. No impairment to walking"}
   {:id "SCORE3_5" :score "3.5" :description "Moderate disability in one FS and more than minimal disability in several others. No impairment to walking"}
   {:id "SCORE_LESS_THAN_4" :score "<4.0" :description "No impairment to walking but neurological examination not performed"}
   {:id "SCORE4_0" :score "4.0" :description "Significant disability but self-sufficient and up and about some 12 hours a day. Able to walk without aid or rest for 500m"}
   {:id "SCORE4_5" :score "4.5" :description "Significant disability but up and about much of the day, able to work a full day, may otherwise have some limitation of full activity or require minimal assistance. Able to walk without aid or rest for 300m"}
   {:id "SCORE5_0" :score "5.0" :description "Disability severe enough to impair full daily activities and ability to work a full day without special provisions. Able to walk without aid or rest for 200m"}
   {:id "SCORE5_5" :score "5.5" :description "Disability severe enough to preclude full daily activities. Able to walk without aid or rest for 100m"}
   {:id "SCORE6_0" :score "6.0" :description " Requires a walking aid – cane, crutch, etc. – to walk about 100m with or without resting"}
   {:id "SCORE6_5" :score "6.5" :description "Requires two walking aids – pair of canes, crutches, etc. – to walk about 20m without resting"}
   {:id "SCORE7_0" :score "7.0" :description "Unable to walk beyond approximately 5m even with aid. Essentially restricted to wheelchair; though wheels self in standard wheelchair and transfers alone. Up and about in wheelchair some 12 hours a day"}
   {:id "SCORE7_5" :score "7.5" :description "Unable to take more than a few steps. Restricted to wheelchair and may need aid in transfering. Can wheel self but cannot carry on in standard wheelchair for a full day and may require a motorised wheelchair"}
   {:id "SCORE8_0" :score "8.0" :description "Essentially restricted to bed or chair or pushed in wheelchair. May be out of bed itself much of the day. Retains many self-care functions. Generally has effective use of arms"}
   {:id "SCORE8_5" :score "8.5" :description "Essentially restricted to bed much of day. Has some effective use of arms retains some self-care functions"}
   {:id "SCORE9_0" :score "9.0" :description "Confined to bed. Can still communicate and eat"}
   {:id "SCORE9_5" :score "9.5" :description "Confined to bed and totally dependent. Unable to communicate effectively or eat/swallow"}
   {:id "SCORE10_0" :score "10.0" :description "Death due to MS"}])

(defn edss-score-display
  "Custom display for EDSS scores with score and description"
  [{:keys [score description]}]
  [:div
   [:span.text-base.font-bold.text-gray-800 score]
   [:span.ms-4.text-sm.text-gray-600.italic description]])

(defn ui-form-edss
  "EDSS form with radio button selection"
  ([]
   [:form/id
    :form_edss/id :form_edss/edss_score :form_edss/is_deleted
    :form_edss/user_fk :form_edss/encounter_fk])
  ([{:form/keys [user] :form_edss/keys [edss_score] :as form-data} {:keys [disabled]}]
   (println "edss form" form-data)
   (ui/ui-simple-form
     #_(ui/ui-simple-form-title {:title "EDSS (short-form)"})
     (ui/ui-simple-form-item {:label "Responsible user"}
       (ui-form-user form-data))
     (ui/ui-simple-form-item {}
       [:div.w-screen.p-4
        [:div.sm:columns-2
         (ui/ui-radio-button
           {:name     "edss_score"
            :disabled disabled
            :value-id (some-> edss_score name)
            :options  (map (fn [{:keys [id score description]}]
                             {:id   id
                              :text (edss-score-display {:score score :description description})})
                           edss-scores)})]]))))

(defn ui-form-ms-relapse
  "MS relapse form with checkbox and dropdowns"
  ([]
   [:form/id :form_ms_relapse/id :form_ms_relapse/in_relapse :form_ms_relapse/ms_disease_course_fk
    :form_ms_relapse/activity :form_ms_relapse/progression
    :form_ms_relapse/is_deleted :form_ms_relapse/user_fk :form_ms_relapse/encounter_fk
    {:form/encounter [:t_encounter/id :t_encounter/date_time :t_encounter/patient_age]}
    {:com.eldrix.rsdb/all-ms-disease-courses [:t_ms_disease_course/id :t_ms_disease_course/name]}])
  ([{:form_ms_relapse/keys [in_relapse ms_disease_course_fk activity progression]
     :form/keys            [encounter]
     :com.eldrix.rsdb/keys [all-ms-disease-courses] :as form-data}
    {:keys [disabled]}]
   (clojure.pprint/pprint form-data)
   (ui/ui-simple-form
     (ui/ui-simple-form-title
       {:title "Neuroinflammatory disease relapse information"})
     (ui/ui-simple-form-item
       {:label "Responsible user"}
       (ui-form-user form-data))
     (ui/ui-simple-form-item
       {:label     "In relapse?"
        :sub-label (str "on " (some-> encounter :t_encounter/date_time LocalDateTime/.toLocalDate))}
       [:div.pt-2
        (ui/ui-checkbox
          {:label       "In relapse"
           :description "Is the patient having a clinical relapse or recovering from a relapse?"
           :checked     in_relapse
           :disabled    disabled
           :name        "in_relapse"})])
     (ui/ui-simple-form-item
       {:label "Current multiple sclerosis disease course"}
       (ui/ui-select-button
         {:selected-id ms_disease_course_fk
          :options     (->> all-ms-disease-courses
                            (map (fn [{:t_ms_disease_course/keys [id name]}]
                                   {:id id :text name}))
                            (sort-by :text))
          :disabled    disabled
          :name        "ms_disease_course_fk"}))
     (ui/ui-simple-form-item
       {:label "Current disease activity"}
       (ui/ui-select-button
         {:no-selection-string "UNKNOWN"
          :selected-id         activity
          :options             [{:id "ACTIVE" :text "ACTIVE"}
                                {:id "INACTIVE" :text "INACTIVE"}
                                {:id "INDETERMINATE" :text "INDETERMINATE"}]
          :disabled            disabled
          :name                "activity"}))
     (ui/ui-simple-form-item
       {:label "Current disease progression"}
       (ui/ui-select-button
         {:no-selection-string "UNKNOWN"
          :selected-id         progression
          :options             [{:id "WITHOUT_PROGRESSION" :text "WITHOUT_PROGRESSION"}
                                {:id "WITH_PROGRESSION" :text "WITH_PROGRESSION"}]
          :disabled            disabled
          :name                "progression"})))))

(defn ui-form-weight-height
  "Weight and height form with BMI calculation"
  ([]
   [:form/id :form_weight_height/id
    :form_weight_height/weight_kilogram :form_weight_height/height_metres
    :form_weight_height/is_deleted
    :form_weight_height/encounter_fk :form_weight_height/user_fk
    {:form/encounter [:t_encounter/id :t_encounter/date_time :t_encounter/patient_age]}])
  ([{:form_weight_height/keys [weight_kilogram height_metres]
     :form/keys               [encounter] :as form-data}
    {:keys [disabled]}]
   (let [patient-age (when-let [period (:t_encounter/patient_age encounter)]
                       (.-years period))
         bmi (when (and weight_kilogram height_metres)
               (.round (/ weight_kilogram (* height_metres height_metres)) 1))]
     [:<>
      (ui/ui-simple-form-item
        {:label "Weight (kilograms)"}
        (ui/ui-textfield*
          {:value    (str weight_kilogram)
           :disabled disabled
           :type     "number"
           :name     "weight_kilogram"}))
      (ui/ui-simple-form-item
        {:label "Height (metres)"}
        (ui/ui-textfield*
          {:value    (str height_metres)
           :disabled disabled
           :type     "number"
           :name     "height_metres"}))
      (when (and patient-age (>= patient-age 18))
        (ui/ui-simple-form-item
          {:label "Body mass index (adult)"}
          [:div.mt-2.text-gray-600.italic
           (when bmi (str bmi " kg/m²"))]))])))

(defn ui-form-smoking-history
  "Smoking history form with conditional fields"
  ([]
   [:form/id :form_smoking_history/id
    :form_smoking_history/status
    :form_smoking_history/current_cigarettes_per_day :form_smoking_history/duration_years
    :form_smoking_history/previous_cigarettes_per_day :form_smoking_history/previous_duration_years
    :form_smoking_history/year_gave_up
    :form_smoking_history/is_deleted
    :form_smoking_history/encounter_fk :form_smoking_history/user_fk
    {:form/encounter [:t_encounter/id :t_encounter/date_time :t_encounter/patient_age]}])
  ([{:form_smoking_history/keys [status current_cigarettes_per_day duration_years
                                 previous_cigarettes_per_day previous_duration_years
                                 year_gave_up] :as form-data}
    {:keys [disabled]}]
   [:<>
    (ui/ui-simple-form-item
      {:label "Smoking status"}
      (ui/ui-select-button
        {:selected status
         :options  [{:id "NEVER_SMOKED" :text "Never Smoked"}
                    {:id "EX_SMOKER" :text "Ex Smoker"}
                    {:id "CURRENT_SMOKER" :text "Current Smoker"}]
         :disabled disabled
         :name     "status"}))
    (case status
      "NEVER_SMOKED" nil
      "CURRENT_SMOKER"
      [:div
       (ui/ui-simple-form-item
         {:label "Current cigarettes per day"}
         (ui/ui-textfield*
           {:value    (str current_cigarettes_per_day)
            :disabled disabled
            :type     "number"
            :name     "current_cigarettes_per_day"}))
       (ui/ui-simple-form-item
         {:label "Duration in years"}
         (ui/ui-textfield*
           {:value    (str duration_years)
            :disabled disabled
            :type     "number"
            :name     "duration_years"}))]
      "EX_SMOKER"
      [:div
       (ui/ui-simple-form-item
         {:label "Previous number of cigarettes per day"}
         (ui/ui-textfield*
           {:value    (str previous_cigarettes_per_day)
            :disabled disabled
            :type     "number"
            :name     "previous_cigarettes_per_day"}))
       (ui/ui-simple-form-item
         {:label "Duration in years"}
         (ui/ui-textfield*
           {:value    (str previous_duration_years)
            :disabled disabled
            :type     "number"
            :name     "previous_duration_years"}))
       (ui/ui-simple-form-item
         {:label "Year gave up smoking cigarettes"}
         (ui/ui-textfield*
           {:value    (str year_gave_up)
            :disabled disabled
            :type     "number"
            :name     "year_gave_up"}))]
      nil)]))

(defn ui-encounter-banner
  "Renders encounter banner with date and project information"
  ([]
   [{:form/encounter [:t_encounter/date_time :t_encounter/is_deleted
                      {:t_encounter/encounter_template [{:t_encounter_template/project [:t_project/title]}
                                                        :t_encounter_template/title]}]}])
  ([{:form/keys [encounter]}]
   (let [date_time (:t_encounter/date_time encounter)
         project-title (get-in encounter [:t_encounter/encounter_template :t_encounter_template/project :t_project/title])
         encounter-template-title (get-in encounter [:t_encounter/encounter_template :t_encounter_template/title])]
     [:div.flex.flex-wrap.w-full.items-center.gap-6.px-4.py-2.sm:flex-nowrap.sm:px-6.lg:px-8.bg-gray-100.shadow
      [:h1.text-base.w-full.text-center.sm:text-left.sm:w-auto.font-semibold.md:leading-2.text-gray-900.sm:text-sm
       (str (ui/day-of-week date_time) " " (ui/format-date-time date_time))]
      [:div.order-last.flex.w-full.gap-x-6.text-sm.font-semibold.leading-2.sm:order-none.sm:w-auto.sm:border-l.sm:border-gray-200.sm:pl-6.sm:leading-0
       [:span.text-gray-400.italic project-title]
       [:span.text-gray-600 encounter-template-title]]])))

(defn form-layout
  "Main form layout with patient banner, encounter banner and action buttons"
  ([{:keys [patient-banner encounter-banner can-edit? form-content action-buttons]}]
   [:<>
    patient-banner
    encounter-banner
    [:div.bg-white.shadow.sm:rounded-lg
     [:div.px-4.py-5.sm:p-6
      [:div.mt-2.text-gray-500 form-content]
      [:div.sm:flex.flex-row.sm:justify-end.mt-5.sm:border.shadow-md.py-2.sm:px-2.sm:bg-gray-100
       action-buttons]]]]))

(defn action-buttons
  "Form action buttons (Save, Cancel, Delete)"
  ([]
   [:form/id
    :form/is_deleted
    {:form/encounter [:t_encounter/is_deleted :t_encounter/is_locked :t_encounter/lock_date_time
                      {:t_encounter/patient [:t_patient/permissions]}]}])
  ([{:form/keys [id is_deleted encounter] :as form-data}]
   (let [is-new-form? (string? (str id))
         patient (:t_encounter/patient encounter)
         is-locked (:t_encounter/is_locked encounter)
         can-edit (or (not is-locked) (:CAN_EDIT (:t_patient/permissions patient)))]
     [:div.sm:flex.flex-row.sm:justify-end.mt-5.border.shadow-md.py-2.bg-gray-100
      (if can-edit
        [:div
         (ui/ui-cancel-button {:hx-get "/cancel-url"} "Cancel")
         (when-not (string? (str id))
           (ui/ui-delete-button {:hx-delete "/delete-url"} "Delete"))
         (ui/ui-submit-button {} "Save")]
        [:div
         [:span.text-sm.pt-3.mr-4.italic.text-gray-500
          (if is-locked
            "You cannot edit this form as the encounter is locked"
            "You do not have permission to edit this form")]
         (ui/ui-cancel-button {:hx-get "/close-url"} "Close")])])))

(defn ui-form-unsupported
  ([]
   [:form/id {:form/form_type [:form_type/title]} :form/summary_result])
  ([{:form/keys [id form_type summary_result]} _]
   (ui/ui-simple-form
     (ui/ui-simple-form-title
       {:title (:form_type/title form_type)})
     (ui/ui-simple-form-item {:label "Warning:"}
       "Sorry, but this form is not yet supported in pc4.")
     (ui/ui-simple-form-item {}
       summary_result))))

(def all-forms
  {:form_edss            ui-form-edss
   :form_weight_height   ui-form-weight-height
   :form_ms_relapse      ui-form-ms-relapse
   :form_smoking_history ui-form-smoking-history})

(defn ui-layout
  "Render layout for form contents."
  ([]
   (into [:ui/csrf-token] (action-buttons)))
  ([{:ui/keys [csrf-token] :as form-data} & content]
   [:div.bg-white.shadow.sm.rounded-lg
    [:div.px-4.mt-2.text-gray-500
     [:form {}
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
      content]]
    (action-buttons form-data)]))

(def form-handler
  (pathom/handler
    (fn [request]
      (let [form (get all-forms (keyword (get-in request [:path-params :form-type])) ui-form-unsupported)
            target (web/hx-target request)
            encounter-id (some-> request :path-params :encounter-id parse-long)
            form-query (pathom/merge-queries (form) (ui-form-user))
            query
            ;; return a query (hinted with current encounter) and requirements for form +/- layout / banner / navbar etc.
            (if (or (nil? target) (= target "body"))
              [{(list :ui/current-form {:encounter-id encounter-id})
                (pathom/merge-queries form-query (ui-encounter-banner) (ui-layout))}
               :ui/navbar
               {:ui/current-patient [:ui/patient-banner]}]
              [{(list :ui/current-form {:encounter-id encounter-id})
                form-query}])]
        (log/debug query)
        query))
    (fn [request {:ui/keys [current-form navbar current-patient] :as result}]
      (clojure.pprint/pprint result)
      (let [form (get all-forms (keyword (get-in request [:path-params :form-type])) ui-form-unsupported)
            target (web/hx-target request)]
        (web/ok
          (if (or (nil? target) (= target "body"))
            (web/render-file "templates/patient/base.html"
                             {:navbar  navbar
                              :banner  (:ui/patient-banner current-patient)
                              :content (web/render [:div
                                                    (ui-encounter-banner current-form)
                                                    (ui-layout current-form (form current-form {}))])})
            (web/render (ui-layout current-form (form current-form {})))))))))