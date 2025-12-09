(ns pc4.workbench.controllers.patient.forms
  "pc4 forms implementation.

  Each 'form' is a multi-arity function:
  - 'data'   : 0-arity returning data requirements for the form (EQL)
  - 'render' : 2-arity fn returning rendered form as Clojure data (hiccup/rum).

  This means the data query and rendering are co-located."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [edn-query-language.core :as eql]
    [io.pedestal.http.route :as route]
    [pc4.log.interface :as log]
    [pc4.pathom-web.interface :as pw]
    [pc4.rsdb.interface :as rsdb]
    [pc4.common-ui.interface :as ui]
    [pc4.web.interface :as web]
    [pc4.workbench.controllers.select-user :as select-user])
  (:import (java.time LocalDateTime)))

(s/def ::request map?)
(s/def ::query-fn (s/fspec :args (s/cat :request ::request)))
(s/def ::query (s/or :q ::eql/query :fn ::query-fn))
(s/def ::data map?)
(s/def ::view (s/fspec :args (s/cat :data ::data)))
(s/def ::form-params map?)
(s/def ::parse-k (s/or :fn fn? :k keyword?))
(s/def ::parse-v (s/or :fn fn? :v any?))
(s/def ::parser (s/or :fn fn? :kv (s/cat :k ::parse-k :v ::parse-v)))
(s/def ::k keyword?)
(s/def ::parse (s/map-of ::k ::parser))
(s/def ::ns keyword?)
(s/def ::form (s/keys :req-un [::query ::view]
                      :opt-un [::parse ::ns]))


(defn view
  "Return a view from a component, which should either be a map (:query ..., :view ...) or a multi-arity function in
  which 0-arity returns the query, and 1-arity returns the view given the result of the query."
  [form data]
  (cond
    (map? form)
    ((:view form) data)
    (fn? form)
    (form data)))

(defn query
  "Return a query from a component, which should either be a map (:query ..., :view ...) or a multi-arity function in
  which 0-arity returns the query, and 1-arity returns the view given the result of the query."
  [form request]
  (cond
    (map? form)
    (let [q (:query form)]
      (if (fn? q) (q request) q))
    (fn? form)
    (form)))

