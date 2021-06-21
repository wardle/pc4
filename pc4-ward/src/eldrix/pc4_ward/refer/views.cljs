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
               (get-in referral [::refer/referrer ::refer/practitioner :urn.oid.2.5.4/commonName])
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


(defn select-or-autocomplete
  "A flexible select/autocompletion control.
  Parameters:
  - label          : label to show
  - value          : currently selected value, if any
  - id-key         : function to get id from a value (e.g. could be a keyword)
  - display-key    : function to get display from value
  - common-choices : collection of common choices to show
  - autocomplete-fn: autocompletion function that takes one parameter
  - clear-fn       : function to run to clear autocompletion, if required
  - select-fn      : function to be called with a selected id
  - minimum-chars  : minimum number of characters needed to run autocompletion
  - autocomplete-results - results of autocompletion
  - placeholder    : placeholder text for autocompletion
  - no-selection-string : label for select when nothing selected"
  [{:keys [clear-fn]}]
  (when clear-fn (clear-fn))
  (let [mode (reagent/atom nil)]
    (fn [{:keys [label value id-key display-key common-choices autocomplete-fn clear-fn
                 autocomplete-results select-fn placeholder minimum-chars
                 no-selection-string]
          :or   {minimum-chars 3}}]
      [:<>
       (when label [ui/ui-label :label label])
       (cond
         (and (seq common-choices) (= :select (or @mode :select)))
         (let [all-choices (if value (conj common-choices value) common-choices)
               choices (zipmap (map id-key all-choices) all-choices)
               sorted-choices (sort-by display-key (vals choices))]
           [:div.flex
            [:select.border.bg-white.rounded.px-3.py-2.outline-none
             {:value (str (id-key value)) :on-change #(when select-fn
                                                        (let [idx (-> % .-target .-selectedIndex)]
                                                          (if (and no-selection-string (= 0 idx))
                                                            (select-fn nil)
                                                            (select-fn (nth sorted-choices idx)))))}
             (when no-selection-string [:option.py-1 {:value nil :id nil} no-selection-string])
             (for [choice sorted-choices]
               (let [id (id-key choice)]
                 [:option.py-1 {:value (str id) :key id} (display-key choice)]))]
            [:button.bg-blue-400.hover:bg-blue-500.text-white.text-xs.py-1.px-2.rounded-full
             {:on-click #(reset! mode :autocomplete)} "..."]])
         (= :autocomplete @mode)
         [:<>
          [:div.flex
           [:input.block.px-4.py-1.border.border-gray-300.rounded-md.dark:bg-gray-800.dark:text-gray-300.dark:border-gray-600.focus:border-blue-500.dark:focus:border-blue-500.focus:outline-none.focus:ring
            {:id            :refer-hospital :type "text" :placeholder placeholder :required true
             :class         ["text-gray-700" "bg-white" "shadow"]
             :default-value nil
             :auto-focus    true
             :on-change     #(let [s (-> % .-target .-value)]
                               (if (>= (count s) minimum-chars)
                                 (debounce/debounce (autocomplete-fn s) 200)
                                 (when clear-fn (clear-fn))))}]
           [:button.bg-blue-400.hover:bg-blue-500.text-white.text-xs.py-1.px-2.rounded-full
            {:on-click #(reset! mode :select)} "Close"]]
          [:div.grid-cols-1.sm:grid-cols-2
           [:div
            [:select.w-full.border.border-gray-300.rounded-md
             {:multiple        true :size 5
              :on-change       #(when select-fn (select-fn (nth autocomplete-results (-> % .-target .-selectedIndex))))
              :on-double-click #(reset! mode :select)}
             (for [result autocomplete-results]
               (let [id (id-key result)]
                 [:option {:value result :key id}
                  (display-key result)]))]]
           [:div
            [:p "Selected:"]]]])])))


(defn patient-panel
  [referral]
  (let [current-patient (::refer/patient referral)
        location (::refer/location referral)]
    (ui/panel {:title         "Patient details?"
               :save-label    "Next"
               :save-disabled false                         ;;(not valid?)
               :on-save       #()}                          ;;#(when (and valid? on-save) (on-save))}
              [ui/textfield-control
               (:uk.nhs.cfh.isb1506/patient-name current-patient)
               :id "pt-name" :label "Patient name" :required true :disabled true]
              [ui/ui-label :label "Location"]
              [:select.w-full.border.bg-white.rounded.px-3.py-2.outline-none
               [:option.py-1 "Inpatient"]]
              [select-or-autocomplete {:label                "Which hospital?"
                                       :value                (or (get-in referral [::refer/location ::refer/hospital]) @(rf/subscribe [::user-subs/default-hospital]))
                                       :id-key               org-events/official-identifier
                                       :display-key          #(str (:org.hl7.fhir.Organization/name %) " : " (:org.hl7.fhir.Address/text (first (:org.hl7.fhir.Organization/address %))))
                                       :common-choices       @(rf/subscribe [::user-subs/common-hospitals])
                                       ;  :no-selection-string ""
                                       :autocomplete-fn      #(rf/dispatch [::org-events/search-uk :refer-hospital {:s % :roles ["RO148" "RO150" "RO198" "RO149" "RO108"]}])
                                       :autocomplete-results @(rf/subscribe [::org-subs/search-results :refer-hospital])
                                       :clear-fn             #(rf/dispatch [::org-events/clear-search-results :refer-hospital])
                                       :select-fn            #(rf/dispatch [::events/update-referral (assoc-in referral [::refer/location ::refer/hospital] %)])
                                       :placeholder          "Search for hospital"}]
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
           :valid-referrer (s/explain-data ::refer/referrer referral)})
    [:<>
     [ui/nav-bar
      :title "PatientCare v4"                               ;:menu [{:id :refer-patient :title "Refer patient"}]   :selected :refer-patient
      :show-user? (get-in referral [::refer/referrer ::refer/practitioner])
      :full-name (get-in referral [::refer/referrer ::refer/practitioner :urn.oid.2.5.4/commonName])
      :initials (get-in referral [::refer/referrer ::refer/practitioner :urn.oid.2.5.4/initials])
      :user-menu [{:id :logout :title "Sign out" :on-click #(rf/dispatch [::user-events/do-logout])}]]

     (when-let [pt (::refer/patient referral)]
       (let [deceased (:org.hl7.fhir.Patient/deceased pt)]
         [ui/patient-banner
          :name (:uk.nhs.cfh.isb1506/patient-name pt)
          :nhs-number (:uk.nhs.cfh.isb1504/nhs-number pt)
          :deceased deceased
          :born (str (com.eldrix.pc4.commons.dates/format-date (:org.hl7.fhir.Patient/birthDate pt)) " " (when-not deceased (:uk.nhs.cfh.isb1505/display-age pt)))
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
         :svg [ui/svg-graph] :title "STEP 4:" :text "What is the question?"]
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
              [patient-panel referral])
            :service
            [:p "Service"]
            :question
            [:p "Question"]
            :send
            [:p "Send"]
            ))

        ]]]]))



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