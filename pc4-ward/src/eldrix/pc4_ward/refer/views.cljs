(ns eldrix.pc4-ward.refer.views
  (:require [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [eldrix.pc4-ward.refer.core :as refer]
            [eldrix.pc4-ward.refer.subs :as subs]
            [eldrix.pc4-ward.refer.events :as events]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.user.views :as user-views]
            [eldrix.pc4-ward.patient.subs :as patient-subs]
            [eldrix.pc4-ward.ui :as ui]
            [reagent.core :as reagent]
            [re-frame.core :as rf]))

(defn user-panel
  "A user details form - to include name, job title, contact details and team."
  [referral]

  (ui/panel {:title "Who are you?"}
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
             :help-text "Include information about your colleagues / who to contact if you are unavailable."
             :on-change #(rf/dispatch-sync [::events/update-referral (assoc-in referral [::refer/referrer ::refer/team-contact-details] %)])]))

(defn refer-page []
  (let [referral @(rf/subscribe [::subs/referral])
        available @(rf/subscribe [::subs/available-stages])
        completed @(rf/subscribe [::subs/completed-stages])
        stage (or (:current-stage referral) :clinician)
        select-stage #(rf/dispatch [::events/set-stage %])]
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
          :address (get-in pt [:org.hl7.fhir.Patient/currentAddress :org.hl7.fhir.Address/text])]))

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
            [user-panel referral]
            :patient
            [:p "Patient"]
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