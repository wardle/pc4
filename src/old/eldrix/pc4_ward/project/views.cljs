(ns eldrix.pc4-ward.project.views
  (:require
    [clojure.spec.alpha :as s]
    [reitit.frontend.easy :as rfe]
    [re-frame.core :as rf]
    [eldrix.pc4-ward.lookups.events :as lookup-events]
    [eldrix.pc4-ward.lookups.subs :as lookup-subs]
    [eldrix.pc4-ward.patient.events :as patient-events]
    [eldrix.pc4-ward.patient.subs :as patient-subs]
    [eldrix.pc4-ward.patient.views :as patient-views]
    [eldrix.pc4-ward.project.subs :as project-subs]
    [pc4.subs :as subs]
    [pc4.snomed.views :as snomed]
    [eldrix.pc4-ward.ui :as ui]
    [clojure.string :as str]
    [pc4.dates :as dates]
    [com.eldrix.nhsnumber :as nhs-number]
    [malli.core :as m]
    [re-frame.db :as db]
    ["big.js" :as Big])
  (:import [goog.date Date]))

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
      [:dd.mt-1.text-sm.text-gray-900 (str/join " "
                                                [(when (:t_project/pseudonymous project) "PSEUDONYMOUS")
                                                 (str/upper-case (name (:t_project/type project)))
                                                 (when (:t_project/virtual project) "VIRTUAL")])]]
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
    [:div.bg-white.overflow-hidden.shadow.sm:rounded-lg.border.shadow-lg
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
                            (rfe/push-state :pseudonymous-patient/home {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)}))
            :on-change   #(let [s (-> % .-target .-value)]
                            (rf/dispatch [::patient-events/search-legacy-pseudonym project-id s]))}]]
         (when patient
           [:div.bg-white.shadow.sm:rounded-lg.mt-4
            [:div.px-4.py-5.sm:p-6.shadow.bg-gray-50
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
                 :on-click #(rfe/push-state :pseudonymous-patient/home {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)})}
                "View patient record"]]]]])]]]]]))