(defn nm->kw
  "Turn HTML properties into namespaced keywords iff the property name
  contains '#'. A property without '#' will be ignored.
  A property with no namespace preceding the '#' will be given the default ns.

  Parameters:
  - ns - default namespace if not otherwise specified
  - k  - key representing HTML form name.

  Examples:
  ```
  (nm->kw :form_edss \"form_edss#in_relapse\")
  => :form_edss/in_relapse

  (nm->kw :form_edss \"#user_fk\")
  => :form_edss/user_fk

  (nm->kw :form_edss \"user-id\")
  => nil
  ```"
  ([ns k]
   (let [[ns' k'] (str/split (name k) #"#" 2)]
     (when (and ns' k')
       (keyword (if (str/blank? ns') (name ns) ns') k')))))

(defn parse
  "Parse a 'form' given the form parameters from a HTTP request.
  The :parse configuration of a form can be:
  - a function that receives all form params and returns parsed results
  - a map of keys to
     - a tuple of k/v where k and v can be a value or a function
     - a function that takes the key and value and returns the transformed key and value"
  [{:keys [ns parse]} form-params]
  (cond
    (map? parse)
    (reduce-kv
      (fn [acc k v]
        (let [parser (get parse (nm->kw ns k))]
          (cond
            (fn? parser)
            (let [[k' v'] (parser k v)]
              (assoc acc k' v'))
            (vector? parser)
            (let [[k# v#] parser]
              (assoc acc (if (fn? k#) (k# k) k#)
                         (if (fn? v#) (v# v) v#)))
            :else
            acc))) {} form-params)
    (fn? parse)
    (parse (update-keys form-params #(nm->kw (name ns) %)))
    :else
    (update-keys form-params #(nm->kw (name ns) %))))

;;
;;
;;
;;


(def ui-form-user
  {:query [{:form/user [:t_user/id :t_user/full_name :t_user/job_title]}]
   :view  (fn ([{:form/keys [actions user]}]
               (let [selected-user (when user
                                     {:user-id   (:t_user/id user) :full-name (:t_user/full_name user)
                                      :job-title (or (:t_user/job_title user) "")})]
                 (select-user/ui-select-user
                   {:name     "user-fk"
                    :selected selected-user
                    :disabled (not (:EDIT actions))}))))})

(def edss-scores
  "EDSS score options with descriptions"
  [{:score "0.0" :description "Normal neurological exam, no disability in any FS"}
   {:score "1.0" :description "No disability, minimal signs in one FS"}
   {:score "1.5" :description "No disability, minimal signs in more than one FS"}
   {:score "2.0" :description "Minimal disability in one FS"}
   {:score "2.5" :description "Mild disability in one FS or minimal disability in two FS"}
   {:score "3.0" :description "Moderate disability in one FS, or mild disability in three or four FS. No impairment to walking"}
   {:score "3.5" :description "Moderate disability in one FS and more than minimal disability in several others. No impairment to walking"}
   {:score "<4" :description "No impairment to walking but neurological examination not performed"}
   {:score "4.0" :description "Significant disability but self-sufficient and up and about some 12 hours a day. Able to walk without aid or rest for 500m"}
   {:score "4.5" :description "Significant disability but up and about much of the day, able to work a full day, may otherwise have some limitation of full activity or require minimal assistance. Able to walk without aid or rest for 300m"}
   {:score "5.0" :description "Disability severe enough to impair full daily activities and ability to work a full day without special provisions. Able to walk without aid or rest for 200m"}
   {:score "5.5" :description "Disability severe enough to preclude full daily activities. Able to walk without aid or rest for 100m"}
   {:score "6.0" :description " Requires a walking aid – cane, crutch, etc. – to walk about 100m with or without resting"}
   {:score "6.5" :description "Requires two walking aids – pair of canes, crutches, etc. – to walk about 20m without resting"}
   {:score "7.0" :description "Unable to walk beyond approximately 5m even with aid. Essentially restricted to wheelchair; though wheels self in standard wheelchair and transfers alone. Up and about in wheelchair some 12 hours a day"}
   {:score "7.5" :description "Unable to take more than a few steps. Restricted to wheelchair and may need aid in transfering. Can wheel self but cannot carry on in standard wheelchair for a full day and may require a motorised wheelchair"}
   {:score "8.0" :description "Essentially restricted to bed or chair or pushed in wheelchair. May be out of bed itself much of the day. Retains many self-care functions. Generally has effective use of arms"}
   {:score "8.5" :description "Essentially restricted to bed much of day. Has some effective use of arms retains some self-care functions"}
   {:score "9.0" :description "Confined to bed. Can still communicate and eat"}
   {:score "9.5" :description "Confined to bed and totally dependent. Unable to communicate effectively or eat/swallow"}
   {:score "10.0" :description "Death due to MS"}])

(defn edss-score-display
  "Custom display for EDSS scores with score and description"
  [{:keys [score description]}]
  [:div
   [:span.text-base.font-bold.text-gray-800 score]
   [:span.ms-4.text-sm.text-gray-600.italic description]])

(def ui-form-edss
  "EDSS form with radio button selection"
  {:query [:form/id :form/actions
           :form/edss :form/is_deleted
           :form/user_fk :form/encounter_fk]
   :view  (fn [{:form/keys [actions edss] :as form-data}]
            (clojure.pprint/pprint form-data)
            (ui/ui-simple-form
              (ui/ui-simple-form-item {:label "Responsible user"}
                                      (view ui-form-user form-data))
              (ui/ui-simple-form-item {}
                                      [:div.w-screen.p-4
                                       [:div.sm:columns-2
                                        (ui/ui-radio-button
                                          {:name     "#edss_score"
                                           :disabled (not (:EDIT actions))
                                           :value-id edss
                                           :options  (map (fn [{:keys [score description]}]
                                                            {:id   score
                                                             :text (edss-score-display {:score score :description description})})
                                                          edss-scores)})]])))})

(def ui-form-ms-relapse
  "MS relapse form with checkbox and dropdowns"
  {:query [:form/id :form/actions :form_ms_relapse/id :form_ms_relapse/in_relapse :form_ms_relapse/ms_disease_course_fk
           :form_ms_relapse/activity :form_ms_relapse/progression
           :form_ms_relapse/is_deleted :form_ms_relapse/user_fk :form_ms_relapse/encounter_fk
           {:form/encounter [:t_encounter/id :t_encounter/date_time :t_encounter/patient_age]}
           {:com.eldrix.rsdb/all-ms-disease-courses [:t_ms_disease_course/id :t_ms_disease_course/name]}]
   :view  (fn [{:form_ms_relapse/keys [in_relapse ms_disease_course_fk activity progression]
                :form/keys            [encounter actions]
                :com.eldrix.rsdb/keys [all-ms-disease-courses] :as form-data}]
            (let [disabled (not (:EDIT actions))]
              (ui/ui-simple-form
                (ui/ui-simple-form-item
                  {:label "Responsible user"}
                  (view ui-form-user form-data))
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
                     :name                "progression"})))))})

(def ui-form-weight-height
  "Weight and height form with BMI calculation"
  {:query [:form/id :form/actions :form_weight_height/id
           :form_weight_height/weight_kilogram :form_weight_height/height_metres
           :form_weight_height/is_deleted
           :form_weight_height/encounter_fk :form_weight_height/user_fk
           {:form/encounter [:t_encounter/id :t_encounter/date_time :t_encounter/patient_age]}]
   :view  (fn [{:form_weight_height/keys [weight_kilogram height_metres]
                :form/keys               [actions encounter] :as form-data}]
            (let [disabled (not (:EDIT actions))
                  patient-age (when-let [period (:t_encounter/patient_age encounter)]
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
                    (when bmi (str bmi " kg/m²"))]))]))})

(def ui-form-smoking-history
  "Smoking history form with conditional fields"
  {:query [:form/id :form/actions :form_smoking_history/id
           :form_smoking_history/status
           :form_smoking_history/current_cigarettes_per_day :form_smoking_history/duration_years
           :form_smoking_history/previous_cigarettes_per_day :form_smoking_history/previous_duration_years
           :form_smoking_history/year_gave_up
           :form_smoking_history/is_deleted
           :form_smoking_history/encounter_fk :form_smoking_history/user_fk
           {:form/encounter [:t_encounter/id :t_encounter/date_time :t_encounter/patient_age]}]
   :view  (fn [{:form/keys [actions]
                :form_smoking_history/keys [status current_cigarettes_per_day duration_years
                                            previous_cigarettes_per_day previous_duration_years
                                            year_gave_up] :as form-data}]
            (let [disabled (not (:EDIT actions))]
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
                 nil)]))})

(def ui-encounter-banner
  "Renders encounter banner with date and project information"
  {:query [{:form/encounter [:t_encounter/date_time :t_encounter/is_deleted
                             {:t_encounter/encounter_template [{:t_encounter_template/project [:t_project/title]}
                                                               :t_encounter_template/title]}]}]
   :view  (fn [{:form/keys [encounter]}]
            (let [date_time (:t_encounter/date_time encounter)
                  project-title (get-in encounter [:t_encounter/encounter_template :t_encounter_template/project :t_project/title])
                  encounter-template-title (get-in encounter [:t_encounter/encounter_template :t_encounter_template/title])]
              [:div.flex.flex-wrap.w-full.items-center.gap-6.px-4.py-2.sm:flex-nowrap.sm:px-6.lg:px-8.bg-gray-100.shadow
               [:h1.text-base.w-full.text-center.sm:text-left.sm:w-auto.font-semibold.md:leading-2.text-gray-900.sm:text-sm
                (str (ui/day-of-week date_time) " " (ui/format-date-time date_time))]
               [:div.order-last.flex.w-full.gap-x-6.text-sm.font-semibold.leading-2.sm:order-none.sm:w-auto.sm:border-l.sm:border-gray-200.sm:pl-6.sm:leading-0
                [:span.text-gray-400.italic project-title]
                [:span.text-gray-600 encounter-template-title]]]))})

;;
;;
;;

(def ui-form-unsupported
  {:query [:form/id {:form/form_type [:form_type/title]} :form/summary_result]
   :view  (fn [{:form/keys [id form_type summary_result]}]
            (ui/ui-simple-form
              (ui/ui-simple-form-title
                {:title (:form_type/title form_type)})
              (ui/ui-simple-form-item {:label "Warning:"}
                                      "Sorry, but this form is not yet supported in pc4.")
              (ui/ui-simple-form-item {}
                                      summary_result)))})

(def all-forms
  {:t_form_edss            ui-form-edss
   :t_form_weight_height   ui-form-weight-height
   :t_form_ms_relapse      ui-form-ms-relapse
   :t_form_smoking_history ui-form-smoking-history})

(defn request->form [request]
  (get all-forms (keyword (get-in request [:path-params :form-type])) ui-form-unsupported))


(defn- ui-button [{:keys [role disabled] :as params} content]
  (let [base "inline-flex items-center justify-center px-4 py-2 text-sm font-semibold rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-offset-2"
        styles (case role
                 :primary "bg-blue-600 text-white hover:bg-blue-700 focus:ring-blue-500"
                 :danger "bg-red-600 text-white hover:bg-red-700 focus:ring-red-500"
                 "bg-white text-gray-700 ring-1 ring-inset ring-gray-300 hover:bg-gray-50 focus:ring-gray-500")]
    [:button (merge {:type "button" :class (str base " " styles (when disabled " opacity-50 cursor-not-allowed"))}
                    (when disabled {:disabled "disabled"})
                    (dissoc params :role :disabled))
     content]))



(def ui-actions
  "Form action buttons (Save, Cancel, Delete/Undelete)"
  {:query [:form/id :form/status :form/actions :form/is_deleted :ui/csrf-token
           {:form/encounter [:t_encounter/id :t_encounter/is_deleted :t_encounter/is_locked
                             {:t_encounter/patient [:t_patient/patient_identifier
                                                    :t_patient/permissions]}]}]
   :view  (fn [{:form/keys [id actions status encounter is_deleted] :ui/keys [csrf-token]}]
            (let [new-form? false                           ;;TODO: fix
                  can-edit (:EDIT actions)
                  can-undelete (:UNDELETE actions)
                  {:t_encounter/keys [patient]} encounter
                  {:t_patient/keys [patient_identifier]} patient
                  encounter-id (:t_encounter/id encounter)
                  [form-type form-id] (if (vector? id)
                                        [(name (first id)) (second id)]
                                        ["nf" id])
                  url-for (fn [route]
                            (route/url-for route :path-params {:patient-identifier patient_identifier
                                                               :encounter-id       encounter-id
                                                               :form-type          form-type
                                                               :form-id            form-id}))
                  hx-post (fn [route hx-vals]
                            {:hx-post     (url-for route)
                             :hx-vals     (str "{\"__anti-forgery-token\":\"" csrf-token "\"" (when hx-vals (str "," hx-vals)) "}")
                             :hx-push-url "true"
                             :hx-target   "body"})]
              [:div.flex.justify-end.gap-2.mt-5.py-3.px-4.bg-gray-50.border-t
               (cond
                 ;; Deleted form - show undelete option
                 is_deleted
                 [:<>
                  (ui-button {:onclick "history.back()"} "Close")
                  (when can-undelete
                    (ui-button (assoc (hx-post :patient/form-delete "\"delete\":\"false\"")
                                      :role :primary)
                               "Undelete"))]

                 ;; Can edit - show full editing options
                 can-edit
                 [:<>
                  (ui-button {:onclick "history.back()"} "Cancel")
                  (when-not new-form?
                    (ui-button (assoc (hx-post :patient/form-delete "\"delete\":\"true\"")
                                      :hx-confirm "Are you sure you want to delete this form?"
                                      :role :danger)
                               "Delete"))
                  (ui-button (assoc (hx-post :patient/form-save nil)
                                    :role :primary) "Save and close")]

                 ;; Cannot edit - show close with message
                 :else
                 [:<>
                  (when-let [msg (cond (:LOCKED status) "You cannot edit this form as the encounter is locked"
                                       (:UNAUTHORIZED status) "You do not have permission to edit this form")]
                    [:span.text-sm.mr-4.italic.text-gray-500 msg])
                  (ui-button {:onclick "history.back()"} "Close")])]))})

(defn ui-layout
  "Render layout for form contents."
  ([] [:ui/csrf-token :form/is_deleted])
  ([{:ui/keys [csrf-token] :form/keys [is_deleted] :as form-data} & content]
   [:div {:class (if is_deleted "bg-red-50 shadow sm:rounded-lg" "bg-white shadow sm:rounded-lg")}
    [:form {}
     (when is_deleted
       [:div.bg-red-100.border-l-4.border-red-500.text-red-700.p-4.mb-4
        [:div.flex.items-center
         [:svg.h-5.w-5.mr-2 {:fill "currentColor" :viewBox "0 0 20 20"}
          [:path {:fill-rule "evenodd"
                  :d "M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
                  :clip-rule "evenodd"}]]
         [:span.font-medium "This form has been deleted"]]])
     [:div.px-4.mt-2.text-gray-500
      [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
      content]
     (view ui-actions form-data)]]))

(def form-handler
  (pw/handler
    (fn [request]
      (let [form (request->form request)
            target (web/hx-target request)
            encounter-id (some-> request :path-params :encounter-id parse-long)
            form-query (pw/merge-queries (query ui-layout request) (query form request) (query ui-form-user request) (query ui-actions request))
            query
            ;; return a query (hinted with current encounter) and requirements for form +/- layout / banner / navbar etc.
            (if (or (nil? target) (= target "body"))
              [{(list :ui/current-form {:encounter-id encounter-id})
                (pw/merge-queries form-query (query ui-encounter-banner request))}
               :ui/navbar
               {:ui/current-patient [:ui/patient-banner]}]
              [{(list :ui/current-form {:encounter-id encounter-id})
                form-query}])]
        (log/debug query)
        query))
    (fn [request {:ui/keys [current-form navbar current-patient] :as result}]
      (let [form (request->form request)
            target (web/hx-target request)]
        (web/ok
          (if (or (nil? target) (= target "body"))
            (ui/render-file "templates/patient/base.html"
                            {:navbar  navbar
                             :banner  (:ui/patient-banner current-patient)
                             :content (ui/render [:div
                                                  (view ui-encounter-banner current-form)
                                                  (ui-layout current-form (view form current-form))])})
            (ui/render (ui-layout current-form (view form current-form)))))))))

(def form-save-handler
  (pw/handler
    [:ui/csrf-token
     {:ui/current-patient [:t_patient/patient_identifier :t_patient/permissions]}
     {:ui/current-encounter [:t_encounter/id]}
     {:ui/current-form [:form/id {:form/form_type [:form_type/nm]}]}]
    (fn [{:keys [env form-params] :as request} {:ui/keys [current-patient current-encounter current-form]}]
      (let [rsdb (:rsdb env)
            form (request->form request)
            form-id (:form/id current-form)
            {:t_patient/keys [patient_identifier permissions]} current-patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            encounter-id (:t_encounter/id current-encounter)
            on-save-url (route/url-for :patient/encounter
                                       :path-params {:patient-identifier patient_identifier
                                                     :encounter-id       encounter-id})]
        (log/debug "save form" {:form-id form-id :encounter-id encounter-id :form form-params})
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          :else
          (let [parsed (parse form (assoc form-params :id form-id :is-deleted false :encounter_fk encounter-id))
                save-data (assoc parsed :form/id form-id)]
            (rsdb/save-form! rsdb save-data)
            (web/redirect-see-other on-save-url)))))))

(def form-delete-handler
  "Handler for delete/undelete form. Uses 'delete' form parameter to determine
  whether to delete (true) or undelete (false). After the operation, redirects
  back to the form view so the user sees the updated state."
  (pw/handler
    {}
    [:ui/csrf-token
     {:ui/current-patient [:t_patient/patient_identifier :t_patient/permissions]}
     {:ui/current-encounter [:t_encounter/id]}
     {:ui/current-form [:form/id]}]
    (fn [{:keys [form-params path-params] :as request} {:ui/keys [current-patient current-encounter current-form]}]
      (let [rsdb (get-in request [:env :rsdb])
            {:t_patient/keys [permissions]} current-patient
            can-edit-patient? (get permissions :PATIENT_EDIT)
            delete? (= "true" (:delete form-params))
            ;; Redirect back to the form itself so user sees updated state
            form-url (route/url-for :patient/form :path-params path-params)]
        (cond
          (not can-edit-patient?)
          (web/forbidden "Not authorized to edit this patient")

          :else
          (do
            (if delete?
              (rsdb/delete-form! rsdb current-form)
              (rsdb/undelete-form! rsdb current-form))
            (web/hx-redirect form-url)))))))