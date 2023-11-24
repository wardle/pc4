(ns pc4.patients
  (:require [clojure.string :as str]
            [com.eldrix.pc4.commons.dates :as dates]
            [eldrix.pc4-ward.snomed.views]
            [pc4.ui.misc :as ui]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [reagent.core :as r]
            [reitit.frontend.easy :as rfe]
            [reitit.frontend.history :as rfh])
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
    [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200.bg-slate-50.relative
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
          :content (content "Treatment")
          :attrs   {:href (rfe/href :pseudonymous-patient/medication {:project-id project-id :pseudonym pseudonym})}}
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
          :attrs   {:href (rfe/href :patient/diagnoses {:patient-identifier patient_identifier})}}
         {:id      :treatment
          :content (content "Treatment")
          :attrs   {:href (rfe/href :patient/treatment {:patient-identifier patient_identifier})}}])

      :sub-menu    sub-menu}]))

(defn layout
  [project patient menu-options & content]
  (when patient
    [:<>
     [rsdb-banner patient]
     [:div.grid.grid-cols-1.md:grid-cols-6.gap-x-4
      [:div.col-span-1.pt-2
       [menu project patient menu-options]]
      (into [:div.col-span-5.pt-2] content)]]))

(defn patient-ident
  "Returns the 'ident' of the patient given route parameters. This works both
  for identifiable and pseudonymous patients. "
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
                                                                       :t_death_certificate/part1a]}]}]}]))}]]]]))})

