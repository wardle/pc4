(ns pc4.ui.results
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.algorithms.merge :as merge]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.ui.core :as ui]
            [pc4.ui.patients :as patients]
            [taoensso.timbre :as log])
  (:import (goog.date Date)))

(defn edit-result*
  [state patient-identifier class result]
  (let [ident [:t_result/id (:t_result/id result)]]
    (when-not class
      (log/warn "missing ::class for result"))
    (cond-> (-> state
                (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-result] ident))
            class                                           ;; if there is a defined component class, add form config
            (fs/add-form-config* class ident))))

(defmutation edit-result
  [{:keys [patient-identifier class result] :as params}]
  (action [{:keys [state]}]
          (println "edit result" params)
          (swap! state edit-result* patient-identifier class result)))

(defn add-result*
  [state patient-identifier class {:t_result/keys [id] :as result}]
  (let [ident [:t_result/id id]]
    (-> state
        (assoc-in [:t_result/id id] result)
        (update-in [:t_patient/patient_identifier patient-identifier :t_patient/results] conj ident)
        (edit-result* patient-identifier class result))))

(defmutation add-result
  [{:keys [patient-identifier class result] :as params}]
  (action [{:keys [state]}]
          (swap! state add-result* patient-identifier class result)))

(defn cancel-edit-result*
  "Cancels editing a result. For a newly created result, this deletes the result
  and removes it from the patient's list of results. For an existing result,
  the result is returned to a pristine state. "
  [state patient-identifier result]
  (let [result-id (:t_result/id result)
        ident [:t_result/id result-id]]
    (if (tempid/tempid? result-id)
      (-> state
          (update-in [:t_patient/patient_identifier patient-identifier] dissoc :ui/editing-result)
          (update :t_result/id dissoc result-id)
          (merge/remove-ident* ident [:t_patient/patient_identifier patient-identifier :t_patient/results]))
      (-> state
          (fs/pristine->entity* ident)
          (update-in [:t_patient/patient_identifier patient-identifier] dissoc :ui/editing-result)))))
(defmutation cancel-edit-result
  [{:keys [patient-identifier result] :as params}]
  (action [{:keys [state]}]
          (println "cancel edit" params)
          (swap! state cancel-edit-result* patient-identifier result)))

;;
;;

