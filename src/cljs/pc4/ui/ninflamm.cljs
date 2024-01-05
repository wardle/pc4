(ns pc4.ui.ninflamm
  (:require [clojure.string :as str]
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
            [taoensso.timbre :as log]))

(declare EditMsEvent)
(declare SummaryMultipleSclerosis)

(defn cancel-edit-ms-event*
  [state patient-identifier {:t_ms_event/keys [id summary_multiple_sclerosis_fk] :as ms-event}]
  (log/info "cancelling" {:patient-identifier patient-identifier
                          :sms-id             summary_multiple_sclerosis_fk
                          :ms-event           ms-event})
  (cond-> (-> state
              (fs/pristine->entity* [:t_ms_event/id id])    ;; restore to pristine state
              (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-ms-event] {}))
          (tempid/tempid? id)
          (->
            (update-in [:t_ms_event/id] dissoc id)
            (merge/remove-ident* [:t_ms_event/id id] [:t_summary_multiple_sclerosis/id summary_multiple_sclerosis_fk :t_summary_multiple_sclerosis/events]))))

(defmutation cancel-edit-ms-event
  [{:keys [patient-identifier ms-event]}]
  (action [{:keys [state]}]
          (swap! state cancel-edit-ms-event* patient-identifier ms-event)))

(defn edit-ms-event*
  [state patient-identifier {:t_ms_event/keys [id]}]
  (-> state
      (fs/add-form-config* EditMsEvent [:t_ms_event/id id])
      (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-ms-event] [:t_ms_event/id id])))

(defmutation edit-ms-event
  [{:keys [patient-identifier ms-event]}]
  (action [{:keys [state]}]
          (swap! state edit-ms-event* patient-identifier ms-event)))

(defmutation refresh-summary
  [{:keys [summary-multiple-sclerosis-id]}]
  (action [{:keys [app]}]
          (df/load! app [:t_summary_multiple_sclerosis/id summary-multiple-sclerosis-id] SummaryMultipleSclerosis)))

(defn add-ms-event*
  [state patient-identifier summary-multiple-sclerosis-id {:t_ms_event/keys [id] :as ms-event}]
  (-> state
      (assoc-in [:t_ms_event/id id] ms-event)
      (update-in [:t_summary_multiple_sclerosis/id summary-multiple-sclerosis-id :t_summary_multiple_sclerosis/events]
                 (fnil conj []) [:t_ms_event/id id])
      (edit-ms-event* patient-identifier ms-event)))

(defmutation add-ms-event
  [{:keys [patient-identifier ms-event] :as props}]
  (action [{:keys [state]}]
          (println "adding ms event" props)
          (swap! state add-ms-event* patient-identifier (:t_ms_event/summary_multiple_sclerosis_fk ms-event) ms-event)))

(defsc MsEventType [this {:t_ms_event_type/keys [id name abbreviation]}]
  {:ident :t_ms_event_type/id
   :query [:t_ms_event_type/id
           :t_ms_event_type/name
           :t_ms_event_type/abbreviation]})


(defmutation load-ms-event-types
  "Lazily loads all MS event types from the server, placing results at
  top level under `:ui/all-ms-event-types`."
  [_]
  (action [{:keys [app state]}]
          (when (empty? (:ui/all-ms-event-types @state))
            (log/debug "Loading MS event types")
            (df/load! app :com.eldrix.rsdb/all-ms-event-types MsEventType
                      {:target [:ui/all-ms-event-types]}))))

(defsc MsDiagnosis [this props]
  {:ident :t_ms_diagnosis/id
   :query [:t_ms_diagnosis/id :t_ms_diagnosis/name]})

(defmutation load-ms-diagnoses
  [_]
  (action [{:keys [app state]}]
          (when (empty? (:ui/all-ms-diagnoses @state))
            (log/debug "Loading MS diagnostic categories")
            (df/load! app :com.eldrix.rsdb/all-ms-diagnoses MsDiagnosis
                      {:target [:ui/all-ms-diagnoses]}))))

