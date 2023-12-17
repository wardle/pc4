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

(defn edit-mutation*
  [state patient-identifier medication-id]
  (-> state
      (fs/add-form-config* EditMedication [:t_medication/id medication-id])
      (assoc-in [:autocomplete/by-id :choose-medication] (comp/get-initial-state snomed/Autocomplete {:id :choose-medication}))
      (assoc-in [:t_medication/id medication-id :ui/choose-medication] [:autocomplete/by-id :choose-medication])
      (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-medication] [:t_medication/id medication-id])))

(defmutation edit-medication
  [{:keys [patient-identifier medication]}]
  (action
    [{:keys [app state]}]
    (when-let [medication-id (:t_medication/id medication)]
      (log/info "editing medication" medication-id)
      (when-not (tempid/tempid? medication-id) (df/load! app [:t_medication/id medication-id] EditMedication))
      (swap! state (fn [s] (edit-mutation* s patient-identifier medication-id))))))

(defmutation add-medication
  [{:keys [patient-identifier medication]}]
  (action [{:keys [state]}]
          (let [medication-id (:t_medication/id medication)]
            (swap! state (fn [s]
                           (-> s
                               (assoc-in [:t_medication/id medication-id] medication)
                               (update-in [:t_patient/patient_identifier patient-identifier :t_patient/medications] (fnil conj []) [:t_medication/id medication-id])
                               (edit-mutation* patient-identifier medication-id)))))))

(defn cancel-edit-medication*
  [state patient-identifier medication-id]
  (cond-> (-> state
              (fs/pristine->entity* [:t_medication/id medication-id]) ;; restore form to pristine state
              (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-medication] {})) ;; clear modal dialog
          (tempid/tempid? medication-id)                    ;; if cancelling a newly created diagnosis, delete it and its relationship
          (merge/remove-ident* [:t_medication/id medication-id] [:t_patient/patient_identifier patient-identifier :t_patient/medications])))

(defmutation cancel-edit-medication
  [{:keys [patient-identifier medication]}]
  (action [{:keys [ref state]}]
          (let [medication-id (:t_medication/id medication)]
            (log/debug "cancelling edit" {:path [:t_patient/patient_identifier patient-identifier :ui/editing-medication]})
            (swap! state #(cancel-edit-medication* % patient-identifier medication-id)))))

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
  [this {:t_medication/keys [id date_from date_to medication reason_for_stopping more_information] :as editing-medication
         :ui/keys           [choose-medication current-patient all-reasons-for-stopping-medication]}]
  {:ident         :t_medication/id
   :query         [:t_medication/id
                   :t_medication/date_from
                   :t_medication/date_to
                   :t_medication/more_information
                   :t_medication/reason_for_stopping
                   :t_medication/patient_fk
                   {:t_medication/medication [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                   {:ui/choose-medication (comp/get-query snomed/Autocomplete)}
                   fs/form-config-join
                   {[:ui/current-patient '_] [:t_patient/patient_identifier :t_patient/id]}
                   {[:ui/all-reasons-for-stopping-medication '_] (comp/get-query MedicationReasonForStopping)}]
   :initial-state (fn [params] {:ui/choose-medication (comp/get-initial-state snomed/Autocomplete {:id :choose-medication})})
   :form-fields   #{:t_medication/date_from :t_medication/date_to :t_medication/medication
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
                 {:id ::cancel-action :title "Cancel" :onClick do-cancel}]}
      {:onClose do-cancel}
      (ui/ui-simple-form {}
        (ui/ui-simple-form-title {:title (if temp? "Add medication" (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))})
        (when temp?
          (ui/ui-simple-form-item {:htmlFor "medication" :label "Medication"}
            (if (:info.snomed.Concept/id medication)
              (dom/div :.mt-2 (ui/ui-link-button {:onClick #(m/set-value! this :t_medication/medication nil)}
                                                 (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
              (snomed/ui-autocomplete choose-medication {:autoFocus true, :constraint "(<10363601000001109 MINUS <<10363901000001102)"
                                                         :onSave    #(m/set-value! this :t_medication/medication %)}))))
        (ui/ui-simple-form-item {:htmlFor "date-from" :label "Date from"}
          (ui/ui-local-date {:value date_from}
                            {:onChange #(m/set-value! this :t_medication/date_from %)}))
        (ui/ui-simple-form-item {:htmlFor "date-to" :label "Date to"}
          (ui/ui-local-date {:value date_to}
                            {:onChange #(do (m/set-value! this :t_medication/date_to %)
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
                                      date_to (disj :NOT_APPLICABLE))}
              {:onChange #(m/set-value! this :t_medication/reason_for_stopping %)})))
        (ui/ui-simple-form-item {:htmlFor "notes" :label "Notes"}
          (ui/ui-textarea {:id "notes" :value more_information}
                          {:onChange #(m/set-value! this :t_medication/more_information %)}))))))

(def ui-edit-medication (comp/factory EditMedication))

(def empty-medication
  {:t_medication/id               nil
   :t_medication/patient_fk       nil
   :t_medication/date_from        nil
   :t_medication/date_to          nil
   :t_medication/more_information ""})


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
                                           :content (ui/ui-menu-button {} {:onClick do-add} "Add medication")}]}})

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
