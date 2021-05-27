(ns eldrix.pc4-ward.refer
  (:require [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.users :as users]
            [reagent.core :as reagent]
            [clojure.string :as str]))


;; these could be automatically generated from the FHIR specs, except that the
;; specifications often have everything as optional (e.g. cardinality 0..1).
(s/def :org.hl7.fhir.Identifier/system string?)
(s/def :org.hl7.fhir.Identifier/value string?)
(s/def :org.hl7.fhir/Identifier (s/keys :req [:org.hl7.fhir.Identifier/system :org.hl7.fhir.Identifier/value]
                                        :opt [:org.hl7.fhir.Identifier/use :org.hl7.fhir.Identifier/type
                                              :org.hl7.fhir.Identifier/period :org.hl7.fhir.Identifier/assigner]))
(s/def :org.hl7.fhir.Coding/system string?)
(s/def :org.hl7.fhir.Coding/code string?)
(s/def :org.hl7.fhir/Coding (s/keys :req [:org.hl7.fhir.Coding/system :org.hl7.fhir.Coding/code]
                                    :opt [:org.hl7.fhir.Coding/version :org.hl7.fhir.Coding/display]))
(s/def :org.hl7.fhir.CodeableConcept/coding :org.hl7.fhir/Coding)
(s/def :org.hl7.fhir.CodeableConcept/text string?)
(s/def :org.hl7.fhir/CodeableConcept (s/keys :req [:org.hl7.fhir.CodeableConcept/coding]
                                             :opt [:org.hl7.fhir.CodeableConcept/text]))

(s/def :org.hl7.fhir.HumanName/given (s/coll-of string?))
(s/def :org.hl7.fhir.HumanName/family string?)
(s/def :org.hl7.fhir/HumanName (s/keys :req [:org.hl7.fhir.HumanName/family :org.hl7.fhir.HumanName/given]))
(s/def :org.hl7.fhir.Practitioner/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.Practitioner/name (s/coll-of :org.hl7.fhir/HumanName))
(s/def :org.hl7.fhir/Practitioner (s/keys :req [:org.hl7.fhir.Practitioner/identifier
                                                :org.hl7.fhir.Practitioner/name]))
(s/def :org.hl7.fhir.Patient/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.Patient/name (s/coll-of :org.hl7.fhir/HumanName))
(s/def :org.hl7.fhir/Patient (s/keys :req [:org.hl7.fhir.Patient/name :org.hl7.fhir.Patient/identifier]))

(s/def :org.hl7.fhir.ServiceRequest/patientInstruction string?)
(s/def :org.hl7.fhir.ServiceRequest/priority #{:org.hl7.fhir.request-priority/routine
                                               :org.hl7.fhir.request-priority/urgent
                                               :org.hl7.fhir.request-priority/asap
                                               :org.hl7.fhir.request-priority/stat})
(s/def :org.hl7.fhir.ServiceRequest/intent #{:org.hl7.fhir.request-intent/proposal
                                             :org.hl7.fhir.request-intent/plan
                                             :org.hl7.fhir.request-intent/directive
                                             :org.hl7.fhir.request-intent/order
                                             :org.hl7.fhir.request-intent/original-order
                                             :org.hl7.fhir.request-intent/reflex-order
                                             :org.hl7.fhir.request-intent/filler-order
                                             :org.hl7.fhir.request-intent/instance-order})
(s/def :org.hl7.fhir.ServiceRequest/code :org.hl7.fhir/CodeableConcept)
(s/def :org.hl7.fhir.ServiceRequest/subject (s/or :org.hl7.fhir/Patient :org.hl7.fhir/Reference))
(s/def :org.hl7.fhir.ServiceRequest/reasonCode (s/coll-of :org.hl7.fhir/CodeableConcept))
(s/def :org.hl7.fhir.ServiceRequest/performerType :org.hl7.fhir/CodeableConcept)
(s/def :org.hl7.fhir/ServiceRequest (s/keys :req [:org.hl7.fhir.ServiceRequest/status
                                                  :org.hl7.fhir.ServiceRequest/code
                                                  :org.hl7.fhir.ServiceRequest/requester
                                                  :org.hl7.fhir.ServiceRequest/intent
                                                  :org.hl7.fhir.ServiceRequest/authoredOn
                                                  :org.hl7.fhir.ServiceRequest/subject
                                                  :org.hl7.fhir.ServiceRequest/performerType
                                                  (or :org.hl7.fhir.ServiceRequest/locationCode
                                                      :org.hl7.fhir.ServiceRequest/locationReference)
                                                  :org.hl7.fhir.ServiceRequest/reasonCode]
                                            :opt [:org.hl7.fhir.ServiceRequest/patientInstruction]))

(s/def ::referrer-information (s/keys :req [::contact-details]
                                      :opt [::other-contact-details]))
