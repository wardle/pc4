(ns pc4.ui.forms
  (:require
   [clojure.string :as str]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.data-fetch :as df]
   [com.fulcrologic.fulcro.dom :as dom :refer [div]]
   [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
   [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
   [com.fulcrologic.fulcro.raw.components :as rc]
   ["big.js" :as Big]
   [pc4.ui.core :as ui]
   [pc4.ui.patients :as patients]
   [com.fulcrologic.fulcro.algorithms.form-state :as fs]
   [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
   [com.fulcrologic.fulcro.algorithms.merge :as merge]
   [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]))

(defmutation edit-form
  [{:keys [form-id class] :as params}]
  (action
   [{:keys [app state]}]
   (println "edit-form" params)
   (swap! state (fn [s] (fs/add-form-config* s class [:form/id form-id])))  ;; add form state
   (dr/target-ready! app [:form/id form-id])))                               ;; tell form it has finished loading 

(defmutation load-form
  [{:keys [form-id class encounter-id] :as params}]
  (action
   [{:keys [app state]}]
   (println "loading form" params)
   ;; if the form-id is a number, then it is one that is already saved from the server
   (if (number? form-id)
     (df/load! app [:form/id form-id] class
               {:params               {:encounter-id encounter-id}
                :post-mutation        `edit-form
                :post-mutation-params {:form-id form-id :class class}})
     ;; otherwise, the form is a newly created form with a tempid
     (comp/transact! app [(edit-form {:form-id form-id :class class})]))))

(defmutation cancel-edit-form
  [{:form/keys [id]}]
  (action
   [{:keys [state]}]
   (println "cancel edit form")
   (swap! state (fn [s]
                  (if (tempid/tempid? id)
                    (update s :form/id dissoc id)
                    (fs/pristine->entity* s  [:form/id id]))))  ;; return form to pristine state, removing any edits
   (.back js/history)))

(defsc MsDiseaseCourse [this {:t_ms_disease_course/keys [id name]}]
  {:ident :t_ms_disease_course/id
   :query [:t_ms_disease_course/id :t_ms_disease_course/name]})

(defmutation load-all-ms-disease-courses
  [_]
  (action
   [{:keys [app state]}]
   (when (empty? (:ui/all-ms-disease-courses @state))
     (println "loading MS disease courses")
     (df/load! app :com.eldrix.rsdb/all-ms-disease-courses MsDiseaseCourse
               {:target [:ui/all-ms-disease-courses]}))))

(defn parse-form-id [s]
  (println "parsing form id" s "type:" (type s))
  (or (parse-long s)
      (tempid/tempid (uuid s))))

(defn form-id->str [form-id]
  (str (if (tempid/tempid? form-id)
         (.-id form-id)
         form-id)))

(defsc FormUser [_ _]
  {:ident :t_user/id
   :query [:t_user/id :t_user/full_name]})

(defsc LayoutPatient
  [_ _]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier {:>/banner (comp/get-query patients/PatientBanner)}]})

(defsc Project [_ _]
  {:ident :t_project/id
   :query [:t_project/id :t_project/title]})

(defsc EncounterTemplate [_ _]
  {:ident :t_encounter_template/id
   :query [:t_encounter_template/id
           :t_encounter_template/title
           {:t_encounter_template/project (comp/get-query Project)}]})

(defsc LayoutEncounter
  [_ _]
  {:ident :t_encounter/id
   :query [:t_encounter/id
           :t_encounter/date_time
           :t_encounter/is_locked
           :t_encounter/lock_date_time
           {:t_encounter/patient (comp/get-query LayoutPatient)}
           {:t_encounter/encounter_template (comp/get-query EncounterTemplate)}]})

(defsc EncounterBanner
  [this {:keys [date project-title encounter-template-title]}]
  (dom/div
   (dom/div
    :.flex.flex-wrap.w-full.items-center.gap-6.px-4.py-2.sm:flex-nowrap.sm:px-6.lg:px-8.bg-gray-100.shadow
    (dom/h1
     :.text-base.w-full.text-center.sm:text-left.sm:w-auto.font-semibold.md:leading-2.text-gray-900.sm:text-sm date)
    (dom/div
     :.order-last.flex.w-full.gap-x-6.text-sm.font-semibold.leading-2.sm:order-none.sm:w-auto.sm:border-l.sm:border-gray-200.sm:pl-6.sm:leading-0
     (dom/span :.text-gray-400.italic project-title)
     (dom/span :.text-gray-600 encounter-template-title)))))

(def ui-encounter-banner (comp/factory EncounterBanner))

(defsc Layout
  [this {:form/keys [id encounter] :as form} {:keys [can-edit save-params]}]
  {:ident :form/id
   :query [:form/id
           {:form/user (comp/get-query FormUser)}
           {:form/encounter (comp/get-query LayoutEncounter)}]}
  (let [{:t_encounter/keys [date_time patient encounter_template is_locked]} encounter
        {:t_patient/keys [patient_identifier]} patient
        cancel-fn #(comp/transact! this [(cancel-edit-form form)])
        delete-fn #(comp/transact! this [(list 'pc4.rsdb/delete-form (assoc save-params
                                                                            :patient-identifier patient_identifier
                                                                            :on-success-tx [(cancel-edit-form {:form/id id})]))])
        save-fn #(comp/transact! this [(list 'pc4.rsdb/save-form (assoc save-params :patient-identifier patient_identifier
                                                                        :on-success-tx [(cancel-edit-form {:form/id id})]))])]
    (comp/fragment
     (patients/ui-patient-banner (:>/banner patient))
     (ui-encounter-banner
      {:date (str (ui/format-week-day date_time) " " (ui/format-date-time date_time))
       :encounter-template-title (:t_encounter_template/title encounter_template)
       :project-title (get-in encounter_template [:t_encounter_template/project :t_project/title])})
     (div :.bg-white.shadow.sm:rounded-lg
          (div :.px-4.py-5.sm:p-6
               (div :.mt-2.text-gray-500
                    (comp/children this))
               (div :.sm:flex.flex-row.sm:justify-end.mt-5.sm:border.shadow-md.py-2.sm:px-2.sm:bg-gray-100
                    (if can-edit
                      (div (ui/ui-button {:onClick cancel-fn} "Cancel")
                           (when-not (tempid/tempid? id) (ui/ui-button {:onClick delete-fn} "Delete"))
                           (when save-params (ui/ui-button {:onClick save-fn :role :primary} "Save")))
                      (div (dom/span :.text-sm.pt-3.italic.text-gray-500
                                     (if is_locked
                                       "You cannot edit this form as the encounter is locked"
                                       "You do not have permission to edit this form"))
                           (ui/ui-button {:onClick cancel-fn} "Close")))))))))

(def ui-layout (comp/computed-factory Layout))

(defsc FormEncounter [this params]
  {:ident :t_encounter/id
   :query [:t_encounter/id :t_encounter/date_time :t_encounter/patient_age]})

(defsc CanEditPatient [_ _]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier :t_patient/permissions]})

(defsc CanEditEncounter [_ _]
  {:ident :t_encounter/id
   :query [:t_encounter/id :t_encounter/lock_date_time :t_encounter/is_locked
           {:t_encounter/patient (comp/get-query CanEditPatient)}]})

(defsc CanEditForm [_ _]
  {:ident :form/id
   :query [:form/id {:form/encounter (comp/get-query CanEditEncounter)}]})

(defn can-edit-form?
  [form-can-edit]
  (let [permissions (get-in form-can-edit [:form/encounter :t_encounter/patient :t_patient/permissions])
        is-locked (get-in form-can-edit [:form/encounter :t_encounter/is_locked])
        lock-date-time (get-in form-can-edit [:form/encounter :t_encounter/lock_date_time])]
    (and permissions
         (permissions :PATIENT_EDIT)
         (not is-locked))))  ;; TODO: also use lock_date_time to manage this, and consider setting a timeout so if lock date time passes, we lock client-side?

(def edss-scores*
  [{:id "SCORE0_0" :s "0.0" :d "Normal neurological exam, no disability in any FS"}
   {:id "SCORE1_0" :s "1.0" :d "No disability, minimal signs in one FS"}
   {:id "SCORE1_5" :s "1.5" :d "No disability, minimal signs in more than one FS"}
   {:id "SCORE2_0" :s "2.0" :d "Minimal disability in one FS"}
   {:id "SCORE2_5" :s "2.5" :d "Mild disability in one FS or minimal disability in two FS"}
   {:id "SCORE3_0" :s "3.0" :d "Moderate disability in one FS, or mild disability in three or four FS. No impairment to walking"}
   {:id "SCORE3_5" :s "3.5" :d "Moderate disability in one FS and more than minimal disability in several others. No impairment to walking"}
   {:id "SCORE_LESS_THAN_4" :s "<4.0" :d "No impairment to walking but neurological examination not performed"}
   {:id "SCORE4_0" :s "4.0" :d "Significant disability but self-sufficient and up and about some 12 hours a day. Able to walk without aid or rest for 500m"}
   {:id "SCORE4_5" :s "4.5" :d "Significant disability but up and about much of the day, able to work a full day, may otherwise have some limitation of full activity or require minimal assistance. Able to walk without aid or rest for 300m"}
   {:id "SCORE5_0" :s "5.0" :d "Disability severe enough to impair full daily activities and ability to work a full day without special provisions. Able to walk without aid or rest for 200m"}
   {:id "SCORE5_5" :s "5.5" :d "Disability severe enough to preclude full daily activities. Able to walk without aid or rest for 100m"}
   {:id "SCORE6_0" :s "6.0" :d " Requires a walking aid – cane, crutch, etc. – to walk about 100m with or without resting"}
   {:id "SCORE6_5" :s "6.5" :d "Requires two walking aids – pair of canes, crutches, etc. – to walk about 20m without resting"}
   {:id "SCORE7_0" :s "7.0" :d "Unable to walk beyond approximately 5m even with aid. Essentially restricted to wheelchair; though wheels self in standard wheelchair and transfers alone. Up and about in wheelchair some 12 hours a day"}
   {:id "SCORE7_5" :s "7.5" :d "Unable to take more than a few steps. Restricted to wheelchair and may need aid in transfering. Can wheel self but cannot carry on in standard wheelchair for a full day and may require a motorised wheelchair"}
   {:id "SCORE8_0" :s "8.0" :d "Essentially restricted to bed or chair or pushed in wheelchair. May be out of bed itself much of the day. Retains many self-care functions. Generally has effective use of arms"}
   {:id "SCORE8_5" :s "8.5" :d "Essentially restricted to bed much of day. Has some effective use of arms retains some self-care functions"}
   {:id "SCORE9_0" :s "9.0" :d "Confined to bed. Can still communicate and eat"}
   {:id "SCORE9_5" :s "9.5" :d "Confined to bed and totally dependent. Unable to communicate effectively or eat/swallow"}
   {:id "SCORE10_0" :s "10.0" :d "Death due to MS"}])

(def edss-scores (mapv :id edss-scores*))

(def edss-score-display
  (reduce (fn [acc {:keys [id s d]}]
            (assoc acc id (div
                           (dom/span :.text-base.font-bold.text-gray-800 s)
                           (dom/span :.ms-4.text-sm.text-gray-600.italic d)))) {} edss-scores*))

(defsc EditFormEdss
  [this {:form_edss/keys [edss_score] :>/keys [can-edit layout] :as form}]
  {:ident         :form/id
   :route-segment ["encounter" :encounter-id "form_edss" :form/id]
   :query         [:form/id
                   :form_edss/id :form_edss/edss_score :form_edss/is_deleted :form_edss/user_fk :form_edss/encounter_fk {:>/can-edit (comp/get-query CanEditForm)}
                   {:>/layout (comp/get-query Layout)}
                   fs/form-config-join]
   :form-fields   #{:form_edss/edss_score}
   :will-enter    (fn [app {:keys [encounter-id] :form/keys [id] :as route-params}]
                    (let [form-id (parse-form-id id), encounter-id (js/parseInt encounter-id)]
                      (dr/route-deferred
                       [:form/id form-id]
                       (fn []
                         (comp/transact! app [(load-form {:form-id form-id :encounter-id encounter-id :class EditFormEdss})])))))}
  (let [can-edit? (can-edit-form? can-edit)
        select-fn #(m/set-value! this :form_edss/edss_score %)]
    (ui-layout
     layout {:can-edit can-edit? :save-params {:form (select-keys form [:form/id :form_edss/id :form_edss/edss_score
                                                                        :form_edss/user_fk :form_edss/is_deleted
                                                                        :form_edss/encounter_fk]) :class `EditFormEdss}}
     (ui/ui-simple-form-title
      {:title "EDSS (short-form)"})
     (div :.w-screen.p-4
          (div :.sm:columns-2
               (ui/ui-radio-button
                {:name        "edss"
                 :disabled    (not can-edit?)
                 :value       edss_score
                 :options     edss-scores
                 :display-key edss-score-display
                 :label-props (when can-edit? {:onClick select-fn})
                 :onChange    (when can-edit? select-fn)}))))))

(def ui-edit-form-edss (comp/factory EditFormEdss))

(defsc EditFormMsRelapse
  [this {:form_ms_relapse/keys [in_relapse ms_disease_course_fk activity progression] :form/keys [encounter] :>/keys [can-edit layout]
         :ui/keys [all-ms-disease-courses] :as params}]
  {:ident         :form/id
   :route-segment ["encounter" :encounter-id "form_ms_relapse" :form/id]
   :query         [:form/id :form_ms_relapse/id :form_ms_relapse/in_relapse :form_ms_relapse/ms_disease_course_fk
                   :form_ms_relapse/activity :form_ms_relapse/progression
                   :form_ms_relapse/is_deleted :form_ms_relapse/user_fk :form_ms_relapse/encounter_fk
                   {:form/encounter (comp/get-query FormEncounter)}
                   {[:ui/all-ms-disease-courses '_] (comp/get-query MsDiseaseCourse)}
                   {:>/can-edit (comp/get-query CanEditForm)}
                   {:>/layout (comp/get-query Layout)}
                   fs/form-config-join]
   :form-fields   #{:form_ms_relapse/in_relapse  :form_ms_relapse/ms_disease_course_fk
                    :form_ms_relapse/activity :form_ms_relapse/progression :form_ms_relapse/is_deleted :form_ms_relapse/user_fk :form_ms_relapse/encounter_fk}
   :will-enter    (fn [app {:keys [encounter-id] :form/keys [id] :as route-params}]
                    (let [form-id (parse-form-id id), encounter-id (js/parseInt encounter-id)]
                      (dr/route-deferred
                       [:form/id form-id] ;; load the form but...
                       (fn []             ;; we also need to lazily load the ms disease courses for the drop-down, unless already one
                         (comp/transact! app [(load-form {:form-id form-id :encounter-id encounter-id :class EditFormMsRelapse})
                                              (load-all-ms-disease-courses {})])))))}
  (let [can-edit? (can-edit-form? can-edit)
        ms-disease-course-by-id (reduce (fn [acc {:t_ms_disease_course/keys [id] :as dc}] (assoc acc id dc)) {} all-ms-disease-courses)]
    (ui-layout
     layout {:can-edit can-edit? :save-params {:form (select-keys params [:form/id :form_ms_relapse/id :form_ms_relapse/in_relapse :form_ms_relapse/ms_disease_course_fk
                                                                          :form_ms_relapse/activity :form_ms_relapse/progression
                                                                          :form_ms_relapse/encounter_fk :form_ms_relapse/user_fk])}}
     (comp/fragment
      (ui/ui-simple-form-title
       {:title "Neuroinflammatory disease relapse information"})
      (ui/ui-simple-form-item
       {:label "In relapse?" :sub-label (str "on " (ui/format-date (:t_encounter/date_time encounter)))}
       (div :.pt-2
            (ui/ui-checkbox
             {:label       "In relapse"
              :description "Is the patient having a clinical relapse or recovering from a relapse?"
              :checked     in_relapse
              :disabled    (not can-edit?)
              :onChange    (when can-edit #(m/set-value! this :form_ms_relapse/in_relapse %))}))))
     (ui/ui-simple-form-item
      {:label "Current multiple sclerosis disease course"}
      (ui/ui-select-popup-button
       {:value       (get ms-disease-course-by-id ms_disease_course_fk)
        :id-key      :t_ms_disease_course/id
        :options     all-ms-disease-courses
        :disabled?   (not can-edit?)
        :display-key :t_ms_disease_course/name
        :onChange    (when can-edit #(m/set-value! this :form_ms_relapse/ms_disease_course_fk (:t_ms_disease_course/id %)))}))
     (ui/ui-simple-form-item
      {:label "Current disease activity"}
      (ui/ui-select-popup-button
       {:value activity
        :options ["ACTIVE" "INACTIVE" "INDETERMINATE"]
        :disabled? (not can-edit?)
        :no-selection-string "NOT RECORDED"
        :onChange (when can-edit #(m/set-value! this :form_ms_relapse/activity %))}))
     (ui/ui-simple-form-item
      {:label "Current disease progression"}
      (ui/ui-select-popup-button
       {:value progression
        :options ["WITHOUT_PROGRESSION" "WITH_PROGRESSION"]
        :disabled? (not can-edit?)
        :no-selection-string "NOT RECORDED"
        :onChange (when can-edit #(m/set-value! this :form_ms_relapse/progression %))})))))

(def ui-edit-form-ms-relapse (comp/computed-factory EditFormMsRelapse))

(defsc EditFormWeightHeight
  [this {:form_weight_height/keys [weight_kilogram height_metres]
         :form/keys [encounter] :>/keys [can-edit layout] :as params}]
  {:ident         :form/id
   :route-segment ["encounter" :encounter-id "form_weight_height" :form/id]
   :query         [:form/id :form_weight_height/id
                   :form_weight_height/weight_kilogram :form_weight_height/height_metres
                   :form_weight_height/is_deleted
                   :form_weight_height/encounter_fk :form_weight_height/user_fk
                   {:form/encounter (comp/get-query FormEncounter)}
                   {:>/can-edit (comp/get-query CanEditForm)}
                   {:>/layout (comp/get-query Layout)}
                   fs/form-config-join]
   :form-fields   #{:form_weight_height/weight_kilogram :form_weight_height/height_metres}
   :will-enter    (fn [app {:keys [encounter-id] :form/keys [id] :as route-params}]
                    (let [form-id (parse-form-id id), encounter-id (js/parseInt encounter-id)]
                      (dr/route-deferred
                       [:form/id form-id]
                       (fn []
                         (comp/transact! app [(load-form {:form-id form-id :encounter-id encounter-id :class EditFormWeightHeight})])))))}
  (let [patient-age (when-let [period (:t_encounter/patient_age encounter)] (.-years ^goog.date.Period period))
        can-edit? (can-edit-form? can-edit)]
    (ui-layout
     layout {:can-edit can-edit? :save-params {:form (select-keys params [:form/id :form_weight_height/id :form_weight_height/is_deleted
                                                                          :form_weight_height/weight_kilogram :form_weight_height/height_metres
                                                                          :form_weight_height/encounter_fk :form_weight_height/user_fk])}}
     (comp/fragment
      (ui/ui-simple-form-item
       {:label "Weight (kilograms)"}
       (ui/ui-textfield {:value    (str weight_kilogram)
                         :disabled (not can-edit?)
                         :type     :number
                         :onChange #(m/set-value! this :form_weight_height/weight_kilogram (when-not (str/blank? %) (Big. %)))}))
      (ui/ui-simple-form-item
       {:label "Height (metres)"}
       (ui/ui-textfield {:value    (str height_metres)
                         :disabled (not can-edit?)
                         :type     :number
                         :onChange #(m/set-value! this :form_weight_height/height_metres (when-not (str/blank? %) (Big. %)))}))
      (when (and patient-age (>= patient-age 18))  ;; only show BMI for adults
        (ui/ui-simple-form-item
         {:label "Body mass index (adult) "}
         (div :.mt-2.text-gray-600.italic
              (when (and weight_kilogram height_metres)
                (str (.round (.div ^Big weight_kilogram (.pow height_metres 2)) 1) " kg/m²")))))))))

(def ui-edit-form-weight-height (comp/computed-factory EditFormWeightHeight))

(defsc EditFormSmoking
  [this {:form_smoking/keys [status current_cigarettes_per_day duration_years previous_cigarettes_per_day previous_duration_years year_gave_up]
         :form/keys [encounter] :>/keys [can-edit layout] :as params}]
  {:ident         :form/id
   :route-segment ["encounter" :encounter-id "form_smoking" :form/id]
   :query         [:form/id :form_smoking/id
                   :form_smoking/status :form_smoking/current_cigarettes_per_day :form_smoking/duration_years
                   :form_smoking/previous_duration_years :form_smoking/previous_cigarettes_per_day :form_smoking/year_gave_up

                   :form_smoking/is_deleted
                   :form_smoking/encounter_fk :form_smoking/user_fk
                   {:form/encounter (comp/get-query FormEncounter)}
                   {:>/can-edit (comp/get-query CanEditForm)}
                   {:>/layout (comp/get-query Layout)}
                   fs/form-config-join]
   :form-fields   #{:form_smoking/status :form_smoking/current_cigarettes_per_day :form_smoking/duration_years
                    :form_smoking/previous_cigarettes_per_day :form_smoking/previous_duration_years :form_smoking/year_gave_up}
   :will-enter    (fn [app {:keys [encounter-id] :form/keys [id] :as route-params}]
                    (let [form-id (parse-form-id id), encounter-id (js/parseInt encounter-id)]
                      (dr/route-deferred
                       [:form/id form-id]
                       (fn []
                         (comp/transact! app [(load-form {:form-id form-id :encounter-id encounter-id :class EditFormSmoking})])))))}
  (let [can-edit? (can-edit-form? can-edit)]
    (ui-layout
     layout {:can-edit can-edit? :save-params {:form (select-keys params [:form/id :form_smoking/id :form_smoking/is_deleted
                                                                          :form_smoking/status :form_smoking/current_cigarettes_per_day :form_smoking/duration_years
                                                                          :form_smoking/previous_duration_years :form_smoking/previous_duration_years :form_smoking/year_gave_up
                                                                          :form_smoking/encounter_fk :form_smoking/user_fk])}}
     (comp/fragment
      (ui/ui-simple-form-item
       {:label "Smoking status"})
      (ui/ui-simple-form-item
       {:label "Height (metres)"})))))
