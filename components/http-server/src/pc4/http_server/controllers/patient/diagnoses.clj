(ns pc4.http-server.controllers.patient.diagnoses
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.route :as route]
            [pc4.http-server.controllers.snomed :as snomed]
            [pc4.http-server.pathom :as pathom]
            [pc4.http-server.ui :as ui]
            [pc4.http-server.web :as web]
            [pc4.log.interface :as log]
            [pc4.rsdb.interface :as rsdb])
  (:import (java.time LocalDate)))

(defn safe-parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s)))

(defn diagnoses-table
  [title patient-identifier diagnoses]
  [:div
   (ui/ui-title {:title title})
   (ui/ui-table
     (ui/ui-table-head
       (ui/ui-table-row
         {}
         (map #(ui/ui-table-heading {} %) ["Diagnosis" "Date onset" "Date diagnosis" "Date to" "Status"])))
     (ui/ui-table-body
       (->> diagnoses
            (sort-by #(get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
            (map #(ui/ui-table-row
                    {:class "hover:bg-gray-50"}
                    (ui/ui-table-cell {}
                                      [:a {:href (route/url-for :patient/edit-diagnosis :path-params {:patient-identifier patient-identifier :diagnosis-id (:t_diagnosis/id %)})}
                                       (get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])])
                    (ui/ui-table-cell {} (str (:t_diagnosis/date_onset %)))
                    (ui/ui-table-cell {} (str (:t_diagnosis/date_diagnosis %)))
                    (ui/ui-table-cell {} (str (:t_diagnosis/date_to %)))
                    (ui/ui-table-cell {} (str/replace (str (:t_diagnosis/status %)) #"_" " ")))))))])

(def diagnoses-handler
  (pathom/handler
    {:menu :diagnoses}
    [:ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       {:t_patient/diagnoses [:t_diagnosis/id :t_diagnosis/date_diagnosis :t_diagnosis/date_onset :t_diagnosis/date_to
                              :t_diagnosis/status {:t_diagnosis/diagnosis
                                                   [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}]}]
    (fn [_ {:ui/keys [patient-page current-patient]}]
      (let [{:t_patient/keys [patient_identifier diagnoses]} current-patient
            active-diagnoses (filter #(= "ACTIVE" (:t_diagnosis/status %)) diagnoses)
            inactive-diagnoses (filter #(not= "ACTIVE" (:t_diagnosis/status %)) diagnoses)]
        (web/ok
          (web/render-file
            "templates/patient/base.html"
            (assoc patient-page
              :content
              (web/render
                [:div
                 [:div (diagnoses-table "Active diagnoses" patient_identifier active-diagnoses)]
                 (when (seq inactive-diagnoses) [:div.pt-4 (diagnoses-table "Inactive diagnoses" patient_identifier inactive-diagnoses)])]))))))))


(defn ui-edit-diagnosis
  [{:keys [csrf-token can-edit diagnosis common-diagnoses error]}]
  (let [{:t_diagnosis/keys [id patient_fk concept_fk date_onset date_diagnosis date_to diagnosis status full_description]} diagnosis
        url (route/url-for :patient/save-diagnosis)
        term (get-in diagnosis [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])
        now (str (LocalDate/now))]
    (ui/active-panel
      {:id    "edit-diagnosis"
       :title (if id term "Add diagnosis")}
      [:form {:method "post" :action url :hx-target "#edit-diagnosis"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:input {:type "hidden" :name "diagnosis-id" :value id}]
       [:input {:type "hidden" :name "existing-term" :value term}]
       (ui/ui-simple-form
         (when error
           (ui/box-error-message {:title "Invalid" :message "You have entered invalid data."}))
         (if id
           [:input {:type "hidden" :name "diagnosis-concept-id" :value concept_fk}]
           (ui/ui-simple-form-item
             {:label "Diagnosis"}
             (snomed/ui-select-autocomplete {:name             "diagnosis-concept-id"
                                             :placeholder      "Enter diagnosis"
                                             :ecl              "<404684003|Clinical finding|"
                                             :selected-concept diagnosis
                                             :common-concepts  common-diagnoses})))
         (ui/ui-simple-form-item
           {:label "Date of onset"}
           (ui/ui-local-date {:name "date-onset" :disabled (not can-edit) :max now} date_onset))
         (ui/ui-simple-form-item
           {:label "Date of diagnosis"}
           (ui/ui-local-date
             {:name "date-diagnosis" :disabled (not can-edit) :max now} date_diagnosis))
         (ui/ui-simple-form-item
           {:label "Date to"}
           (ui/ui-local-date {:name    "date-to" :disabled (not can-edit) :max now :hx-trigger "change delay:300ms"
                              :hx-disabled-elt "this,#status"
                              :hx-post url :hx-target "#edit-diagnosis" :hx-swap "outerHTML" :hx-vals "{\"partial\":true}"} date_to))
         (ui/ui-simple-form-item
           {:label "Status"}
           (ui/ui-select-button {:name        "status"
                                 :disabled    (not can-edit)
                                 :selected-id status
                                 :options     (if date_to [{:id "INACTIVE_REVISED" :text "Inactive - revised"}
                                                           {:id "INACTIVE_RESOLVED" :text "Inactive - resolved"}
                                                           {:id "INACTIVE_IN_ERROR" :text "Inactive - recorded in error"}]
                                                          [{:id "ACTIVE" :text "Active"}])}))
         (ui/ui-simple-form-item
           {:label "Notes"}
           (ui/ui-textarea {:name "full-description" :disabled (not can-edit)} full_description))
         (when (and id can-edit)
           [:p.text-sm.font-medium.leading-6.text-gray-600
            "To delete a diagnosis, record a 'to' date and update the status as appropriate."])
         (ui/ui-action-bar
           (ui/ui-submit-button {:disabled (not can-edit)} "Save")))])))

(def diagnosis-properties
  [:t_diagnosis/id :t_diagnosis/patient_fk
   :t_diagnosis/date_diagnosis :t_diagnosis/date_diagnosis_accuracy
   :t_diagnosis/date_onset :t_diagnosis/date_onset_accuracy
   :t_diagnosis/date_to :t_diagnosis/date_to_accuracy
   :t_diagnosis/status :t_diagnosis/full_description
   :t_diagnosis/concept_fk
   {:t_diagnosis/diagnosis
    [{:info.snomed.Concept/preferredDescription
      [:info.snomed.Description/term]}]}])

(s/def ::ordered-diagnosis-dates
  (fn [{:t_diagnosis/keys [patient date_onset date_diagnosis date_to]}]
    (let [{:t_patient/keys [date_birth date_death]} patient]
      (->> [date_birth date_onset date_diagnosis date_to date_death (LocalDate/now)]
           (remove nil?)
           (partition 2 1)
           (every? (fn [[a b]] (nat-int? (.compareTo b a))))))))

(s/def ::valid-diagnosis-status
  (fn [{:t_diagnosis/keys [date_to status]}]
    (if date_to
      (#{"INACTIVE_REVISED" "INACTIVE_IN_ERROR" "INACTIVE_RESOLVED"} status)
      (#{"ACTIVE"} status))))


(s/def ::create-or-save-diagnosis
  (s/and
    (s/keys :req [:t_diagnosis/patient_fk
                  :t_diagnosis/concept_fk
                  :t_diagnosis/date_onset
                  :t_diagnosis/date_diagnosis
                  :t_diagnosis/date_to
                  :t_diagnosis/status
                  :t_diagnosis/full_description]
            :opt [:t_diagnosis/id])
    ::ordered-diagnosis-dates
    ::valid-diagnosis-status))

(defn parse-save-diagnosis-params
  [{patient-pk :t_patient/id :as patient} request]
  (let [form-params (:form-params request)
        [concept-id term] (some-> form-params :diagnosis-concept-id snomed/parse-id+term)
        diagnosis-id (some-> form-params :diagnosis-id parse-long)]
    (cond-> {:t_diagnosis/patient_fk       (or (some-> form-params :patient-pk parse-long) patient-pk)
             :t_diagnosis/concept_fk       concept-id
             :t_diagnosis/diagnosis        {:info.snomed.Concept/id                   concept-id
                                            :info.snomed.Concept/preferredDescription {:info.snomed.Description/term term}}
             :t_diagnosis/patient          patient
             :t_diagnosis/date_onset       (-> form-params :date-onset safe-parse-local-date)
             :t_diagnosis/date_diagnosis   (-> form-params :date-diagnosis safe-parse-local-date)
             :t_diagnosis/date_to          (-> form-params :date-to safe-parse-local-date)
             :t_diagnosis/status           (:status form-params)
             :t_diagnosis/full_description (:full-description form-params)}
      diagnosis-id
      (assoc :t_diagnosis/id diagnosis-id))))

(def save-diagnosis-handler
  "Fragment to permit editing diagnosis. Designed to be used to replace page
  fragment for form validation.
  - on-cancel-url : URL to redirect if cancel
  - on-save-url   : URL to redirect after save"
  (pathom/handler
    [{:ui/current-patient [:t_patient/id :t_patient/patient_identifier
                           :t_patient/date_birth :t_patient/date_death]}
     {:ui/authenticated-user [(list :t_user/common_concepts {:ecl "<404684003|Clinical finding|" :accept-language "en-GB"})]}]
    (fn [{:keys [env] :as request} {:ui/keys [authenticated-user current-patient]}]
      (let [{:keys [rsdb]} env
            data (parse-save-diagnosis-params current-patient request)
            common-diagnoses (:t_user/common_concepts authenticated-user)
            trigger (web/hx-trigger request)
            valid? (s/valid? ::create-or-save-diagnosis data)]
        (when-not valid?
          (clojure.pprint/pprint (s/explain ::create-or-save-diagnosis data))
          (clojure.pprint/pprint data)
          (log/error "invalid diagnosis" (s/explain-data ::create-or-save-diagnosis data)))
        (cond
          (and valid? (nil? trigger))                       ;; if there was no trigger, this was a submit!
          (do
            (log/info "saving diagnosis" data)
            (if (:t_diagnosis/id data)
              (rsdb/update-diagnosis! rsdb data)
              (rsdb/create-diagnosis! rsdb current-patient data))
            (web/hx-redirect (route/url-for :patient/diagnoses)))
          :else                                             ;; just updating in place
          (web/ok (web/render (ui-edit-diagnosis {:csrf-token       (csrf/existing-token request)
                                                  :can-edit         true ;; by definition, we can edit. Permissions will also be checked on submit however
                                                  :error            (and (nil? trigger) (not valid?))
                                                  :diagnosis        data
                                                  :common-diagnoses common-diagnoses}))))))))

(def edit-diagnosis-handler
  (pathom/handler
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier :t_patient/id :t_patient/permissions]}
     {:ui/current-diagnosis diagnosis-properties}
     {:ui/authenticated-user [(list :t_user/common_concepts {:ecl "<404684003|Clinical finding|" :accept-language "en-GB"})]}]
    (fn [_ {:ui/keys [authenticated-user csrf-token patient-page current-patient current-diagnosis]}]
      (log/debug "edit diagnosis" {:patient current-patient :diagnosis current-diagnosis})
      (let [common-diagnoses (:t_user/common_concepts authenticated-user)
            diagnosis-id (:t_diagnosis/id current-diagnosis)]
        (if (or (nil? diagnosis-id) (= (:t_patient/id current-patient) (:t_diagnosis/patient_fk current-diagnosis))) ;; check diagnosis is for same patient
          (web/ok                                           ;; render a whole page
            (web/render-file
              "templates/patient/base.html"
              (assoc patient-page
                :content
                (web/render
                  (ui-edit-diagnosis
                    {:csrf-token       csrf-token
                     :can-edit         (:PATIENT_EDIT (:t_patient/permissions current-patient))
                     :diagnosis        (assoc current-diagnosis :t_diagnosis/patient_fk (:t_patient/id current-patient))
                     :common-diagnoses common-diagnoses})))))
          (web/forbidden "Not authorized"))))))


