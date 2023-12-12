(ns pc4.patient.encounters
  (:require [clojure.string :as str]
            ["big.js" :as Big]
            [pc4.dates :as dates]
            [pc4.events :as events]
            [pc4.patient.banner :as banner]
            [pc4.patient.home :as patient]
            [pc4.ui :as ui]
            [re-frame.core :as rf]
            [reitit.frontend.easy :as rfe]
            [taoensso.timbre :as log]))


(def neuro-inflammatory
  [{:t_encounter/form_edss
    [:t_form_edss/score :t_form_edss_fs/score]}
   {:t_encounter/form_ms_relapse
    [:t_form_ms_relapse/in_relapse
     {:t_form_ms_relapse/ms_disease_course [:t_ms_disease_course/name]}]}
   {:t_encounter/form_weight_height
    [:t_form_weight_height/weight_kilogram]}])

(def encounters-page
  {:tx (fn [{:keys [query] :as params}]
         (let [optional-headings neuro-inflammatory]
           [{(patient/patient-ident params)
             (conj banner/banner-query
                   {:t_patient/encounters
                    (into [:t_encounter/id :t_encounter/date_time
                           :t_encounter/active :t_encounter/is_deleted
                           {:t_encounter/encounter_template
                            [:t_encounter_template/id
                             :t_encounter_template/title]}]
                          optional-headings)})}]))
   :view
   (fn [_ctx [{project-id :t_episode/project_fk, patient-pk :t_patient/id :t_patient/keys [patient_identifier encounters] :as patient}]]
     [patient/layout {:t_project/id project-id} patient
      {:selected-id :encounters
       :sub-menu
       {:items [{:id      :add-encounter
                 :content [ui/menu-button {:on-click #(rf/dispatch
                                                        [::events/push-state :patient/encounter {:patient-identifier patient_identifier
                                                                                                 :encounter-id       0}])} "Add encounter"]}]}}
      (when encounters
        [ui/ui-table
         [ui/ui-table-head
          [ui/ui-table-row
           (for [{:keys [id title]} [{:id :date :title "Date"} {:id :type :title "Type"}
                                     {:id :edss :title "EDSS"} {:id :disease-course :title "Disease course"}
                                     {:id :in-relapse :title "In relapse?"} {:id :weight :title "Weight"}
                                     {:id :actions :title ""}]]
             ^{:key id} [ui/ui-table-heading {} title])]]
         [ui/ui-table-body
          (for [{:t_encounter/keys [id date_time encounter_template form_edss form_ms_relapse form_weight_height] :as encounter}
                (->> encounters
                     (filter :t_encounter/active))]
            [ui/ui-table-row {:key id}
             [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_time)]
             [ui/ui-table-cell {} (:t_encounter_template/title encounter_template)]
             [ui/ui-table-cell {} (str (or (:t_form_edss_fs/score form_edss) (:t_form_edss/score form_edss)))]
             [ui/ui-table-cell {} (get-in form_ms_relapse [:t_form_ms_relapse/ms_disease_course :t_ms_disease_course/name])]
             [ui/ui-table-cell {} (case (:t_form_ms_relapse/in_relapse form_ms_relapse) true "Yes" false "No" "")]
             [ui/ui-table-cell {} (when-let [wt (:t_form_weight_height/weight_kilogram form_weight_height)]
                                    (str wt "kg"))]
             [ui/ui-table-cell {} (ui/ui-table-link
                                    {:href (rfe/href :patient/encounter {:patient-identifier patient_identifier :encounter-id id})}
                                    "Edit")]])]])])})

(defn menuitem [& content]
  (into [:span.truncate] content))

(defn menu
  [patient encounter {:keys [selected-id sub-menu]}]
  [ui/vertical-navigation
   {:selected-id selected-id
    :items       []
    :sub-menu    sub-menu}])

