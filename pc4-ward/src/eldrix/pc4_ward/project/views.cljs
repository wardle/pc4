(ns eldrix.pc4-ward.project.views
  (:require
    [clojure.spec.alpha :as s]
    [reitit.frontend.easy :as rfe]
    [re-frame.core :as rf]
    [eldrix.pc4-ward.lookups.events :as lookup-events]
    [eldrix.pc4-ward.lookups.subs :as lookup-subs]
    [eldrix.pc4-ward.patient.events :as patient-events]
    [eldrix.pc4-ward.patient.subs :as patient-subs]
    [eldrix.pc4-ward.project.subs :as project-subs]
    [eldrix.pc4-ward.user.subs :as user-subs]
    [eldrix.pc4-ward.user.events :as user-events]
    [eldrix.pc4-ward.snomed.views :as snomed]
    [eldrix.pc4-ward.ui :as ui]
    [clojure.string :as str]
    [com.eldrix.pc4.commons.dates :as dates]
    [malli.core :as m]
    [clojure.string :as str]
    [re-frame.db :as db])
  (:import [goog.date Date]))

(defn valid-nhs-number?
  "Very crude validation of NHS number. We could implement in cljs, but the
  server will flag if we send it an invalid number, so this is just for the
  purposes of the UI enabling the 'register' button."
  [s]
  (= 10 (count (str/replace s #"\s" ""))))

(defn inspect-project [project]
  [:div.bg-white.shadow.overflow-hidden.sm:rounded-lg
   [:div.px-4.py-5.sm:px-6
    [:h3.text-lg.leading-6.font-medium.text-gray-900 (:t_project/title project)]
    [:p.mt-1.max-w-2xl.text-sm.text-gray-500 {:dangerouslySetInnerHTML {:__html (:t_project/long_description project)}}]]
   [:div.border-t.border-gray-200.px-4.py-5.sm:px-6
    [:dl.grid.grid-cols-1.gap-x-4.gap-y-8.sm:grid-cols-2
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Status"]
      [:dd.mt-1.text-sm.text-gray-900 (if (:t_project/active? project) "Active" "Inactive")]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Type"]
      [:dd.mt-1.text-sm.text-gray-900 (str/upper-case (name (:t_project/type project))) " " (when (:t_project/virtual project) "VIRTUAL")]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Date from"]
      [:dd.mt-1.text-sm.text-gray-900 (dates/format-date (:t_project/date_from project))]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Date to"]
      [:dd.mt-1.text-sm.text-gray-900 (dates/format-date (:t_project/date_to project))]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Registered patients"]
      [:dd.mt-1.text-sm.text-gray-900 (:t_project/count_registered_patients project)]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Discharged episodes"]
      [:dd.mt-1.text-sm.text-gray-900 (:t_project/count_discharged_episodes project)]]

     (when (:t_project/inclusion_criteria project)
       [:div.sm:col-span-2
        [:dt.text-sm.font-medium.text-gray-500 "Inclusion criteria"]
        [:dd.mt-1.text-sm.text-gray-900 {:dangerouslySetInnerHTML {:__html (:t_project/inclusion_criteria project)}}]])
     (when (:t_project/exclusion_criteria project)
       [:div.sm:col-span-2
        [:dt.text-sm.font-medium.text-gray-500 "Exclusion criteria"]
        [:dd.mt-1.text-sm.text-gray-900 {:dangerouslySetInnerHTML {:__html (:t_project/exclusion_criteria project)}}]])]]])

(defn search-by-pseudonym-panel
  [project-id]
  (let [patient @(rf/subscribe [::patient-subs/search-by-legacy-pseudonym-result])]
    [:div.bg-white.overflow-hidden.shadow.sm:rounded-lg
     [:div.px-4.py-6.sm:p-6
      [:form.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
       [:div.divide-y.divide-gray-200.sm:space-y-5
        [:div
         [:div
          [:h3.text-lg.leading-6.font-medium.text-gray-900 "Search by pseudonymous identifier"]
          [:p.max-w-2xl.text-sm.text-gray-500 "Enter a project-specific pseudonym, or choose register to search by patient identifiable information."]]
         [:div.mt-4
          [:label.sr-only {:for "pseudonym"} "Pseudonym"]
          [:input.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md.pl-5.py-2
           {:type        "text" :name "pseudonym" :placeholder "Start typing pseudonym" :auto-focus true
            :on-key-down #(when (and patient (= 13 (.-which %)))
                            (rfe/push-state :patient-by-project-pseudonym {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)}))
            :on-change   #(let [s (-> % .-target .-value)]
                            (rf/dispatch [::patient-events/search-legacy-pseudonym project-id s]))}]]
         (when patient
           [:div.bg-white.shadow.sm:rounded-lg.mt-4
            [:div.px-4.py-5.sm:p-6
             [:h3.text-lg.leading-6.font-medium.text-gray-900
              (str (name (:t_patient/sex patient))
                   " "
                   "born: " (.getYear (:t_patient/date_birth patient)))]
             [:div.mt-2.sm:flex.sm:items-start.sm:justify-between
              [:div.max-w-xl.text-sm.text-gray-500
               [:p (:t_episode/stored_pseudonym patient)]]
              [:div.mt-5.sm:mt-0.sm:ml-6.sm:flex-shrink-0.sm:flex.sm:items-center
               [:button.inline-flex.items-center.px-4.py-2.border.border-transparent.shadow-sm.font-medium.rounded-md.text-white.bg-indigo-600.hover:bg-indigo-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500.sm:text-sm
                {:type     "button"
                 :on-click #(rfe/push-state :patient-by-project-pseudonym {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)})}
                "View patient record"]]]]])]]]]]))


(def patient-registration-schema
  (m/schema [:map
             [:project-id int?]
             [:nhs-number [:fn valid-nhs-number?]]
             [:date-birth some?]
             [:sex [:enum :MALE :FEMALE :UNKNOWN]]]))

(defn register-pseudonymous-patient                         ;; TODO: create re-usable components from this example form
  [project-id]
  (let [data (reagent.core/atom {:project-id project-id})
        visited (reagent.core/atom #{})]
    (fn []
      (let [error @(rf/subscribe [::patient-subs/open-patient-error])
            valid? (m/validate patient-registration-schema @data)
            submit-fn #(when valid?
                         (rf/dispatch [::patient-events/register-pseudonymous-patient @data]))
            _ (tap> {:values @data
                     :error  error
                     :valid? valid? :explain (m/explain patient-registration-schema @data) :visited @visited})]
        [:div.space-y-6
         [:div.bg-white.shadow.px-4.py-5.sm:rounded-lg.sm:p-6
          [:div.md:grid.md:grid-cols-3.md:gap-6
           [:div.md:col-span-1
            [:h3.text-lg.font-medium.leading-6.text-gray-900 "Register a patient"]
            [:p.mt-1.mr-12.text-sm.text-gray-500 "Enter your patient details."
             [:p "This is safe even if patient already registered"]
             [:p.mt-4 "Patient identifiable information is not stored but simply used to generate a pseudonym."]]]
           [:div.mt-5.md:mt-0.md:col-span-2
            [:form {:on-submit #(do (.preventDefault %) (submit-fn))}
             [:div.grid.grid-cols-6.gap-6
              [:div.col-span-6.sm:col-span-3.space-y-6
               [:div [ui/textfield-control (:nhs-number @data) :label "NHS number" :auto-focus true
                      :on-change #(swap! data assoc :nhs-number %)
                      :on-blur #(swap! visited conj :nhs-number)]]
               [:div
                [ui/ui-label :for "date-birth" :label "Date of birth"]
                [:input.pb-4 {:name      "date-birth" :value (:date-birth @data) ;; TODO: spin out into own component
                              :type      "date"
                              :on-blur   #(swap! visited conj :date-birth)
                              :on-change #(swap! data assoc :date-birth (-> % .-target .-value))}]]
               [ui/select :name "gender"
                :value (:sex @data)
                :label "Gender"
                :choices [:MALE :FEMALE :UNKNOWN]
                :no-selection-string ""
                :on-key-down #(when (and (= 13 %) valid? (submit-fn)))
                :select-fn #(swap! data assoc :sex %)]
               ;[ui/textfield-control "" :label "Postal code" :disabled true :help-text "You will only need to enter this if a patient isn't already registered"]

               (when error [ui/box-error-message :message error])]]]]]]
         [:div.flex.justify-end.mr-8
          [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
           {:type     "submit"
            :class    (if-not valid? "opacity-50 pointer-events-none" "hover:bg-blue-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-blue-500")
            :on-click #(when valid? (submit-fn))
            } "Search or register patient »"]]]))))

(defn preferred-synonym [diagnosis]
  (get-in diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term]))

(defn select-multiple-sclerosis-diagnosis [& {:keys [_name _value _disabled? _on-change]}]
  (let [v (reagent.core/atom {:status :view})
        choices (rf/subscribe [::lookup-subs/all-ms-diagnoses])]
    (fn [& {:keys [name value disabled? on-change]}]
      (let [choices @choices]
        [ui/select :name name :value value :disabled? disabled?
         :choices choices
         :no-selection-string "< Choose diagnosis >"
         :id-key :t_ms_diagnosis/id
         :display-key :t_ms_diagnosis/name
         :select-fn on-change]))))

(defn inspect-edit-lsoa [& params]
  (let [mode (reagent.core/atom :inspect)
        postcode (reagent.core/atom "")]
    (fn [& {:keys [value on-change]}]
      (let [save-fn #(do (on-change @postcode)
                         (reset! mode :inspect))]
        (case @mode
          :inspect [:a.cursor-pointer.underline
                    {:class    (if (str/blank? value) "text-red-600 hover:text-red-800" "text-red-600.hover:text-red-800")
                     :on-click #(do
                                  (reset! postcode "")
                                  (reset! mode :edit))} (if (str/blank? value) "Not set" value)]
          :edit [:span
                 [ui/textfield-control @postcode :auto-focus true :label "Enter postal code:"
                  :on-change #(reset! postcode %)
                  :on-enter save-fn
                  :help-text "This postal code will not be stored but mapped to a larger geographical region instead."]
                 [:button.bg-red-500.hover:bg-red-700.text-white.text-xs.py-1.px-2.rounded-full
                  {:on-click save-fn} "Save"]
                 [:button.bg-blue-500.hover:bg-blue-700.text-white.text-xs.py-1.px-2.rounded
                  {:on-click #(do (reset! mode :inspect) (reset! postcode ""))}
                  "Cancel"]])))))

(defn multiple-sclerosis-main []
  (let [current-patient @(rf/subscribe [::patient-subs/current])]
    [:div.bg-white.shadow.overflow-hidden.sm:rounded-lg
     [ui/section-heading "Neuro-inflammatory disease"]
     [:div.border-t.border-gray-200.px-4.py-5.sm:p-0
      [:dl.sm:divide-y.sm:divide-gray-200

       [:div.py-4.sm:py-5.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
        [:dt.text-sm.font-medium.text-gray-500 "Diagnostic criteria"]
        [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2
         [select-multiple-sclerosis-diagnosis :name "ms-diagnosis" :value (get-in current-patient [:t_patient/summary_multiple_sclerosis]) :disabled false
          :on-change #(do (println "changed diagnosis to " %)
                          (rf/dispatch [::patient-events/save-ms-diagnosis {:t_patient/patient_identifier (:t_patient/patient_identifier current-patient)
                                                                            :t_ms_diagnosis/id            (:t_ms_diagnosis/id %)}]))]]]

       [:div.py-4.sm:py-5.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
        [:dt.text-sm.font-medium.text-gray-500 "Most recent EDSS"]
        [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 0]]

       [:div.py-4.sm:py-5.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
        [:dt.text-sm.font-medium.text-gray-500 "Number of relapses in last 2 years"]
        [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 0]]

       [:div.py-4.sm:py-5.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
        [:dt.text-sm.font-medium.text-gray-500 "Number of relapses in last year"]
        [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 0]]

       [:div.py-4.sm:py-5.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
        [:dt.text-sm.font-medium.text-gray-500 "LSOA (geography)"]
        [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2
         [inspect-edit-lsoa
          :value (or (get-in current-patient [:t_patient/address :uk.gov.ons.nhspd/LSOA11])
                     (get-in current-patient [:t_patient/address :t_address/lsoa]))
          :on-change #(do (println "Setting LSOA to " %)
                          (rf/dispatch [::patient-events/save-pseudonymous-postcode {:t_patient/patient_identifier (:t_patient/patient_identifier current-patient)
                                                                                     :uk.gov.ons.nhspd/PCD2        %}]))]]]]]]))


(defn edit-diagnosis
  "Edit diagnosis form.
  TODO: this should generate itself from a schema, including client side
  validation...."
  [diagnosis]
  [:form.space-y-8.divide-y.divide-gray-200
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for ::choose-diagnosis} "Diagnosis"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         (if (:t_diagnosis/id diagnosis)                    ;; if we already have a saved diagnosis, don't allow user to change
           [:h3.text-lg.font-medium.leading-6.text-gray-900 (get-in diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
           [eldrix.pc4-ward.snomed.views/select-snomed
            :id ::choose-diagnosis
            :common-choices []
            :value (:t_diagnosis/diagnosis diagnosis)
            :constraint "<404684003"
            :select-fn #(rf/dispatch [::patient-events/set-current-diagnosis (assoc diagnosis :t_diagnosis/diagnosis %)])])]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-onset"} "Date onset"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date-onset" :value (:t_diagnosis/date_onset diagnosis)
         :on-change #(rf/dispatch-sync [::patient-events/set-current-diagnosis (assoc diagnosis :t_diagnosis/date_onset %)])]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-diagnosis"} "Date diagnosis"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date-diagnosis" :value (:t_diagnosis/date_diagnosis diagnosis)
         :on-change #(rf/dispatch-sync [::patient-events/set-current-diagnosis (assoc diagnosis :t_diagnosis/date_diagnosis %)])]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-onset"} "Date to"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date-to" :value (:t_diagnosis/date_to diagnosis)
         :on-change #(rf/dispatch-sync [::patient-events/set-current-diagnosis (assoc diagnosis :t_diagnosis/date_to %)])]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "status"} "Status"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/select
         :name "status"
         :value (:t_diagnosis/status diagnosis)
         :default-value "ACTIVE"
         :choices ["INACTIVE_REVISED" "ACTIVE" "INACTIVE_RESOLVED" "INACTIVE_IN_ERROR"]
         :select-fn #(rf/dispatch [::patient-events/set-current-diagnosis (assoc diagnosis :t_diagnosis/status %)])]]]
      (when (:t_diagnosis/id diagnosis)
        [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
         [:div.mt-1.sm:mt-0.sm:col-span-2.text-gray-700
          [:p "To delete a diagnosis, record a 'to' date and update the status as appropriate."]]])]]]])

(defn list-diagnoses []
  (let [current-patient @(rf/subscribe [::patient-subs/current])
        current-diagnosis @(rf/subscribe [::patient-subs/current-diagnosis])
        sorted-diagnoses (sort-by preferred-synonym @(rf/subscribe [::patient-subs/diagnoses]))
        active-diagnoses (filter #(= "ACTIVE" (:t_diagnosis/status %)) sorted-diagnoses)
        resolved-diagnoses (filter #(= "INACTIVE_RESOLVED" (:t_diagnosis/status %)) sorted-diagnoses)
        incorrect-diagnoses (filter #(#{"INACTIVE_REVISED" "INACTIVE_IN_ERROR"} (:t_diagnosis/status %)) sorted-diagnoses)
        _ (tap> @db/app-db)]
    [:<>
     (when current-diagnosis
       [ui/modal :disabled? false
        :content [edit-diagnosis current-diagnosis]
        :actions [{:id       ::save-action
                   :title    "Save"
                   :role     :primary
                   :on-click #(rf/dispatch [::patient-events/save-diagnosis
                                            (assoc current-diagnosis
                                              :t_patient/patient_identifier (:t_patient/patient_identifier current-patient))])}
                  {:id ::cancel-action :title "Cancel" :on-click #(rf/dispatch [::patient-events/clear-diagnosis])}]
        :on-close #(rf/dispatch [::patient-events/clear-diagnosis])])
     [ui/section-heading "Active diagnoses"
      :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                {:on-click #(rf/dispatch [::patient-events/set-current-diagnosis {}])} "Add diagnosis"]]
     [ui/list-entities-fixed
      :items active-diagnoses
      :headings ["Diagnosis" "Date onset" "Date diagnosis" "Date to" "Status"]
      :width-classes {"Diagnosis" "w-2/6" "Date onset" "w-1/6" "Date diagnosis" "w-1/6" "Date to" "w-1/6" "Status" "w-1/6"}
      :id-key :t_diagnosis/id
      :value-keys [preferred-synonym
                   #(dates/format-date (:t_diagnosis/date_onset %))
                   #(dates/format-date (:t_diagnosis/date_diagnosis %))
                   #(dates/format-date (:t_diagnosis/date_to %))
                   :t_diagnosis/status]
      :on-edit (fn [diagnosis] (js/console.log "edt diag") (rf/dispatch [::patient-events/set-current-diagnosis diagnosis]))]
     (when (seq resolved-diagnoses)
       [:div.mt-8
        [ui/section-heading "Inactive diagnoses"]
        [ui/list-entities-fixed
         :items resolved-diagnoses
         :headings ["Diagnosis" "Date onset" "Date diagnosis" "Date to" "Status"]
         :width-classes {"Diagnosis" "w-2/6" "Date onset" "w-1/6" "Date diagnosis" "w-1/6" "Date to" "w-1/6" "Status" "w-1/6"}
         :id-key :t_diagnosis/id
         :value-keys [preferred-synonym
                      #(dates/format-date (:t_diagnosis/date_onset %))
                      #(dates/format-date (:t_diagnosis/date_diagnosis %))
                      #(dates/format-date (:t_diagnosis/date_to %))
                      :t_diagnosis/status]
         :on-edit (fn [diagnosis] (rf/dispatch [::patient-events/set-current-diagnosis diagnosis]))]])]))

(defn edit-medication
  "Edit medication form.
  TODO: this should generate itself from a schema, including client side
  validation...."
  [medication]
  [:form.space-y-8.divide-y.divide-gray-200
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for ::choose-medication} "Medication"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         (if (:t_medication/id medication)                  ;; if we already have a saved diagnosis, don't allow user to change
           [:h3.text-lg.font-medium.leading-6.text-gray-900 (get-in medication [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
           [eldrix.pc4-ward.snomed.views/select-snomed
            :id ::choose-medication
            :common-choices []
            :value (:t_medication/medication medication)
            :constraint "(<10363601000001109 MINUS <<10363901000001102)"
            :select-fn #(rf/dispatch [::patient-events/set-current-medication (assoc medication :t_medication/medication %)])])]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-onset"} "Date from"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date-from" :value (:t_medication/date_from medication)
         :on-change #(rf/dispatch-sync [::patient-events/set-current-medication (assoc medication :t_medication/date_from %)])]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-to"} "Date to"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date-to" :value (:t_medication/date_to medication)
         :on-change #(rf/dispatch-sync [::patient-events/set-current-medication (assoc medication :t_medication/date_to %)])]]]]]]])

(defn list-medications []
  (let [current-patient @(rf/subscribe [::patient-subs/current])
        current-medication @(rf/subscribe [::patient-subs/current-medication]) ;; currently edited medication
        sorted-medications (sort-by #(if-let [date-from (:t_medication/date_from %)] (.valueOf date-from) 0)
                                    @(rf/subscribe [::patient-subs/medications]))
        _ (tap> @db/app-db)]
    [:<>
     (when current-medication
       [ui/modal
        :disabled? false
        :content [edit-medication current-medication]
        :actions [{:id       ::save-action
                   :title    "Save" :role :primary
                   :on-click #(rf/dispatch [::patient-events/save-medication
                                            (assoc current-medication
                                              :t_patient/patient_identifier (:t_patient/patient_identifier current-patient))])}
                  {:id       ::delete-action
                   :title    "Delete"
                   :on-click #(if (:t_medication/id current-medication)
                                (rf/dispatch [::patient-events/save-medication
                                              (-> current-medication
                                                  (dissoc :t_medication/medication)
                                                  (assoc :t_patient/patient_identifier (:t_patient/patient_identifier current-patient)))])
                                (rf/dispatch [::patient-events/clear-medication]))}
                  {:id       ::cancel-action
                   :title    "Cancel"
                   :on-click #(rf/dispatch [::patient-events/clear-medication])}]
        :on-close #(rf/dispatch [::patient-events/clear-medication])])
     [ui/section-heading "Medications"
      :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                {:on-click #(rf/dispatch [::patient-events/set-current-medication {}])} "Add medication"]]
     [ui/list-entities-fixed
      :items sorted-medications
      :headings ["Medication" "From" "To"]
      :width-classes {"Medication" "w-4/6" "From" "w-1/6" "To" "w-1/6"}

      :id-key :t_medication/id
      :value-keys [#(get-in % [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])
                   #(dates/format-date (:t_medication/date_from %))
                   #(dates/format-date (:t_medication/date_to %))]
      :on-edit (fn [medication] (rf/dispatch [::patient-events/set-current-medication medication]))]]))


(def impact-choices ["UNKNOWN" "NON_DISABLING" "DISABLING" "SEVERE"])

(defn ms-event-site-to-string [k]
  (str/capitalize (str/join " " (rest (str/split (name k) #"_")))))

(defn edit-event [event & {:keys [on-change]}]
  (let [all-ms-event-types @(rf/subscribe [::lookup-subs/all-ms-event-types])]
    [:form.space-y-8.divide-y.divide-gray-200
     [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
      [:div
       [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
        [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
         [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
         [:div.mt-1.sm:mt-0.sm:col-span-2
          [ui/html-date-picker :name "date" :value (:t_ms_event/date event)
           :on-change #(on-change (assoc event :t_ms_event/date %))]]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "event-type"} "Type"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/select :name "event-type"
          :value event
          :choices all-ms-event-types
          :sort? false
          :select-fn #(on-change (merge event %))
          :id-key :t_ms_event_type/id
          :display-key (fn [et] (when (:t_ms_event_type/abbreviation et) (str (:t_ms_event_type/abbreviation et) ": " (:t_ms_event_type/name et))))]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "impact"} "Impact"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/select :name "impact"
          :value (:t_ms_event/impact event) :choices impact-choices :sort? false
          :select-fn #(on-change (assoc event :t_ms_event/impact %))]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 "Site(s)"]
        [:div.mt-4.sm:grid.sm:grid-cols-2.sm:gap-4
         [:div.mt-1.sm:mt-0:sm:col-span-1
          ;"UK" "UE" "LE" "SS" "SP" "SX" "FM" "FS" "OM" "VE" "BB" "CB" "ON" "PS" "OT" "MT"
          [ui/multiple-checkboxes event
           :keys [:t_ms_event/site_unknown :t_ms_event/site_arm_motor :t_ms_event/site_leg_motor
                  :t_ms_event/site_limb_sensory :t_ms_event/site_sphincter :t_ms_event/site_sexual
                  :t_ms_event/site_face_motor :t_ms_event/site_face_sensory]
           :display-key ms-event-site-to-string
           :on-change on-change]]
         [:div.mt-1.sm:mt-0:sm:col-span-1
          [ui/multiple-checkboxes event
           :keys [:t_ms_event/site_diplopia :t_ms_event/site_vestibular :t_ms_event/site_bulbar
                  :t_ms_event/site_ataxia :t_ms_event/site_optic_nerve :t_ms_event/site_psychiatric
                  :t_ms_event/site_other :t_ms_event/site_cognitive]
           :display-key ms-event-site-to-string
           :on-change on-change]]]]
       ]]]))


(s/def :t_ms_event/date #(instance? goog.date.Date %))
(s/def ::ms-event
  (s/keys :req [:t_ms_event/date]))

(defn list-ms-events []
  (let [editing-event (reagent.core/atom nil)]
    (fn []
      (let [current-patient @(rf/subscribe [::patient-subs/current])
            sorted-events (sort-by #(if-let [date (:t_ms_event/date %)] (.valueOf date) 0)
                                   @(rf/subscribe [::patient-subs/ms-events]))
            editing-event' @editing-event
            valid? (s/valid? ::ms-event editing-event')
            _ (tap> {:editing-event editing-event'
                     :valid?        valid?
                     :problems      (s/explain-data ::ms-event editing-event')})]
        (if-not (:t_patient/summary_multiple_sclerosis current-patient)
          [ui/box-error-message :title "No neuro-inflammatory diagnosis recorded" :message "You must record a neuro-inflammatory diagnosis before recording events"]
          [:<>
           (when editing-event'
             [ui/modal
              :content [edit-event editing-event' :on-change #(reset! editing-event %)]
              :actions [{:id        ::save-action
                         :title     "Save"
                         :disabled? (not valid?)
                         :role      :primary
                         :on-click  #(do (rf/dispatch [::patient-events/save-ms-event
                                                       (assoc editing-event'
                                                         :t_patient/patient_identifier (:t_patient/patient_identifier current-patient))])
                                         (reset! editing-event nil))}
                        {:id       ::delete-action
                         :title    "Delete"
                         :hidden?  (not (:t_ms_event/id editing-event')) ;; hide when new
                         :on-click #(do (when (:t_ms_event/id editing-event')
                                          (rf/dispatch [::patient-events/delete-ms-event editing-event']))
                                        (reset! editing-event nil))}
                        {:id       ::cancel-action
                         :title    "Cancel"
                         :on-click #(reset! editing-event nil)}]
              :on-close #(reset! editing-event nil)])
           [ui/section-heading "Relapses and disease events"
            :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                      {:on-click #(reset! editing-event {:t_ms_event/summary_multiple_sclerosis_fk (get-in current-patient [:t_patient/summary_multiple_sclerosis :t_summary_multiple_sclerosis/id])
                                                         :t_ms_event_type/id                       11
                                                         :t_ms_event/impact                        "DISABLING"})} "Add event"]]
           [ui/list-entities-fixed
            :items sorted-events
            :headings ["Date" "Type" "Impact" "UK" "UE" "LE" "SS" "SP" "SX" "FM" "FS" "OM" "VE" "BB" "CB" "ON" "PS" "OT" "MT"]
            :width-classes {"UK" "w-8"
                            "UE" "w-8" "LE" "w-8" "SS" "w-8" "SP" "w-8" "SX" "w-8" "FM" "w-8" "FS" "w-8" "OM" "w-8" "VE" "w-8" "BB" "w-8" "CB" "w-8" "ON" "w-8" "PS" "w-8" "OT" "w-8" "MT" "w-8"}
            :id-key :t_ms_event/id
            :value-keys [#(dates/format-date (:t_ms_event/date %))
                         ;;UK:unknown. UE:arm motor. LE:leg motor. SS:limb sensory. SP:sphincter. SX:sexual. FM:face motor. FS:face sensory. OM:diplopia.
                         ;; VE:vestibular. BB:bulbar. CB:ataxia. ON:optic nerve. PS:psychiatric. OT:other. MT:cognitive.
                         :t_ms_event_type/abbreviation
                         :t_ms_event/impact
                         #(if (:t_ms_event/site_unknown %) "UK")
                         #(if (:t_ms_event/site_arm_motor %) "UE")
                         #(if (:t_ms_event/site_leg_motor %) "LE")
                         #(if (:t_ms_event/site_limb_sensory %) "SS")
                         #(if (:t_ms_event/site_sphincter %) "SP")
                         #(if (:t_ms_event/site_sexual %) "SX")
                         #(if (:t_ms_event/site_face_motor %) "FM")
                         #(if (:t_ms_event/site_face_sensory %) "FS")
                         #(if (:t_ms_event/site_diplopia %) "OM")
                         #(if (:t_ms_event/site_vestibular %) "VE")
                         #(if (:t_ms_event/site_bulbar %) "BB")
                         #(if (:t_ms_event/site_ataxia %) "CB")
                         #(if (:t_ms_event/site_optic_nerve %) "ON")
                         #(if (:t_ms_event/site_psychiatric %) "PS")
                         #(if (:t_ms_event/site_other %) "OT")
                         #(if (:t_ms_event/site_cognitive %) "MT")]
            :on-edit (fn [event] (reset! editing-event event))]])))))



(s/def ::encounter
  (s/keys :req [:t_encounter/date_time
                :t_encounter/patient_fk
                :t_encounter/episode_fk]))

(defn edit-edss [encounter & {:keys [on-change]}]
  (let [current-project @(rf/subscribe [::project-subs/current])
        all-encounter-templates @(rf/subscribe [::project-subs/active-encounter-templates])
        _ (tap> {:project current-project :all-encounter-templates all-encounter-templates})]
    [:form.space-y-8.divide-y.divide-gray-200
     [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
      [:div
       [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
        [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
         [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
         [:div.mt-1.sm:mt-0.sm:col-span-2
          [ui/html-date-picker :name "date" :value (:t_encounter/date_time encounter)
           :on-change #(on-change (assoc encounter :t_encounter/date_time %))]]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "encounter-template"} "Type"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/select :name "encounter-template"
          :value encounter
          :choices all-encounter-templates
          :sort? false
          :select-fn #(on-change (merge encounter %))
          :id-key :t_encounter_template/id
          :display-key (fn [et] (when et (:t_encounter_template/title et)))]]]

       ]]]))


(defn list-encounters
  "This shows a list of encounters. Presently, the list headings are hard-coded
  for multiple sclerosis"
  []
  (let [editing-encounter (reagent.core/atom nil)]
    (fn []
      (let [current-patient @(rf/subscribe [::patient-subs/current])
            current-project @(rf/subscribe [::project-subs/current])
            sorted-encounters (->> @(rf/subscribe [::patient-subs/encounters])
                                   (sort-by #(if-let [date (:t_encounter/date_time %)] (.valueOf date) 0))
                                   reverse)
            default-encounter-template @(rf/subscribe [::project-subs/default-encounter-template])
            active-episode-for-patient @(rf/subscribe [::patient-subs/active-episode-for-project (:t_project/id current-project)])
            editing-encounter' @editing-encounter
            valid? (s/valid? ::encounter editing-encounter')
            _ (tap> {:editing-encounter editing-encounter'
                     :active-episode    active-episode-for-patient
                     :sorted-encounters sorted-encounters
                     :valid?            valid?
                     :problems          (s/explain-data ::encounter editing-encounter')})]
        [:<>
         (when editing-encounter'
           [ui/modal
            :content [edit-edss editing-encounter' :on-change #(reset! editing-encounter %)]
            :actions [{:id        ::save-action
                       :title     "Save"
                       :disabled? (not valid?)
                       :role      :primary
                       :on-click  #(do (rf/dispatch [::patient-events/save-encounter editing-encounter'])
                                       (reset! editing-encounter nil))}
                      {:id       ::delete-action
                       :title    "Delete"
                       :hidden?  (not (:t_encounter/id editing-encounter')) ;; hide when new
                       :on-click #(do (when (:t_encounter/id editing-encounter')
                                        (rf/dispatch [::patient-events/delete-encounter editing-encounter']))
                                      (reset! editing-encounter nil))}
                      {:id       ::cancel-action
                       :title    "Cancel"
                       :on-click #(reset! editing-encounter nil)}]
            :on-close #(reset! editing-encounter nil)])
         [ui/section-heading "Encounters"
          :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                    {:on-click #(reset! editing-encounter (merge default-encounter-template
                                                                 {:t_encounter/patient_fk (:t_patient/id current-patient)
                                                                  :t_encounter/episode_fk (:t_episode/id active-episode-for-patient)}))}
                    "Add encounter"]]
         [ui/list-entities-fixed
          :items sorted-encounters
          :headings ["Date" "Type" "EDSS" "Disease course" "In relapse?" "Weight"]
          :width-classes {"Date" "w-1/6" "Type" "w-1/6" "EDSS" "w-1/6" "Disease course" "w-1/6" "In relapse?" "w-1/6" "Weight" "w-1/6"}
          :id-key :t_encounter/id
          :value-keys [#(dates/format-date (:t_encounter/date_time %))
                       #(get-in % [:t_encounter/encounter_template :t_encounter_template/title])
                       #(get-in % [:t_encounter/form_edss :t_form_edss/edss])
                       #(get-in % [:t_encounter/form_ms_relapse :t_ms_disease_course/name])
                       #(when (get-in % [:t_encounter/form_ms_relapse :t_form_ms_relapse/in_relapse]) "✔️")
                       #(get-in % [:t_encounter/form_weight_height :t_form_weight_height/weight_kilograms])]
          :on-edit (fn [encounter] (reset! editing-encounter encounter))]]))))


(defn list-investigations
  "This shows a list of investigations"
  []
  (let [editing-encounter (reagent.core/atom nil)]
    (fn []
      (let [current-patient @(rf/subscribe [::patient-subs/current])
            current-project @(rf/subscribe [::project-subs/current])
            sorted-encounters (->> @(rf/subscribe [::patient-subs/all-edss])
                                   (sort-by #(if-let [date (:t_encounter/date_time %)] (.valueOf date) 0))
                                   reverse)
            default-encounter-template @(rf/subscribe [::project-subs/default-encounter-template])
            active-episode-for-patient @(rf/subscribe [::patient-subs/active-episode-for-project (:t_project/id current-project)])
            editing-encounter' @editing-encounter
            valid? (s/valid? ::encounter editing-encounter')
            _ (tap> {:editing-encounter editing-encounter'
                     :active-episode    active-episode-for-patient
                     :sorted-encounters sorted-encounters
                     :valid?            valid?
                     :problems          (s/explain-data ::encounter editing-encounter')})]
        [:<>
         (when editing-encounter'
           [ui/modal
            :content [edit-edss editing-encounter' :on-change #(reset! editing-encounter %)]
            :actions [{:id        ::save-action
                       :title     "Save"
                       :disabled? (not valid?)
                       :role      :primary
                       :on-click  #(do (rf/dispatch [::patient-events/save-encounter editing-encounter'])
                                       (reset! editing-encounter nil))}
                      {:id       ::delete-action
                       :title    "Delete"
                       :hidden?  (not (:t_encounter/id editing-encounter')) ;; hide when new
                       :on-click #(do (when (:t_encounter/id editing-encounter')
                                        (rf/dispatch [::patient-events/delete-encounter editing-encounter']))
                                      (reset! editing-encounter nil))}
                      {:id       ::cancel-action
                       :title    "Cancel"
                       :on-click #(reset! editing-encounter nil)}]
            :on-close #(reset! editing-encounter nil)])
         [ui/section-heading "Investigations"
          :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                    {:on-click #(reset! editing-encounter (merge default-encounter-template
                                                                 {:t_encounter/patient_fk (:t_patient/id current-patient)
                                                                  :t_encounter/episode_fk (:t_episode/id active-episode-for-patient)}))}
                    "Add investigation"]]
         [ui/list-entities-fixed
          :items [{:t_result/date   (goog.date.Date/fromIsoString "2005-01-01")
                   :t_result/title  "MRI Brain"
                   :t_result/result "Typical: There are numerous T2/FLAIR hyperintensities"}
                  {:t_result/date   (goog.date.Date/fromIsoString "2008-01-01")
                   :t_result/title  "JC Virus"
                   :t_result/result "POSITIVE"}]
          :headings ["Date" "Investigation" "Result"]
          :width-classes {"Date" "w-1/6" "Investigation" "w-1/6" "Result" "w-4/6"}
          :id-key :t_encounter/id
          :value-keys [#(dates/format-date (:t_result/date %))
                       :t_result/title
                       :t_result/result]
          :on-edit (fn [encounter] (reset! editing-encounter encounter))]]))))

(defn list-admissions
  "This shows a list of investigations"
  []
  (let [editing-encounter (reagent.core/atom nil)]
    (fn []
      (let [current-patient @(rf/subscribe [::patient-subs/current])
            current-project @(rf/subscribe [::project-subs/current])
            sorted-encounters (->> @(rf/subscribe [::patient-subs/all-edss])
                                   (sort-by #(if-let [date (:t_encounter/date_time %)] (.valueOf date) 0))
                                   reverse)
            default-encounter-template @(rf/subscribe [::project-subs/default-encounter-template])
            active-episode-for-patient @(rf/subscribe [::patient-subs/active-episode-for-project (:t_project/id current-project)])
            editing-encounter' @editing-encounter
            valid? (s/valid? ::encounter editing-encounter')
            _ (tap> {:editing-encounter editing-encounter'
                     :active-episode    active-episode-for-patient
                     :sorted-encounters sorted-encounters
                     :valid?            valid?
                     :problems          (s/explain-data ::encounter editing-encounter')})]
        [:<>
         (when editing-encounter'
           [ui/modal
            :content [edit-edss editing-encounter' :on-change #(reset! editing-encounter %)]
            :actions [{:id        ::save-action
                       :title     "Save"
                       :disabled? (not valid?)
                       :role      :primary
                       :on-click  #(do (rf/dispatch [::patient-events/save-encounter editing-encounter'])
                                       (reset! editing-encounter nil))}
                      {:id       ::delete-action
                       :title    "Delete"
                       :hidden?  (not (:t_encounter/id editing-encounter')) ;; hide when new
                       :on-click #(do (when (:t_encounter/id editing-encounter')
                                        (rf/dispatch [::patient-events/delete-encounter editing-encounter']))
                                      (reset! editing-encounter nil))}
                      {:id       ::cancel-action
                       :title    "Cancel"
                       :on-click #(reset! editing-encounter nil)}]
            :on-close #(reset! editing-encounter nil)])
         [ui/section-heading "Admissions"
          :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                    {:on-click #(reset! editing-encounter (merge default-encounter-template
                                                                 {:t_encounter/patient_fk (:t_patient/id current-patient)
                                                                  :t_encounter/episode_fk (:t_episode/id active-episode-for-patient)}))}
                    "Add admission"]]
         [ui/list-entities-fixed
          :items [{:t_admission/from     (goog.date.Date/fromIsoString "2012-05-01")
                   :t_admission/to       (goog.date.Date/fromIsoString "2012-05-05")
                   :t_admission/hospital "UNIVERSITY HOSPITAL WALES"
                   :t_admission/problems "Urinary tract infection; Sepsis; Pneumonia NOS"}
                  {:t_admission/from     (goog.date.Date/fromIsoString "2015-01-02")
                   :t_admission/to       (goog.date.Date/fromIsoString "2015-01-06")
                   :t_admission/hospital "ROOKWOOD HOSPITAL"
                   :t_admission/problems "Spasticity"}]
          :headings ["From" "To" "Hospital" "Problems"]
          :width-classes {"From" "w-1/6" "To" "w-1/6" "Hospital" "w-1/6" "Problems" "w-3/6"}
          :id-key :t_encounter/id
          :value-keys [#(dates/format-date (:t_admission/from %))
                       #(dates/format-date (:t_admission/to %))
                       :t_admission/hospital
                       :t_admission/problems]
          :on-edit (fn [encounter] (reset! editing-encounter encounter))]]))))

(def neuro-inflammatory-menus
  [{:id        :main
    :title     "Main"
    :component multiple-sclerosis-main}
   {:id        :diagnoses
    :title     "Diagnoses"
    :component list-diagnoses}
   {:id        :treatment
    :title     "Treatment"
    :component list-medications}
   {:id        :relapses
    :title     "Relapses"
    :component list-ms-events}
   {:id        :encounters
    :title     "Encounters"
    :component list-encounters}
   {:id        :investigations
    :title     "Investigations"
    :component list-investigations}
   {:id        :admissions
    :title     "Admissions"
    :component list-admissions}])

(def menu-by-id (reduce (fn [acc v] (assoc acc (:id v) v)) {} neuro-inflammatory-menus))

(defn view-pseudonymous-patient
  "This is a neuro-inflammatory 'view' of the patient record.
  TODO: split out common functionality and components into libraries"
  []
  (let [menu (reagent.core/atom :main)]
    (fn []
      (let [patient @(rf/subscribe [::patient-subs/current])
            loading? @(rf/subscribe [::patient-subs/loading?])
            authenticated-user @(rf/subscribe [::user-subs/authenticated-user])
            _ (tap> {:patient patient :user authenticated-user})]
        [:<>
        (when loading?
          [:div.flex.h-screen
           [:div.m-auto
            [:div
             [:div.flex.justify-center.items-center
              [:div.animate-spin.rounded-full.h-32.w-32.border-b-2.border-gray-900]]]]]
          )
        (when patient
          [:div
           [ui/patient-banner
            :name (:t_patient/sex patient)
            :born (when-let [dob (:t_patient/date_birth patient)] (.getYear dob))
            :address (:t_episode/stored_pseudonym patient)
            :on-close #(when-let [project-id (:t_episode/project_fk patient)]
                         (println "opening project page for project" project-id)
                         (rfe/push-state :projects {:project-id project-id :slug "home"}))
            :content [ui/tabbed-menu
                      :name "patient-menu"
                      :value @menu
                      :on-change #(do (println "chosen" %) (reset! menu %))
                      :choices neuro-inflammatory-menus
                      :value-key :id
                      :display-key :title]]
           [:div.pt-3.border.bg-white.overflow-hidden.shadow-lg.sm:rounded-lg
            [:div.px-4.py-5.sm:p-6
             (when-let [component (:component (menu-by-id @menu))]
               [component])]]])]))))

(defn list-users [users]
  [:div.flex.flex-col
   [:div.-my-2.overflow-x-auto.sm:-mx-6.lg:-mx-8
    [:div.py-2.align-middle.inline-block.min-w-full.sm:px-6.lg:px-8
     [:div.shadow.overflow-hidden.border-b.border-gray-200.sm:rounded-lg
      [:table.min-w-full.divide-y.divide-gray-200
       [:thead.bg-gray-50
        [:tr
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Name"]
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Title"]
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Email"]]]
       [:tbody.bg-white.divide-y.divide-gray-200
        (for [user (sort-by (juxt :t_user/last_name :t_user/first_names) (reduce-kv (fn [acc k v] (conj acc (first v))) [] (group-by :t_user/id users)))
              :let [id (:t_user/id user)]]
          [:tr {:key id}
           [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900 (str/join " " [(:t_user/title user) (:t_user/first_names user) (:t_user/last_name user)])]
           [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (or (:t_user/custom_job_title user) (:t_job_title/name user))]
           [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (:t_user/email user)]])]]]]]])

(defn project-home-page []
  (let [selected-page (reagent.core/atom :home)]
    (rf/dispatch [::patient-events/search-legacy-pseudonym nil ""])
    (rf/dispatch [::patient-events/clear-open-patient-error])
    (rf/dispatch [::lookup-events/fetch])
    (fn []
      (let [route @(rf/subscribe [:eldrix.pc4-ward.subs/current-route])
            authenticated-user @(rf/subscribe [::user-subs/authenticated-user])
            current-project @(rf/subscribe [::project-subs/current])]
        (when current-project
          [:<>
           [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200
            [:ul.flex
             [:div.font-bold.text-lg.min-w-min.mr-6.py-1 (:t_project/title current-project)]
             [ui/flat-menu [{:title "Home" :id :home}
                            {:title "Register" :id :register}
                            {:title "Search" :id :search}
                            {:title "Users" :id :users}]
              :selected-id @selected-page
              :select-fn #(do (reset! selected-page %)
                              (rf/dispatch [::patient-events/search-legacy-pseudonym (:t_project/id current-project) ""]))]]]
           (case @selected-page
             :home [inspect-project current-project]
             :search [search-by-pseudonym-panel (:t_project/id current-project)]
             :register [register-pseudonymous-patient (:t_project/id current-project)]
             :users [list-users (:t_project/users current-project)])])))))