(defn edit-diagnosis
  [{:t_diagnosis/keys [id date_onset date_diagnosis date_to status] :as diagnosis} {:keys [on-change]}]
  [ui/ui-simple-form
   [ui/ui-simple-form-title {:title (if id "Edit diagnosis" "Add diagnosis")}]
   [ui/ui-simple-form-item {:label "Diagnosis"}
    [:div.pt-2
     (if id                                                 ;; if we already have a saved diagnosis, don't allow user to change
       [:h3.text-lg.font-medium.leading-6.text-gray-900 (get-in diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
       [eldrix.pc4-ward.snomed.views/select-snomed
        :id ::choose-diagnosis, :common-choices [] :value (:t_diagnosis/diagnosis diagnosis)
        :constraint "<404684003", :select-fn #(on-change (assoc diagnosis :t_diagnosis/diagnosis %))])]]
   [ui/ui-simple-form-item {:label "Date of onset"}
    [ui/ui-local-date {:name      "date-onset" :value date_onset
                       :on-change #(on-change (assoc diagnosis :t_diagnosis/date_onset %))}]]
   [ui/ui-simple-form-item {:label "Date of diagnosis"}
    [ui/ui-local-date {:name      "date-diagnosis" :value date_diagnosis
                       :on-change #(on-change (assoc diagnosis :t_diagnosis/date_diagnosis %))}]]
   [ui/ui-simple-form-item {:label "Date to"}
    [ui/ui-local-date {:name      "date-to" :value date_to
                       :on-change #(on-change (cond-> (assoc diagnosis :t_diagnosis/date_to %)
                                                      (nil? %)
                                                      (assoc :t_diagnosis/status "ACTIVE")
                                                      (some? %)
                                                      (assoc :t_diagnosis/status "INACTIVE_IN_ERROR")))}]]
   [ui/ui-simple-form-item {:label "Status"}
    [ui/ui-select
     {:name      "status", :value status, :default-value "ACTIVE"
      :choices   (if date_to ["INACTIVE_REVISED" "INACTIVE_RESOLVED" "INACTIVE_IN_ERROR"]
                             ["ACTIVE"])
      :select-fn #(on-change (assoc diagnosis :t_diagnosis/status %))}]]
   (when (:t_diagnosis/id diagnosis)
     [:p.text-gray-500.pt-8 "To delete a diagnosis, record a 'to' date and update the status as appropriate."])])

(defn save-diagnosis [patient-identifier diagnosis]
  (rf/dispatch
    [:eldrix.pc4-ward.server/load
     {:query      [{(list 'pc4.rsdb/save-diagnosis (assoc diagnosis :t_patient/patient_identifier patient-identifier))
                    [:t_diagnosis/id :t_diagnosis/date_onset
                     :t_diagnosis/date_diagnosis :t_diagnosis/date_to
                     :t_diagnosis/status {:t_diagnosis/diagnosis [:info.snomed.Concept/id
                                                                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                     {:t_diagnosis/patient [:t_patient/id :t_patient/diagnoses]}]}]
      :failed?    (fn [response] (get-in response ['pc4.rsdb/save-diagnosis :com.wsscode.pathom3.connect.runner/mutation-error]))
      :on-success (fn [_] [:eldrix.pc4-ward.events/modal :diagnoses nil])}]))

(defn diagnoses-table
  [title diagnoses]
  [:<>
   [ui/ui-title {:title title}]
   [ui/ui-table
    [ui/ui-table-head
     [ui/ui-table-row
      (for [{:keys [id title]} [{:id :diagnosis :title "Diagnosis"} {:id :date-onset :title "Date onset"} {:id :date-diagnosis :title "Date diagnosis"} {:id :date-to :title "Date to"} {:id :status :title "Status"} {:id :actions :title ""}]]
        ^{:key id} [ui/ui-table-heading {} title])]]
    [ui/ui-table-body
     (for [{:t_diagnosis/keys [id date_onset date_diagnosis date_to status] :as diagnosis}
           (sort-by #(get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term]) diagnoses)]
       [ui/ui-table-row
        {:key id}
        [ui/ui-table-cell {} (get-in diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
        [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_onset)]
        [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_diagnosis)]
        [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_to)]
        [ui/ui-table-cell {} (str status)]
        [ui/ui-table-cell {} (ui/ui-table-link {:on-click #(rf/dispatch [:eldrix.pc4-ward.events/modal :diagnoses diagnosis])} "Edit")]])]]])

(def diagnoses-page
  {:query
   (fn [{:keys [query] :as params}]
     [{(patient-ident params)
       (conj banner-query
             {(if (str/blank? (:filter query))
                :t_patient/diagnoses
                (list :t_patient/diagnoses {:ecl (str "<< (* {{ D term = \"" (:filter query) "\"}})")}))
              [:t_diagnosis/id :t_diagnosis/date_diagnosis :t_diagnosis/date_onset :t_diagnosis/date_to :t_diagnosis/status
               {:t_diagnosis/diagnosis
                [:info.snomed.Concept/id
                 {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]})}])

   :view
   (fn [_ [{project-id :t_episode/project_fk :t_patient/keys [patient_identifier] :as patient}]]
     (let [editing-diagnosis @(rf/subscribe [:eldrix.pc4-ward.subs/modal :diagnoses])]
       [:<>
        (when editing-diagnosis
          [ui/ui-modal {:on-close #(rf/dispatch [:eldrix.pc4-ward.events/modal :diagnoses nil])
                        :actions  [{:id       :save, :title "Save", :role :primary
                                    :on-click #(save-diagnosis patient_identifier editing-diagnosis)}
                                   {:id       :cancel, :title "Cancel"
                                    :on-click #(rf/dispatch [:eldrix.pc4-ward.events/modal :diagnoses nil])}]}
           [edit-diagnosis editing-diagnosis {:on-change #(rf/dispatch [:eldrix.pc4-ward.events/modal :diagnoses %])}]])
        [layout {:t_project/id project-id} patient
         {:selected-id :diagnoses
          :sub-menu    {:items [{:id      :filter
                                 :content [:input.border.p-2.w-full
                                           {:type     "search" :name "search" :placeholder "Search..."
                                            :onChange #(let [s (-> % .-target .-value)]
                                                         (com.eldrix.pc4.commons.debounce/dispatch-debounced [:eldrix.pc4-ward.events/push-query-params (if (str/blank? s) {} {:filter (-> % .-target .-value)})]))}]}
                                {:id      :add-diagnosis
                                 :content [ui/menu-button {:on-click #(rf/dispatch [:eldrix.pc4-ward.events/modal :diagnoses {}])} "Add diagnosis"]}]}}
         (let [active-diagnoses (filter #(= "ACTIVE" (:t_diagnosis/status %)) (:t_patient/diagnoses patient))
               inactive-diagnoses (remove #(= "ACTIVE" (:t_diagnosis/status %)) (:t_patient/diagnoses patient))]
           [:<>
            (when (seq active-diagnoses)
              [diagnoses-table "Active diagnoses" active-diagnoses])
            (when (seq inactive-diagnoses)
              [diagnoses-table "Inactive diagnoses" inactive-diagnoses])])]]))})

(def treatment-page
  {:query
   (fn [params]
     [{(patient-ident params)
       (conj banner-query
             {:t_patient/medications
              [:t_medication/id :t_medication/date_from :t_medication/date_to
               {:t_medication/medication [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
               :t_medication/reason_for_stopping]})}])

   :view
   (fn [_ [{project-id :t_episode/project_fk :t_patient/keys [patient_identifier medications] :as patient}]]
     [layout {:t_project/id project-id} patient
      {:selected-id :treatment
       :sub-menu
       {:items [{:id      :add-medication
                 :content [ui/menu-button
                           {:on-click #(rf/dispatch [:eldrix.pc4-ward.events/modal :treatment {}])} "Add medication"]}]}}
      [ui/ui-table
       [ui/ui-table-head
        [ui/ui-table-row
         (for [{:keys [id title]} [{:id :medication :title "Medication"} {:id :from :title "From"} {:id :to :title "To"} {:id :stop :title "Reason to stop"} {:id :actions :title ""}]]
           ^{:key id} [ui/ui-table-heading {} title])]]
       [ui/ui-table-body
        (for [{:t_medication/keys [id date_from date_to reason_for_stopping] :as medication}
              (sort-by #(if-let [date-from (:t_medication/date_from %)] (.valueOf date-from) 0) medications)]
          [ui/ui-table-row {:key id}
           [ui/ui-table-cell {} (str id " " (get-in medication [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term]))]
           [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_from)]
           [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_to)]
           [ui/ui-table-cell {} (name reason_for_stopping)]
           [ui/ui-table-cell {} (ui/ui-table-link {:on-click #(rf/dispatch [:eldrix.pc4-ward.events/modal :treatment medication])} "Edit")]])]]])})


