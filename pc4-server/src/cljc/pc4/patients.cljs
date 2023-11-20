(ns pc4.patients
  (:require [clojure.string :as str]
            [com.eldrix.pc4.commons.dates :as dates]
            [eldrix.pc4-ward.patient.events :as patient.events]
            [pc4.ui.misc :as ui]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [reagent.core :as r]
            [reitit.frontend.easy :as rfe])
  (:import (goog.date Date)))




(def ^:deprecated
  core-patient-properties                                   ;; TODO: these properties to be generic properties not rsdb.
  [:t_patient/id
   :t_patient/patient_identifier
   :t_patient/nhs_number
   :t_patient/first_names
   :t_patient/last_name
   :t_patient/sex
   :t_patient/date_birth
   :t_patient/status
   :t_patient/date_death
   :t_patient/death_certificate
   {:t_patient/address [:uk.gov.ons.nhspd/LSOA11
                        :t_address/lsoa]}])

(def ^:deprecated patient-diagnosis-properties
  [:t_diagnosis/id
   :t_diagnosis/date_onset
   :t_diagnosis/date_diagnosis
   :t_diagnosis/date_to
   :t_diagnosis/status
   :t_diagnosis/date_onset_accuracy
   :t_diagnosis/date_diagnosis_accuracy
   :t_diagnosis/date_to_accuracy
   {:t_diagnosis/diagnosis [:info.snomed.Concept/id
                            :info.snomed.Concept/preferredDescription
                            :info.snomed.Concept/parentRelationshipIds]}])

