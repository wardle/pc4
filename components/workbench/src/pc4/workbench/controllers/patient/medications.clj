(ns pc4.workbench.controllers.patient.medications
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.workbench.controllers.patient :as patient]
    [pc4.workbench.controllers.snomed :as snomed]
    [pc4.workbench.pathom :as pathom]
    [pc4.workbench.ui :as ui]
    [pc4.workbench.web :as web]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb])
  (:import (java.time LocalDate)))

(def medication-event-properties
  [:t_medication_event/id
   :t_medication_event/event_concept_fk
   {:t_medication_event/event_concept [:info.snomed.Concept/id
                                       {:info.snomed.Concept/preferredDescription
                                        [:info.snomed.Description/term]}]}
   :t_medication_event/severity
   :t_medication_event/type
   :t_medication_event/reaction_date_time])

(def medication-properties
  [:t_medication/id :t_medication/patient_fk
   :t_medication/medication_concept_fk
   {:t_medication/medication [:info.snomed.Concept/id
                              {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
   :t_medication/date_from :t_medication/date_to
   :t_medication/as_required
   :t_medication/date_from_accuracy :t_medication/date_to_accuracy
   :t_medication/dose :t_medication/units
   :t_medication/route :t_medication/frequency
   :t_medication/indication :t_medication/more_information :t_medication/reason_for_stopping
   {:t_medication/events medication-event-properties}])


(def infusion-event-severities
  "These are the supported severities for a medication infusion event."
  ["PROLONGED_REACTION"
   "MILD_NO_INTERRUPTION"
   "LIFE_THREATENING"
   "MODERATE_TEMPORARY_INTERRUPTION"])

(defn ui-edit-medication-event
  [idx {:t_medication_event/keys
        [id type event_concept_fk event_concept reaction_date_time description_of_reaction
         action_taken premedication severity sample_obtained_antibodies drug_batch_identifier]}
   {:keys [url can-edit]}]
  (ui/active-panel
    {}
    [:input {:type "hidden" :name (str "events[" id "][medication-event-id]") :value id}]
    [:input {:type "hidden" :name (str "events[" id "][idx]") :value idx}]
    (snomed/ui-select-autocomplete
      {:id               (str "events-" id "-event-concept")
       :disabled         (not can-edit)
       :selected-concept event_concept
       :name             (str "events[" id "][event-concept]")
       :ecl              "<404684003|Clinical finding|"})
    (ui/ui-textarea
      {:name (str "events[" id "][description-of-reaction]")}
      description_of_reaction)
    (ui/ui-button {:disabled (not can-edit)
                   :hx-post  url :hx-target "#edit-medication" :hx-swap "outerHTML"
                   :hx-vals  (web/write-hx-vals :action {:action :remove-event :event-id id})} "Remove event")))


(defn reason->option
  [{:t_medication_reason_for_stopping/keys [id name]}]
  {:id id :text name})

(defn ui-edit-medication
  [medication {:keys [csrf-token can-edit common-medications reasons error]}]
  (let [{:t_medication/keys [id patient_fk medication_concept_fk date_from date_from_accuracy date_to date_to_accuracy
                             medication
                             reason_for_stopping events more_information indication]} medication
        url (route/url-for :patient/save-medication)
        term (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])
        now (str (LocalDate/now))]
    (ui/active-panel
      {:id    "edit-medication"
       :title (if id term "Add medication")}
      [:form {:method "post" :action url :hx-target "#edit-medication"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:input {:type "hidden" :name "medication-id" :value id}]
       [:input {:type "hidden" :name "existing-term" :value term}]
       (ui/ui-simple-form
         (when error
           (ui/box-error-message {:title "Invalid" :message "You have entered invalid data."}))
         (if id
           [:input {:type "hidden" :name "medication-concept" :value (snomed/make-id+term medication_concept_fk term)}]
           (ui/ui-simple-form-item
             {:label "Medication:"}
             (snomed/ui-select-autocomplete {:name             "medication-concept"
                                             :placeholder      "Enter medication"
                                             :ecl              "<10363601000001109"
                                             :selected-concept medication
                                             :common-concepts  common-medications})))
         (ui/ui-simple-form-item
           {:label "Date from:"}
           [:div.mt-2.grid.grid-cols-1.md:grid-cols-2
            [:div.col-span-1 (ui/ui-local-date {:name "date-from" :disabled (not can-edit) :max now} date_from)]
            [:div.col-span-1 (ui/ui-local-date-accuracy {:name "date-from-accuracy" :disabled (not can-edit)} date_from_accuracy)]])
         (ui/ui-simple-form-item
           {:label "Date to:"}
           [:div.mt-2.grid.grid-cols-1.md:grid-cols-2
            [:div.col-span-1 (ui/ui-local-date {:name "date-to" 
                                               :disabled (not can-edit) 
                                               :max now 
                                               :hx-trigger "change delay:1000ms, blur"
                                               :hx-disabled-elt "this,#reason-for-stopping"
                                               :hx-post url 
                                               :hx-target "#edit-medication" 
                                               :hx-swap "outerHTML" 
                                               :hx-vals (web/write-hx-vals :action {:action :update-medication})} date_to)]
            [:div.col-span-1 (ui/ui-local-date-accuracy {:name "date-to-accuracy" :disabled (not can-edit)} date_to_accuracy)]])
         (ui/ui-simple-form-item
           {:label "Reason for stopping:"}
           (ui/ui-select-button {:name        "reason-for-stopping"
                                 :id          "reason-for-stopping"
                                 :disabled    (not can-edit)
                                 :selected-id (cond
                                                (and date_to (= reason_for_stopping :NOT_APPLICABLE)) :OTHER
                                                (and date_to reason_for_stopping) reason_for_stopping
                                                date_to :OTHER
                                                :else :NOT_APPLICABLE)
                                 :options     (let [pred (if date_to 
                                                    #(not= :NOT_APPLICABLE (:id %))  ;; When date_to has a value, exclude NOT_APPLICABLE
                                                    #(= :NOT_APPLICABLE (:id %)))    ;; When date_to is blank, only include NOT_APPLICABLE
                                               options (->> reasons
                                                            (map reason->option)
                                                            (filter pred)
                                                            (sort-by :text))]
                                                options)}))
         (ui/ui-simple-form-item
           {:label "Notes"}
           (ui/ui-textarea {:name "more-information" :disabled (not can-edit)} more_information))
         (when (seq events)
           [:div
            (ui/ui-simple-form-item
              {:label "Events / reactions"})
            (map-indexed (fn [idx evt] (ui-edit-medication-event idx evt {:url url :can-edit can-edit})) events)])
         (ui/ui-action-bar
           (ui/ui-submit-button
             {:disabled (not can-edit)
              :hx-vals  (web/write-hx-vals :action {:action :save-medication})}
             "Save")
           (ui/ui-button
             {:disabled  (not can-edit)
              :hx-post   url
              :hx-target "#edit-medication" :hx-swap "outerHTML"
              :hx-vals   (web/write-hx-vals :action {:action :add-event})}
             "Add event")
           (ui/ui-delete-button
             {:disabled (or (not can-edit) (= id "new") (nil? id))
              :hx-delete (when (and id can-edit (not= id "new"))
                           (route/url-for :patient/delete-medication 
                                         :path-params {:patient-identifier patient_fk
                                                       :medication-id id}))
              :hx-headers (json/write-str {"X-CSRF-Token" csrf-token})
              :hx-params "none"
              :hx-confirm "Are you sure you want to delete this medication?"
              :hx-target "body"}
             "Delete")))])))

(def edit-medication-handler
  (pathom/handler
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier :t_patient/id :t_patient/permissions]}
     {:ui/current-medication medication-properties}
     :com.eldrix.rsdb/all-medication-reasons-for-stopping
     {:ui/authenticated-user [(list :t_user/common_concepts {:ecl "<10363601000001109|UK Product|" :accept-language "en-GB"})]}]
    (fn [request {:ui/keys              [authenticated-user csrf-token patient-page current-patient current-medication]
                  :com.eldrix.rsdb/keys [all-medication-reasons-for-stopping]}]
      (clojure.pprint/pprint current-medication)
      (let [common-medications (:t_user/common_concepts authenticated-user)
            medication-id (:t_medication/id current-medication)]
        (if (or (nil? medication-id) (= (:t_patient/id current-patient) (:t_medication/patient_fk current-medication)))
          (web/ok
            (web/render-file
              "templates/patient/base.html"
              (assoc patient-page
                :content
                (web/render
                  (ui-edit-medication (assoc current-medication :t_medication/patient_fk (:t_patient/id current-patient))
                                      {:csrf-token         csrf-token
                                       :can-edit           (:PATIENT_EDIT (:t_patient/permissions current-patient))
                                       :reasons            all-medication-reasons-for-stopping
                                       :common-medications common-medications})))))
          (web/forbidden "Not authorized"))))))