(def patient-pseudonymous-registration-schema
  (m/schema [:map
             [:project-id int?]
             [:nhs-number [:fn #(nhs-number/valid? (nhs-number/normalise %))]]
             [:date-birth some?]
             [:sex [:enum :MALE :FEMALE :UNKNOWN]]]))

(defn register-pseudonymous-patient                         ;; TODO: create re-usable components from this example form
  [project-id]
  (let [data (reagent.core/atom {:project-id project-id})
        visited (reagent.core/atom #{})]
    (fn []
      (let [error @(rf/subscribe [::patient-subs/open-patient-error])
            valid? (m/validate patient-pseudonymous-registration-schema @data)
            submit-fn #(when valid?
                         (rf/dispatch [::patient-events/register-pseudonymous-patient @data]))
            _ (tap> {:values @data
                     :error  error
                     :valid? valid? :explain (m/explain patient-pseudonymous-registration-schema @data) :visited @visited})]
        [:div.space-y-6
         [:div.border.border-black.bg-white.shadow-lg.px-4.py-5.sm:rounded-lg.sm:p-6
          [:div.md:grid.md:grid-cols-3.md:gap-6
           [:div.md:col-span-1
            [:h3.text-lg.font-medium.leading-6.text-gray-900 "Register a patient"]
            [:div.mt-1.mr-12.text-sm.text-gray-500
             [:p "Enter patient details."]
             [:p.mt-4 "This is safe even if patient already registered"]
             [:p.mt-4 "Patient identifiable information is not stored but simply used to generate a pseudonym."]]]
           [:div.mt-5.md:mt-0.md:col-span-2
            [:form {:on-submit #(do (.preventDefault %) (submit-fn))}
             [:div.grid.grid-cols-6.gap-6
              [:div.col-span-6.sm:col-span-3.space-y-6
               [:div [ui/textfield-control (:nhs-number @data)
                      :label "NHS number" :auto-focus true :required true
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
            :on-click #(when valid? (submit-fn))}
           "Search or register patient »"]]]))))

(def patient-registration-schema
  (m/schema [:map
             [:project-id int?]
             [:nhs-number [:fn #(nhs-number/valid? (nhs-number/normalise %))]]]))

(defn register-patient
  [project-id]
  (let [data (reagent.core/atom {:project-id project-id})
        visited (reagent.core/atom #{})]
    (fn []
      (let [error @(rf/subscribe [::patient-subs/open-patient-error])
            valid? (m/validate patient-registration-schema @data)
            submit-fn #(when valid?
                         (rf/dispatch [::patient-events/register-patient-by-nhs-number @data]))]
        [:div.space-y-6
         [:div.bg-white.shadow.px-4.py-5.sm:rounded-lg.sm:p-6
          [:div.md:grid.md:grid-cols-3.md:gap-6
           [:div.md:col-span-1
            [:h3.text-lg.font-medium.leading-6.text-gray-900 "Register a patient"]
            [:div.mt-1.mr-12.text-sm.text-gray-500
             [:p "Enter patient details."]]]
           [:div.mt-5.md:mt-0.md:col-span-2
            [:form {:on-submit #(do (.preventDefault %) (submit-fn))}
             [:div.grid.grid-cols-1.gap-6
              [:div.col-span-1.sm:col-span-3.space-y-6
               [:div [ui/textfield-control (:nhs-number @data) :label "NHS number" :auto-focus true
                      :on-change #(swap! data assoc :nhs-number %)
                      :on-blur #(swap! visited conj :nhs-number)]]
               (when error [ui/box-error-message :message error])]
              [:div.col-span-1.sm:col-span-3.space-y-6
               [:div [ui/textfield-control (:first-names @data) :label "First names"
                      :on-change #(swap! data assoc :first-names %)
                      :on-blur #(swap! visited conj :first-names)]]
               (when error [ui/box-error-message :message error])]
              [:div.col-span-1.sm:col-span-3.space-y-6
               [:div [ui/textfield-control (:last-name @data) :label "Last name"
                      :on-change #(swap! data assoc :last-name %)
                      :on-blur #(swap! visited conj :last-name)]]
               (when error [ui/box-error-message :message error])]]]]]]
         [:div.flex.justify-end.mr-8
          [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
           {:type     "submit"
            :class    (if-not valid? "opacity-50 pointer-events-none" "hover:bg-blue-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-blue-500")
            :on-click #(when valid? (submit-fn))}
           "Search or register patient »"]]]))))


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

(defn inspect-edit-death-certificate [patient]
  (let [mode (reagent.core/atom :inspect)
        data (reagent.core/atom {})]
    (fn [patient]
      (let [date_death (:t_patient/date_death patient)
            certificate (:t_patient/death_certificate patient)]
        (case @mode
          :inspect [:<>
                    [:p (if-not date_death "Alive"
                                           [:<> [:span "Died " (dates/format-date date_death)]
                                            [:ul.mt-4.ml-4
                                             (when (:t_death_certificate/part1a certificate) [:li [:strong "1a: "] (:t_death_certificate/part1a certificate)])
                                             (when (:t_death_certificate/part1b certificate) [:li [:strong "1b: "] (:t_death_certificate/part1b certificate)])
                                             (when (:t_death_certificate/part1c certificate) [:li [:strong "1c: "] (:t_death_certificate/part1c certificate)])
                                             (when (:t_death_certificate/part2 certificate) [:li [:strong "2: "] (:t_death_certificate/part2 certificate)])]])]
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.text-xs.py-1.px-2.rounded.mt-4
                     {:on-click #(do (reset! data (merge (select-keys patient [:t_patient/patient_identifier :t_patient/date_death])
                                                         (:t_patient/death_certificate patient)))
                                     (reset! mode :edit))} "Edit"]]
          :edit [:<>
                 [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
                  [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-death"} "Date death"]
                  [:div.mt-1.sm:mt-0.sm:col-span-2
                   [ui/html-date-picker :name "date-death" :value (:t_patient/date_death patient)
                    :on-change #(swap! data assoc :t_patient/date_death %)]]]
                 [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
                  [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 "Cause of death"]
                  [:div.mt-1.sm:mt-0.sm:col-span-2
                   [ui/textfield-control (:t_death_certificate/part1a certificate)
                    :name "1a" :disabled (not (:t_patient/date_death @data))
                    :on-change #(swap! data assoc :t_death_certificate/part1a %)]]]
                 [:button.bg-red-500.hover:bg-red-700.text-white.text-xs.py-1.px-2.rounded
                  {:on-click #(do (reset! mode :inspect)
                                  (tap> {:death-data @data})
                                  (rf/dispatch [::patient-events/notify-death (merge
                                                                                {:t_patient/patient_identifier (:t_patient/patient_identifier patient)
                                                                                 :t_patient/date_death         nil}
                                                                                @data)]))}
                  "Save"]
                 [:button.ml-2.bg-blue-500.hover:bg-blue-700.text-white.text-xs.py-1.px-2.rounded
                  {:on-click #(do (reset! mode :inspect) (reset! data {}))}
                  "Cancel"]])))))


(defn multiple-sclerosis-main []
  (let [current-patient @(rf/subscribe [::patient-subs/current])
        most-recent-edss-encounter @(rf/subscribe [::patient-subs/most-recent-edss-encounter])
        _ (tap> {:most-recent most-recent-edss-encounter})]
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
        [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2
         (or (get-in most-recent-edss-encounter [:t_encounter/form_edss :t_form_edss/edss_score])
             (get-in most-recent-edss-encounter [:t_encounter/form_edss_fs :t_form_edss_fs/edss_score]))]]

       #_[:div.py-4.sm:py-5.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
          [:dt.text-sm.font-medium.text-gray-500 "Number of relapses in last 2 years"]
          [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2 0]]

       #_[:div.py-4.sm:py-5.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
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
                                                                                     :uk.gov.ons.nhspd/PCD2        %}]))]]]
       [:div.py-4.sm:py-5.sm:grid.sm:grid-cols-3.sm:gap-4.sm:px-6
        [:dt.text-sm.font-medium.text-gray-500 "Vital status"]
        [:dd.mt-1.text-sm.text-gray-900.sm:mt-0.sm:col-span-2
         [inspect-edit-death-certificate current-patient]]]]]]))



(defn edit-diagnosis
  "Edit diagnosis form.
  TODO: this should generate itself from a schema, including client side
  validation...."
  [diagnosis]
  [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for ::choose-diagnosis} "Diagnosis"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         (if (:t_diagnosis/id diagnosis)                    ;; if we already have a saved diagnosis, don't allow user to change
           [:h3.text-lg.font-medium.leading-6.text-gray-900 (get-in diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
           [snomed/select-snomed
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


(defn remove-medication-event-by-idx
  "Returns 'medication' with the "
  [medication event-idx]
  (update medication :t_medication/events
          (fn [evts]
            (->> evts
                 (map-indexed vector)
                 (filterv (fn [[i _]]
                            (not= event-idx i)))
                 (map second)))))

(defn edit-medication
  "Edit medication form.
  TODO: this should generate itself from a schema, including client side
  validation...."
  [medication]
  (tap> medication)
  [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for ::choose-medication} "Medication"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         (if (:t_medication/id medication)                  ;; if we already have a saved diagnosis, don't allow user to change
           [:h3.text-lg.font-medium.leading-6.text-gray-900 (get-in medication [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
           [snomed/select-snomed
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
         :on-change #(rf/dispatch-sync [::patient-events/set-current-medication (assoc medication :t_medication/date_to %)])]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "reason-for-stopping"} "Reason for stopping"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/select :name "reason-for-stopping" :value (some-> (:t_medication/reason_for_stopping medication) name)
         :choices (map name #{:CHANGE_OF_DOSE :ADVERSE_EVENT :NOT_APPLICABLE :PREGNANCY :LACK_OF_EFFICACY :PLANNING_PREGNANCY :RECORDED_IN_ERROR
                              :ALLERGIC_REACTION :ANTI_JCV_POSITIVE__PML_RISK :LACK_OF_TOLERANCE
                              :NON_ADHERENCE :OTHER
                              :PATIENT_CHOICE_CONVENIENCE :PERSISTENCE_OF_RELAPSES
                              :PERSISTING_MRI_ACTIVITY :DISEASE_PROGRESSION :SCHEDULED_STOP})
         :disabled? (nil? (:t_medication/date_to medication))
         :select-fn #(rf/dispatch-sync [::patient-events/set-current-medication
                                        (assoc medication :t_medication/reason_for_stopping (if (str/blank? %) nil (keyword %)))])]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "more_information"} "More information"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/textarea :name "more_information"
         :value (:t_medication/more_information medication)
         :on-change #(rf/dispatch-sync [::patient-events/set-current-medication
                                        (assoc medication :t_medication/more_information %)])]]]
      (for [[idx event] (map-indexed vector (:t_medication/events medication))]
        [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
         [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2
          (:t_medication_event/type event)
          [ui/button :label "Delete"
           :on-click #(rf/dispatch [::patient-events/set-current-medication
                                    (remove-medication-event-by-idx medication idx)])]]
         [:div.mt-1.sm:mt-0.sm:col-span-2
          [pc4.snomed.views/select-snomed
           :id (str ::choose-medication-event-concept idx)
           :common-choices []
           :value (:t_medication_event/event_concept event)
           :constraint "<404684003"
           :select-fn #(rf/dispatch [::patient-events/set-current-medication
                                     (assoc-in medication [:t_medication/events idx :t_medication_event/event_concept] %)])]]])]]]])

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
                                              :t_medication/patient_fk (:t_patient/id current-patient)
                                              :t_patient/patient_identifier (:t_patient/patient_identifier current-patient))])}
                  {:id       ::delete-action
                   :title    "Delete"
                   :on-click #(if (:t_medication/id current-medication)
                                (rf/dispatch [::patient-events/delete-medication
                                              (assoc current-medication :t_patient/patient_identifier (:t_patient/patient_identifier current-patient))])
                                (rf/dispatch [::patient-events/clear-medication]))}
                  {:id       ::add-event-action
                   :title    "Add event"
                   :on-click #(rf/dispatch [::patient-events/set-current-medication
                                            (update current-medication :t_medication/events (fnil conj []) {:t_medication_event/type :ADVERSE_EVENT})])}
                  {:id       ::cancel-action
                   :title    "Cancel"
                   :on-click #(rf/dispatch [::patient-events/clear-medication])}]
        :on-close #(rf/dispatch [::patient-events/clear-medication])])
     [ui/section-heading "Medications"
      :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                {:on-click #(rf/dispatch [::patient-events/set-current-medication {:t_medication/reason_for_stopping :NOT_APPLICABLE}])} "Add medication"]]
     [ui/list-entities-fixed
      :items sorted-medications
      :headings ["Medication" "From" "To" "Reason for stopping"]
      :width-classes {"Medication" "w-3/6" "From" "w-1/6" "To" "w-1/6" "Reason for stopping" "w-1/6"}

      :id-key :t_medication/id
      :value-keys [#(get-in % [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])
                   #(dates/format-date (:t_medication/date_from %))
                   #(dates/format-date (:t_medication/date_to %))
                   #(some-> % :t_medication/reason_for_stopping name)]
      :on-edit (fn [medication] (rf/dispatch [::patient-events/set-current-medication medication]))]]))


(def impact-choices ["UNKNOWN" "NON_DISABLING" "DISABLING" "SEVERE"])

(defn ms-event-site-to-string [k]
  (str/capitalize (str/join " " (rest (str/split (name k) #"_")))))

(defn edit-event [event & {:keys [on-change]}]
  (let [all-ms-event-types @(rf/subscribe [::lookup-subs/all-ms-event-types])]
    [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
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
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "notes"} "Notes"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/textarea :name "notes"
          :value (:t_ms_event/notes event)
          :on-change #(on-change (assoc event :t_ms_event/notes %))]]]]]]))


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
                                                         :t_ms_event/impact                        "UNKNOWN"})} "Add event"]]
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

(def edss-scores
  ["SCORE0_0" "SCORE1_0" "SCORE1_5" "SCORE2_0" "SCORE2_5" "SCORE3_0" "SCORE3_5"
   "SCORE4_0" "SCORE4_5" "SCORE5_0" "SCORE5_5" "SCORE6_0" "SCORE6_5" "SCORE7_0"
   "SCORE7_5" "SCORE8_0" "SCORE8_5" "SCORE9_0" "SCORE9_5" "SCORE10_0"
   "SCORE_LESS_THAN_4"])

(def smoking-status-choices #{"NEVER_SMOKED" "CURRENT_SMOKER" "EX_SMOKER"})

(s/def :t_encounter/patient_fk number?)
(s/def :t_form_ms_relapse/in_relapse boolean?)
(s/def :t_smoking_history/status smoking-status-choices)
(s/def :t_smoking_history/current_cigarettes_per_day int?)
(s/def :t_form_weight_height/weight_kilogram (s/and #(instance? Big %) #(.gte % 20) #(.lte % 200)))
(s/def :t_form_weight_height/height_metres (s/nilable (s/and #(instance? Big %) #(.gte % 0.5) #(.lte % 3))))
(s/def ::encounter
  (s/keys :req [:t_encounter/date_time
                :t_patient/patient_identifier
                :t_episode/id
                :t_encounter_template/id]
          :opt [:t_form_ms_relapse/in_relapse
                :t_form_edss/edss_score
                :t_ms_disease_course/id
                :t_form_weight_height/height_metres
                :t_form_weight_height/weight_kilogram
                :t_smoking_history/status
                :t_smoking_history/current_cigarettes_per_day]))

(defn edit-encounter [encounter & {:keys [on-change]}]
  (let [current-project @(rf/subscribe [::project-subs/current])
        all-encounter-templates @(rf/subscribe [::project-subs/active-encounter-templates])
        all-ms-disease-courses @(rf/subscribe [::lookup-subs/all-ms-disease-courses])
        _ (tap> {:encounter               encounter
                 :project                 current-project
                 :all-encounter-templates all-encounter-templates
                 :all-ms-disease-courses  all-ms-disease-courses})]
    [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
     [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
      [:div
       [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
        [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
         [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
         [:div.mt-1.sm:mt-0.sm:col-span-2
          [ui/html-date-picker :name "date" :value (:t_encounter/date_time encounter)
           :on-change #(on-change (assoc encounter :t_encounter/date_time %))]]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "encounter-template"} "Type"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/select :name "encounter-template"
          :value encounter
          :choices all-encounter-templates
          :sort? false
          :select-fn #(on-change (merge encounter %))
          :id-key :t_encounter_template/id
          :display-key (fn [et] (when et (:t_encounter_template/title et)))]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "edss"} "EDSS"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         (if (:t_form_edss_fs encounter)
           [:<> [ui/select :name "edss" :sort? false :disabled? true :value (:t_form_edss_fs/edss_score encounter)]
            [:p.text-sm.text-gray-500 "You cannot edit a functional systems score here. Please use PatientCare v3."]]
           [ui/select :name "edss"
            :sort? false
            :choices edss-scores
            :no-selection-string "Not recorded"
            :value (:t_form_edss/edss_score encounter)
            :select-fn #(on-change (if % (merge encounter {:t_form_edss/edss_score %})
                                         (dissoc encounter :t_form_edss/edss_score)))])]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "in-relapse"} ""]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/checkbox :name "in-relapse"
          :label "In relapse?"
          :description "Tick if the EDSS was recorded when patient in relapse."
          :checked (:t_form_ms_relapse/in_relapse encounter)
          :on-change #(on-change (merge encounter {:t_form_ms_relapse/in_relapse %}))]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "disease-course"} "Current disease course"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/select :name "disease-course"
          :sort? true
          :choices all-ms-disease-courses
          :display-key :t_ms_disease_course/name
          :id-key :t_ms_disease_course/id
          :no-selection-string "Not recorded"
          :value encounter
          :select-fn #(on-change (if % (merge encounter %)
                                       (dissoc encounter :t_ms_disease_course/id :t_ms_disease_course/name)))]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "weight"} "Weight (kg)"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/textfield-control (str (:t_form_weight_height/weight_kilogram encounter))
          :name "weight"
          :type "number"
          :on-change #(on-change (if % (merge encounter {:t_form_weight_height/weight_kilogram (Big. %)})
                                       (dissoc encounter :t_form_weight_height/weight_kilogram)))]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "height"} "Height (m)"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/textfield-control (str (:t_form_weight_height/height_metres encounter))
          :name "height"
          :type "number"
          :on-change #(on-change (if % (merge encounter {:t_form_weight_height/height_metres (Big. %)})
                                       (dissoc encounter :t_form_weight_height/height_metres)))]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "smoking"} "Smoking history"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/select :name "smoking"
          :sort? true
          :choices smoking-status-choices
          :no-selection-string "Not recorded"
          :value (:t_smoking_history/status encounter)
          :select-fn #(on-change (if % (merge encounter
                                              {:t_smoking_history/status %}
                                              (when-not (:t_smoking_history/current_cigarettes_per_day encounter) {:t_smoking_history/current_cigarettes_per_day 0}))
                                       (dissoc encounter :t_smoking_history/status :t_smoking_history/current_cigarettes_per_day)))]]]
       [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
        [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "cigarettes"} "Cigarettes per day"]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [ui/textfield-control (str (:t_smoking_history/current_cigarettes_per_day encounter))
          :name "cigarettes" :type "number"
          :on-change #(on-change (if % (merge encounter {:t_smoking_history/current_cigarettes_per_day (js/parseInt %)})
                                       (dissoc encounter :t_smoking_history/current_cigarettes_per_day)))]]]]]]))

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
                                   (filter :t_encounter/active)
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
            :content [edit-encounter editing-encounter' :on-change #(reset! editing-encounter %)]
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
                                                                 {:t_patient/patient_identifier (:t_patient/patient_identifier current-patient)
                                                                  :t_encounter/patient_fk       (:t_patient/id current-patient)
                                                                  :t_episode/id                 (:t_episode/id active-episode-for-patient)
                                                                  :t_encounter/episode_fk       (:t_episode/id active-episode-for-patient)}))}
                    "Add encounter"]]
         (tap> sorted-encounters)
         [ui/list-entities-fixed
          :items sorted-encounters
          :headings ["Date" "Type" "EDSS" "Disease course" "In relapse?" "Weight"]
          :width-classes {"Date" "w-1/6" "Type" "w-1/6" "EDSS" "w-1/6" "Disease course" "w-1/6" "In relapse?" "w-1/6" "Weight" "w-1/6"}
          :id-key :t_encounter/id
          :value-keys [#(dates/format-date (:t_encounter/date_time %))
                       #(get-in % [:t_encounter/encounter_template :t_encounter_template/title])
                       #(or (get-in % [:t_encounter/form_edss :t_form_edss/edss_score])
                            (get-in % [:t_encounter/form_edss_fs :t_form_edss_fs/edss_score]))
                       #(get-in % [:t_encounter/form_ms_relapse :t_ms_disease_course/name])
                       #(case (get-in % [:t_encounter/form_ms_relapse :t_form_ms_relapse/in_relapse])
                          true "Yes" false "No" "")
                       #(when-let [wt (get-in % [:t_encounter/form_weight_height :t_form_weight_height/weight_kilogram])]
                          (str wt "kg"))]
          :on-edit (fn [encounter]
                     (tap> {:editing-encounter true
                            :encounter         encounter})
                     (reset! editing-encounter (merge encounter ;; flatten all of the to-one relationships...
                                                      {:t_episode/id                 (:t_encounter/episode_fk encounter)
                                                       :t_patient/patient_identifier (:t_patient/patient_identifier current-patient)}
                                                      (:t_encounter/encounter_template encounter)
                                                      (:t_encounter/form_edss encounter)
                                                      (:t_encounter/form_ms_relapse encounter)
                                                      (:t_encounter/form_weight_height encounter)
                                                      (:t_encounter/form_smoking_history encounter))))]]))))

