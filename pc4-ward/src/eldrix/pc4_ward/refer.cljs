(ns eldrix.pc4-ward.refer
  (:require [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.patient.events :as patient-events]
            [eldrix.pc4-ward.patient.subs :as patient-subs]
            [reagent.core :as reagent]
            [clojure.string :as str]))

;; these could be automatically generated from the FHIR specs, except that the
;; specifications often have everything as optional (e.g. cardinality 0..1).
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def :org.hl7.fhir.Identifier/system ::non-blank-string)
(s/def :org.hl7.fhir.Identifier/value ::non-blank-string)
(s/def :org.hl7.fhir/Identifier (s/keys :req [:org.hl7.fhir.Identifier/system :org.hl7.fhir.Identifier/value]
                                        :opt [:org.hl7.fhir.Identifier/use :org.hl7.fhir.Identifier/type
                                              :org.hl7.fhir.Identifier/period :org.hl7.fhir.Identifier/assigner]))
(s/def :org.hl7.fhir.Coding/system ::non-blank-string)
(s/def :org.hl7.fhir.Coding/code ::non-blank-string)
(s/def :org.hl7.fhir/Coding (s/keys :req [:org.hl7.fhir.Coding/system :org.hl7.fhir.Coding/code]
                                    :opt [:org.hl7.fhir.Coding/version :org.hl7.fhir.Coding/display]))
(s/def :org.hl7.fhir.CodeableConcept/coding :org.hl7.fhir/Coding)
(s/def :org.hl7.fhir.CodeableConcept/text ::non-blank-string)
(s/def :org.hl7.fhir/CodeableConcept (s/keys :req [:org.hl7.fhir.CodeableConcept/coding]
                                             :opt [:org.hl7.fhir.CodeableConcept/text]))

(s/def :org.hl7.fhir.HumanName/given (s/coll-of ::non-blank-string))
(s/def :org.hl7.fhir.HumanName/family ::non-blank-string)
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


(s/def ::practitioner :org.hl7.fhir/Practitioner)
(s/def ::job-title ::non-blank-string)
(s/def ::contact-details ::non-blank-string)
(s/def ::referrer (s/keys :req [::practitioner
                                ::job-title
                                ::contact-details]
                          :opt [::team-contact-details]))