(def ^:deprecated patient-medication-properties
  [:t_medication/id
   :t_medication/date_from
   :t_medication/date_to
   :t_medication/reason_for_stopping
   :t_medication/more_information
   {:t_medication/events [:t_medication_event/id
                          :t_medication_event/type
                          :t_medication_event/reaction_date_time
                          {:t_medication_event/event_concept [:info.snomed.Concept/id
                                                              {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}
   {:t_medication/medication [:info.snomed.Concept/id
                              :info.snomed.Concept/preferredDescription
                              :info.snomed.Concept/parentRelationshipIds]}])


(def ^:deprecated full-patient-properties
  ;; at the moment we download all of the data in one go - this should be replaced by
  ;; fetches that are made when a specific panel is opened. Simpler, more current information
  ;; TODO: break up into modules instead for specific purposes
  (into
    core-patient-properties
    [{:t_patient/diagnoses patient-diagnosis-properties}
     {:t_patient/medications patient-medication-properties}
     {:t_patient/encounters [:t_encounter/id
                             :t_encounter/episode_fk
                             :t_encounter/date_time
                             :t_encounter_template/id
                             {:t_encounter/encounter_template [:t_encounter_template/title :t_encounter_template/id]}
                             :t_encounter/is_deleted
                             :t_encounter/active
                             :t_encounter/form_edss
                             :t_encounter/form_ms_relapse
                             :t_encounter/form_weight_height
                             :t_encounter/form_smoking_history]}
     {:t_patient/summary_multiple_sclerosis [:t_summary_multiple_sclerosis/id
                                             :t_summary_multiple_sclerosis/events
                                             :t_ms_diagnosis/id ; we flatten this to-one attribute
                                             :t_ms_diagnosis/name]}
     {:t_patient/episodes [:t_episode/id
                           :t_episode/project_fk
                           :t_episode/patient_fk
                           {:t_episode/project [:t_project/id
                                                :t_project/name
                                                :t_project/title
                                                :t_project/active?]}
                           :t_episode/date_registration
                           :t_episode/date_discharge
                           :t_episode/stored_pseudonym
                           {:t_episode/diagnoses patient-diagnosis-properties}
                           :t_episode/status]}
     :t_patient/results]))


(defn patient-search-by-id []
  (let [s (r/atom "")]
    (fn []
      [:input.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md.pl-5.py-2
       {:type      "text" :placeholder "Patient identifier"
        :autoFocus true
        :value     @s
        :on-key-up #(when (= (-> % .-keyCode) 13)
                      (rf/dispatch [:eldrix.pc4-ward.events/push-state :patient/home {:project-id 1 :patient-identifier @s}]))
        :on-change #(reset! s (-> % .-target .-value))}])))

(defn banner
  "A patient banner.
  Parameters
  - patient-name : patient name
  - nhs-number   : NHS number
  - gender       : Gender
  - born         : Date of birth, a goog.date.Date, or String
  - age          : Textual representation of age
  - deceased     : Date of death, a goog.date.Date or String
  - crn          : Hospital case record number
  - approximate  : boolean - if true, date of birth and age will be shown as approximate
  - close        : properties for close button (e.g. use on-click or hx-get)
  - content      : nested content"
  [{:keys [patient-name nhs-number gender born age deceased crn approximate address close content]}]
  (let [born' (if (instance? Date born) (if approximate (dates/format-month-year born)
                                                        (dates/format-date born)) born)]
    [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200.bg-gray-50.relative
     (when close
       [:div.absolute.top-0.5.sm:-top-2.5.right-0.sm:-right-2.5
        [:button.rounded.bg-white.border.hover:bg-gray-300.bg-gray-50.px-1.py-1
         close
         [:svg {:xmlns "http://www.w3.org/2000/svg" :width "20" :height "20" :viewBox "0 0 18 18"} [:path {:d "M14.53 4.53l-1.06-1.06L9 7.94 4.53 3.47 3.47 4.53 7.94 9l-4.47 4.47 1.06 1.06L9 10.06l4.47 4.47 1.06-1.06L10.06 9z"}]]]])
     (when deceased
       [:div.grid.grid-cols-1.pb-2
        [ui/badge {:s (cond
                        (instance? Date deceased) (str "Died " (dates/format-date deceased))
                        (boolean? deceased) "Deceased"
                        :else deceased)}]])
     [:div.grid.grid-cols-2.lg:grid-cols-5.pt-1
      (when patient-name (if (> (count patient-name) 20)
                           [:div.font-bold.text-sm.min-w-min patient-name]
                           [:div.font-bold.text-lg.min-w-min patient-name]))
      [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
       (when gender [:span [:span.text-sm.font-thin.sm:inline "Gender "] [:span.font-bold gender]])]
      [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
       [:span.text-sm.font-thin "Born "]
       (when born'
         [:span.font-bold born'])
       (when (and (not deceased) age) [:span.font-thin " (" (when approximate "~") age ")"])]
      [:div.lg:hidden.text-right.mr-8.md:mr-0 gender " " [:span.font-bold born']]
      (when (and nhs-number (not approximate))
        [:div.lg:text-center.lg:ml-2.min-w-min [:span.text-sm.font-thin "NHS No "] [:span.font-bold nhs-number]])
      (when (and crn (not approximate))
        [:div.text-right.min-w-min [:span.text-sm.font-thin "CRN "] [:span.font-bold crn]])]
     [:div.grid.grid-cols-1 {:class (if-not deceased "bg-gray-100" "bg-red-100")}
      [:div.font-light.text-sm.tracking-tighter.text-gray-500.truncate address]]
     (when content
       [:div content])]))

(defn rsdb-banner
  [{:t_patient/keys [id patient_identifier nhs_number sex title first_names last_name
                     date_birth date_death status current_age address]
    :t_episode/keys [stored_pseudonym]}]
  (let [pseudonymous (= :PSEUDONYMOUS status)]
    [banner
     {:patient-name (str last_name ", " (str/join " " [title first_names]))
      :nhs-number   nhs_number
      :approximate  pseudonymous
      :address      (if pseudonymous
                      (str/join " / " [stored_pseudonym (:t_address/address1 address)])
                      (str/join ", " (remove nil? [(:t_address/address1 address)
                                                   (:t_address/address2 address)
                                                   (:t_address/address3 address)
                                                   (:t_address/address4 address)
                                                   (:t_address/postcode address)])))
      :gender       sex
      :born         date_birth
      :age          current_age
      :deceased     date_death}]))

(defn menu
  [{project-id :t_project/id}
   {:t_patient/keys [patient_identifier first_names title last_name status]
    pseudonym       :t_episode/stored_pseudonym}
   {:keys [selected-id sub-menu]}]
  (let [content (fn [s] (vector :span.truncate s))
        pseudonymous (= status :PSEUDONYMOUS)]
    [ui/vertical-navigation
     {:selected-id selected-id
      :items
      (if pseudonymous
        [{:id      :home
          :content (content "Home")
          :attrs   {:href (rfe/href :pseudonymous-patient/home {:project-id project-id :pseudonym pseudonym})}}
         {:id      :diagnoses
          :content (content "Diagnoses")
          :attrs   {:href (rfe/href :pseudonymous-patient/diagnoses {:project-id project-id :pseudonym pseudonym})}}
         {:id      :treatment
          :content (content "Treatment")}
         ;:attrs   {:href (rfe/href :pseudonymous-patient/treatment patient-link-attrs)}}
         {:id      :relapses
          :content (content "Relapses")}
         ; :attrs   {:href (rfe/href :pseudonymous-patient/relapses patient-link-attrs)}}
         {:id      :encounters
          :content (content "Encounters")}
         ; :attrs   {:href (rfe/href :pseudonymous-patient/encounters patient-link-attrs)}}
         {:id      :investigations
          :content (content "Investigations")}
         ; :attrs   {:href (rfe/href :pseudonymous-patient/investigations patient-link-attrs)}}
         {:id      :admissions
          :content (content "Admissions")
          :attrs   {} #_{:href (rfe/href :pseudonymous-patient/admissions {:project-id project-id :pseudonym pseudonym})}}]
        [{:id      :home
          :content (content "Home")
          :attrs   {:href (rfe/href :patient/home {:patient-identifier patient_identifier})}}
         {:id      :diagnoses
          :content (content "Diagnoses")
          :attrs   {:href (rfe/href :patient/diagnoses {:patient-identifier patient_identifier})}}])
      :sub-menu    sub-menu}]))

(defn layout
  [project patient menu-options & content]
  (when patient
    [:div.grid.grid-cols-1.md:grid-cols-6
     [:div.col-span-1.pt-6
      [menu project patient menu-options]]
     (into [:div.col-span-5.p-6] content)]))

(defn patient-ident
  [params]
  (let [patient-identifier (get-in params [:path :patient-identifier])
        project-id (get-in params [:path :project-id])
        pseudonym (get-in params [:path :pseudonym])]
    (if patient-identifier
      [:t_patient/patient_identifier patient-identifier]
      [:t_patient/project_pseudonym [project-id pseudonym]])))

(def banner-query
  [:t_patient/id :t_patient/patient_identifier :t_patient/nhs_number
   :t_patient/title :t_patient/first_names :t_patient/last_name
   {:t_patient/address [:t_address/id :t_address/address1 :t_address/address2
                        :t_address/address3 :t_address/address4 :t_address/postcode
                        :t_address/lsoa]}
   :t_patient/sex :t_patient/date_birth :t_patient/current_age :t_patient/date_death
   :t_patient/status :t_episode/project_fk :t_episode/stored_pseudonym])


(defn inspect-edit-lsoa
  "A specialised component to view and edit the LSOA for a pseudonymous patient."
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
  {:query
   (fn [params]
     [{(patient-ident params)
       (conj banner-query
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
     (let [select-diagnosis-fn #(rf/dispatch [:eldrix.pc4-ward.server/load ;; the result will be automatically normalised and therefore update
                                              {:query [{(list 'pc4.rsdb/save-ms-diagnosis {:t_patient/patient_identifier patient_identifier
                                                                                           :t_ms_diagnosis/id            (:t_ms_diagnosis/id %)})
                                                        ['*]}]}])
           save-lsoa-fn #(do (println "Setting LSOA to " %)
                             (rf/dispatch [:eldrix.pc4-ward.server/load
                                           {:query [{(list 'pc4.rsdb/save-pseudonymous-patient-postal-code
                                                           {:t_patient/patient_identifier patient_identifier
                                                            :uk.gov.ons.nhspd/PCD2        %})
                                                     [:t_address/id :t_address/lsoa]}]}]))]
       [:<>
        [rsdb-banner patient]
        [layout {:t_project/id project-id} patient {:selected-id :home}
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
             :select-fn           select-diagnosis-fn}]]
          [ui/ui-simple-form-item {:label "LSOA (Geography)"}
           [inspect-edit-lsoa
            {:value     (get-in patient [:t_patient/address :t_address/lsoa])
             :on-change save-lsoa-fn}]]
          [ui/ui-simple-form-item {:label "Vital status"}
           [inspect-edit-death-certificate patient
            {:on-save #(do (println "updating death certificate" %)
                           (rf/dispatch
                             [:eldrix.pc4-ward.server/load
                              {:query [{(list 'pc4.rsdb/notify-death
                                              (merge {:t_patient/patient_identifier patient_identifier, :t_patient/date_death nil} %))
                                        [:t_patient/id
                                         :t_patient/date_death
                                         {:t_patient/death_certificate [:t_death_certificate/id
                                                                        :t_death_certificate/part1a]}]}]}]))}]]]]]))})


(def diagnoses-page
  {:query
   (fn [params]
     [{(patient-ident params)
       (conj banner-query
             {:t_patient/diagnoses [:t_diagnosis/id
                                    :t_diagnosis/date_diagnosis
                                    :t_diagnosis/date_onset
                                    :t_diagnosis/date_to
                                    :t_diagnosis/status
                                    {:t_diagnosis/diagnosis [:info.snomed.Concept/id
                                                             {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]})}])

   :view
   (fn [_ [{project-id :t_episode/project_fk :as patient}]]
     [:<>
      [rsdb-banner patient]
      [layout {:t_project/id project-id} patient
       {:selected-id :diagnoses
        :sub-menu    {:title "Diagnoses"
                      :items [{:id      :add-diagnosis
                               :content [ui/button {:s "Add diagnosis"}]}]}}
       (when (:t_patient/diagnoses patient)                 ;; take care to not draw until data loaded
         [:div
          [ui/ui-table
           [ui/ui-table-head
            [ui/ui-table-row
             (for [heading ["Diagnosis" "Date onset" "Date diagnosis" "Date to" "Status" ""]]
               ^{:key heading} [ui/ui-table-heading heading])]]
           [ui/ui-table-body
            (for [{:t_diagnosis/keys [id date_onset date_diagnosis date_to status diagnosis]}
                  (sort-by #(get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term]) (:t_patient/diagnoses patient))]
              (ui/ui-table-row {:key id}
                               (ui/ui-table-cell (get-in diagnosis [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
                               (ui/ui-table-cell (dates/format-date date_onset))
                               (ui/ui-table-cell (dates/format-date date_diagnosis))
                               (ui/ui-table-cell (dates/format-date date_to))
                               (ui/ui-table-cell (str status))))]]])]])})



