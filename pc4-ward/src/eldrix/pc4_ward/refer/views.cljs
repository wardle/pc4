(ns eldrix.pc4-ward.refer.views
  (:require [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [com.eldrix.pc4.commons.debounce :as debounce]
            [eldrix.pc4-ward.refer.core :as refer]
            [eldrix.pc4-ward.refer.subs :as subs]
            [eldrix.pc4-ward.refer.events :as events]
            [eldrix.pc4-ward.org.events :as org-events]
            [eldrix.pc4-ward.org.subs :as org-subs]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.user.views :as user-views]
            [eldrix.pc4-ward.patient.subs :as patient-subs]
            [eldrix.pc4-ward.patient.events :as patient-events]
            [eldrix.pc4-ward.ui :as ui]
            [reagent.core :as reagent]
            [re-frame.core :as rf]))

(defn user-panel
  "A user details form - to include name, job title, contact details and team."
  [referral & {:keys [on-save]}]
  (let [valid? (contains? (refer/completed-stages referral) :clinician)]
    (ui/panel {:title         "Who are you?"
               :save-label    "Next"
               :save-disabled (not valid?)
               :on-save       #(when (and valid? on-save) (on-save))}
              [ui/textfield-control
               (get-in referral [::refer/referrer ::refer/practitioner :urn:oid:2.5.4/commonName])
               :id "user-name" :label "Name" :required true :disabled true]
              [ui/textfield-control (get-in referral [::refer/referrer ::refer/job-title])
               :id "user-job-title" :label "Job title / grade" :required true
               :on-change #(rf/dispatch-sync [::events/update-referral (assoc-in referral [::refer/referrer ::refer/job-title] %)])]
              [ui/textfield-control
               (get-in referral [::refer/referrer ::refer/contact-details])
               :id "user-contact" :label "Your contact details (pager / mobile)" :required true
               :on-change #(rf/dispatch-sync [::events/update-referral (assoc-in referral [::refer/referrer ::refer/contact-details] %)])]
              [ui/textfield-control
               (get-in referral [::refer/referrer ::refer/team-contact-details])
               :id "user-team" :label "Team contact details" :required false
               :help-text "Include information about your colleagues or who to contact if you are unavailable."
               :on-change #(rf/dispatch-sync [::events/update-referral (assoc-in referral [::refer/referrer ::refer/team-contact-details] %)])])))

(defn select-patients                                       ;;TODO: move to library
  "A simple table allowing selection of a patient from a list."
  [patients & {:keys [select-fn is-selectable-fn]}]
  (cond
    (nil? patients)
    nil

    (empty? patients)
    [:div
     [:button] "No patients found"]

    :else
    [:table.min-w-full
     [:thead.bg-gray-50
      [:tr
       (when select-fn [:th])
       [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Name"]
       [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider [:abbr {:title "NHS number"} "NHS No"]]
       [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Born"]
       [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Age"]
       [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Address"]
       [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider [:abbr {:title "Hospital numbers"} "CRNs"]]]]

     [:tbody.bg-white.divide-y.divide-gray-200
      (for [[idx patient] (map-indexed vector patients)]
        (let [selectable? (if is-selectable-fn (is-selectable-fn patient) true)]
          [:tr {:key idx :class (if (odd? idx) "bg-white" "bg-gray-150")}
           (when select-fn
             [:td.px-2.py-4.sm:whitespace-nowrap
              [:button.px-3.py-2.leading-5.text-black.transition-colors.duration-200.border.border-gray-500.transform.bg-gray-200.rounded-md.hover:bg-gray-400.focus:outline-none.focus:bg-gray-600
               (if selectable? {
                                :title    (str "Select " (:uk.nhs.cfh.isb1506/patient-name patient))
                                :on-click #(when select-fn (select-fn patient))}
                               {:disabled true :hidden true}) "Select"]])
           [:td.px-6.py-4.sm:whitespace-nowrap (:uk.nhs.cfh.isb1506/patient-name patient)]
           [:td.px-6.py-4.sm:whitespace-nowrap {:dangerouslySetInnerHTML {:__html (str/replace (:uk.nhs.cfh.isb1504/nhs-number patient) #" " "&nbsp;")}}]
           [:td.px-6.py-4.sm:whitespace-nowrap (com.eldrix.pc4.commons.dates/format-date (:org.hl7.fhir.Patient/birthDate patient))]
           [:td.px-6.py-4.sm:whitespace-nowrap (if-let [deceased (:org.hl7.fhir.Patient/deceased patient)]
                                                 [:span (if (instance? goog.date.Date deceased)
                                                          (str "Died on " (com.eldrix.pc4.commons.dates/format-date deceased))
                                                          "Deceased")]
                                                 (:uk.nhs.cfh.isb1505/display-age patient))]
           [:td.px-6.py-4 (get-in patient [:org.hl7.fhir.Patient/currentAddress :org.hl7.fhir.Address/text])]
           [:td.px-6.py-4 "A123456 " [:br] "M1234567"]]))]]))


(defn patient-select-panel
  "A simple panel to allow search/selection of a patient."
  []
  (let [search-text (reagent/atom "")
        search-results (rf/subscribe [::patient-subs/search-results])
        do-search-fn #(do (js/console.log "searching for " @search-text)
                          (rf/dispatch [::patient-events/fetch @search-text]))]
    (fn []
      [:section.p-2.mx-auto.bg-white.rounded-md.shadow-md.dark:bg-gray-800
       [:h2.text-lg.font-semibold.text-gray-700.dark:text-white "Search for patient"]
       [:div.my-4
        [:input.block.w-full.px-4.py-1.border.border-gray-300.rounded-md.dark:bg-gray-800.dark:text-gray-300.dark:border-gray-600.focus:border-blue-500.dark:focus:border-blue-500.focus:outline-none.focus:ring
         {:id          "patient-name" :type "text"
          :value       @search-text
          :auto-focus  true
          :on-key-down #(if (= 13 (.-which %))
                          (do-search-fn))
          :on-change   #(do (reset! search-text (-> % .-target .-value))
                            (rf/dispatch [::patient-events/clear-search-results]))}]]
       [:button.px-3.py-2.mb-8.leading-5.text-white.transition-colors.duration-200.transform.bg-gray-600.rounded-md.hover:bg-indigo-400.focus:outline-none.focus:bg-gray-600
        {:disabled (str/blank? @search-text)
         :on-click do-search-fn}
        "Search"]

       (if-not (str/blank? @search-text)
         [select-patients @search-results
          :select-fn (fn [pt] (rf/dispatch [::patient-events/set-current-patient pt]))
          :is-selectable-fn (complement :org.hl7.fhir.Patient/deceased)])])))

(defn patient-panel
  [referral & {:keys [on-save]}]
  (let [valid? (contains? (refer/completed-stages referral) :patient)
        current-patient (::refer/patient referral)
        location (::refer/location referral)]
    (ui/panel {:title         "Patient details?"
               :save-label    "Next"
               :save-disabled (not valid?)
               :on-save       #(when (and valid? on-save) (on-save))}
              [ui/textfield-control
               (:uk.nhs.cfh.isb1506/patient-name current-patient)
               :id "pt-name" :label "Patient name" :required true :disabled true]
              [ui/select :label "Location"
               :value (get-in referral [::refer/location ::refer/type])
               :default-value "Inpatient"
               :choices ["Inpatient" "Outpatient"]
               :select-fn #(rf/dispatch [::events/update-referral (assoc-in referral [::refer/location ::refer/type] %)])]
              [:p]
              [ui/select-or-autocomplete :label "Which hospital?"
               :value (get-in referral [::refer/location ::refer/hospital])
               :default-value @(rf/subscribe [::user-subs/default-hospital])
               :id-key org-events/official-identifier
               :display-key #(str (:org.hl7.fhir.Organization/name %) " : " (:org.hl7.fhir.Address/text (first (:org.hl7.fhir.Organization/address %))))
               :common-choices @(rf/subscribe [::user-subs/common-hospitals])
               ;  :no-selection-string ""
               :autocomplete-fn (debounce/debounce #(rf/dispatch [::org-events/search-uk :refer-hospital {:s % :roles ["RO148" "RO150" "RO198" "RO149" "RO108"]}]) 400)
               :autocomplete-results @(rf/subscribe [::org-subs/search-results :refer-hospital])
               :clear-fn #(rf/dispatch [::org-events/clear-search-results :refer-hospital])
               :select-fn #(rf/dispatch [::events/update-referral (assoc-in referral [::refer/location ::refer/hospital] %)])
               :placeholder "Search for hospital"]
              [ui/textfield-control
               (get-in referral [::refer/location ::refer/ward])
               :id "pt-ward" :label "Ward" :required true :disabled false
               :on-change #(rf/dispatch [::events/update-referral (assoc-in referral [::refer/location ::refer/ward] %)])
               :help-text "On which ward is the patient?"]
              [ui/textfield-control
               (get-in referral [::refer/location ::refer/consultant])
               :id "pt-consultant" :label "Consultant" :required true :disabled false
               :on-change #(rf/dispatch [::events/update-referral (assoc-in referral [::refer/location ::refer/consultant] %)])]
              )))


(def data (reagent.core/atom {}))

(defn service-panel
  [referral & {:keys [on-save]}]
  (let [valid? (contains? (refer/completed-stages referral) :service)
        _ (tap> {:data @data})]
    (ui/panel {:title         "To which service?"
               :save-label    "Next"
               :save-disabled (not valid?)
               :on-save       #(when (and valid? on-save) (on-save))}
              [ui/select :choices (sort ["Neuro-oncology MDT" "Neurology" "Gastroenterology" "Respiratory medicine"])
               :value (::refer/service referral)
               :default-value "Neuro-oncology MDT"
               :no-selection-string ""
               :select-fn #(rf/dispatch [::events/update-referral (assoc referral ::refer/service %)])]
              [:div.mt-4
               [ui/select
                :label "Have you discussed the details of this case with the on-call Neurosurgeon (bleep 6464 at UHW or 07583104201) to ensure that any intervention is safe to wait until after an MDT discussion?"
                :choices (sort ["Yes" "No. I am happy this can wait."])
                :no-selection-string ""
                :value (:discussed? @data)
                :select-fn #(do (println "selected:" %) (swap! data assoc :discussed? %))]]
              (when (:discussed? @data)
                [:fieldset.mt-4
                 (when (= "Yes" (:discussed? @data))
                   [:<>
                    [ui/ui-label :label "When did you discuss with the on-call neurosurgeon?"]
                    [ui/html-date-picker :value (goog.date.Date.)]])
                 [:div.mt-8
                  [:label.block.text-sm.font-medium.text-gray-700 {:for "email"} "Nature of referral"]]
                 [:legend.sr-only "Nature of referral"]
                 [:div.space-y-5
                  [:div.relative.flex.items-start
                   [:div.flex.items-center.h-5
                    [:input#small.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:aria-describedby "small-description" :name "plan" :type "radio" :checked "false"}]]
                   [:div.ml-3.text-sm
                    [:label.font-medium.text-gray-700 {:for "small"} "New diagnosis"]
                    [:p#small-description.text-gray-500 "This is a patient with a NEW diagnosis of a brain/CNS tumour. There is NO previously known malignancy"]]]
                  [:div.relative.flex.items-start
                   [:div.flex.items-center.h-5
                    [:input#medium.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:aria-describedby "medium-description" :name "plan" :type "radio"}]]
                   [:div.ml-3.text-sm
                    [:label.font-medium.text-gray-700 {:for "medium"} "Oncologist and known other malignancy"]
                    [:p#medium-description.text-gray-500 "I am the Oncologist (or deputy) whose care this patient is under. They have a KNOWN malignancy and I wish to discuss options for their presumed CNS metastatic disease"]]]
                  [:div.relative.flex.items-start
                   [:div.flex.items-center.h-5
                    [:input#large.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:aria-describedby "large-description" :name "plan" :type "radio" :checked true}]]
                   [:div.ml-3.text-sm
                    [:label.font-medium.text-gray-700 {:for "large"} "Surveillance"]
                    [:p#large-description.text-gray-500 "I am a Neurosurgeon/Neuro-oncologist and this is a patient under my clinical and radiological surveillance. They have had previous MDT discussions"]]]
                  [:div.relative.flex.items-start
                   [:div.flex.items-center.h-5
                    [:input#large.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:aria-describedby "large-description" :name "plan" :type "radio"}]]
                   [:div.ml-3.text-sm
                    [:label.font-medium.text-gray-700 {:for "large"} "Neuropathology update"]
                    [:p#large-description.text-gray-500 "This is a Neuropathology Update"]]]
                  [:div.relative.flex.items-start
                   [:div.flex.items-center.h-5
                    [:input#large.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:aria-describedby "large-description" :name "plan" :type "radio"}]]
                   [:div.ml-3.text-sm
                    [:label.font-medium.text-gray-700 {:for "large"} "Stereotactic radiosurgery"]
                    [:p#large-description.text-gray-500 "I am an Oncologist and would like this MDT’s approval for Stereotactic Radiosurgery"]]]]

                 [:div.mt-4]
                 [ui/textarea
                  :label "Please update us on the latest MDT (for the known malignancy) recommendation for your patient:"]
                 [ui/textarea
                  :label "What (if any) systemic treatment options exist for your patient?"]]))))

(defn clinical-panel
  [referral & {:keys [on-save]}]
  (let [valid? (contains? (refer/completed-stages referral) :service)]
    (ui/panel {:title         "Clinical details"
               :save-label    "Next"
               :save-disabled (not valid?)
               :on-save       #(when (and valid? on-save) (on-save))}

              [:div
               [:label.text-base.font-medium.text-gray-900 "EORTC Performance Status"]
               #_[:p.text-sm.leading-5.text-gray-500 "How do you prefer to receive notifications?"]
               [:fieldset.mt-4
                [:legend.sr-only "Notification method"]
                [:div.space-y-4
                 [:div.flex.items-center
                  [:input#email.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:name "notification-method" :type "radio" :checked "true"}]
                  [:label.ml-3.block.text-sm.font-medium.text-gray-700 {:for "email"} "0 - Fully active, able to carry on all pre-disease performance without restriction"]]
                 [:div.flex.items-center
                  [:input#email.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:name "notification-method" :type "radio" :checked false}]
                  [:label.ml-3.block.text-sm.font-medium.text-gray-700 {:for "email"} "1 - Restricted in physically strenuous activity but ambulatory & able to carry out light work . e.g. house/office work"]]
                 [:div.flex.items-center
                  [:input#email.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:name "notification-method" :type "radio" :checked false}]
                  [:label.ml-3.block.text-sm.font-medium.text-gray-700 {:for "email"} "2 - Ambulatory and capable of all self care but unable to carry out any work activities. Up and about more than 50% of waking hours\n"]]
                 [:div.flex.items-center
                  [:input#email.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:name "notification-method" :type "radio" :checked false}]
                  [:label.ml-3.block.text-sm.font-medium.text-gray-700 {:for "email"} "3 - Capable of only limited selfcare, confined to bed or chair more than 50% of waking hours"]]
                 [:div.flex.items-center
                  [:input#email.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300 {:name "notification-method" :type "radio" :checked false}]
                  [:label.ml-3.block.text-sm.font-medium.text-gray-700 {:for "email"} "4 - Completely disabled. Cannot carry on any self care. Totally confined to bed or chair"]]

                 ]]]
              [:div.mt-4
               [ui/textarea :label "Relevant symptoms, signs and current clinical state"]
               [:p.text-sm.text-gray-400 "The members of the MDT rely on accurate yet concise information that is relevant to your patient’s condition in order that an appropriate recommendation can be made.  Therefore please do not paste verbose clinic letters in the above and instead provide a reasonable summary or timeline of events. All referrals to us are reviewed beforehand and will be returned if the above request is found to be disregarded"]]

              [:div.mt-4
               [ui/textarea :label "Relevant past medical history and co-morbidities"]
               ]
              [:div.mt-4
               [ui/textarea :label "Drug history"]]
              [ui/select
               :label "Anticoagulation / antiplatelet therapy"
               :no-selection-string "None"
               :choices ["Any NOAC" "Warfarin" "One of, or combination of aspirin / clopidogrel / prasugrel / ticagrelor / dipyrimidole"]]
              [ui/select
               :label "Steroid therapy"
               :choices ["Yes" "No"]]
              [ui/textarea
               :label "Details of steroid therapy"]
              [:div.mt-8
               [:h2.text-lg.font-semibold.text-gray-700.dark:text-white "Imaging to be discussed"]
               [:div.px-8 [ui/list-entities-fixed
                           :items [{:date     "01-Feb-2018"
                                    :type     "MRI brain"
                                    :hospital "University Hospital Wales"
                                    :report   "Right frontal glioma increased in size compared to 2017"}
                                   {:date     "12-Jul-2017"
                                    :type     "MRI brain"
                                    :hospital "Royal Glamorgan Hospital"
                                    :report   "Right frontal mass lesion, possible glioma"}]
                           :headings ["Date" "Type" "Hospital" "Report summary"]
                           :value-keys [:date :type :hospital :report]]]]
              [ui/button :label "Add imaging report..."])))


(def finished (reagent.core/atom false))

(defn refer-page []
  (let [referral @(rf/subscribe [::subs/referral])
        available @(rf/subscribe [::subs/available-stages])
        completed @(rf/subscribe [::subs/completed-stages])
        stage (or (:current-stage referral) :clinician)
        select-stage #(rf/dispatch [::events/set-stage %])]
    (tap> {:referral       referral
           :available      available
           :completed      completed
           :stage          stage
           :valid-referrer (s/explain-data ::refer/referrer (::refer/referrer referral))
           :valid-patient  (s/explain-data ::refer/patient (::refer/patient referral))
           :valid-location (s/explain-data ::refer/location (::refer/location referral))})
    [:<>
     (when-let [pt (::refer/patient referral)]
       (let [deceased (:org.hl7.fhir.Patient/deceased pt)]
         [ui/patient-banner
          :name (:uk.nhs.cfh.isb1506/patient-name pt)
          :nhs-number (:uk.nhs.cfh.isb1504/nhs-number pt)
          :deceased deceased
          :born (str (com.eldrix.pc4.commons.dates/format-date (:org.hl7.fhir.Patient/birthDate pt)) " " (when-not deceased (:uk.nhs.cfh.isb1505/display-age pt)))
          :gender (str/capitalize (name (:org.hl7.fhir.Patient/gender pt)))
          :hospital-identifier (:wales.nhs.cavuhb.Patient/HOSPITAL_ID pt) ;; TODO: switch to using whichever organisation makes sense in context
          :address (get-in pt [:org.hl7.fhir.Patient/currentAddress :org.hl7.fhir.Address/text])
          :on-close #(rf/dispatch [::patient-events/close-current-patient])]))

     [:div.grid.grid-cols-12
      [:div.col-span-12.sm:hidden.p-4
       [:h1.font-bold.font-lg.uppercase "Make referral"]]
      [:div.hidden.sm:block.col-span-12.sm:col-span-4.md:col-span-3.sm:bg-gray-100.shadow
       [ui/progress "Make referral"
        [ui/progress-item :id :clinician
         :active available :done completed :selected stage :on-click select-stage
         :svg [ui/svg-shield] :title "STEP 1:" :text "Who are you?"]
        [ui/progress-item :id :patient
         :active available :done completed :selected stage :on-click select-stage
         :svg [ui/svg-person] :title "STEP 2:" :text "Who is the patient?"]
        [ui/progress-item :id :service
         :active available :done completed :selected stage :on-click select-stage
         :svg [ui/svg-anchor] :title "STEP 3:" :text "To which service?"]
        [ui/progress-item :id :question
         :active available :done completed :selected stage :on-click select-stage
         :svg [ui/svg-graph] :title "STEP 4:" :text "Clinical details?"]
        [ui/progress-item :id :send
         :active available :done completed :selected stage :on-click select-stage
         :svg [ui/svg-tick] :title "FINISH:" :text "Send referral" :end? true]]]

      [:div.col-span-12.sm:col-span-8.md:col-span-9
       [:div.container.px-2.py-4.mx-auto.w-full.h-full
        (if-not (::refer/referrer referral)
          [user-views/login-panel]
          (case stage
            :clinician
            [user-panel referral :on-save #(select-stage :patient)]
            :patient
            (if-not (::refer/patient referral)
              [patient-select-panel referral]
              [patient-panel referral :on-save #(select-stage :service)])
            :service
            [service-panel referral :on-save #(select-stage :question)]
            :question
            [clinical-panel referral :on-save #(select-stage :send)]
            :send
            [:<> [ui/panel {:title         "Send referral"
                            :save-label    "Send"
                            :save-disabled false
                            :on-save       #(reset! finished true)}]
             (when @finished
             [ui/modal :title "Referral sent"
              :content nil
              :actions [{:title "Finish" :id :finish
                         :on-click #(do (reset! finished false)
                                        (rf/dispatch [::user-events/do-logout])
                                        (rf/dispatch [:eldrix.pc4-ward.events/push-state :refer]))}]])]))]]]]))



; [login-panel :disabled false :on-login #(println "login for user : " %1)]

; [example-form]

(comment


  [patient-banner
   :name "DUMMY, Albert (Mr)"
   :nhs-number "111 111 1111"
   :deceased false
   :born "01-Jun-1980 (31y)"
   :hospital-identifier "C123456"                           ;; TODO: switch to using whichever organisation makes sense in context
   :address "1 Station Road, Cardiff, CF14 4XW"]

  (tap> {:referral              referral
         :stage                 stage
         :is-referrer-complete? (s/valid? ::refer/valid-referrer? referral)
         :is-patient-complete?  (s/valid? ::refer/valid-patient? referral)
         :available             available
         :completed             completed}))