(s/def ::result-renal (s/keys :req [:t_result_renal/date :t_patient/patient_identifier]))
(s/def ::result-full-blood-count (s/keys :req [:t_result_full_blood_count/date :t_patient/patient_identifier]))
(s/def ::result-ecg (s/keys :req [:t_result_ecg/date :t_patient/patient_identifier]))
(s/def ::result-urinalysis (s/keys :req [:t_result_urinalysis/date :t_patient/patient_identifier]))
(s/def ::result-liver-function (s/keys :req [:t_result_liver_function/date :t_patient/patient_identifier]))

(defn make-edit-result [title entity-name]
  (let [date-key (keyword (name entity-name) "date")
        notes-key (keyword (name entity-name) "notes")]
    (fn [result & {:keys [on-change]}]
      [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
       [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
        [:div
         [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
          [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
           [:div.mt-1.sm:mt-0.sm:col-span-2
            [:div.w-full.rounded-md.shadow-sm.space-y-2
             [:h3.text-lg.font-medium.leading-6.text-gray-900 title]]]]
          [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
           [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
           [:div.mt-1.sm:mt-0.sm:col-span-2
            [ui/html-date-picker :name "date" :value (date-key result)
             :on-change #(on-change (assoc result date-key %))]]]]
         [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
          [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "notes"} "Notes"]
          [:div.mt-1.sm:mt-0.sm:col-span-2
           [ui/textarea :name "notes"
            :value (notes-key result)
            :on-change #(on-change (assoc result notes-key %))]]]]]])))


(s/def :t_result_mri_brain/compare_to_result_mri_brain_fk (s/nilable int?))
(s/def ::result-mri-brain (s/keys :req [:t_result_mri_brain/date
                                        :t_patient/patient_identifier]
                                  :opt [:t_result_mri_brain/compare_to_result_mri_brain_fk
                                        :t_result_mri_brain/total_t2_hyperintense
                                        :t_result_mri_brain/change_t2_hyperintense
                                        :t_result_mri_brain/total_gad_enhancing_lesions
                                        :t_result_mri_brain/multiple_sclerosis_summary]))
(def lesion-count-help-text "Format as one of x, ~x, x+/-y, x-y or >x'")
(defn edit-result-mri-brain [result & {:keys [on-change]}]
  (let [t2-mode (reagent.core/atom :absolute)]
    (fn [result & {:keys [on-change]}]
      (when-not (str/blank? (:t_result_mri_brain/change_t2_hyperintense result))
        (reset! t2-mode :relative))
      (when-not (str/blank? (:t_result_mri_brain/total_t2_hyperintense result))
        (reset! t2-mode :absolute))
      [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
       [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
        [:div
         [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
          [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
           [:div.mt-1.sm:mt-0.sm:col-span-2
            [:div.w-full.rounded-md.shadow-sm.space-y-2
             [:h3.text-lg.font-medium.leading-6.text-gray-900 "MRI brain"]]]]
          [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
           [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
           [:div.mt-1.sm:mt-0.sm:col-span-2
            [ui/html-date-picker :name "date" :value (:t_result_mri_brain/date result)
             :on-change #(on-change (assoc result :t_result_mri_brain/date %))]]]]
         [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
          [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "report"} "Report"]
          [:div.mt-1.sm:mt-0.sm:col-span-2
           [ui/textarea :name "report"
            :value (:t_result_mri_brain/report result)
            :on-change #(on-change (assoc result :t_result_mri_brain/report %))]]]
         [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
          [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "summary"} "Interpretation"]
          [:div.mt-1.sm:mt-0.sm:col-span-2
           [ui/select :name "summary"
            :value (:t_result_mri_brain/multiple_sclerosis_summary result)
            :choices ["TYPICAL" "ATYPICAL" "NON_SPECIFIC" "ABNORMAL_UNRELATED" "NORMAL"]
            :default-value "TYPICAL" :sort? false
            :select-fn #(on-change (assoc result :t_result_mri_brain/multiple_sclerosis_summary %))]]]
         [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
          [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "summary"} "With gadolinium?"]
          [:div.pt-2.sm:mt-0.sm:col-span-2
           [ui/checkbox :name "with-gad" :description "Was the scan performed with gadolinium?"
            :checked (:t_result_mri_brain/with_gadolinium result)
            :on-change #(on-change (assoc result :t_result_mri_brain/with_gadolinium %))]]]
         (when (:t_result_mri_brain/with_gadolinium result)
           [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
            [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "num-gad-lesions"} "Number of enhancing lesions"]
            [:div.mt-1.sm:mt-0.sm:col-span-2
             [ui/textfield-control (:t_result_mri_brain/total_gad_enhancing_lesions result)
              :name "num-gad-lesions" :type "text"
              :help-text lesion-count-help-text
              :on-change #(on-change (if % (merge result {:t_result_mri_brain/total_gad_enhancing_lesions %})
                                           (dissoc result :t_result_mri_brain/total_gad_enhancing_lesions)))]]])
         [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
          [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "t2-lesions"} "T2 lesions"]
          [:div.mt-1.sm:mt-0.sm:col-span-2
           (let [disabled? (or (:t_result_mri_brain/change_t2_hyperintense result)
                               (:t_result_mri_brain/total_t2_hyperintense result))]
             [:<> [ui/select :name "t2-lesions"
                   :value @t2-mode
                   :display-key (fn [v] (str/upper-case (name v)))
                   :choices [:absolute :relative]
                   :disabled? disabled?
                   :select-fn #(reset! t2-mode %)]
              (when disabled? [:p.pl-4.text-sm.text-gray-500.italic "You cannot change mode if data recorded"])])]]
         (when (= :absolute @t2-mode)
           [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
            [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "total-t2-lesions"} "Total number of T2 hyperintense lesions"]
            [:div.mt-1.sm:mt-0.sm:col-span-2
             [ui/textfield-control (:t_result_mri_brain/total_t2_hyperintense result)
              :name "total-t2-lesions" :type "text"
              :help-text lesion-count-help-text
              :on-change (fn [v] (on-change (cond-> (dissoc result :t_result_mri_brain/change_t2_hyperintense
                                                            :t_result_mri_brain/total_t2_hyperintense)
                                                    v (assoc :t_result_mri_brain/total_t2_hyperintense v))))]]])
         (when (= :relative @t2-mode)
           [:<>
            [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
             [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "previous-scan"} "Previous scan"]
             [:div.mt-1.sm:mt-0.sm:col-span-2
              [ui/select :name "previous-scan"
               :value {:t_result_mri_brain/id (:t_result_mri_brain/compare_to_result_mri_brain_fk result)}
               :choices (if (:t_result_mri_brain/date result)
                          @(rf/subscribe [::patient-subs/results-mri-brains {:before-date (:t_result_mri_brain/date result)}])
                          @(rf/subscribe [::patient-subs/results-mri-brains]))
               :id-key :t_result_mri_brain/id
               :no-selection-string "<None>"
               :display-key #(pc4.dates/format-date (:t_result_mri_brain/date %))
               :select-fn #(do
                             (tap> {:selected-scan %})
                             (on-change (if % (assoc result :t_result_mri_brain/compare_to_result_mri_brain_fk (:t_result_mri_brain/id %))
                                              (dissoc result :t_result_mri_brain/compare_to_result_mri_brain_fk))))]
              (when-let [compare-result-id (:t_result_mri_brain/compare_to_result_mri_brain_fk result)]
                (let [compare-result (->> @(rf/subscribe [::patient-subs/results-mri-brains])
                                          (filter #(= (:t_result_mri_brain/id %) compare-result-id)) first)]
                  [:p.pl-4.text-sm.text-gray-500.italic "\"" (:t_result_mri_brain/report compare-result) "\""]))]]
            [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
             [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "change-t2-lesions"} "Change in T2 hyperintense lesions"]
             [:div.mt-1.sm:mt-0.sm:col-span-2
              [ui/textfield-control (:t_result_mri_brain/change_t2_hyperintense result)
               :name "change-t2-lesions" :type "text"
               :help-text (if (:t_result_mri_brain/compare_to_result_mri_brain_fk result)
                            "Use +x or -x to record the change in T2 hyperintense lesions compared to a previous scan"
                            "Use +x or -x to record the change in T2 hyperintense lesions compared to the scan above")
               :on-change (fn [v] (on-change (cond-> (dissoc result :t_result_mri_brain/total_t2_hyperintense
                                                             :t_result_mri_brain/change_t2_hyperintense)
                                                     v (assoc :t_result_mri_brain/change_t2_hyperintense v))))]]]])]]])))

(def mri-spine-types #{"CERVICAL_AND_THORACIC" "CERVICAL" "LUMBOSACRAL" "WHOLE_SPINE" "THORACIC"})
(s/def :t_result_mri_spine/type mri-spine-types)

(s/def ::result-mri-spine (s/keys :req [:t_result_mri_spine/date
                                        :t_result_mri_spine/type
                                        :t_patient/patient_identifier]))
(defn edit-result-mri-spine [result & {:keys [on-change]}]
  [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         [:h3.text-lg.font-medium.leading-6.text-gray-900 "MRI spine"]]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date" :value (:t_result_mri_spine/date result)
         :on-change #(on-change (assoc result :t_result_mri_spine/date %))]]]]
     [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
      [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "spine-type"} "Type"]
      [:div.mt-1.sm:mt-0.sm:col-span-2
       (ui/select :value (:t_result_mri_spine/type result)
                  :choices mri-spine-types
                  :select-fn #(on-change (assoc result :t_result_mri_spine/type %)))]]
     [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
      [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "report"} "Report"]
      [:div.mt-1.sm:mt-0.sm:col-span-2
       [ui/textarea :name "report"
        :value (:t_result_mri_spine/report result)
        :on-change #(on-change (assoc result :t_result_mri_spine/report %))]]]]]])


(def ocb-results #{"POSITIVE" "PAIRED" "NEGATIVE" "EQUIVOCAL"})
(s/def :t_result_csf_ocb/result ocb-results)
(s/def ::result-csf-ocb (s/keys :req [:t_result_csf_ocb/date
                                      :t_result_csf_ocb/result]))

(defn edit-result-csf-ocb [result & {:keys [on-change]}]
  [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         [:h3.text-lg.font-medium.leading-6.text-gray-900 "CSF oligoclonal bands"]]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date" :value (:t_result_csf_ocb/date result)
         :on-change #(on-change (assoc result :t_result_csf_ocb/date %))]]]]
     [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
      [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "result"} "Result"]
      [:div.mt-1.sm:mt-0.sm:col-span-2
       (ui/select :value (:t_result_csf_ocb/result result)
                  :choices ocb-results
                  :no-selection-string ""
                  :select-fn #(on-change (assoc result :t_result_csf_ocb/result %)))]]]]])

(def jc-virus-results #{"POSITIVE" "NEGATIVE"})
(s/def :t_result_jc_virus/jc_virus jc-virus-results)
(s/def ::result-jc-virus (s/keys :req [:t_result_jc_virus/date
                                       :t_result_jc_virus/jc_virus]))
(defn edit-result-jc-virus [result & {:keys [on-change]}]
  [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         [:h3.text-lg.font-medium.leading-6.text-gray-900 "JC virus"]]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date" :value (:t_result_jc_virus/date result)
         :on-change #(on-change (assoc result :t_result_jc_virus/date %))]]]]
     [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
      [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "result"} "Result"]
      [:div.mt-1.sm:mt-0.sm:col-span-2
       (ui/select :value (:t_result_jc_virus/jc_virus result)
                  :choices jc-virus-results
                  :no-selection-string ""
                  :select-fn #(on-change (assoc result :t_result_jc_virus/jc_virus %)))]]]]])

(s/def ::result-thyroid-function (s/keys :req [:t_result_thyroid_function/date :t_patient/patient_identifier]))
(defn edit-result-thyroid-function [result & {:keys [on-change]}]
  [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         [:h3.text-lg.font-medium.leading-6.text-gray-900 "Thyroid function"]]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date" :value (:t_result_thyroid_function/date result)
         :on-change #(on-change (assoc result :t_result_thyroid_function/date %))]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "free-t4"} "Free T4"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/textfield-control (str (:t_result_thyroid_function/free_t4 result))
         :name "free-t4" :type "number"
         :on-change #(on-change (if % (merge result {:t_result_thyroid_function/free_t4 (js/parseFloat %)})
                                      (dissoc result :t_result_thyroid_function/free_t4)))]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5.pb-2
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "tsh"} "TSH"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/textfield-control (str (:t_result_thyroid_function/tsh result))
         :name "tsh" :type "number"
         :on-change #(on-change (if % (merge result {:t_result_thyroid_function/tsh (js/parseFloat %)})
                                      (dissoc result :t_result_thyroid_function/tsh)))]]]]]]])

(def supported-results
  [{:t_result_type/name               "MRI brain"
    :t_result_type/result_entity_name "ResultMriBrain"
    ::editor                          edit-result-mri-brain
    ::spec                            ::result-mri-brain
    ::initial-data                    {:t_result_mri_brain/with_gadolinium false
                                       :t_result_mri_brain/report          ""}}
   {:t_result_type/name               "MRI spine"
    :t_result_type/result_entity_name "ResultMriSpine"
    ::editor                          edit-result-mri-spine
    ::spec                            ::result-mri-spine
    ::initial-data                    {:t_result_mri_spine/type   "CERVICAL_AND_THORACIC"
                                       :t_result_mri_spine/report ""}}
   {:t_result_type/name               "CSF OCB"
    :t_result_type/result_entity_name "ResultCsfOcb"
    ::editor                          edit-result-csf-ocb
    ::spec                            ::result-csf-ocb}
   {:t_result_type/name               "JC virus"
    :t_result_type/result_entity_name "ResultJCVirus"
    ::editor                          edit-result-jc-virus
    ::spec                            ::result-jc-virus}
   {:t_result_type/name               "Renal profile"
    :t_result_type/result_entity_name "ResultRenalProfile"
    ::editor                          (make-edit-result "Renal profile" :t_result_renal)
    ::spec                            ::result-renal}
   {:t_result_type/name               "Full blood count"
    :t_result_type/result_entity_name "ResultFullBloodCount"
    ::editor                          (make-edit-result "Full blood count" :t_result_full_blood_count)
    ::spec                            ::result-full-blood-count}
   {:t_result_type/name               "Electrocardiogram (ECG)"
    :t_result_type/result_entity_name "ResultECG"
    ::editor                          (make-edit-result "Electrocardiogram (ECG)" :t_result_ecg)
    ::spec                            ::result-ecg}
   {:t_result_type/name               "Urinalysis"
    :t_result_type/result_entity_name "ResultUrinalysis"
    ::editor                          (make-edit-result "Urinalysis" :t_result_urinalysis)
    ::spec                            ::result-urinalysis}
   {:t_result_type/name               "Liver function tests"
    :t_result_type/result_entity_name "ResultLiverFunction"
    ::editor                          (make-edit-result "Liver function tests" :t_result_liver_function)
    ::spec                            ::result-liver-function}
   {:t_result_type/name               "Thyroid function tests"
    :t_result_type/result_entity_name "ResultThyroidFunction"
    ::editor                          edit-result-thyroid-function
    ::spec                            ::result-thyroid-function}])

(def results-lookup (zipmap (map :t_result_type/result_entity_name supported-results) supported-results))

(defn editor-for-result [result]
  (get-in results-lookup [(:t_result_type/result_entity_name result) ::editor]))

(defn spec-for-result [result]
  (get-in results-lookup [(:t_result_type/result_entity_name result) ::spec]))

(defn initial-data-for-result [result]
  (get-in results-lookup [(:t_result_type/result_entity_name result) ::initial-data]))

(defn truncate [s length]
  (when s
    (let [len (count s)] (if (> len length) (str (subs s 0 length) "…") s))))

(defn list-investigations
  "This shows a list of investigations"
  []
  (let [new-result (reagent.core/atom (first supported-results))]
    (fn []
      (let [editing-result @(rf/subscribe [::patient-subs/current-result])
            current-patient @(rf/subscribe [::patient-subs/current])
            current-project @(rf/subscribe [::project-subs/current])
            sorted-results @(rf/subscribe [::patient-subs/results])
            new-result' @new-result
            editor (editor-for-result editing-result)
            spec (spec-for-result editing-result)
            valid? (when spec (s/valid? spec editing-result))
            _ (tap> {:editing-result editing-result
                     :valid?         valid?
                     :problems       (when spec (s/explain-data spec editing-result))})]
        [:<>
         (when (and editing-result editor)
           [ui/modal
            :content [editor editing-result :on-change #(rf/dispatch [::patient-events/set-current-result %])]
            :actions [{:id        ::save-action
                       :title     "Save"
                       :disabled? (not valid?)
                       :role      :primary
                       :on-click  #(rf/dispatch [::patient-events/save-result editing-result])}
                      {:id       ::delete-action
                       :title    "Delete"
                       :hidden?  (not (:t_result/id editing-result)) ;; hide when new
                       :on-click #(do (when (:t_result/id editing-result)
                                        (rf/dispatch [::patient-events/delete-result editing-result]))
                                      (rf/dispatch [::patient-events/clear-current-result]))}
                      {:id       ::cancel-action
                       :title    "Cancel"
                       :on-click #(rf/dispatch [::patient-events/clear-current-result])}]
            :on-close #(rf/dispatch [::patient-events/clear-current-result])])
         [ui/section-heading "Investigations"
          :buttons
          [:div.grid.grid-cols-2
           [:div.col-span-1
            [ui/select :value new-result' :choices supported-results :display-key :t_result_type/name :id-key :t_result_type/result_entity_name
             :select-fn #(reset! new-result %)]]
           [:div.col-span-1
            [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
             {:on-click #(rf/dispatch [::patient-events/set-current-result
                                       (merge
                                         (initial-data-for-result new-result')
                                         {:t_result_type/result_entity_name (:t_result_type/result_entity_name new-result')
                                          :t_patient/patient_identifier     (:t_patient/patient_identifier current-patient)})])}
             (str "Add " (:t_result_type/name new-result'))]]]]
         [ui/list-entities-fixed
          :items sorted-results
          :headings ["Date" "Investigation" "Result"]
          :width-classes {"Date" "w-1/6" "Investigation" "w-1/6" "Result" "w-4/6"}
          :id-key :t_result/id
          :value-keys [#(dates/format-date (:t_result/date %))
                       :t_result_type/name
                       (fn [result] (truncate (:t_result/summary result) 120))] ;; TODO: should use css to overflow hidden instead
          :on-edit (fn [result] (rf/dispatch [::patient-events/set-current-result (assoc result :t_patient/patient_identifier (:t_patient/patient_identifier current-patient))]))]]))))

(s/def :t_episode/date_registration #(instance? Date %))
(s/def :t_episode/date_discharge #(instance? Date %))
(s/def ::admission (s/and
                     (s/keys :req [:t_episode/date_registration
                                   :t_episode/date_discharge])
                     #(>= (Date/compare (:t_episode/date_discharge %) (:t_episode/date_registration %)) 0)))

(defn edit-admission [admission & {:keys [on-change]}]
  [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    [:div
     [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
      [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [:div.w-full.rounded-md.shadow-sm.space-y-2
         [:h3.text-lg.font-medium.leading-6.text-gray-900 "Admission"]]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date from"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date" :value (:t_episode/date_registration admission)
         :max-date (Date.)
         :on-change #(on-change (assoc admission :t_episode/date_registration %))]]]
      [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
       [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date"} "Date to"]
       [:div.mt-1.sm:mt-0.sm:col-span-2
        [ui/html-date-picker :name "date" :value (:t_episode/date_discharge admission)
         :min-date (:t_episode/date_registration admission)
         :max-date (Date.)
         :on-change #(on-change (assoc admission :t_episode/date_discharge %))]]]

      #_[ui/list-entities-fixed
         :items (map-indexed (fn [idx item] (assoc item :id idx)) (:t_episode/diagnoses admission))
         :headings ["Diagnoses / problems"]
         :id-key :id
         :value-keys [#(get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
         :on-delete (fn [diag] (on-change (assoc admission
                                            :t_episode/diagnoses
                                            (remove #(= (get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/id])
                                                        (get-in diag [:t_diagnosis/diagnosis :info.snomed.Concept/id]))
                                                    (:t_episode/diagnoses admission)))))]
      #_[:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
         [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for ::choose-diagnosis} "Add a problem/ diagnosis"]
         [:div.mt-1.sm:mt-0.sm:col-span-2
          [:div.w-full.rounded-md.shadow-sm.space-y-2
           [snomed/select-snomed
            :id ::choose-diagnosis
            :common-choices []
            :value nil
            :constraint "<404684003"
            :select-fn (fn [selected]
                         (on-change (update admission :t_episode/diagnoses conj {:t_diagnosis/diagnosis selected})))]]]]]]]])


(defn list-admissions
  "This shows a list of admissions."
  []
  (let [editing-admission (reagent.core/atom nil)]
    (fn []
      (let [current-patient @(rf/subscribe [::patient-subs/current])
            current-project @(rf/subscribe [::project-subs/current])
            admission-episodes @(rf/subscribe [::patient-subs/admission-episodes])
            editing-admission' @editing-admission
            valid? (s/valid? ::admission editing-admission')
            _ (tap> {:admission editing-admission'
                     :valid?    valid?
                     :explain   (s/explain-data ::admission editing-admission')})]
        [:<>
         (when editing-admission'
           [ui/modal
            :content [edit-admission editing-admission' :on-change #(reset! editing-admission %)]
            :actions [{:id        ::save-action
                       :title     "Save"
                       :disabled? (not valid?)
                       :role      :primary
                       :on-click  #(do (rf/dispatch [::patient-events/save-admission editing-admission'])
                                       (reset! editing-admission nil))}
                      {:id       ::delete-action
                       :title    "Delete"
                       :hidden?  (not (:t_episode/id editing-admission')) ;; hide when new
                       :on-click #(do (when (:t_episode/id editing-admission')
                                        (rf/dispatch [::patient-events/delete-admission editing-admission']))
                                      (reset! editing-admission nil))}
                      {:id       ::cancel-action
                       :title    "Cancel"
                       :on-click #(reset! editing-admission nil)}]
            :on-close #(reset! editing-admission nil)])
         [ui/section-heading "Admissions"
          :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                    {:on-click #(reset! editing-admission {:t_episode/patient_fk (:t_patient/id current-patient)})}
                    "Add admission"]]
         [ui/list-entities-fixed
          :items admission-episodes
          :headings ["From" "To" "Problems"]
          :width-classes {"From" "w-1/6" "To" "w-1/6" "Problems" "w-4/6"}
          :id-key :t_episode/id
          :value-keys [#(dates/format-date (:t_episode/date_registration %))
                       #(dates/format-date (:t_episode/date_discharge %))
                       :t_episode/diagnoses]
          :on-edit (fn [admission] (reset! editing-admission (select-keys admission [:t_episode/id
                                                                                     :t_episode/patient_fk
                                                                                     :t_episode/project_fk
                                                                                     :t_episode/date_registration
                                                                                     :t_episode/date_discharge])))]]))))

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
            project @(rf/subscribe [::project-subs/current])
            loading? @(rf/subscribe [::patient-subs/loading?])
            authenticated-user @(rf/subscribe [::subs/authenticated-user])
            _ (tap> {:patient patient :user authenticated-user})]
        [:<>
         (when loading?
           [:div.flex.h-screen
            [:div.m-auto
             [:div
              [:div.flex.justify-center.items-center.mb-8
               [:p.font-sans.font-thin.text-gray-500 "Loading patient record"]]
              [:div.flex.justify-center.items-center
               [:div.animate-spin.rounded-full.h-32.w-32.border-b-2.border-gray-900]]]]])

         (when patient
           [:div
            [ui/patient-banner
             :name (:t_patient/sex patient)
             :nhs-number (when (= :FULL (:t_patient/status patient)) (:t_patient/nhs_number patient)) ;; when non-pseudonymous, show NHS number in banner
             :born (when-let [dob (:t_patient/date_birth patient)] (.getYear dob))
             :address (:t_episode/stored_pseudonym patient)
             :on-close #(when-let [project-id (:t_project/id project)]
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
      (let [route @(rf/subscribe [::subs/current-route])
            authenticated-user @(rf/subscribe [::subs/authenticated-user])
            current-project @(rf/subscribe [::project-subs/current])]
        (when current-project
          [:<>
           [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200
            [:ul.flex
             [:div.font-bold.text-lg.min-w-min.mr-6.py-1 (:t_project/title current-project)]
             [ui/flat-menu
              (if (:t_project/pseudonymous current-project)
                [{:title "Home" :id :home}
                 {:title "Register" :id :pseudonymous-register}
                 {:title "Search" :id :pseudonymous-search}
                 {:title "Users" :id :users}]
                [{:title "Home" :id :home}
                 {:title "Search / register" :id :register-nhs-number}
                 {:title "Users" :id :users}])
              :selected-id @selected-page
              :select-fn #(do (reset! selected-page %)
                              (rf/dispatch [::patient-events/search-legacy-pseudonym (:t_project/id current-project) ""]))]]]
           (case @selected-page
             :home [inspect-project current-project]
             :pseudonymous-search [search-by-pseudonym-panel (:t_project/id current-project)]
             :pseudonymous-register [register-pseudonymous-patient (:t_project/id current-project)]
             :register-nhs-number [register-patient (:t_project/id current-project)]
             :users [list-users (:t_project/users current-project)])])))))