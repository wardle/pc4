(ns pc4.ui.medications
  (:require [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.algorithms.merge :as merge]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.rsdb]
            [pc4.ui.core :as ui]
            [pc4.ui.patients :as patients]
            [pc4.ui.snomed :as snomed]
            [taoensso.timbre :as log]))


(declare EditMedication)
(declare EditMedicationEvent)

(defn edit-medication-event*
  "For a given medication event, prepare for editing."
  [state id]
  (log/info "preparing to edit event" id)
  (let [ident [:choose-medication-event id]]
    (-> state
        (fs/add-form-config* EditMedicationEvent [:t_medication_event/id id])
        (assoc-in [:autocomplete/by-id ident] (comp/get-initial-state snomed/Autocomplete {:id ident}))
        (assoc-in [:t_medication_event/id id :ui/choose-event] [:autocomplete/by-id ident]))))


(defn edit-medication-events*
  [state {:t_medication/keys [events]}]
  (log/info "Preparing to edit events" events)
  (reduce edit-medication-event* state (map :t_medication_event/id events)))

(defn add-medication-event*
  "Add a new medication event to the medication and prepare for editing."
  [state medication event-id]
  (-> state
      (assoc-in [:t_medication_event/id event-id] {:t_medication_event/id            event-id
                                                   :t_medication_event/medication_fk (:t_medication/id medication)
                                                   :t_medication_event/type          :ADVERSE_EVENT})
      (edit-medication-event* event-id)
      (update-in [:t_medication/id (:t_medication/id medication) :t_medication/events] (fnil conj []) [:t_medication_event/id event-id])))

(defmutation add-medication-event
  [{:keys [id medication]}]
  (action
    [{:keys [state]}]
    (swap! state add-medication-event* medication id)))

(defn remove-medication-event*
  [state medication-id event-id]
  (-> state
      (update :t_medication_event/id dissoc event-id)
      (merge/remove-ident* [:t_medication_event/id event-id] [:t_medication/id medication-id :t_medication/events])))

(defmutation remove-medication-event
  [{:keys [id medication-id]}]
  (action
    [{:keys [state]}]
    (swap! state remove-medication-event* medication-id id)))

(defn edit-medication*
  "Set up application state in order to edit a given medication."
  [state patient-identifier {medication-id :t_medication/id :as medication}]
  (-> state
      (fs/add-form-config* EditMedication [:t_medication/id medication-id])
      (assoc-in [:autocomplete/by-id :choose-medication] (comp/get-initial-state snomed/Autocomplete {:id :choose-medication}))
      (assoc-in [:t_medication/id medication-id :ui/choose-medication] [:autocomplete/by-id :choose-medication])
      (edit-medication-events* medication)
      (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-medication] [:t_medication/id medication-id])))

