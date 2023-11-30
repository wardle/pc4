(ns pc4.patient.neuroinflamm
  (:require [clojure.string :as str]
            [pc4.dates :as dates]
            [pc4.patient.home :as patient]
            [pc4.patient.banner :as banner]
            [pc4.server :as server]
            [pc4.events :as events]
            [pc4.subs :as subs]
            [pc4.ui :as ui]
            [reagent.core :as r]
            [re-frame.core :as rf]))


(defn inspect-edit-lsoa
  "A specialised inspect/edit component to view and edit the LSOA for a
  pseudonymous patient."
  [params]
  (let [mode (r/atom :inspect)
        postcode (r/atom "")]
    (fn [{:keys [value on-change]}]
      (let [save-fn #(do (on-change @postcode) (reset! mode :inspect))
            edit-fn #(do (reset! postcode "") (reset! mode :edit))
            on-change #(reset! postcode %)]
        (case @mode
          :inspect
          [:a.cursor-pointer.underline
           {:class    (if (str/blank? value) "text-red-600 hover:text-red-800" "text-red-600.hover:text-red-800")
            :on-click edit-fn} (if (str/blank? value) "Not set" value)]
          :edit
          [:span
           [:p (str @postcode)]
           [ui/ui-textfield
            {:id        :postcode, :value @postcode :auto-focus true,
             :label     "Enter postal code:", :on-change on-change, :on-enter save-fn
             :help-text "This postal code will not be stored but mapped to a larger geographical region instead."}]
           [:button.bg-red-500.hover:bg-red-700.text-white.text-xs.py-1.px-2.rounded-full
            {:on-click save-fn} "Save"]
           [:button.bg-blue-500.hover:bg-blue-700.text-white.text-xs.py-1.px-2.rounded
            {:on-click #(do (reset! mode :inspect) (reset! postcode ""))}
            "Cancel"]])))))

(defn inspect-edit-death-certificate
  "An inspect/edit component to edit date of death and cause of death."
  [_ _]
  (let [mode (reagent.core/atom :inspect)
        data (reagent.core/atom {})]
    (fn [patient {:keys [on-save]}]
      (let [date_death (:t_patient/date_death patient)
            certificate (:t_patient/death_certificate patient)]
        (case @mode
          :inspect [:<>
                    [:p (if-not date_death
                          "Alive"
                          [:<> [:span "Died " (dates/format-date date_death)]
                           [:ul.mt-4.ml-4
                            (when (:t_death_certificate/part1a certificate) [:li [:strong "1a: "] (:t_death_certificate/part1a certificate)])
                            (when (:t_death_certificate/part1b certificate) [:li [:strong "1b: "] (:t_death_certificate/part1b certificate)])
                            (when (:t_death_certificate/part1c certificate) [:li [:strong "1c: "] (:t_death_certificate/part1c certificate)])
                            (when (:t_death_certificate/part2 certificate) [:li [:strong "2: "] (:t_death_certificate/part2 certificate)])]])]
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.text-xs.py-1.px-2.rounded.mt-4
                     {:on-click #(do (reset! data (merge (select-keys patient [:t_patient/id :t_patient/patient_identifier :t_patient/date_death])
                                                         (:t_patient/death_certificate patient)))
                                     (reset! mode :edit))} "Edit"]]
          :edit [:<>
                 [ui/ui-simple-form-item {:label "Date death"}
                  [ui/ui-local-date
                   {:name      "date-death" :value (:t_patient/date_death @data)
                    :on-change #(do (when-not %
                                      (swap! data assoc :t_death_certificate/part1a ""))
                                    (swap! data assoc :t_patient/date_death %))}]]
                 [ui/ui-simple-form-item {:label "Cause of death"}
                  [ui/ui-textfield
                   {:value     (:t_death_certificate/part1a @data)
                    :name      "1a" :disabled (not (:t_patient/date_death @data))
                    :on-change #(swap! data assoc :t_death_certificate/part1a %)}]]
                 [:button.bg-red-500.hover:bg-red-700.text-white.text-xs.py-1.px-2.rounded-full
                  {:on-click #(do (reset! mode :inspect)
                                  (tap> {:death-data @data})
                                  (on-save @data))}
                  "Save"]
                 [:button.bg-blue-500.hover:bg-blue-700.text-white.text-xs.py-1.px-2.rounded
                  {:on-click #(do (reset! mode :inspect) (reset! data {}))}
                  "Cancel"]])))))

