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

(defmutation edit-medication
  [{:t_medication/keys [id] :as params}]
  (action
    [{:keys [app state]}]
    (tap> {:merging-edit-medication params})
    (swap! state update-in [:component/id :edit-medication] merge params)
    (when-not (get-in @state [:component/id :edit-medication :com.eldrix.rsdb/all-medication-reasons-for-stopping])
      (df/load! app :com.eldrix.rsdb/all-medication-reasons-for-stopping EditMedication
                {:target [:component/id :edit-medication :com.eldrix.rsdb/all-medication-reasons-for-stopping]}))))

(defmutation cancel-medication-edit
  [params]
  (action
    [{:keys [state]}]
    (swap! state update-in [:component/id :edit-medication]
           dissoc :t_medication/date_to :t_medication/more_information :t_medication/medication :t_medication/date_from :t_medication/patient_fk)))

(defsc MedicationReasonForStopping
  [this {:t_medication_reason_for_stopping/keys [id name]}]
  {:ident :t_medication_reason_for_stopping/id
   :query [:t_medication_reason_for_stopping/id
           :t_medication_reason_for_stopping/name]})


(s/def :t_medication/date_from (s/nilable #(instance? goog.date.Date %)))
(s/def :t_medication/date_to (s/nilable #(instance? goog.date.Date %)))
(s/def :t_medication/medication (s/keys :req [:info.snomed.Concept/id]))
(s/def :t_medication/more_information (s/nilable string?))
(s/def ::save-medication
  (s/keys :req [:t_medication/date_from :t_medication/date_to
                :t_medication/medication :t_medication/more_information]))

(defsc MedicationEdit
  [this {:t_medication/keys [id date_from date_to medication more_information]
         :ui/keys           [choose-medication] :as params}
   {:keys [onClose onSave onDelete]}]
  {:ident         (fn [] [:component/id :edit-medication])  ;; singleton component
   :query         [:t_medication/id :t_medication/date_from :t_medication/date_to
                   :t_medication/more_information :t_medication/patient_fk
                   {:t_medication/medication [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                   {:ui/choose-medication (comp/get-query snomed/Autocomplete)}
                   {:com.eldrix.rsdb/all-medication-reasons-for-stopping (comp/get-query MedicationReasonForStopping)}]
   :initial-state (fn [params] {:ui/choose-medication (comp/get-initial-state snomed/Autocomplete {:id :choose-medication})})}
  (tap> {:component  :edit-medication
         :params     params
         :valid?     (s/valid? ::save-medication params)
         :validation (s/explain-data ::save-medication params)})
  (ui/ui-modal
    {:actions [(when onSave {:id        ::save-action :title "Save" :role :primary
                             :disabled? (not (s/valid? ::save-medication params))
                             :onClick   onSave})
               (when onDelete {:id ::delete-action :title "Delete" :onClick onDelete :disabled? (not id)})
               (when onClose {:id ::cancel-action :title "Cancel" :onClick onClose})]}
    {:onClose onClose}
    (ui/ui-simple-form {}
      (ui/ui-simple-form-title {:title (if id "Edit medication" "Add medication")})
      (ui/ui-simple-form-item {:htmlFor "medication" :label "Medication"}
        (tap> {:choose-medication choose-medication})
        (if id
          (dom/div :.mt-2 (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
          (if (:info.snomed.Concept/id medication)
            (dom/div :.mt-2 (ui/ui-link-button {:onClick #(m/set-value! this :t_medication/medication nil)}
                                               (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
            (snomed/ui-autocomplete choose-medication {:autoFocus true, :constraint "<10363601000001109"
                                                       :onSave    #(m/set-value! this :t_medication/medication %)}))))
      (ui/ui-simple-form-item {:htmlFor "date-from" :label "Date from"}
        (ui/ui-local-date {:value date_from}
                          {:onChange #(m/set-value! this :t_medication/date_from %)}))
      (ui/ui-simple-form-item {:htmlFor "date-to" :label "Date to"}
        (ui/ui-local-date {:value date_to}
                          {:onChange #(m/set-value! this :t_medication/date_to %)}))
      (ui/ui-simple-form-item {:htmlFor "notes" :label "Notes"}
        (ui/ui-textarea {:id "notes" :value more_information}
                        {:onChange #(m/set-value! this :t_medication/more_information %)})))))

(def ui-medication-edit (comp/computed-factory MedicationEdit))

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
    (ui/ui-table-cell {} (when-not (= :NOT_APPLICABLE reason_for_stopping) (name reason_for_stopping)))))

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
                   {[:ui/editing-medication '_] (comp/get-query MedicationEdit)}]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientMedications
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))
   :pre-merge     (fn [{:keys [current-normalized data-tree]}]
                    (merge {:ui/editing-medication {}} current-normalized data-tree))}
  (tap> {:patient-medication medications})
  (let [do-add-medication #(println "Add medication")]
    (when patient_identifier
      (patients/ui-layout
        {:banner (patients/ui-patient-banner banner)
         :menu   (patients/ui-pseudonymous-menu
                   patient
                   {:selected-id :medications
                    :sub-menu    {:items [{:id      :add-medication
                                           :content (ui/ui-menu-button {}
                                                                       {:onClick do-add-medication} "Add medication")}]}})

         :content
         (comp/fragment
           (when (:t_medication/patient_fk editing-medication)
             (ui-medication-edit editing-medication
                                 {:onSave   #(let [m (select-keys editing-medication [:t_medication/patient_fk :t_medication/medication :t_medication/date_from :t_medication/date_to :t_medication/more_information])
                                                   m' (if-let [med-id (:t_medication/id editing-medication)] (assoc m :t_medication/id med-id) m)]
                                               (println "Saving medication" m')
                                               (comp/transact! this [(pc4.rsdb/save-medication m')
                                                                     (cancel-medication-edit nil)])
                                               (df/load-field! this :t_patient/medications {}))
                                  :onDelete #(comp/transact! this [(pc4.rsdb/delete-medication editing-medication)
                                                                   (cancel-medication-edit nil)])
                                  :onClose  #(comp/transact! this [(cancel-medication-edit nil)])}))
           (ui/ui-table {}
             (ui/ui-table-head {}
               (ui/ui-table-row {}
                 (map #(ui/ui-table-heading {:react-key %} %) ["Treatment" "Date from" "Date to" "Reason for stopping" ""])))
             (ui/ui-table-body {}
               (->> medications
                    (remove #(#{:RECORDED_IN_ERROR} (:t_medication/reason_for_stopping %)))
                    (sort-by (juxt medication-by-date-from
                                   #(get-in % [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))


                    (map #(ui-medication-list-item % {:onClick (fn [med] (println "edit medication" med))
                                                      :classes ["cursor-pointer" "hover:bg-gray-200"]}))))))}))))

(def ui-patient-medications (comp/factory PatientMedications))