(s/def ::mode #{:inpatient :outpatient :advice})
(s/def ::location (s/keys :req [::mode
                                ::hospital] :opt [::ward]))
(s/def ::draft-referral (s/keys :req [::date-time ::user]
                                :opt [::patient
                                      ::referrer-information
                                      ::location
                                      ::referral-question
                                      ::service]))
(s/def ::referral (s/keys :req [::date-time
                                ::user
                                ::patient
                                ::location
                                ::referral-question
                                ::service]))

(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (s/valid? ::user {:urn.oid.1.2.840.113556.1.4/sAMAccountName "ma090906"})
  (s/explain ::referral {::date-time         1
                         ::user              {:org.hl7.fhir.Practitioner/name       [{:org.hl7.fhir.HumanName/family "Wardle"
                                                                                      :org.hl7.fhir.HumanName/given  ["Mark"]
                                                                                      :org.hl7.fhir.HumanName/prefix ["Dr"]}]
                                              :org.hl7.fhir.Practitioner/identifier [{:org.hl7.fhir.Identifier/system "nadex"
                                                                                      :org.hl7.fhir.Identifier/value  "ma090906"}]}
                         ::patient           {:org.hl7.fhir.Patient/name       [{:org.hl7.fhir.HumanName/family "Duck"
                                                                                 :org.hl7.fhir.HumanName/given  ["Donald"]}]
                                              :org.hl7.fhir.Patient/identifier [{:org.hl7.fhir.Identifier/system "cav"
                                                                                 :org.hl7.fhir.Identifier/value  "A999998"}]}
                         ::location          {::mode :advice}
                         ::referral-question {}
                         ::service           {}})
  )


(defn referral-progress []
  [:aside.menu
   [:p.menu-label "Make a referral"]
   [:ul.menu-list
    [:li [:a.button.is-active "Who are you?"]]
    [:li [:a.button {:disabled true} "Who is the patient?"]]
    [:li [:a.button {:disabled true} "What is the question?"]]
    [:li [:a.button {:disabled true} "To which service?"]]]])

(defn login-panel
  []
  (let [error (rf/subscribe [::users/login-error])
        username (reagent/atom "")
        password (reagent/atom "")
        submitting false                                    ;; @(rf/subscribe [:show-foreground-spinner])
        do-login #(rf/dispatch [::users/do-login "wales.nhs.uk" (str/trim @username) @password])]
    (fn []
      [:<>
       [:div.box
        ;; username field - if user presses enter, automatically switch to password field
        [:div.field [:label.label {:for "login-un"} "Username"]
         [:div.control
          [:input.input {:id          "login-un" :type "text" :placeholder "e.g. ma090906" :required true
                         :disabled    submitting
                         :auto-focus  true
                         :on-key-down #(if (= 13 (.-which %)) (do (.focus (.getElementById js/document "login-pw"))))
                         :on-change   #(reset! username (-> % .-target .-value))}]]]

        ;; password field - if user presses enter, automatically submit
        [:div.field [:label.label {:for "login-pw"} "Password"]
         [:div.control
          [:input.input {:id          "login-pw" :type "password" :placeholder "Enter password" :required true
                         :disabled    submitting
                         :on-key-down #(if (= 13 (.-which %))
                                         (do (reset! password (-> % .-target .-value)) (do-login)))
                         :on-change   #(reset! password (-> % .-target .-value))}]]]

        [:button.button {:class    ["is-primary" (when submitting "is-loading")]
                         :disabled submitting
                         :on-click do-login} " Login "]]
       (when-not (str/blank? @error) [:div.notification.is-danger [:p @error]])])))

(def username (reagent/atom ""))
(def password (reagent/atom ""))

(defn refer-page []
  (let [authenticated-user @(rf/subscribe [::users/authenticated-user])]
    (println "user:" authenticated-user)
    [:<>
     [:section.section
      [:nav.navbar.is-black.is-fixed-top {:role "navigation" :aria-label "main navigation"}
       [:div.navbar-brand
        [:a.navbar-item {:href "#/"} [:h1 "PatientCare v4: " [:strong "Refer a patient"]]]]
       (when authenticated-user
         [:div.navbar-end
          [:div.navbar-item (:urn.oid.2.5.4/commonName authenticated-user)]
          [:a.navbar-item {:on-click #(rf/dispatch [::users/do-logout])} "Logout"]])]]

     [:section.section
      [:div.columns
       [:div.column.is-one-fifth
        [referral-progress]]
       [:div.column.is-four-fifths
        (if authenticated-user
          [:div.box
           [:div.field [:label.label {:for "name-un"} "Your name"]
            [:div.control
             [:input.input {:id       "name-un" :type "text"
                            :value    (:urn.oid.2.5.4/commonName authenticated-user)
                            :disabled true}]]]
           [:div.field [:label.label {:for "title-un"} "Your grade / job title"]
            [:div.control
             [:input.input {:id "title-un" :type "text" :value (:urn.oid.2.5.4/title authenticated-user)}]]]
           [:div.field [:label.label {:for "contact-un"} "Your contact details (pager / mobile)"]
            [:div.control
             [:input.input {:id "contact-un" :type "text"}]]]
           [:div.field [:label.label {:for "contact2-un"} "Team contact details "]
            [:div.control
             [:input.input {:id "contact2-un" :type "text"}]]]
           [:button.button.is-primary {:disabled true} "Next"]]
          [login-panel])]]]]))