(defn safe-parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s)))

(defn safe-parse-id-or-tempid
  [s]
  (when-not (str/blank? s)
    (or (parse-long s) (parse-uuid s))))

(defn parse-medication-event-from-form
  [{:strs [medication-event-id event-concept severity description-of-reaction]}]
  (let [[event-concept-id term] (some-> event-concept snomed/parse-id+term)]
    {:t_medication_event/id                      (safe-parse-id-or-tempid medication-event-id)
     :t_medication_event/event_concept_fk        event-concept-id
     :t_medication_event/event_concept           (when event-concept-id {:info.snomed.Concept/id event-concept-id
                                                                         :info.snomed.Concept/preferredDescription
                                                                         {:info.snomed.Description/term term}})
     :t_medication_event/severity                severity
     :t_medication_event/description_of_reaction description-of-reaction}))

(defn parse-medication-from-form
  [{patient-pk :t_patient/id}
   {:strs [medication-id more-information events
           date-to medication-concept date-to-accuracy date-from date-from-accuracy reason-for-stopping]}]
  (let [medication-id (some-> medication-id parse-long)
        [medication-concept-id term] (some-> medication-concept snomed/parse-id+term)]
    (cond-> {:t_medication/patient_fk            patient-pk
             :t_medication/more_information      more-information
             :t_medication/events                (mapv parse-medication-event-from-form (sort-by #(some-> (get % "idx") parse-long) (vals events)))
             :t_medication/date_to               (safe-parse-local-date date-to)
             :t_medication/date_to_accuracy      (keyword date-to-accuracy)
             :t_medication/date_from             (safe-parse-local-date date-from)
             :t_medication/date_from_accuracy    (keyword date-from-accuracy)
             :t_medication/medication_concept_fk medication-concept-id
             :t_medication/medication            {:info.snomed.Concept/id                   medication-concept-id
                                                  :info.snomed.Concept/preferredDescription {:info.snomed.Description/term term}}
             :t_medication/reason_for_stopping   (keyword reason-for-stopping)}
      medication-id (assoc :t_medication/id medication-id))))





(def save-medication-handler
  "Handler called for a 'save' operation, or during editing"
  (patient/editable-handler
    [:ui/csrf-token
     {:ui/current-patient [:t_patient/patient_identifier :t_patient/id :t_patient/permissions]}
     :com.eldrix.rsdb/all-medication-reasons-for-stopping]
    (fn [{:keys [params] :as request} {:ui/keys              [csrf-token current-patient]
                                       :com.eldrix.rsdb/keys [all-medication-reasons-for-stopping]}]
      (println "\n\n\n\nSAVE medication\n")
      (clojure.pprint/pprint params)
      (let [rsdb (get-in request [:env :rsdb])
            {:keys [action event-id] :or {action :save-medication}} (web/read-hx-vals "action" params)
            medication (cond-> (parse-medication-from-form current-patient params)
                         (= :add-event action)
                         (update :t_medication/events
                                 conj {:t_medication_event/id   (random-uuid)
                                       :t_medication_event/type :ADVERSE_EVENT})
                         (= :remove-event action)
                         (update :t_medication/events
                                 (fn [evts]
                                   (remove #(= event-id (:t_medication_event/id %)) evts)))
                         (= :save-medication action)
                         (update :t_medication/events
                                 (fn [evts]
                                   (map #(if (uuid? (:t_medication_event/id %))
                                           (dissoc % :t_medication_event/id) %) evts))))]
        (println "\n\n\n medication:")
        (if (= :save-medication action)
          (do (rsdb/upsert-medication! rsdb medication)
              (web/hx-redirect (route/url-for :patient/medications))) ;; TODO: implement return-url parameter
          (web/ok
            (web/render
              (ui-edit-medication medication
                                  {:csrf-token         csrf-token
                                   :can-edit           (:PATIENT_EDIT (:t_patient/permissions current-patient))
                                   :reasons            all-medication-reasons-for-stopping
                                   :common-medications nil}))))))))

(defn medications-table
  [title patient-identifier medications]
  [:div
   (ui/ui-title {:title title})
   (ui/ui-table
     (ui/ui-table-head
       (ui/ui-table-row
         {}
         (map #(ui/ui-table-heading {} %) ["Medication" "Date from" "Date to"])))
     (ui/ui-table-body
       (->> medications
            (sort-by #(get-in % [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
            (map #(ui/ui-table-row
                    {:class "cursor-pointer hover:bg-gray-50"
                     :hx-get (route/url-for :patient/edit-medication :path-params {:patient-identifier patient-identifier :medication-id (:t_medication/id %)})
                     :hx-target "body"
                     :hx-push-url "true"}
                    (ui/ui-table-cell {}
                                      [:a {:href (route/url-for :patient/edit-medication :path-params {:patient-identifier patient-identifier :medication-id (:t_medication/id %)})}
                                       (get-in % [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])])
                    (ui/ui-table-cell {} (str (:t_medication/date_from %)))
                    (ui/ui-table-cell {} (str (:t_medication/date_to %))))))))])

(def medications-handler
  (pathom/handler
    {:menu :medications}
    [:ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       {:t_patient/medications
        [:t_medication/id
         {:t_medication/medication [{(list :info.snomed.Concept/preferredDescription {:accept-language "en-nhs-dmd"})
                                     [:info.snomed.Description/term]}]}
         :t_medication/date_from :t_medication/date_to]}]}]
    (fn [_ {:ui/keys [patient-page current-patient]}]
      (let [{:t_patient/keys [patient_identifier medications]} current-patient
            active-medications (filter #(nil? (:t_medication/date_to %)) medications)
            inactive-medications (filter #(some? (:t_medication/date_to %)) medications)] ;; TODO: fix 'active' derivation
        (web/ok
          (web/render-file
            "templates/patient/base.html"
            (assoc patient-page
              :content
              (web/render
                [:div
                 [:div (medications-table "Active medications" patient_identifier active-medications)]
                 (when (seq inactive-medications)
                   [:div.pt-4
                    [:div (medications-table "Inactive medications" patient_identifier inactive-medications)]])]))))))))

(def delete-medication-handler
  "Handler to delete a medication"
  (patient/editable-handler
    [:ui/csrf-token
     {:ui/current-patient [:t_patient/patient_identifier :t_patient/permissions]}
     {:ui/current-medication medication-properties}]
    (fn [request {:ui/keys [current-patient current-medication]}]
      (let [rsdb (get-in request [:env :rsdb])
            medication-id (:t_medication/id current-medication)
            patient-identifier (:t_patient/patient_identifier current-patient)]
        (log/info "deleting medication" {:medication-id medication-id :patient-identifier patient-identifier})
        (rsdb/delete-medication! rsdb current-medication)
        (web/hx-redirect (route/url-for :patient/medications))))))


