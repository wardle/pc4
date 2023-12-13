(ns pc4.ui.diagnoses
  (:require [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.ui.core :as ui]
            [pc4.ui.patients :as patients]
            [taoensso.timbre :as log]))

(defsc EditDiagnosis
  [this {:t_diagnosis/keys [id date_diagnosis date_onset date_to status diagnosis]}]
  {:ident :t_diagnosis/id
   :query [:t_diagnosis/id
           :t_diagnosis/date_onset
           :t_diagnosis/date_diagnosis
           :t_diagnosis/date_to
           {:t_diagnosis/diagnosis [:info.snomed.Concept/id
                                    {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
           :t_diagnosis/status
           fs/form-config-join]
   :form-fields #{:t_diagnosis/date_onset :t_diagnosis/date_to :t_diagnosis/date_diagnosis
                  :t_diagnosis/diagnosis :t_diagnosis/status}}
  (ui/ui-simple-form {}
    (ui/ui-simple-form-title {:title (if id "Edit diagnosis" "Add diagnosis")})
    (ui/ui-simple-form-item {:label "Diagnosis"}
      (div :.pt-2
        (if id                                              ;; if we already have a saved diagnosis, don't allow user to change
          (dom/h3 :.text-lg.font-medium.leading-6.text-gray-900 (get-in diagnosis [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
          (div "Edit control"))))                           ;; insert SNOMED control here)))
    (ui/ui-simple-form-item {:label "Date of onset"}
      (ui/ui-local-date {:name "date-onset" :value date_onset}
                        {:onChange #(m/set-value!! this :t_diagnosis/date_onset %)}))
    (ui/ui-simple-form-item {:label "Date of diagnosis"}
      (ui/ui-local-date {:name "date-diagnosis" :value date_diagnosis}
                        {:onChange #(m/set-value!! this :t_diagnosis/date_diagnosis %)}))
    (ui/ui-simple-form-item {:label "Date to"}
      (ui/ui-local-date {:name "date-to" :value date_to}
                        {:onChange #(m/set-value!! this :t_diagnosis/date_to %)}))
    (ui/ui-simple-form-item {:label "Status"}
      (log/info "date to" date_to)
      (ui/ui-select-popup-button
        {:name            "status", :value status, :update-options? false
         :options         (if date_to ["INACTIVE_REVISED" "INACTIVE_RESOLVED" "INACTIVE_IN_ERROR"]
                                      ["ACTIVE"])}
        {:onChange #(com.fulcrologic.fulcro.mutations/set-value!! this :t_diagnosis/status %)}))
    (when id
      (dom/p :.text-gray-500.pt-8 "To delete a diagnosis, record a 'to' date and update the status as appropriate."))))

(def ui-edit-diagnosis (comp/factory EditDiagnosis))


(defsc DiagnosisListItem
  [this {:t_diagnosis/keys [id date_onset date_diagnosis date_to status diagnosis]} {:keys [onClick] :as computed-props}]
  {:ident :t_diagnosis/id
   :query [:t_diagnosis/id :t_diagnosis/date_diagnosis :t_diagnosis/date_onset :t_diagnosis/date_to :t_diagnosis/status
           {:t_diagnosis/diagnosis [:info.snomed.Concept/id
                                    {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}
  (ui/ui-table-row computed-props
    (ui/ui-table-cell {} (get-in diagnosis [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
    (ui/ui-table-cell {} (ui/format-date date_onset))
    (ui/ui-table-cell {} (ui/format-date date_diagnosis))
    (ui/ui-table-cell {} (ui/format-date date_to))
    (ui/ui-table-cell {} (str status))))

(def ui-diagnosis-list-item (comp/computed-factory DiagnosisListItem {:keyfn :t_diagnosis/id}))

(defn diagnoses-table
  [{:keys [title diagnoses onClick]}]
  (dom/div
    (ui/ui-title {:title title})
    (ui/ui-table {}
      (ui/ui-table-head {}
        (ui/ui-table-row {}
          (map #(ui/ui-table-heading {:react-key %} %) ["Diagnosis" "Date onset" "Date diagnosis" "Date to" "Status"])))
      (ui/ui-table-body {}
        (->> diagnoses
             (sort-by #(get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
             (map #(ui-diagnosis-list-item % (when onClick {:onClick (fn [] (onClick %))
                                                            :classes ["cursor-pointer" "hover:bg-gray-200"]}))))))))


(defmutation cancel-edit-diagnosis
  [{:t_diagnosis/keys [id]}]
  (action [{:keys [ref state]}]
          (swap! state (fn [s]
                         (-> s
                             (fs/pristine->entity* [:t_diagnosis/id id])
                             (assoc-in (conj ref :ui/editing-diagnosis) {}))))))

(defmutation edit-diagnosis
  [{:keys [diagnosis]}]
  (action
    [{:keys [app ref state]}]
    (log/debug "editing diagnosis" {:diagnosis diagnosis
                                    :path      (conj ref :ui/editing-diagnosis)})
    (if diagnosis
      (swap! state (fn [s]
                     (-> s
                         (fs/add-form-config* EditDiagnosis [:t_diagnosis/id (:t_diagnosis/id diagnosis)])
                         (assoc-in (conj ref :ui/editing-diagnosis) [:t_diagnosis/id (:t_diagnosis/id diagnosis)]))))
      (swap! state assoc-in (conj ref :ui/editing-diagnosis) {}))))

(defsc PatientDiagnoses
  [this {:t_patient/keys [patient_identifier diagnoses] :as patient
         :>/keys         [banner], :ui/keys [editing-diagnosis]}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/patient_identifier
                   {:>/banner (comp/get-query patients/PatientBanner)}
                   {:t_patient/diagnoses (comp/get-query DiagnosisListItem)}
                   {:ui/editing-diagnosis (comp/get-query pc4.ui.diagnoses/EditDiagnosis)}]
   :route-segment ["pt" :t_patient/patient_identifier "diagnoses"]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientDiagnoses
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))
   :pre-merge     (fn [{:keys [current-normalized data-tree]}]
                    (merge {:ui/editing-diagnosis {}} current-normalized data-tree))}
  (when patient_identifier
    (patients/ui-layout
      {:banner (patients/ui-patient-banner banner)
       :menu   (patients/ui-pseudonymous-menu
                 patient
                 {:selected-id :diagnoses
                  :sub-menu    {:items [{:id      :add-diagnosis
                                         :content (ui/ui-menu-button {} {:onClick #(println "Add diagnosis")} "Add diagnosis")}]}})

       :content
       (let [active-diagnoses (filter #(= "ACTIVE" (:t_diagnosis/status %)) diagnoses)
             inactive-diagnoses (filter #(not= "ACTIVE" (:t_diagnosis/status %)) diagnoses)]
         (comp/fragment
           (when (seq editing-diagnosis)
             (ui/ui-modal {:actions [{:id ::save-diagnosis :title "Save" :role :primary}
                                     {:id ::cancel-diagnosis :title "Cancel" :onClick #(comp/transact! this [(cancel-edit-diagnosis editing-diagnosis)])}]}
               {:onClose #(comp/transact! this [(cancel-edit-diagnosis editing-diagnosis)])}
               (pc4.ui.diagnoses/ui-edit-diagnosis editing-diagnosis)))
           (diagnoses-table {:title     "Active diagnoses"
                             :diagnoses active-diagnoses
                             :onClick   #(comp/transact! this [(edit-diagnosis {:diagnosis %})])})
           (when (seq inactive-diagnoses)
             (diagnoses-table {:title     "Inactive diagnoses"
                               :diagnoses inactive-diagnoses
                               :onClick   #(comp/transact! this [(edit-diagnosis {:diagnosis %})])}))))})))