(defn layout
  [patient {:t_encounter/keys [id is_deleted date_time] :as encounter} {:keys [sub-menu]} & content]
  [:div.grid.grid-cols-1.md:grid-cols-6
   [:div.col-span-1.p-2
    (when (and date_time (:t_encounter/encounter_template encounter))
      [:div.shadow.bg-gray-50
       [:div.pl-4.font-semibold.bg-gray-200.text-center.italic.text-gray-600
        (dates/format-date date_time)]
       [:div.text-sm.p-2.pt-4.text-gray-600.italic.text-center {:style {:text-wrap "pretty"}}
        (get-in encounter [:t_encounter/encounter_template :t_encounter_template/project :t_project/title])]
       [:div.font-bold.text-lg.min-w-min.p-4.pt-0.text-center
        (get-in encounter [:t_encounter/encounter_template :t_encounter_template/title])]])
    (when is_deleted
      [:div.mt-4.font-bold.text-center.bg-red-100.p-4.border.border-red-600.rounded
       "Warning: this encounter has been deleted"])
    [:div.mt-2
     (menu patient encounter {:selected-id :home :sub-menu sub-menu})]]
   (into [:div.col-span-5.p-6.pt-2] content)])

(def edss-scores
  ["SCORE0_0" "SCORE1_0" "SCORE1_5" "SCORE2_0" "SCORE2_5" "SCORE3_0" "SCORE3_5"
   "SCORE4_0" "SCORE4_5" "SCORE5_0" "SCORE5_5" "SCORE6_0" "SCORE6_5" "SCORE7_0"
   "SCORE7_5" "SCORE8_0" "SCORE8_5" "SCORE9_0" "SCORE9_5" "SCORE10_0"
   "SCORE_LESS_THAN_4"])