(def re-count-lesion
  #"(^\d+$)|(^~\d+$)|(^>\d+$)|(^\d+\+/\-\d+$)|(^\d+\-\d+$)")

(def re-change-lesions
  "Regular expression to match 'change in lesion count' syntax such as +2 or -2.
  A plus or minus sign is mandatory in order to be absolutely clear this is reflecting change."
  #"(?<change>^(\+|-)(\d+)$)")

(def mri-spine-types #{"CERVICAL_AND_THORACIC" "CERVICAL" "LUMBOSACRAL" "WHOLE_SPINE" "THORACIC"})
(s/def :t_result_mri_spine/date some?)
(s/def :t_result_mri_spine/type mri-spine-types)
(s/def :t_result_mri_brain/patient_fk int?)
(s/def :t_result_mri_brain/date some?)
(s/def :t_result_mri_brain/compare_to_result_mri_brain_fk (s/nilable int?))
(s/def :t_result_mri_brain/total_t2_hyperintense (s/nilable #(or (str/blank? %) (re-matches re-count-lesion %))))
(s/def :t_result_mri_brain/total_gad_enhancing_lesions (s/nilable #(or (str/blank? %) (re-matches re-count-lesion %))))
(s/def :t_result_mri_brain/change_t2_hyperintense (s/nilable #(or (str/blank? %) (re-matches re-change-lesions %))))
(s/def ::result-mri-brain (s/keys :req [:t_result_mri_brain/id
                                        :t_result_mri_brain/date
                                        :t_result_mri_brain/patient_fk]
                                  :opt [:t_result_mri_brain/compare_to_result_mri_brain_fk
                                        :t_result_mri_brain/total_t2_hyperintense
                                        :t_result_mri_brain/change_t2_hyperintense
                                        :t_result_mri_brain/total_gad_enhancing_lesions
                                        :t_result_mri_brain/multiple_sclerosis_summary]))

;;
;;

(defsc EditMriSpine
  [this {:t_result_mri_spine/keys [date report]
         scan-type                :t_result_mri_spine/type, :as result}
   {:keys [patient-identifier]}]
  {:ident       :t_result/id
   :form-fields #{:t_result_mri_spine/date :t_result_mri_spine/type :t_result_mri_spine/report}
   :query       [:t_result/id :t_result/date :t_result/summary
                 :t_result_mri_spine/date :t_result_mri_spine/type
                 :t_result_mri_spine/report fs/form-config-join]}
  (let [cancel-edit #(comp/transact! this [(cancel-edit-result {:patient-identifier patient-identifier :result result})])]
    (ui/ui-modal
      {:actions [{:id        ::save :role, :primary, :title "Save"
                  :disabled? (not date)
                  :onClick   #(comp/transact! this [(list 'pc4.rsdb/save-result {:patient-identifier patient-identifier
                                                                                 :result             result})])}
                 {:id      ::delete :title "Delete"
                  :onClick #(comp/transact! this [(list 'pc4.rsdb/delete-result {:patient-identifier patient-identifier
                                                                                 :result             result})])}
                 {:id ::cancel :title "Cancel" :onClick cancel-edit}]
       :onClose cancel-edit}
      (ui/ui-simple-form {}
        (ui/ui-simple-form-item {:label "Date of MRI scan of spine"}
          (ui/ui-local-date {:value    date
                             :onChange #(m/set-value! this :t_result_mri_spine/date %)
                             :max-date (Date.)
                             :onBlur   #(comp/transact! this [(fs/mark-complete! {:field :t_result_mri_spine/date})])})
          (when (fs/invalid-spec? result :t_result_mri_spine/date)
            (ui/box-error-message {:message "Invalid date for scan"})))
        (ui/ui-simple-form-item {:label "Type"}
          (ui/ui-select-popup-button
            {:value    scan-type
             :options  mri-spine-types
             :onChange #(m/set-value! this :t_result_mri_spine/type %)}))
        (ui/ui-simple-form-item {:label "Report"}
          (ui/ui-textarea {:value    report
                           :rows     4
                           :onChange #(m/set-value! this :t_result_mri_spine/report (or % ""))}))))))

(def ui-edit-mri-spine (comp/computed-factory EditMriSpine {:keyfn :t_result_mri_spine/id}))

(def lesion-count-help-text "Format as one of x, ~x, x+/-y, x-y or >x'")

(defsc EditMriBrain
  [this
   {:t_result_mri_brain/keys [date report multiple_sclerosis_summary
                              with_gadolinium total_gad_enhancing_lesions total_t2_hyperintense
                              change_t2_hyperintense compare_to_result_mri_brain_fk] :as result
    :ui/keys [t2-mode]}
   {:keys [patient-identifier all-results]}]
  {:ident       :t_result/id
   :form-fields #{:t_result_mri_brain/date
                  :t_result_mri_brain/report
                  :t_result_mri_brain/multiple_sclerosis_summary
                  :t_result_mri_brain/total_t2_hyperintense
                  :t_result_mri_brain/change_t2_hyperintense
                  :t_result_mri_brain/compare_to_result_mri_brain_fk
                  :t_result_mri_brain/with_gadolinium
                  :t_result_mri_brain/total_gad_enhancing_lesions}
   :query       [:t_result/id :t_result/date :t_result/summary
                 :t_result_mri_brain/date
                 :t_result_mri_brain/report
                 :t_result_mri_brain/multiple_sclerosis_summary
                 :t_result_mri_brain/total_t2_hyperintense
                 :t_result_mri_brain/change_t2_hyperintense
                 :t_result_mri_brain/with_gadolinium :t_result_mri_brain/total_gad_enhancing_lesions
                 :t_result_mri_brain/compare_to_result_mri_brain_fk
                 :ui/t2-mode :ui/current-patient
                 fs/form-config-join]}
  (let [result (dissoc result :all-results ::fs/config ::fs/fields ::fs/complete? ::fs/pristine-state)
        has-total-t2 (not (str/blank? total_t2_hyperintense))
        has-change-t2 (not (str/blank? change_t2_hyperintense))
        disable-change-t2-mode (or has-change-t2 has-total-t2)
        default-t2-mode (cond has-change-t2 :relative has-total-t2 :absolute :else :not-counted)
        valid (s/valid? ::result-mri-brain result)
        cancel-edit #(comp/transact! this [(cancel-edit-result {:patient-identifier patient-identifier :result result})])]
    (tap> {:valid-mri-brain (s/explain-data ::result-mri-brain result)})
    (ui/ui-modal
      {:actions [{:id        ::save :role, :primary, :title "Save"
                  :disabled? (not valid)
                  :onClick   #(comp/transact! this [(list 'pc4.rsdb/save-result {:patient-identifier patient-identifier
                                                                                 :result             result})])}
                 {:id      ::delete :title "Delete"
                  :onClick #(comp/transact! this [(list 'pc4.rsdb/delete-result {:patient-identifier patient-identifier
                                                                                 :result             result})])}
                 {:id ::cancel :title "Cancel" :onClick cancel-edit}]
       :onClose cancel-edit}
      (ui/ui-simple-form {}
        (ui/ui-simple-form-item {:label "Date of MRI scan of brain"}
          (ui/ui-local-date {:value    date
                             :onChange #(m/set-value! this :t_result_mri_brain/date %)
                             :onBlur   #(comp/transact! this [(fs/mark-complete! {:field :t_result_mri_brain/date})])})
          (when (fs/invalid-spec? result :t_result_mri_brain/date)
            (ui/box-error-message {:message "Invalid date for scan"})))
        (ui/ui-simple-form-item {:label "Report"}
          (ui/ui-textarea {:value    report
                           :rows     4
                           :onChange #(m/set-value! this :t_result_mri_brain/report %)}))
        (ui/ui-simple-form-item {:label     "Interpretation"
                                 :sub-label "in the context of multiple sclerosis"}
          (ui/ui-select-popup-button {:value    multiple_sclerosis_summary
                                      :options  ["TYPICAL" "ATYPICAL" "NON_SPECIFIC" "ABNORMAL_UNRELATED" "NORMAL"]
                                      :sort?    false
                                      :onChange #(m/set-value! this :t_result_mri_brain/multiple_sclerosis_summary %)}))
        (ui/ui-simple-form-item {:label "With gadolinium?"}
          (div :.pt-2
            (ui/ui-checkbox {:checked     with_gadolinium
                             :description "Was the scan performed with gadolinium?"
                             :onChange    #(do (when-not % (m/set-value! this :t_result_mri_brain/total_gad_enhancing_lesions nil))
                                               (m/set-value! this :t_result_mri_brain/with_gadolinium %))})))
        (when with_gadolinium
          (ui/ui-simple-form-item {:label "Number of enhancing lesions"}
            (ui/ui-textfield {:value     total_gad_enhancing_lesions
                              :disabled  (not with_gadolinium)
                              :help-text lesion-count-help-text
                              :onChange  #(m/set-value! this :t_result_mri_brain/total_gad_enhancing_lesions (when-not (str/blank? %) %))
                              :onBlur    #(comp/transact! this [(fs/mark-complete! {:field :t_result_mri_brain/total_gad_enhancing_lesions})])})
            (when (fs/invalid-spec? result :t_result_mri_brain/total_gad_enhancing_lesions)
              (ui/box-error-message {:message "Invalid enhancing lesion count"}))))
        (ui/ui-simple-form-item {:label     "T2 lesions"
                                 :sub-label "Record T2 lesions either as an absolute count, or change from a prior scan. You cannot change T2 mode if data recorded"}
          (ui/ui-select-popup-button {:value         t2-mode
                                      :options       [:not-counted :absolute :relative]
                                      :display-key   #(-> % name str/upper-case)
                                      :default-value default-t2-mode
                                      :disabled?     disable-change-t2-mode
                                      :sort?         false
                                      :onChange      #(do (m/set-value! this :ui/t2-mode %)
                                                          (when (= :absolute %)
                                                            (m/set-value! this :t_result_mri_brain/compare_to_result_mri_brain_fk nil)))}))
        (when (= :absolute t2-mode)
          (ui/ui-simple-form-item {:label "Total number of T2 hyperintense lesions"}
            (ui/ui-textfield {:value     total_t2_hyperintense
                              :disabled  (not= :absolute t2-mode)
                              :help-text lesion-count-help-text
                              :onChange  #(do (m/set-value! this :t_result_mri_brain/total_t2_hyperintense (when-not (str/blank? %) %))
                                              (m/set-value! this :t_result_mri_brain/change_t2_hyperintense nil))
                              :onBlur    #(comp/transact! this [(fs/mark-complete! {:field :t_result_mri_brain/total_t2_hyperintense})])})
            (when (fs/invalid-spec? result :t_result_mri_brain/total_t2_hyperintense)
              (ui/box-error-message {:message "Invalid T2 lesion count"}))))
        (when (= :relative t2-mode)
          (comp/fragment
            (ui/ui-simple-form-item {:label "Date of prior scan for comparison"}
              (let [all-prior-mri-scans (when date
                                          (->> all-results
                                               (filter #(= "ResultMriBrain" (:t_result_type/result_entity_name %)))
                                               (filter #(pos? (Date/compare date (:t_result/date %))))
                                               (reduce (fn [acc {:t_result/keys [id] :as result}]
                                                         (assoc acc id result)) {})))
                    selected-prior-mri-scan (get all-prior-mri-scans (:t_result_mri_brain/compare_to_result_mri_brain_fk result))]
                (if (seq all-prior-mri-scans)
                  (comp/fragment
                    (ui/ui-select-popup-button
                      {:value       selected-prior-mri-scan
                       :disabled?   (not= :relative t2-mode)
                       :options     (vals all-prior-mri-scans)
                       :display-key #(some-> % :t_result_mri_brain/date ui/format-date)
                       :id-key      :t_result_mri_brain/id
                       :sort?       true
                       :sort-fn     #(some-> :t_result_mri_brain/date .valueOf)
                       :onChange    #(m/set-value! this :t_result_mri_brain/compare_to_result_mri_brain_fk (:t_result_mri_brain/id %))})
                    (when selected-prior-mri-scan
                      (ui/ui-textarea {:label    "Scan report"
                                       :value    (:t_result_mri_brain/report selected-prior-mri-scan)
                                       :rows     3
                                       :disabled true})))
                  (dom/p :.pt-2.text-sm.text-gray-500.italic "There are no prior recorded scans to which to compare"))))
            (ui/ui-simple-form-item {:label     "Change in T2 hyperintense lesions"
                                     :sub-label (when (or (not= :relative t2-mode) (nil? compare_to_result_mri_brain_fk))
                                                  "You cannot record a change in lesion counts until you have chosen a scan to which to compare")}
              (ui/ui-textfield {:value     change_t2_hyperintense
                                :disabled  (or (not= :relative t2-mode) (nil? compare_to_result_mri_brain_fk))
                                :help-text "Use +x or -x to record the change in T2 hyperintense lesions compared to previous scan"
                                :onChange  #(do (m/set-value! this :t_result_mri_brain/change_t2_hyperintense (when-not (str/blank? %) %))
                                                (m/set-value! this :t_result_mri_brain/total_t2_hyperintense nil))
                                :onBlur    #(comp/transact! this [(fs/mark-complete! {:field :t_result_mri_brain/change_t2_hyperintense})])})
              (when (fs/invalid-spec? result :t_result_mri_brain/change_t2_hyperintense)
                (ui/box-error-message {:message "Invalid change in T2 lesion count"})))))))))

(def ui-edit-mri-brain (comp/computed-factory EditMriBrain {:keyfn :t_result_mri_brain/id}))

(def supported-results
  [{:t_result_type/name               "MRI brain"
    :t_result_type/result_entity_name "ResultMriBrain"
    ::class                           EditMriBrain
    ::editor                          ui-edit-mri-brain
    ::table                           :t_result_mri_brain
    ::spec                            ::result-mri-brain
    ::initial-data                    {:t_result_mri_brain/with_gadolinium false
                                       :t_result_mri_brain/report          ""}}
   {:t_result_type/name               "MRI spine"
    :t_result_type/result_entity_name "ResultMriSpine"
    ::class                           EditMriSpine
    ::editor                          ui-edit-mri-spine
    ::table                           :t_result_mri_spine
    ::spec                            ::result-mri-spine
    ::initial-data                    {:t_result_mri_spine/type   "CERVICAL_AND_THORACIC"
                                       :t_result_mri_spine/report ""}}
   {:t_result_type/name               "CSF OCB"
    :t_result_type/result_entity_name "ResultCsfOcb"
    ;    ::editor                          edit-result-csf-ocb
    ::spec                            ::result-csf-ocb}
   {:t_result_type/name               "JC virus"
    :t_result_type/result_entity_name "ResultJCVirus"
    ;    ::editor                          edit-result-jc-virus
    ::spec                            ::result-jc-virus}
   {:t_result_type/name               "Renal profile"
    :t_result_type/result_entity_name "ResultRenalProfile"
    ;    ::editor                          (make-edit-result "Renal profile" :t_result_renal)
    ::spec                            ::result-renal}
   {:t_result_type/name               "Full blood count"
    :t_result_type/result_entity_name "ResultFullBloodCount"
    ;    ::editor                          (make-edit-result "Full blood count" :t_result_full_blood_count)
    ::spec                            ::result-full-blood-count}
   {:t_result_type/name               "Electrocardiogram (ECG)"
    :t_result_type/result_entity_name "ResultECG"
    ;    ::editor                          (make-edit-result "Electrocardiogram (ECG)" :t_result_ecg)
    ::spec                            ::result-ecg}
   {:t_result_type/name               "Urinalysis"
    :t_result_type/result_entity_name "ResultUrinalysis"
    ;    ::editor                          (make-edit-result "Urinalysis" :t_result_urinalysis)
    ::spec                            ::result-urinalysis}
   {:t_result_type/name               "Liver function tests"
    :t_result_type/result_entity_name "ResultLiverFunction"
    ;    ::editor                          (make-edit-result "Liver function tests" :t_result_liver_function)
    ::spec                            ::result-liver-function}
   {:t_result_type/name               "Thyroid function tests"
    :t_result_type/result_entity_name "ResultThyroidFunction"
    ;    ::editor                          edit-result-thyroid-function
    ::spec                            ::result-thyroid-function}])

(def result-type-by-entity-name
  (reduce (fn [acc {entity-name :t_result_type/result_entity_name :as result-type}]
            (assoc acc entity-name result-type))
          {} supported-results))

(defn create-result
  [{:t_result_type/keys [result_entity_name] ::keys [table initial-data]} patient-pk]
  (let [result-id (tempid/tempid)
        table-name (name table)
        kw-patient-fk (keyword table-name "patient_fk")
        kw-result-id (keyword table-name "id")]
    (assoc initial-data
      kw-patient-fk patient-pk
      :t_result_type/result_entity_name result_entity_name
      :t_result/id result-id
      kw-result-id result-id)))

(defsc ResultListItem
  [this {:t_result/keys [id date summary]
         entity-name    :t_result_type/result_entity_name
         result-name    :t_result_type/name
         result-desc    :t_result_type/description} computed-props]
  {:ident :t_result/id
   :query [:t_result/id :t_result/date :t_result/summary
           :t_result_type/result_entity_name :t_result_type/id
           :t_result_type/name :t_result_type/description '*
           {[:ui/current-patient '_] [:t_patient/patient_identifier]}
           fs/form-config-join]}
  (ui/ui-table-row computed-props
    (ui/ui-table-cell {} (ui/format-date date))
    (ui/ui-table-cell {} result-name)
    (ui/ui-table-cell {} (div :.overflow-hidden (ui/truncate summary 120)))))

(def ui-result-list-item (comp/computed-factory ResultListItem {:keyfn :t_result/id}))

(defsc PatientResults
  [this {:t_patient/keys [patient_identifier results] :as patient
         :>/keys         [banner] :ui/keys [editing-result choose-investigation]}]
  {:ident         :t_patient/patient_identifier
   :route-segment ["pt" :t_patient/patient_identifier "results"]
   :query         [:t_patient/patient_identifier
                   :t_patient/id
                   {:ui/editing-result (comp/get-query ResultListItem)}
                   :ui/choose-investigation
                   {:>/banner (comp/get-query patients/PatientBanner)}
                   {:t_patient/results (comp/get-query ResultListItem)}]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier]}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientResults
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))}
  (let [do-add-result (fn [_]
                        (comp/transact! this [(add-result {:patient-identifier patient_identifier
                                                           :class              (::class choose-investigation)
                                                           :result             (create-result choose-investigation (:t_patient/id patient))})]))]

    (when patient_identifier
      (patients/ui-layout
        {:banner (patients/ui-patient-banner banner)
         :menu   (patients/ui-patient-menu
                   patient
                   {:selected-id :results
                    :sub-menu    {:items [{:id      ::select-result
                                           :content (div :.border
                                                      (ui/ui-select-popup-button
                                                        {:options     (filter ::editor supported-results)
                                                         :value       choose-investigation
                                                         :display-key :t_result_type/name
                                                         :id-key      :t_result_type/result_entity_name
                                                         :onChange    #(m/set-value! this :ui/choose-investigation %)}))}
                                          (when (::editor choose-investigation) ;;only show 'add' button when we support
                                            {:id      ::add-result
                                             :content "Add investigation"
                                             :onClick do-add-result})]}})}

        (when editing-result
          (let [{::keys [editor]} (result-type-by-entity-name (:t_result_type/result_entity_name editing-result))
                cancel-edit #(comp/transact! this [(cancel-edit-result {:patient-identifier patient_identifier
                                                                        :result             editing-result})])]
            (if editor
              (editor editing-result {:patient-identifier patient_identifier
                                      :all-results        results})
              (ui/ui-modal {:actions [{:id ::close, :title "Close", :onClick cancel-edit}] :onClose cancel-edit}
                (div "It is not currently possible to edit this result.")))))
        (ui/ui-table {}
          (ui/ui-table-head {}
            (ui/ui-table-row {}
              (for [heading ["Date/time" "Investigation" "Result"]]
                (ui/ui-table-heading {:react-key heading} heading))))
          (ui/ui-table-body {}
            (for [result (sort-by #(some-> % :t_result/date .valueOf -) results)]
              (ui-result-list-item result
                                   {:onClick #(comp/transact! this [(edit-result {:patient-identifier patient_identifier
                                                                                  :class              (::class (result-type-by-entity-name (:t_result_type/result_entity_name result)))
                                                                                  :result             result})])
                                    :classes ["cursor-pointer" "hover:bg-gray-200"]}))))))))