(def neuroinflamm-page
  "This is a bespoke page that acts as the main page for the current
  https://patientcare.app application for use in neuro-inflammatory disease.
  As pc4 evolves, this will not be needed, as that functionality will be
  available as determined by runtime configuration (ie: combination of project
  and patient types together with knowledge of diagnoses of project and patient."
  {:query
   (fn [params]
     [{(patient/patient-ident params)
       (conj banner/banner-query
             {:t_patient/summary_multiple_sclerosis [:t_summary_multiple_sclerosis/id
                                                     {:t_summary_multiple_sclerosis/ms_diagnosis [:t_ms_diagnosis/id
                                                                                                  :t_ms_diagnosis/name]}]}
             {:t_patient/death_certificate [:t_death_certificate/id
                                            :t_death_certificate/part1a]})}
      {:com.eldrix.rsdb/all-ms-diagnoses [:t_ms_diagnosis/name :t_ms_diagnosis/id]}])

   :view
   (fn [_ [{project-id      :t_episode/project_fk
            :t_patient/keys [patient_identifier] :as patient}
           all-ms-diagnoses]]
     (let [select-diagnosis-fn #(rf/dispatch [::server/load ;; the result will be automatically normalised and therefore update
                                              {:id    ::save-ms-diagnosis
                                               :query [{(list 'pc4.rsdb/save-ms-diagnosis {:t_patient/patient_identifier patient_identifier
                                                                                           :t_ms_diagnosis/id            (:t_ms_diagnosis/id %)})
                                                        ['*]}]}])
           save-lsoa-fn #(do (println "Setting LSOA to " %)
                             (rf/dispatch [:server/load
                                           {:id    ::save-postal-code
                                            :query [{(list 'pc4.rsdb/save-pseudonymous-patient-postal-code
                                                           {:t_patient/patient_identifier patient_identifier
                                                            :uk.gov.ons.nhspd/PCD2        %})
                                                     [:t_address/id :t_address/lsoa]}]}]))]
       [patient/layout {:t_project/id project-id} patient {:selected-id :home}
        [ui/ui-title {:title "Neuroinflammatory home page"}]
        [ui/ui-simple-form
         [ui/ui-simple-form-item {:label "Neuro-inflammatory diagnosis"}
          [ui/ui-select
           {:name                :ms-diagnosis
            :value               (get-in patient [:t_patient/summary_multiple_sclerosis
                                                  :t_summary_multiple_sclerosis/ms_diagnosis])
            :disabled?           false
            :choices             all-ms-diagnoses
            :no-selection-string "=Choose diagnosis="
            :id-key              :t_ms_diagnosis/id
            :display-key         :t_ms_diagnosis/name
            :on-select           select-diagnosis-fn}]]
         [ui/ui-simple-form-item {:label "LSOA (Geography)"}
          [inspect-edit-lsoa
           {:value     (get-in patient [:t_patient/address :t_address/lsoa])
            :on-change save-lsoa-fn}]]
         [ui/ui-simple-form-item {:label "Vital status"}
          [inspect-edit-death-certificate patient
           {:on-save #(do (println "updating death certificate" %)
                          (rf/dispatch
                            [:server/load
                             {:id    ::notify-death
                              :query [{(list 'pc4.rsdb/notify-death
                                             (merge {:t_patient/patient_identifier patient_identifier, :t_patient/date_death nil} %))
                                       [:t_patient/id
                                        :t_patient/date_death
                                        {:t_patient/death_certificate [:t_death_certificate/id
                                                                       :t_death_certificate/part1a]}]}]}]))}]]]]))})

(def relapse-headings
  [{:s "Date"} {:s "Type"} {:s "Impact"}
   {:s "UK" :title "Unknown"}
   {:s "UE" :title "Upper extremity (arm motor)"}
   {:s "LE" :title "Lower extremity (leg motor)"}
   {:s "SS" :title "Limb sensory"}
   {:s "SP" :title "Sphincter"}
   {:s "SX" :title "Sexual"}
   {:s "FM" :title "Face motor"}
   {:s "FS" :title "Face sensory"}
   {:s "OM" :title "Oculomotor (diplopia)"}
   {:s "VE" :title "Vestibular"}
   {:s "BB" :title "Bulbar"}
   {:s "CB" :title "Cerebellar (ataxia)"}
   {:s "ON" :title "Optic nerve"}
   {:s "PS" :title "Psychiatric"}
   {:s "OT" :title "Other"}
   {:s "MT" :title "Cognitive"}
   {}])

(defn relapses-table [events]
  [ui/ui-table
   [ui/ui-table-head
    [ui/ui-table-row
     (for [{:keys [s title]} relapse-headings]
       ^{:key s} [ui/ui-table-heading (if title {:title title} {}) s])]]
   [ui/ui-table-body
    (for [{:t_ms_event/keys [id date type impact site_unknown site_arm_motor site_leg_motor site_limb_sensory
                             site_sphincter site_sexual site_face_motor site_face_sensory site_diplopia
                             site_vestibular site_bulbar site_ataxia site_optic_nerve site_psychiatric
                             site_other site_cognitive], :as event} events]
      [ui/ui-table-row
       {:key id}
       [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date)]
       [ui/ui-table-cell {} (:t_ms_event_type/abbreviation type)]
       [ui/ui-table-cell {} impact]
       [ui/ui-table-cell {} (when site_unknown "UK")]
       [ui/ui-table-cell {} (when site_arm_motor "UE")]
       [ui/ui-table-cell {} (when site_leg_motor "LE")]
       [ui/ui-table-cell {} (when site_limb_sensory "SS")]
       [ui/ui-table-cell {} (when site_sphincter "SP")]
       [ui/ui-table-cell {} (when site_sexual "SX")]
       [ui/ui-table-cell {} (when site_face_motor "FM")]
       [ui/ui-table-cell {} (when site_face_sensory "FS")]
       [ui/ui-table-cell {} (when site_diplopia "OM")]
       [ui/ui-table-cell {} (when site_vestibular "VE")]
       [ui/ui-table-cell {} (when site_bulbar "BB")]
       [ui/ui-table-cell {} (when site_ataxia "CB")]
       [ui/ui-table-cell {} (when site_optic_nerve "ON")]
       [ui/ui-table-cell {} (when site_psychiatric "PS")]
       [ui/ui-table-cell {} (when site_other "OT")]
       [ui/ui-table-cell {} (when site_cognitive "MT")]
       [ui/ui-table-cell {}
        (ui/ui-table-link {:on-click #(rf/dispatch [::events/modal :relapses event])} "Edit")]])]])

(def impact-choices ["UNKNOWN" "NON_DISABLING" "DISABLING" "SEVERE"])

(defn ms-event-site-to-string [k]
  (str/capitalize (str/join " " (rest (str/split (name k) #"_")))))

(def event-properties
  [:t_ms_event/id :t_ms_event/summary_multiple_sclerosis_fk
   :t_ms_event/date :t_ms_event/impact :t_ms_event/is_relapse
   :t_ms_event/site_arm_motor :t_ms_event/site_ataxia :t_ms_event/site_bulbar
   :t_ms_event/site_cognitive :t_ms_event/site_diplopia :t_ms_event/site_face_motor
   :t_ms_event/site_face_sensory :t_ms_event/site_leg_motor :t_ms_event/site_limb_sensory
   :t_ms_event/site_optic_nerve :t_ms_event/site_other :t_ms_event/site_psychiatric
   :t_ms_event/site_sexual :t_ms_event/site_sphincter :t_ms_event/site_unknown
   :t_ms_event/site_vestibular {:t_ms_event/type [:t_ms_event_type/id :t_ms_event_type/name
                                                  :t_ms_event_type/abbreviation]}])

(defn save-event [patient-identifier event {:keys [on-success]}]
  (rf/dispatch
    [::server/load
     {:id         ::save-event                              ;; take care here to return all events
      :query      [{(list 'pc4.rsdb/save-ms-event
                          (assoc event :t_patient/patient_identifier patient-identifier
                                       :t_ms_event_type/id (get-in event [:t_ms_event/type :t_ms_event_type/id])))
                    [:t_ms_event/id
                     {:t_ms_event/summary_multiple_sclerosis
                      [:t_summary_multiple_sclerosis/id
                       {:t_summary_multiple_sclerosis/events event-properties}]}]}]
      :failed?    (fn [response] (get-in response ['pc4.rsdb/save-ms-event :com.wsscode.pathom3.connect.runner/mutation-error]))
      :on-success on-success}]))

(defn delete-event [event {:keys [on-success]}]
  (rf/dispatch
    [::server/load
     {:id ::delete-event
      :query [{(list 'pc4.rsdb/delete-ms-event event)
               [{:t_ms_event/summary_multiple_sclerosis
                 [{:t_summary_multiple_sclerosis/events event-properties}]}]}]}]))

(defn edit-ms-event
  [event all-ms-event-types {:keys [on-change]}]
  (let [{:t_ms_event/keys [id date impact type notes]} event]
    [ui/ui-simple-form
     [ui/ui-simple-form-title {:title (if id "Edit relapse / disease event" "Add relapse / disease event")}]
     [ui/ui-simple-form-item {:label "Date"}
      [ui/ui-local-date {:value date, :on-change #(on-change (assoc event :t_ms_event/date %))}]]
     [ui/ui-simple-form-item {:label "Type"}
      [ui/ui-select {:value       type, :choices all-ms-event-types, :sort? false
                     :default-value (first all-ms-event-types)
                     :display-key (fn [{:t_ms_event_type/keys [abbreviation name]}]
                                    (str abbreviation ": " name))
                     :on-select   #(on-change (assoc event :t_ms_event/type %))}]]
     [ui/ui-simple-form-item {:label "Impact"}
      [ui/ui-select {:value         impact, :choices impact-choices, :sort? false
                     :default-value "UNKNOWN"
                     :on-select     #(on-change (assoc event :t_ms_event/impact %))}]]
     [ui/ui-simple-form-item {:label "Site"}
      [:div.columns-1.sm:columns-2.md:columns-3.lg:columns-4
       (ui/ui-multiple-checkboxes
         {:value     event, :display-key ms-event-site-to-string
          :keys      [:t_ms_event/site_unknown :t_ms_event/site_arm_motor :t_ms_event/site_leg_motor
                      :t_ms_event/site_limb_sensory :t_ms_event/site_sphincter :t_ms_event/site_sexual
                      :t_ms_event/site_face_motor :t_ms_event/site_face_sensory
                      :t_ms_event/site_diplopia :t_ms_event/site_vestibular :t_ms_event/site_bulbar
                      :t_ms_event/site_ataxia :t_ms_event/site_optic_nerve :t_ms_event/site_psychiatric
                      :t_ms_event/site_other :t_ms_event/site_cognitive]
          :on-change on-change})]]
     [ui/ui-simple-form-item {:label "Notes"}
      [ui/ui-textarea {:value     notes
                       :on-change #(on-change (assoc event :t_ms_event/notes %))}]]]))
(def relapses-page
  {:query
   (fn [params]
     [{(patient/patient-ident params)
       (conj banner/banner-query
             {:t_patient/summary_multiple_sclerosis
              [:t_summary_multiple_sclerosis/id
               {:t_summary_multiple_sclerosis/events
                event-properties}]})}
      {:com.eldrix.rsdb/all-ms-event-types [:t_ms_event_type/id
                                            :t_ms_event_type/name
                                            :t_ms_event_type/abbreviation]}])
   :view
   (fn [_ [{project-id :t_episode/project_fk, patient-identifier :t_patient/patient_identifier :as patient} ms-event-types]]
     (tap> patient)
     (let [sms (:t_patient/summary_multiple_sclerosis patient)
           relapses (:t_summary_multiple_sclerosis/events sms)
           editing-event @(rf/subscribe [::subs/modal :relapses])]
       [patient/layout {:t_project/id project-id} patient
        {:selected-id :relapses
         :sub-menu    {:items [{:id      :add-ms-event
                                :content [ui/menu-button {:on-click #(rf/dispatch [::events/modal :relapses {:t_ms_event/summary_multiple_sclerosis_fk (:t_summary_multiple_sclerosis/id sms)}])} "Add event"]}]}}
        (if-not sms
          [ui/box-error-message {:title "No neuro-inflammatory diagnosis recorded" :message "You must record a neuro-inflammatory diagnosis before recording events"}]
          [:<>
           (when editing-event
             (tap> {:editing-event editing-event})
             [ui/ui-modal
              {:on-close #(rf/dispatch [::events/modal :relapses nil])
               :actions  [{:id       :save, :title "Save", :role :primary
                           :on-click #(save-event patient-identifier editing-event {:on-success [:pc4.events/modal :relapses nil]})}
                          {:id       :delete, :hidden? (not (:t_ms_event/id editing-event))
                           :title    "Delete"
                           :on-click #(delete-event editing-event {:on-success [:pc4.events/modal :relapses nil]})}
                          {:id       :cancel, :title "Cancel"
                           :on-click #(rf/dispatch [::events/modal :relapses nil])}]}

              (edit-ms-event editing-event ms-event-types {:on-change #(rf/dispatch-sync [::events/modal :relapses %])})])
           [relapses-table (sort-by #(-> % :t_ms_event/date .valueOf ) relapses)]])]))})