(s/def ::mode #{:inpatient :outpatient :advice})
(s/def ::location (s/keys :req [::mode]
                          :opt [::hospital ::ward]))

(s/def ::valid-referrer? (s/keys :req [::referrer]))
(s/def ::valid-patient? (s/keys :req [::referrer ::patient]))
(s/def ::valid-service? (s/keys :req [::referrer ::patient ::service]))
(s/def ::valid-question? (s/keys :req [::referrer ::patient ::service ::question]))
(s/def ::valid-referral? (s/keys :req [::date-time ::referrer ::patient ::question ::service]))

(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (s/valid? :org.hl7.fhir/Identifier {:org.hl7.fhir.Identifier/system "wales.nhs.uk" :org.hl7.fhir.Identifier/value "ma090906"})
  (s/valid? :org.hl7.fhir.Practitioner/identifier [{:org.hl7.fhir.Identifier/system "wales.nhs.uk" :org.hl7.fhir.Identifier/value "ma090906"}])
  (s/valid? :org.hl7.fhir/Practitioner {:org.hl7.fhir.Practitioner/identifier [{:org.hl7.fhir.Identifier/system "wales.nhs.uk" :org.hl7.fhir.Identifier/value "ma090906"}]
                                        :org.hl7.fhir.Practitioner/name       [{:org.hl7.fhir.HumanName/given ["Mark"] :org.hl7.fhir.HumanName/family "Wardle"}]})
  (s/valid? ::valid-referrer? {::date-time 1
                               ::referrer  {::practitioner    {:org.hl7.fhir.Practitioner/name       [{:org.hl7.fhir.HumanName/family "Wardle"
                                                                                                       :org.hl7.fhir.HumanName/given  ["Mark"]
                                                                                                       :org.hl7.fhir.HumanName/prefix ["Dr"]}]
                                                               :org.hl7.fhir.Practitioner/identifier [{:org.hl7.fhir.Identifier/system "nadex"
                                                                                                       :org.hl7.fhir.Identifier/value  "ma090906"}]}
                                            ::contact-details "02920747747"
                                            ::job-title       "Consultant Neurologist"}
                               ::patient   {:org.hl7.fhir.Patient/name       [{:org.hl7.fhir.HumanName/family "Duck"
                                                                               :org.hl7.fhir.HumanName/given  ["Donald"]}]
                                            :org.hl7.fhir.Patient/identifier [{:org.hl7.fhir.Identifier/system "cav"
                                                                               :org.hl7.fhir.Identifier/value  "A999998"}]}
                               ::location  {::mode :advice}
                               ::service   {}}))

(defn active-menus [referral]
  (cond-> #{::who-are-you?}
          (s/valid? ::valid-referrer? referral)
          (conj ::who-is-patient?)
          (s/valid? ::valid-patient? referral)
          (conj ::to-which-service?)
          (s/valid? ::valid-service? referral)
          (conj ::what-is-question?)
          (s/valid? ::valid-question? referral)
          (conj ::send-referral)))

(defn user-panel
  "Make a user details form - to include name, job title, contact details and
  team.
  Parameters:
   - value : atom "
  [value {:keys [name-kp title-kp contact-kp team-kp valid-fn next-fn]}]
  (let [valid? (if valid-fn (valid-fn) true)]
    [:div.box
     [:div.field [:label.label {:for "name-un"} "Your name"]
      [:div.control
       [:input.input {:id        "name-un" :type "text"
                      :value     (or (get-in @value name-kp) "")
                      :read-only true :disabled true}]]]
     (when title-kp
       [:div.field [:label.label {:for "title-un"} "Your grade / job title"]
        [:div.control
         [:input.input {:id        "title-un" :type "text"
                        :value     (or (get-in @value title-kp) "")
                        :on-change #(swap! value assoc-in title-kp (-> % .-target .-value))}]]])
     (when contact-kp
       [:div.field [:label.label {:for "contact-un"} "Your contact details (pager / mobile)"]
        [:div.control
         [:input.input {:id        "contact-un" :type "text"
                        :value     (or (get-in @value contact-kp) "")
                        :on-change #(swap! value assoc-in contact-kp (-> % .-target .-value))}]]])
     (when team-kp
       [:div.field [:label.label {:for "contact2-un"} "Team contact details "]
        [:div.control
         [:input.input {:id        "contact2-un" :type "text"
                        :value     (or (get-in @value team-kp))
                        :on-change #(swap! value assoc-in team-kp (-> % .-target .-value))}]]
        [:p.help "Include information about your colleagues / who to contact if you are unavailable."]])
     [:button.button.is-primary {:disabled (not valid?)
                                 :on-click (when valid? next-fn)} "Next"]]))


(defn select-patients
  "A simple table allowing selection of a patient from a list."
  [patients & {:keys [select-fn is-selectable-fn]}]
  (cond
    (nil? patients)
    nil

    (empty? patients)
    [:div.notification
     [:button.delete] "No patients found"]

    :else
    [:table.table.is-striped.is-fullwidth
     [:thead
      [:tr
       [:th]
       [:th "Name"]
       [:th [:abbr {:title "NHS number"} "NHS No"]]
       [:th "Born"]
       [:th "Age"]
       [:th "Address"]
       [:th [:abbr {:title "Hospital numbers"} "CRNs"]]]]

     [:tbody
      (for [patient patients]
        (let [selectable? (if is-selectable-fn (is-selectable-fn patient) true)]
          [:tr {:key (:uk.nhs.cfh.isb1504/nhs-number patient)}
           [:td [:button.button.is-info.is-rounded
                 (if selectable? {:title    (str "Select " (:uk.nhs.cfh.isb1506/patient-name patient))
                                  :on-click #(when select-fn (select-fn patient))}
                                 {:disabled true}) "Select"]]
           [:td (:uk.nhs.cfh.isb1506/patient-name patient)]
           [:td {:dangerouslySetInnerHTML {:__html (str/replace (:uk.nhs.cfh.isb1504/nhs-number patient) #" " "&nbsp;")}}]
           [:td (com.eldrix.pc4.commons.dates/format-date (:org.hl7.fhir.Patient/birthDate patient))]
           [:td (if-let [deceased (:org.hl7.fhir.Patient/deceased patient)]
                  [:span.tag.is-danger (if (instance? goog.date.Date deceased)
                                         (str "Died on " (com.eldrix.pc4.commons.dates/format-date deceased))
                                         "Deceased")]
                  (:uk.nhs.cfh.isb1505/display-age patient))]
           [:td (get-in patient [:org.hl7.fhir.Patient/currentAddress :org.hl7.fhir.Address/text])]
           [:td "A123456 " [:br] "M1234567"]]))]]))

(defn patient-banner [patient & {:keys [on-close]}]
  [:div.card
   [:header.card-header
    [:p.card-header-title.level
     [:div.level-left
      [:div.level-item (:uk.nhs.cfh.isb1506/patient-name patient)]
      [:div.level-item
       [:span.has-text-weight-light "NHS No:"]
       (:uk.nhs.cfh.isb1504/nhs-number patient)]
      [:div.level-item
       [:span.has-text-weight-light "Gender:"]
       (str/upper-case (name (:org.hl7.fhir.Patient/gender patient)))]
      [:div.level-item
        (get-in patient [:org.hl7.fhir.Patient/currentAddress :org.hl7.fhir.Address/text])]]
     [:div.level-right
      [:div.level-item [:span.has-text-weight-light "Born:"]
       (com.eldrix.pc4.commons.dates/format-date (:org.hl7.fhir.Patient/birthDate patient))]
      [:div.level-item [:span.has-text-weight-light "Age:"]
       (:uk.nhs.cfh.isb1505/display-age patient)]
      (when on-close [:div.level-item [:button.delete {:on-click on-close}]])]]]])

(defn patient-panel
  [patient]
  [:div.box
     [:div.field [:label.label {:for "name-un"} "Patient"]
      [:div.control
       [:input.input {:id        "name-un" :type "text" :read-only true
                      :value     (:uk.nhs.cfh.isb1506/patient-name patient)}]]]])

(defn patient-search-panel
  []
  (let [search-text (reagent/atom "")
        search-results (rf/subscribe [::patient-subs/search-results])
        current-patient (rf/subscribe [::patient-subs/current-patient])
        do-search-fn #(do (js/console.log "searching for " @search-text)
                          (rf/dispatch [::patient-events/fetch @search-text]))]
    (fn []
      (if-let [selected-patient @current-patient]
        [patient-panel selected-patient]
        [:div.box
         [:div.field [:label.label {:for "name-un"} "Search for patient"]
          [:div.control
           [:input.input {:id          "name-un" :type "text"
                          :value       @search-text
                          :auto-focus  true
                          :on-key-down #(if (= 13 (.-which %))
                                          (do-search-fn))
                          :on-change   #(do (reset! search-text (-> % .-target .-value))
                                            (rf/dispatch [::patient-events/clear-search-results]))}]]]
         [:a.button.is-primary
          {:disabled (str/blank? @search-text)
           :on-click do-search-fn}
          "Search"]

         [select-patients @search-results
          :select-fn (fn [pt] (rf/dispatch [::patient-events/set-current-patient pt]))
          :is-selectable-fn (complement :org.hl7.fhir.Patient/deceased)]
         ]))))

(def menu
  {:title "Make a referral"
   :items [{:id ::who-are-you? :label "Who are you?"}
           {:id ::who-is-patient? :label "Who is the patient?"}
           {:id ::to-which-service? :label "To which service?"}
           {:id ::what-is-question? :label "What is the question?"}
           {:id ::send-referral :label "Send referral"}]})

(defn render-menu
  "Render a simple menu with a title and items.
  Parameters:
   - :title    : title of the menu
   - :items    : a sequence of maps representing each item
                 |- :id    - unique identifier for item
                 |- :label - label to use
   - :selected : which item is selected  (item identifier)
   - :enabled  : function to determine whether item is enabled, optional.
   - :choose-fn: a function to be called when an item is chosen (fn [item-id]).

   As sets act as functions, :enabled can be #{::my-first-item ::my-second-item}."
  [{:keys [title items selected enabled choose-fn] :or {enabled (constantly true)}}]
  [:aside.menu
   [:p.menu-label title]
   [:ul.menu-list
    (for [item items]
      [:li {:key (:id item)}
       [:a.button
        (cond-> {}
                (= selected (:id item)) (assoc :class "is-active")
                (enabled (:id item)) (assoc :on-click #(choose-fn (:id item)))
                (not (enabled (:id item))) (assoc :disabled true))
        (:label item)]])]])

(comment
  (render-menu (assoc menu
                 :selected ::who-are-you?
                 :enabled #{::who-are-you? ::who-is-patient?}
                 :choose-fn nil))
  )

(defn login-panel
  []
  (let [error (rf/subscribe [::user-subs/login-error])
        ping-error (rf/subscribe [::user-subs/ping-error])
        username (reagent/atom "")
        password (reagent/atom "")
        submitting false                                    ;; @(rf/subscribe [:show-foreground-spinner])
        do-login #(rf/dispatch [::user-events/do-login "wales.nhs.uk" (str/trim @username) @password])]
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
       (when-not (str/blank? @error) [:div.notification.is-danger [:p @error]])
       (when @ping-error [:div.notification.is-warning [:p "Warning: connection error; unable to connect to server. Will retry automatically."]])])))



(defn initialize-referral [user]
  (-> {:active-panel ::who-are-you?}
      (assoc-in [::referrer ::practitioner] user)
      (assoc-in [::referrer ::job-title] (:urn:oid:2.5.4/title user))
      (assoc-in [::referrer ::contact-details] (:urn:oid:2.5.4/telephoneNumber user))))

(defn refer-page []
  (let [referral (reagent/atom {})]
    (fn []
      (let [user @(rf/subscribe [::user-subs/authenticated-user])]
        (when-not (= (get-in @referral [::referrer ::practitioner :urn:oid:1.2.840.113556.1.4/sAMAccountName]) (:urn:oid:1.2.840.113556.1.4/sAMAccountName user))
          (swap! referral #(when-not (= (get-in % [::referrer :practitioner :urn:oid:1.2.840.113556.1.4/sAMAccountName])
                                        (:urn:oid:1.2.840.113556.1.4/sAMAccountName user))
                             (js/console.log "Currently logged in user changed; resetting referral. was: " (get-in % [::referrer :practitioner :urn:oid:1.2.840.113556.1.4/sAMAccountName]) "\nnow:" (:urn:oid:1.2.840.113556.1.4/sAMAccountName user))
                             (initialize-referral user))))
        [:<>
         [:nav.navbar.is-black {:role "navigation" :aria-label "main navigation"}
          [:div.navbar-brand
           [:a.navbar-item {:href "#/"} [:h1 "PatientCare v4: " [:strong "Refer a patient"]]]]
          (when user
            [:div.navbar-end
             [:div.navbar-item (:urn:oid:2.5.4/commonName user)]
             [:a.navbar-item {:on-click #(rf/dispatch [::user-events/do-logout])} "Logout"]])]

         (when-let [pt @(rf/subscribe [::patient-subs/current-patient])]
           [patient-banner pt :on-close #(rf/dispatch [::patient-events/close-current-patient])])

         [:section.section
          [:div.columns
           [:div.column.is-one-fifth
            [render-menu (assoc menu
                           :selected (or (:active-panel @referral)
                                         (do (swap! referral assoc :active-panel ::who-are-you?) (:active-panel @referral)))
                           :enabled (active-menus @referral)
                           :choose-fn #(swap! referral assoc :active-panel %))]
            [:a.button {:on-click #(js/console.log "referral: " @referral)} "Log referral [debug]"]]
           [:div.column.is-four-fifths
            (if-not user
              [login-panel]
              ;; TODO: make menu handle component to show in a generic fashion?
              (case (:active-panel @referral)
                ::who-are-you?
                [user-panel referral {:name-kp    [::referrer ::practitioner :urn:oid:2.5.4/commonName]
                                      :title-kp   [::referrer ::job-title]
                                      :contact-kp [::referrer ::contact-details]
                                      :team-kp    [::referrer ::team-details]
                                      :valid-fn   (fn [] (s/valid? ::valid-referrer? @referral))
                                      :next-fn    #(swap! referral assoc :active-panel ::who-is-patient?)}]
                ::who-is-patient?
                [patient-search-panel referral {:identifier-kp      [::patient-search-identifier]
                                                :authenticated-user user}]
                [:p "Invalid menu selected"]))]]]]))))