;; TODO: this should come from the server?
(def impact-choices ["UNKNOWN" "NON_DISABLING" "DISABLING" "SEVERE"])

(defn ms-event-site-to-string [k]
  (str/capitalize (str/join " " (rest (str/split (name k) #"_")))))

(def all-ms-event-sites
  [:t_ms_event/site_unknown :t_ms_event/site_arm_motor :t_ms_event/site_leg_motor
   :t_ms_event/site_limb_sensory :t_ms_event/site_sphincter :t_ms_event/site_sexual
   :t_ms_event/site_face_motor :t_ms_event/site_face_sensory
   :t_ms_event/site_diplopia :t_ms_event/site_vestibular :t_ms_event/site_bulbar
   :t_ms_event/site_ataxia :t_ms_event/site_optic_nerve :t_ms_event/site_psychiatric
   :t_ms_event/site_other :t_ms_event/site_cognitive])

(defn change-event-site*
  [event k v]
  (cond
    (and (= k :t_ms_event/site_unknown) v)                  ;; if site_unknown is checked, clear all other sites
    (apply assoc (assoc event :t_ms_event/site_unknown true) (reduce #(conj %1 %2 false) [] (next all-ms-event-sites)))
    (= k :t_ms_event/site_unknown)                          ;; if site_unknown is unchecked, just uncheck it
    (assoc event k v)
    (false? v)                                              ;; if a site is unchecked, just uncheck it
    (assoc event k v)
    :else                                                   ;; if another site is checked, uncheck site_unknown
    (assoc event k v :t_ms_event/site_unknown false)))

(defmutation toggle-event-site [{:keys [ms-event-id k v]}]
  (action [{:keys [state]}]
          (swap! state (fn [s]
                         (update-in s [:t_ms_event/id ms-event-id] change-event-site* k v)))))

(defsc EditMsEvent [this {:t_ms_event/keys [id type date impact notes summary_multiple_sclerosis_fk] :as ms-event
                          :ui/keys         [all-ms-event-types current-patient]}]
  {:ident       :t_ms_event/id
   :query       [:t_ms_event/id :t_ms_event/summary_multiple_sclerosis_fk
                 :t_ms_event/date :t_ms_event/impact :t_ms_event/is_relapse :t_ms_event/is_progressive
                 :t_ms_event/site_arm_motor :t_ms_event/site_ataxia :t_ms_event/site_bulbar
                 :t_ms_event/site_cognitive :t_ms_event/site_diplopia :t_ms_event/site_face_motor
                 :t_ms_event/site_face_sensory :t_ms_event/site_leg_motor :t_ms_event/site_limb_sensory
                 :t_ms_event/site_optic_nerve :t_ms_event/site_other :t_ms_event/site_psychiatric
                 :t_ms_event/site_sexual :t_ms_event/site_sphincter :t_ms_event/site_unknown
                 :t_ms_event/site_vestibular :t_ms_event/notes
                 {:t_ms_event/type (comp/get-query MsEventType)}
                 {[:ui/all-ms-event-types '_] (comp/get-query MsEventType)}
                 {[:ui/current-patient '_] [:t_patient/patient_identifier :t_patient/id :t_patient/date_birth]}
                 fs/form-config-join]
   :form-fields (into #{:t_ms_event/id :t_ms_event/date :t_ms_event/impact
                        :t_ms_event/notes :t_ms_event/type} all-ms-event-sites)}
  (let [patient-identifier (:t_patient/patient_identifier current-patient)
        do-delete #(comp/transact! this [(list 'pc4.rsdb/delete-ms-event ms-event)
                                         (refresh-summary {:summary-multiple-sclerosis-id summary_multiple_sclerosis_fk})])
        do-save #(comp/transact! this [(list 'pc4.rsdb/save-ms-event
                                             (-> ms-event
                                                 (assoc :t_patient/patient_identifier patient-identifier)
                                                 (dissoc :ui/all-ms-event-types :ui/current-patient ::fs/config)))
                                       (refresh-summary {:summary-multiple-sclerosis-id summary_multiple_sclerosis_fk})])
        do-cancel #(comp/transact! this [(cancel-edit-ms-event {:patient-identifier patient-identifier
                                                                :ms-event           ms-event})])]
    (ui/ui-modal
      {:actions [{:id ::save :title "Save", :role :primary, :disabled? (not date) :onClick do-save}
                 {:id ::delete :title "Delete" :onClick do-delete}
                 {:id ::cancel :title "Cancel" :onClick do-cancel}]
       :onClose do-cancel}
      (ui/ui-simple-form {}
        (ui/ui-simple-form-title {:title (if (tempid/tempid? id) "Add relapse / disease event" "Edit relapse / disease event")})
        (ui/ui-simple-form-item {:label "Date"}
          (ui/ui-local-date {:value    date
                             :min-date (:t_patient/date_birth current-patient)
                             :max-date (goog.date.Date.)
                             :onChange #(m/set-value! this :t_ms_event/date %)}))
        (ui/ui-simple-form-item {:label "Type"}
          (tap> {:edit-ms-event {:all-ms-event-types all-ms-event-types
                                 :type               type}})
          (ui/ui-select-popup-button
            {:value         type
             :options       all-ms-event-types
             :sort?         true
             :default-value (first (filter #(= "UK" (:t_ms_event_type/abbreviation %)) all-ms-event-types))
             :display-key   (fn [{:t_ms_event_type/keys [abbreviation name]}]
                              (str abbreviation ": " name))
             :sort-fn       :t_ms_event_type/id
             :id-key        :t_ms_event_type/id
             :onChange      #(m/set-value! this :t_ms_event/type %)}))
        (ui/ui-simple-form-item {:label "Impact"}
          (ui/ui-select-popup-button
            {:value         impact
             :options       impact-choices
             :sort?         false
             :default-value "UNKNOWN"
             :onChange      #(m/set-value! this :t_ms_event/impact %)}))
        (ui/ui-simple-form-item {:label "Sites"}
          (div :.columns-1.sm:columns-2.md:columns-3.lg:columns-4
            (ui/ui-multiple-checkboxes
              {:value        ms-event
               :display-key  ms-event-site-to-string
               :keys         all-ms-event-sites
               :onItemChange (fn [k v] (comp/transact! this [(toggle-event-site {:ms-event-id id :k k :v v})]))})))
        (ui/ui-simple-form-item {:label "Notes"}
          (ui/ui-textarea {:value    notes
                           :onChange #(m/set-value! this :t_ms_event/notes %)}))))))

(def ui-edit-ms-event (comp/factory EditMsEvent))



(def relapse-headings
  [{:s "Date"}
   {:s "Type"}
   {:s "Impact"}
   {:s "UK" :title "Unknown"}
   {:s "UE" :title "Upper extremity (arm motor)"}
   {:s "LE" :title "Lower extremity (leg motor)"}
   {:s "SS" :title "Limb sensory"}
   {:s "SP" :title "Sphincter"}
   {:s "SX" :title "Sexual"}
   {:s "FM" :title "Face motor"}
   {:s "FS" :title "Face sensory"}
   {:s "OM" :title "Oculomotor (diplopia)"}
   {:s "VE" :title "Vestibular"}
   {:s "BB" :title "Bulbar"}
   {:s "CB" :title "Cerebellar (ataxia)"}
   {:s "ON" :title "Optic nerve"}
   {:s "PS" :title "Psychiatric"}
   {:s "OT" :title "Other"}
   {:s "MT" :title "Cognitive"}])

(defsc MsEventListItem
  [this {:t_ms_event/keys [id date type is_relapse is_progressive impact site_unknown site_arm_motor site_leg_motor site_limb_sensory
                           site_sphincter site_sexual site_face_motor site_face_sensory site_diplopia
                           site_vestibular site_bulbar site_ataxia site_optic_nerve site_psychiatric
                           site_other site_cognitive]}
   {:keys [onClick]}]
  {:ident :t_ms_event/id
   :query [:t_ms_event/id :t_ms_event/summary_multiple_sclerosis_fk
           :t_ms_event/date :t_ms_event/impact :t_ms_event/is_relapse :t_ms_event/is_progressive
           :t_ms_event/site_arm_motor :t_ms_event/site_ataxia :t_ms_event/site_bulbar
           :t_ms_event/site_cognitive :t_ms_event/site_diplopia :t_ms_event/site_face_motor
           :t_ms_event/site_face_sensory :t_ms_event/site_leg_motor :t_ms_event/site_limb_sensory
           :t_ms_event/site_optic_nerve :t_ms_event/site_other :t_ms_event/site_psychiatric
           :t_ms_event/site_sexual :t_ms_event/site_sphincter :t_ms_event/site_unknown
           :t_ms_event/site_vestibular
           {:t_ms_event/type (comp/get-query MsEventType)}]}
  (ui/ui-table-row
    {:key     id
     :onClick onClick
     :classes (cond-> ["cursor-pointer" "hover:bg-gray-200"]
                      (not is_progressive) (conj "bg-red-50/50")
                      (not is_relapse) (conj "border-t" "border-dashed" "border-gray-400") ;; mark start of progressive disease
                      is_progressive (conj "italic" "bg-blue-50/25"))}
    (ui/ui-table-cell {:classes ["whitespace-nowrap"]} (ui/format-date date))
    (ui/ui-table-cell {} (:t_ms_event_type/abbreviation type))
    (ui/ui-table-cell {} impact)
    (ui/ui-table-cell {} (when site_unknown "UK"))
    (ui/ui-table-cell {} (when site_arm_motor "UE"))
    (ui/ui-table-cell {} (when site_leg_motor "LE"))
    (ui/ui-table-cell {} (when site_limb_sensory "SS"))
    (ui/ui-table-cell {} (when site_sphincter "SP"))
    (ui/ui-table-cell {} (when site_sexual "SX"))
    (ui/ui-table-cell {} (when site_face_motor "FM"))
    (ui/ui-table-cell {} (when site_face_sensory "FS"))
    (ui/ui-table-cell {} (when site_diplopia "OM"))
    (ui/ui-table-cell {} (when site_vestibular "VE"))
    (ui/ui-table-cell {} (when site_bulbar "BB"))
    (ui/ui-table-cell {} (when site_ataxia "CB"))
    (ui/ui-table-cell {} (when site_optic_nerve "ON"))
    (ui/ui-table-cell {} (when site_psychiatric "PS"))
    (ui/ui-table-cell {} (when site_other "OT"))
    (ui/ui-table-cell {} (when site_cognitive "MT"))))

(def ui-ms-event-list-item (comp/computed-factory MsEventListItem {:keyfn :t_ms_event/id}))

(defsc SummaryMultipleSclerosis
  [this {:t_summary_multiple_sclerosis/keys [id events event_ordering_errors]
         :ui/keys                           [current-patient]}]
  {:ident :t_summary_multiple_sclerosis/id
   :query [:t_summary_multiple_sclerosis/id :t_summary_multiple_sclerosis/ms_diagnosis
           :t_summary_multiple_sclerosis/event_ordering_errors
           {:t_summary_multiple_sclerosis/events (comp/get-query MsEventListItem)}
           {[:ui/current-patient '_] [:t_patient/patient_identifier]}]}
  (let [patient-identifier (:t_patient/patient_identifier current-patient)]
    (comp/fragment
      (when (seq event_ordering_errors)
        (div :.pb-4 {}
          (ui/box-error-message
            {:title   "Warning: invalid disease relapses and events"
             :message (dom/ul {} (for [error event_ordering_errors]
                                   (dom/li {:key error} error)))})))
      (ui/ui-table {}
        (ui/ui-table-head {}
          (ui/ui-table-row {}
            (for [{:keys [s key title]} relapse-headings]
              (ui/ui-table-heading (cond-> {:react-key (or key s)} title (assoc :title title)) s))))
        (ui/ui-table-body {}
          (for [event (sort-by #(some-> % :t_ms_event/date .valueOf) events)]
            (ui-ms-event-list-item event {:onClick #(comp/transact! this [(edit-ms-event {:patient-identifier patient-identifier
                                                                                          :ms-event           event})])})))))))

(def ui-summary-multiple-sclerosis (comp/factory SummaryMultipleSclerosis))

(defsc PatientNeuroInflammatory
  [this {:t_patient/keys [id patient_identifier summary_multiple_sclerosis] :as patient
         :>/keys         [banner], :ui/keys [editing-ms-event all-ms-diagnoses]}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/id :t_patient/patient_identifier
                   {:>/banner (comp/get-query patients/PatientBanner)}
                   {:t_patient/summary_multiple_sclerosis (comp/get-query SummaryMultipleSclerosis)}
                   {:ui/editing-ms-event (comp/get-query EditMsEvent)}
                   {[:ui/all-ms-diagnoses '_] [:t_ms_diagnosis/id :t_ms_diagnosis/name]}]
   :route-segment ["pt" :t_patient/patient_identifier "neuroinflammatory"]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (comp/transact! app [(load-ms-event-types {}) (load-ms-diagnoses {})])
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientNeuroInflammatory
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))
   :pre-merge     (fn [{:keys [current-normalized data-tree]}]
                    (merge {:ui/editing-ms-event {}} current-normalized data-tree))}

  (let [not-ms-diagnosis (first (filter #(= 15 (:t_ms_diagnosis/id %)) all-ms-diagnoses)) ;; = "NOT MS"
        has-summary? (:t_summary_multiple_sclerosis/id summary_multiple_sclerosis)
        show-ms? (and has-summary? (not= not-ms-diagnosis (:t_summary_multiple_sclerosis/ms_diagnosis summary_multiple_sclerosis)))]
    (when patient_identifier
      (let [do-add #(comp/transact! this [(add-ms-event {:patient-identifier patient_identifier
                                                         :ms-event           {:t_ms_event/id                            (tempid/tempid)
                                                                              :t_ms_event/patient_fk                    id
                                                                              :t_ms_event/summary_multiple_sclerosis_fk (:t_summary_multiple_sclerosis/id summary_multiple_sclerosis)
                                                                              :t_ms_event/site_unknown                  true}})])]
        (patients/ui-layout
          {:banner (patients/ui-patient-banner banner)
           :menu   (patients/ui-patient-menu
                     patient
                     {:selected-id :relapses
                      :sub-menu
                      {:items [{:id      :add-ms-event
                                :content (when show-ms? (ui/ui-menu-button {:onClick do-add} "Add disease event"))}]}})}
          (comp/fragment
            (tap> patient)
            (when (:t_ms_event/id editing-ms-event)
              (ui-edit-ms-event editing-ms-event))
            (ui/ui-panel {:classes ["mb-4"]}
              (ui/ui-simple-form {}
                (ui/ui-simple-form-item {:label "Neuroinflammatory diagnostic category"}
                  (ui/ui-select-popup-button
                    {:value         (or (:t_summary_multiple_sclerosis/ms_diagnosis summary_multiple_sclerosis) not-ms-diagnosis)
                     :default-value not-ms-diagnosis        ;; we take care not to call onChange unless user chooses to do so here
                     :options       all-ms-diagnoses
                     :id-key        :t_ms_diagnosis/id
                     :display-key   :t_ms_diagnosis/name
                     :onChange      #(comp/transact! this [(list 'pc4.rsdb/save-ms-diagnosis (assoc % :t_patient/patient_identifier patient_identifier))])}))))

            (when show-ms?                                  ;; TODO: allow creation
              (ui-summary-multiple-sclerosis summary_multiple_sclerosis))))))))