(defmutation edit-medication
  [{:keys [patient-identifier medication no-load] :as params}]
  (action
    [{:keys [app state]}]
    (when-let [medication-id (:t_medication/id medication)]
      (log/info "editing medication" medication-id)
      (if (or no-load (tempid/tempid? medication-id))
        (swap! state edit-medication* patient-identifier medication)
        (df/load! app [:t_medication/id medication-id] EditMedication
                  {:post-mutation        `edit-medication
                   :post-mutation-params (assoc params :no-load true)})))))

(defn add-medication*
  [state patient-identifier {:t_medication/keys [id] :as medication}]
  (-> state
      (assoc-in [:t_medication/id id] medication)
      (update-in [:t_patient/patient_identifier patient-identifier :t_patient/medications] (fnil conj []) [:t_medication/id id])
      (edit-medication* patient-identifier medication)))

(defmutation add-medication
  [{:keys [patient-identifier medication]}]
  (action [{:keys [state]}] (swap! state add-medication* patient-identifier medication)))

(defn cancel-edit-medication*
  [state patient-identifier medication]
  (let [medication-id (:t_medication/id medication)
        temp-event-ids (->> (:t_medication/events medication)
                            (map :t_medication_event/id)
                            (filter tempid/tempid?))]
    (cond-> (-> state
                (fs/pristine->entity* [:t_medication/id medication-id]) ;; restore form to pristine state
                (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-medication] {})) ;; clear modal dialog)
            ;; if cancelling a newly created diagnosis, delete it and its relationship
            (tempid/tempid? medication-id)
            (merge/remove-ident* [:t_medication/id medication-id] [:t_patient/patient_identifier patient-identifier :t_patient/medications])
            ;; remove all temporarily created medication events linked to this medication
            (seq temp-event-ids)
            (update :t_medication_event/id (fn [m] (apply dissoc m temp-event-ids))))))

(defmutation cancel-edit-medication
  [{:keys [patient-identifier medication]}]
  (action
    [{:keys [state]}]
    (swap! state cancel-edit-medication* patient-identifier medication)))

(defsc MedicationReasonForStopping
  [this {:t_medication_reason_for_stopping/keys [id name]}]
  {:ident :t_medication_reason_for_stopping/id
   :query [:t_medication_reason_for_stopping/id
           :t_medication_reason_for_stopping/name]})

(defmutation load-all-reasons-for-stopping
  "Lazily loads all reasons for stopping from the server, placing results at
  top level under `:ui/all-reasons-for-stopping-medication`."
  [_]
  (action [{:keys [app state]}]
          (when (empty? (:ui/all-reasons-for-stopping-medication @state))
            (log/debug "Loading reasons for stopping medication")
            (df/load! app :com.eldrix.rsdb/all-medication-reasons-for-stopping MedicationReasonForStopping
                      {:target [:ui/all-reasons-for-stopping-medication]}))))

(defsc EditMedicationEvent
  [this {event-id      :t_medication_event/id
         medication-id :t_medication_event/medication_fk
         event-type    :t_medication_event/type
         event-concept :t_medication_event/event_concept
         :ui/keys      [choose-event]}]
  {:ident       :t_medication_event/id
   :query       [:t_medication_event/id :t_medication_event/medication_fk
                 :t_medication_event/type fs/form-config-join
                 {:ui/choose-event (comp/get-query snomed/Autocomplete)}
                 {:t_medication_event/event_concept [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]
   :pre-merge   (fn [{:keys [current-normalized data-tree]}]
                  (log/info "premerge for editmedicationevent" data-tree)
                  (merge current-normalized
                         {:ui/choose-event (comp/get-initial-state snomed/Autocomplete {:id [:autocomplete/by-id [:choose-medication-event (:t_medication_event/id data-tree)]]})}
                         data-tree))
   :form-fields #{:t_medication_event/type :t_medication_event/event_concept}}
  (ui/ui-simple-form-item {:label (div
                                    (dom/h3 :.pb-4 (case event-type :ADVERSE_EVENT "Adverse Event" :INFUSION_REACTION "Infusion reaction" "Event"))
                                    (ui/ui-link-button {:onClick #(comp/transact! this [(remove-medication-event {:id event-id :medication-id medication-id})])}
                                                       "Remove"))}
    (let [term (get-in event-concept [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
      (if (str/blank? term)
        (snomed/ui-autocomplete choose-event
                                {:value  event-concept, :constraint "<473010000"
                                 :onSave #(m/set-value! this :t_medication_event/event_concept %)})
        (let [s (get-in event-concept [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
          (dom/div :.mt-2 (ui/ui-link-button {:onClick #(m/set-value! this :t_medication_event/event_concept nil)} term)))))))


(def ui-edit-medication-event (comp/factory EditMedicationEvent {:keyfn :t_medication_event/id}))

(defn reason-for-stopping-description
  [v]
  (some-> v name (str/replace #"_" " ")))

(s/def :t_medication/date_from (s/nilable #(instance? goog.date.Date %)))
(s/def :t_medication/date_to (s/nilable #(instance? goog.date.Date %)))
(s/def :t_medication/medication (s/keys :req [:info.snomed.Concept/id]))
(s/def :t_medication/more_information (s/nilable string?))
(s/def ::save-medication
  (s/keys :req [:t_medication/medication]
          :opt [:t_medication/date_from :t_medication/date_to
                :t_medication/more_information]))

(defsc EditMedication
  [this {:t_medication/keys [id date_from date_to medication reason_for_stopping more_information events] :as editing-medication
         :ui/keys           [choose-medication current-patient all-reasons-for-stopping-medication]}]
  {:ident         :t_medication/id
   :query         [:t_medication/id
                   :t_medication/date_from
                   :t_medication/date_to
                   :t_medication/more_information
                   :t_medication/reason_for_stopping
                   :t_medication/patient_fk
                   {:t_medication/events (comp/get-query EditMedicationEvent)}
                   {:t_medication/medication [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                   {:ui/choose-medication (comp/get-query snomed/Autocomplete)}
                   fs/form-config-join
                   {[:ui/current-patient '_] [:t_patient/patient_identifier :t_patient/id]}
                   {[:ui/all-reasons-for-stopping-medication '_] (comp/get-query MedicationReasonForStopping)}]
   :initial-state (fn [params] {:ui/choose-medication (comp/get-initial-state snomed/Autocomplete {:id :choose-medication})})
   :form-fields   #{:t_medication/date_from :t_medication/date_to :t_medication/medication
                    :t_medication/events
                    :t_medication/reason_for_stopping :t_medication/more_information}}
  (let [patient-identifier (:t_patient/patient_identifier current-patient)
        temp? (tempid/tempid? id)
        do-save #(comp/transact! this [(pc4.rsdb/save-medication (-> editing-medication
                                                                     (assoc :t_patient/patient_identifier patient-identifier)
                                                                     (assoc :t_medication/patient_fk (:t_patient/id current-patient))
                                                                     (dissoc :ui/choose-medication :ui/current-patient :ui/all-reasons-for-stopping-medication)))])
        do-cancel #(comp/transact! this [(cancel-edit-medication {:patient-identifier patient-identifier :medication editing-medication})])
        do-delete #(comp/transact! this [(pc4.rsdb/delete-medication {:t_patient/patient_identifier patient-identifier :t_medication/id id})])]
    (tap> {:component  :edit-medication
           :params     editing-medication
           :valid?     (s/valid? ::save-medication editing-medication)
           :validation (s/explain-data ::save-medication editing-medication)})
    (ui/ui-modal
      {:actions [{:id        ::save-action :title "Save" :role :primary
                  :disabled? (not (s/valid? ::save-medication editing-medication))
                  :onClick   do-save}
                 (when-not (tempid/tempid? id) {:id ::delete-action :title "Delete" :onClick do-delete})
                 {:id      :add-event :title "Add event"
                  :onClick #(comp/transact! this [(add-medication-event {:id (tempid/tempid) :medication editing-medication})])}
                 {:id ::cancel-action :title "Cancel" :onClick do-cancel}]
       :onClose do-cancel}
      (ui/ui-simple-form {}
        (ui/ui-simple-form-title {:title (if temp? "Add medication" (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))})
        (when temp?
          (ui/ui-simple-form-item {:htmlFor "medication" :label "Medication"}
            (if (:info.snomed.Concept/id medication)
              (dom/div :.mt-2 (ui/ui-link-button {:onClick #(m/set-value! this :t_medication/medication nil)}
                                                 (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
              (snomed/ui-autocomplete choose-medication
                                      {:autoFocus true, :constraint "(<10363601000001109 MINUS <<10363901000001102)"
                                       :onSave    #(m/set-value! this :t_medication/medication %)}))))
        (ui/ui-simple-form-item {:htmlFor "date-from" :label "Date from"}
          (ui/ui-local-date
            {:value date_from
             :min-date (:t_patient/date_birth current-patient)
             :max-date (goog.date.Date.)
             :onChange #(m/set-value! this :t_medication/date_from %)}))
        (ui/ui-simple-form-item {:htmlFor "date-to" :label "Date to"}
          (ui/ui-local-date
            {:value date_to
             :min-date (:t_patient/date_birth current-patient)
             :onChange #(do (m/set-value! this :t_medication/date_to %)
                            (cond
                              (nil? %)
                              (m/set-value! this :t_medication/reason_for_stopping :NOT_APPLICABLE)
                              (= :NOT_APPLICABLE reason_for_stopping)
                              (m/set-value! this :t_medication/reason_for_stopping :ADVERSE_EVENT)))}))
        (when date_to
          (ui/ui-simple-form-item {:label "Reason for stopping"}
            (ui/ui-select-popup-button
              {:name          "reason-for-stopping"
               :value         reason_for_stopping
               :disabled?     (nil? date_to)
               :default-value (if date_to :ADVERSE_EVENT :NOT_APPLICABLE)
               :display-key   reason-for-stopping-description
               :options       (cond-> (set (mapv :t_medication_reason_for_stopping/id all-reasons-for-stopping-medication))
                                      date_to (disj :NOT_APPLICABLE))
               :onChange      #(m/set-value! this :t_medication/reason_for_stopping %)})))
        (ui/ui-simple-form-item {:htmlFor "notes" :label "Notes"}
          (ui/ui-textarea {:id       "notes" :value more_information
                           :onChange #(m/set-value! this :t_medication/more_information %)}))
        (for [event events]
          (ui-edit-medication-event event))))))

(def ui-edit-medication (comp/factory EditMedication))


(defsc MedicationListItem
  [this {:t_medication/keys [id date_from date_to medication reason_for_stopping] :as params} computed-props]
  {:ident :t_medication/id
   :query [:t_medication/id :t_medication/date_from :t_medication/date_to :t_medication/patient_fk :t_medication/more_information
           :t_medication/reason_for_stopping
           {:t_medication/medication [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}
  (ui/ui-table-row computed-props
    (ui/ui-table-cell {}
      (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
    (ui/ui-table-cell {} (ui/format-date date_from))
    (ui/ui-table-cell {} (ui/format-date date_to))
    (ui/ui-table-cell {} (when-not (= :NOT_APPLICABLE reason_for_stopping) (reason-for-stopping-description reason_for_stopping)))))

(def ui-medication-list-item (comp/computed-factory MedicationListItem {:keyfn :t_medication/id}))

(defn ^:private medication-by-date-from
  [med]
  (- 0 (if-let [date-from (:t_medication/date_from med)] (.valueOf date-from) 0)))

(defsc PatientMedications
  [this {:t_patient/keys [id patient_identifier medications] :as patient
         :>/keys         [banner], :ui/keys [editing-medication]}]
  {:ident         :t_patient/patient_identifier
   :route-segment ["pt" :t_patient/patient_identifier "medications"]
   :query         [:t_patient/id :t_patient/patient_identifier
                   {:>/banner (comp/get-query patients/PatientBanner)}
                   {:t_patient/medications (comp/get-query MedicationListItem)}
                   {:ui/editing-medication (comp/get-query EditMedication)}]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (comp/transact! app [(load-all-reasons-for-stopping {})])
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientMedications
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))
   :pre-merge     (fn [{:keys [current-normalized data-tree]}]
                    (merge {:ui/editing-medication {}} current-normalized data-tree))}
  (tap> {:patient-medication medications})
  (let [do-edit #(comp/transact! this [(edit-medication {:patient-identifier patient_identifier :medication %})])
        do-add #(comp/transact! this [(add-medication {:patient-identifier patient_identifier :medication {:t_medication/id (tempid/tempid)}})])]
    (when patient_identifier
      (patients/ui-layout
        {:banner (patients/ui-patient-banner banner)
         :menu   (patients/ui-pseudonymous-menu
                   patient
                   {:selected-id :medications
                    :sub-menu    {:items [{:id      :add-medication
                                           :content (ui/ui-menu-button {:onClick do-add} "Add medication")}]}})

         :content
         (comp/fragment
           (when (:t_medication/id editing-medication)
             (ui-edit-medication editing-medication))
           (ui/ui-table {}
             (ui/ui-table-head {}
               (ui/ui-table-row {}
                 (map #(ui/ui-table-heading {:react-key %} %) ["Treatment" "Date from" "Date to" "Reason for stopping" ""])))
             (ui/ui-table-body {}
               (->> medications
                    (remove #(#{:RECORDED_IN_ERROR} (:t_medication/reason_for_stopping %)))
                    (sort-by (juxt medication-by-date-from
                                   #(get-in % [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))


                    (map #(ui-medication-list-item % {:onClick (fn [] (do-edit %))
                                                      :classes ["cursor-pointer" "hover:bg-gray-200"]}))))))}))))

(def ui-patient-medications (comp/factory PatientMedications))