(def smoking-status-choices #{"NEVER_SMOKED" "CURRENT_SMOKER" "EX_SMOKER"})


(defn select-encounter-template
  [encounter all-encounter-templates {:keys [on-change]}]
  [ui/ui-select
   {:name        "encounter-template"
    :value       encounter
    :choices     (remove :t_encounter_template/is_deleted all-encounter-templates)
    :sort?       true
    :on-select   #(on-change (assoc encounter :t_encounter/encounter_template_fk %))
    :id-key      :t_encounter_template/id
    :display-key #(some-> % :t_encounter_template/title)}])

(defn form-edss
  [{:t_encounter/keys [form_edss form_ms_relapse] :as encounter} {:keys [key on-change]}]
  [ui/ui-simple-form-item {:html-for key :label "EDSS"}
   [:div.space-y-4
    [ui/ui-select
     {:name                (or key "edss")
      :sort?               false
      :choices             edss-scores
      :no-selection-string "Not recorded"
      :value               (:t_form_edss/edss_score form_edss)
      :on-select           #(on-change
                              (if %
                                (assoc-in encounter [:t_encounter/form_edss :t_form_edss/edss_score] %)
                                (assoc encounter :t_encounter/form_edss nil)))}]
    [ui/ui-checkbox
     {:name        (or key "in-relapse")
      :label       "In relapse?"
      :description "Tick if EDSS recorded when patient in relapse."
      :checked     (or (:t_form_ms_relapse/in_relapse form_ms_relapse) false)
      :on-change   #(on-change (assoc-in encounter [:t_encounter/form_ms_relapse :t_form_ms_relapse/in_relapse] %))}]]])

(defn form-ms-disease-course [encounter all-ms-disease-courses {:keys [on-change]}]
  [ui/ui-simple-form-item {:label "Disease course"}
   [ui/ui-select
    {:name          "disease-course"
     :sort?         true
     :choices       all-ms-disease-courses
     :default-value (first (filter #(= 1 (:t_ms_disease_course/id %)) all-ms-disease-courses))
     :display-key   :t_ms_disease_course/name
     :value         (get-in encounter [:t_encounter/form_ms_relapse :t_form_ms_relapse/ms_disease_course])
     :on-select     #(on-change (-> encounter
                                    (assoc-in [:t_encounter/form_ms_relapse :t_form_ms_relapse/ms_disease_course_fk] (:t_form_ms_relapse/id %))
                                    (assoc-in [:t_encounter/form_ms_relapse :t_form_ms_relapse/ms_disease_course] %)))}]])

(defn form-weight-height [encounter {:keys [on-change]}]
  [:<>
   [ui/ui-simple-form-item {:label "Weight (kg)"}
    [ui/ui-textfield
     {:value     (str (get-in encounter [:t_encounter/form_weight_height :t_form_weight_height/weight_kilogram]))
      :name      "weight"
      :type      "number"
      :on-change #(on-change (assoc-in encounter [:t_encounter/form_weight_height :t_form_weight_height/weight_kilogram] (some-> % (Big.))))}]]
   [ui/ui-simple-form-item {:label "Height (m)"}
    [ui/ui-textfield
     {:value     (str (get-in encounter [:t_encounter/form_weight_height :t_form_weight_height/height_metres]))
      :name      "height"
      :type      "number"
      :min       "0"
      :max       "2"
      :on-change #(on-change (assoc-in encounter [:t_encounter/form_weight_height :t_form_weight_height/height_metres] (some-> % (Big.))))}]]])

(defn form-smoking
  [encounter {:keys [on-change]}]
  [:<>
   [ui/ui-simple-form-item {:label "Smoking"}
    [ui/ui-select
     {:name                "smoking"
      :sort?               true
      :choices             smoking-status-choices
      :no-selection-string "Not recorded"
      :value               (get-in encounter [:t_encounter/form_smoking_history :t_smoking_history/status])
      :on-select           #(on-change
                              (cond-> (assoc-in encounter [:t_encounter/form_smoking_history :t_smoking_history/status] %)
                                      (and % (nil? (get-in encounter [:t_encounter/form_smoking_history :t_smoking_history/current_cigarettes_per_day])))
                                      (assoc-in [:t_encounter/form_smoking_history :t_smoking_history/current_cigarettes_per_day] 0)
                                      (nil? %)
                                      (update :t_encounter/form_smoking_history dissoc :t_smoking_history/status :t_smoking_history/current_cigarettes_per_day)
                                      (= "NEVER_SMOKED" %)
                                      (assoc-in [:t_encounter/form_smoking_history :t_smoking_history/current_cigarettes_per_day] 0)))}]]

   [ui/ui-simple-form-item {:label "Cigarettes per day"}
    [ui/ui-textfield
     {:value     (str (get-in encounter [:t_encounter/form_smoking_history :t_smoking_history/current_cigarettes_per_day]))
      :name      "cigarettes"
      :type      "number"
      :min       0
      :max       200
      :on-change #(on-change (assoc-in encounter [:t_encounter/form_smoking_history :t_smoking_history/current_cigarettes_per_day] (parse-long (or % ""))))}]]])

(defn push-encounter
  [encounter]
  (rf/dispatch-sync [::events/local-push {:data encounter}]))

(defn save-encounter
  [patient-identifier encounter]
  (tap> {:save-encounter encounter})
  (rf/dispatch [::events/remote {:id            ::save-encounter
                                 :tx            [(list 'pc4.rsdb/save-encounter
                                                       (-> encounter
                                                           (assoc :t_patient/patient_identifier patient-identifier
                                                                  :t_encounter/encounter_template_fk (get-in encounter [:t_encounter/encounter_template :t_encounter_template/id]))
                                                           (dissoc :t_encounter/encounter_template)))]
                                 :on-success-fx [[:dispatch [::events/navigate-back]]]}]))

(defn delete-encounter
  [patient-identifier encounter]                            ;; TODO: delete all of the forms locally?
  (rf/dispatch [::events/remote {:id            ::delete-encounter
                                 :tx            [(list 'pc4.rsdb/delete-encounter (assoc encounter :t_patient/patient_identifier patient-identifier))]
                                 :on-success-fx [[:dispatch [::events/local-delete [:t_encounter/id (:t_encounter/id encounter)]]]
                                                 [:dispatch [::events/navigate-back]]]}]))


(def encounter-page
  {:tx
   (fn [params]
     [{(patient/patient-ident params)
       banner/banner-query}
      {[:t_encounter/id (get-in params [:path :encounter-id])]
       [:t_encounter/id :t_encounter/patient_fk :t_encounter/date_time :t_encounter/is_deleted
        :t_encounter/notes
        {:t_encounter/encounter_template [:t_encounter_template/id :t_encounter_template/title]}
        {:t_encounter/form_edss [:t_form_edss/edss_score]}
        {:t_encounter/form_smoking_history                  ;; TODO: rename t_smoking_history to t_form_smoking_history?
         [:t_smoking_history/status
          :t_smoking_history/current_cigarettes_per_day]}
        {:t_encounter/form_weight_height
         [:t_form_weight_height/weight_kilogram
          :t_form_weight_height/height_metres]}
        {:t_encounter/form_ms_relapse
         [:t_form_ms_relapse/in_relapse
          {:t_form_ms_relapse/ms_disease_course
           [:t_ms_disease_course/id
            :t_ms_disease_course/name]}]}]}
      {:com.eldrix.rsdb/all-ms-disease-courses
       [:t_ms_disease_course/id :t_ms_disease_course/name]}])
   :view
   (fn [ctx [{patient-pk :t_patient/id :t_patient/keys [patient_identifier] :as patient}
             {:t_encounter/keys [id patient_fk date_time is_deleted encounter_template] :as encounter}
             all-ms-disease-courses]]
     (when-not (:loading ctx)
       (if (and (pos-int? id) (not= patient-pk patient_fk)) ;; check existing encounter is for given patient
         [ui/box-error-message "Invalid access" "Access not permitted"]
         [:<>
          [banner/rsdb-banner patient]
          [layout patient encounter
           {:sub-menu {:items [{:id      :save-changes
                                :content [ui/menu-button {:role     :primary
                                                          :on-click #(save-encounter patient_identifier encounter)} "Save changes"]}
                               (if is_deleted
                                 {:id      :undelete-encounter
                                  :content [ui/menu-button {:on-click #(log/debug "Undelete clicked")} "Undelete"]}
                                 {:id      :delete-encounter
                                  :content [ui/menu-button {:on-click #(delete-encounter patient_identifier encounter)} "Delete"]})
                               {:id      :cancel
                                :content [ui/menu-button {:on-click #(rf/dispatch [::events/navigate-back])} "Cancel"]}]}}

           [ui/ui-panel
            [ui/ui-simple-form-item {:label "Date of encounter"}
             [ui/ui-local-date-time
              {:value date_time
               :on-change #(push-encounter (assoc encounter :t_encounter/date_time %))}]]
            [ui/ui-simple-form-item {:label "Encounter type"}
             [ui/ui-select
              {:value (:t_encounter/encounter_template encounter)
               :choices []}]]]
           [ui/ui-panel
            [form-edss encounter {:on-change push-encounter}]
            [form-ms-disease-course encounter all-ms-disease-courses {:on-change push-encounter}]
            [form-weight-height encounter {:on-change push-encounter}]
            [form-smoking encounter {:on-change push-encounter}]]
           [ui/ui-panel
            [ui/ui-simple-form-item {:label "Notes"}
             [ui/ui-textarea {:value     (:t_encounter/notes encounter)
                              :on-change #(push-encounter (assoc encounter :t_encounter/notes %))}]]]]])))})


(comment
  (rf/dispatch [::events/remote {:id :test
                                 :tx [{[:t_patient/patient_identifier 14032]
                                       [:t_patient/id :t_patient/nhs_number]}]}]))