(ns pc4.ui.encounter
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
            ["big.js" :as Big]
            [pc4.ui.core :as ui]
            [pc4.ui.forms :as forms]
            [pc4.ui.patients :as patients]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.algorithms.merge :as merge]
            [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
            [pc4.route :as route]))

(defsc Layout
  [this {:keys [banner encounter menu]}]
  (let [{:t_encounter/keys [id patient date_time is_deleted is_locked lock_date_time encounter_template]} encounter
        {:t_encounter_template/keys [title project]} encounter_template
        {project-title :t_project/title} project
        {:t_patient/keys [patient_identifier permissions]} patient]
    (comp/fragment
     (patients/ui-patient-banner banner)
     (div :.grid.grid-cols-1.lg:grid-cols-6.gap-x-2.relative.pr-2
          (div :.col-span-1.p-2.space-y-2
               (ui/ui-menu-button {:onClick #(.back js/history)} "Back")
               (when (and date_time encounter_template)
                 (div :.shadow.bg-gray-50
                      (div :.font-semibold.bg-gray-200.text-center.italic.text-gray-600.pt-2.pb-2
                           (str (ui/format-week-day date_time) " " (ui/format-date-time date_time)))
                      (div :.text-sm.p-2.pt-4.text-gray-600.italic.text-center {:style {:textWrap "pretty"}}
                           project-title)
                      (div :.font-bold.text-lg.min-w-min.pt-0.text-center.pb-4
                           title)))
               (when is_deleted
                 (div :.mt-4.font-bold.text-center.bg-red-100.p-4.border.border-red-600.rounded
                      "Warning: this encounter has been deleted"))
               (when (or is_locked lock_date_time (and (not is_locked) (permissions :PATIENT_EDIT)))
                 (div :.mt-2.italic.text-sm.text-center.bg-gray-100.p-2.border.border-gray-200.shadow.rounded {:style {:textWrap "pretty"}}
                      (cond
                        is_locked
                        (div :.grid.grid-cols-1.gap-2 "This encounter has been locked against editing"
                             (when (permissions :PATIENT_EDIT)
                               (ui/ui-button {:onClick #(comp/transact! this [(list 'pc4.rsdb/unlock-encounter
                                                                                    {:t_encounter/id id
                                                                                     :t_patient/patient_identifier patient_identifier})])}
                                             "Unlock")))
                        (and (not is_locked) (permissions :PATIENT_EDIT))
                        (ui/ui-button {:onClick #(comp/transact! this [(list 'pc4.rsdb/lock-encounter
                                                                             {:t_encounter/id id
                                                                              :t_patient/patient_identifier patient_identifier})])}
                                      "Lock encounter now")
                        lock_date_time
                        (dom/span "This encounter will lock at " (dom/br) (ui/format-date-time lock_date_time)))))

               menu)
          (div :.col-span-1.lg:col-span-5.pt-2
               (comp/children this))))))

(def ui-layout (comp/factory Layout))

(defsc Project [this params]
  {:ident :t_project/id
   :query [:t_project/id :t_project/title]})

(defsc EncounterTemplate [this params]
  {:ident :t_encounter_template/id
   :query [:t_encounter_template/id
           :t_encounter_template/title
           {:t_encounter_template/project (comp/get-query Project)}]})

(defsc User [this params]
  {:ident :t_user/id
   :query [:t_user/id :t_user/full_name :t_user/initials]})

(defsc Encounter [this params]
  {:ident :t_encounter/id
   :query [:t_encounter/id]})

(defsc FormType [this params]
  {:ident :form_type/id
   :query [:form_type/id
           :form_type/nm
           :form_type/nspace
           :form_type/one_per_encounter
           :form_type/table
           :form_type/title]})

(defsc Form [this params]
  {:ident :form/id
   :query (fn []
            [:form/id
             '*
             :form/form_type
             :form/is_deleted
             :form/summary_result
             {:form/user (comp/get-query User)}
             {:form/encounter (comp/get-query Encounter)}])})

;; 
;; It is possible that a better approach would be to use dynamic routing here and work at the form
;; level. However. at the moment, this mainly works at the encounter level, with form data pulled
;; in for an encounter - so we can simply switch components ourselves. This does mean that forms
;; cannot load additional data such as lookups. 
#_(def form-types
    [{:nm     "form_edss"
      :class  EditFormEdss
      :view   ui-edit-form-edss}
     {:nm     "form_ms_relapse"
      :class  EditFormMsRelapse
      :view   ui-edit-form-ms-relapse}
     {:nm     "form_weight_height"
      :class  EditFormWeightHeight
      :view   ui-edit-form-weight-height}])

#_(def form-type-by-name
    (reduce (fn [acc {:keys [nm view class] :as v}]
              (assoc acc nm (cond-> v
                              (nil? view) (assoc :view (comp/computed-factory class))))) {} form-types))

(def form-types
  {"form_edss"          forms/EditFormEdss
   "form_ms_relapse"    forms/EditFormMsRelapse
   "form_weight_height" forms/EditFormWeightHeight
   "form_smoking"       forms/EditFormSmoking})

(def supported-form-types (set (keys form-types)))

(defsc EncounterPatient [_ _]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           :t_patient/permissions
           {:>/banner (comp/get-query patients/PatientBanner)}]})

(defsc EditEncounter
  [this {encounter-id :t_encounter/id
         :t_encounter/keys [patient notes is_locked completed_forms available_form_types duplicated_form_types] :as encounter
         :ui/keys [editing-form]}]
  {:ident         :t_encounter/id
   :route-segment ["encounter" :t_encounter/id]
   :query         (fn []
                    [:t_encounter/id
                     :t_encounter/date_time
                     :t_encounter/is_deleted
                     :t_encounter/lock_date_time
                     :t_encounter/is_locked
                     :t_encounter/notes
                     {:t_encounter/users (comp/get-query User)}
                     {:t_encounter/completed_forms (comp/get-query Form)}
                     {:t_encounter/deleted_forms (comp/get-query Form)}
                     {:t_encounter/available_form_types (comp/get-query FormType)}
                     {:t_encounter/mandatory_form_types (comp/get-query FormType)}  ;; TODO: show mandatory form types as inline forms?
                     {:t_encounter/optional_form_types (comp/get-query FormType)}   ;; TODO: add quick list in a drop down for optional forms (takes one extra click to get)
                     {:t_encounter/duplicated_form_types (comp/get-query Form)}
                     {:t_encounter/encounter_template (comp/get-query EncounterTemplate)}
                     {:t_encounter/patient (comp/get-query EncounterPatient)}
                     [df/marker-table :encounter]])
   :will-enter    (fn [app {:t_encounter/keys [id]}]
                    (when-let [encounter-id (some-> id (js/parseInt))]
                      (df/load! app [:t_encounter/id encounter-id] EditEncounter
                                {:target [:ui/current-encounter]
                                 :marker :encounter})
                      (dr/route-immediate [:t_encounter/id encounter-id])
                      #_(dr/route-deferred
                         [:t_encounter/id encounter-id]
                         (fn []
                           (df/load! app [:t_encounter/id encounter-id] EditEncounter
                                     {:target               [:ui/current-encounter]
                                      :post-mutation        `dr/target-ready
                                      :post-mutation-params {:target [:t_encounter/id encounter-id]}})))))}
  (let [loading-marker (get encounter [df/marker-table :encounter])
        patient-identifier (:t_patient/patient_identifier patient)
        permissions (:t_patient/permissions patient)
        can-edit-patient? (when permissions (permissions :PATIENT_EDIT))   ;; TODO: also take into account lock date time client side in order to autolock if time elapses
        can-edit-encounter? (and can-edit-patient? (not is_locked))]  ;; TODO: or, keep checking server - e.g. another user may lock - or better handle of concurrent access through ws
    (if-not (= :loading (:status loading-marker))
      (ui-layout
       {:banner    (-> encounter :t_encounter/patient :>/banner)
        :encounter encounter
        :menu      []}
       (ui/ui-panel
        {}
        (when (seq duplicated_form_types)   ; the legacy application permits duplicate forms if created concurrently... flag any to user
          (ui/box-error-message
           {:title "Warning: Duplicated forms"
            :message "There are duplicate forms within this encounter. Please delete any incorrect duplicates."}))
        (ui/ui-table
         {}
         (ui/ui-table-head
          {}
          (ui/ui-table-row
           {}
           (ui/ui-table-heading {} "Form")
           (ui/ui-table-heading {} "Result")
           (ui/ui-table-heading {} "User")))
         (ui/ui-table-body
          {}
          (for [{:form/keys [id form_type summary_result user] :as form} completed_forms
                :let [form-type-name (:form_type/nm form_type)
                      supported (supported-form-types form-type-name)  ;; do we support viewing/editing this form yet?
                      title (:form_type/title form_type)]]
            (do (println "form" {:id id :form_type form_type :supported? supported})
                (ui/ui-table-row
                 {:key id
                  :onClick (when supported #(comp/transact! this [(route/route-to {:handler ::route/form
                                                                                   :params {:encounter-id encounter-id
                                                                                            :form-type-name form-type-name
                                                                                            :form/id (forms/form-id->str id)}})]))
                  :classes (if supported ["cursor-pointer" "hover:bg-gray-200"] ["cursor-not-allowed"])}
                 (ui/ui-table-cell {} (dom/span {:classes ["text-blue-500" "underline"]} title))
                 (ui/ui-table-cell {} (if-not supported "Not yet supported" summary_result))
                 (ui/ui-table-cell
                  {}
                  (dom/span :.hidden.lg:block (:t_user/full_name user))
                  (dom/span :.block.lg:hidden {:title (:t_user/full_name user)} (:t_user/initials user))))))
          (when can-edit-encounter?      ;; TODO: also offer to show other available forms?
            (for [{:form_type/keys [id nm title] :as form-type} available_form_types
                  :let [supported (supported-form-types nm)]]
              (ui/ui-table-row
               {:onClick (when supported #(do (println "add form " form-type)
                                              (comp/transact! this [(list 'pc4.rsdb/create-form {:patient-identifier patient-identifier
                                                                                                 :encounter-id       encounter-id
                                                                                                 :form-type-name     nm
                                                                                                 :component-class    (get form-types nm)
                                                                                                 :form-type-id       id
                                                                                                 :on-success-tx      []})])))
                :classes (cond-> ["italic"] supported (conj "cursor-pointer" "hover:bg-gray-200"))}
               (ui/ui-table-cell {} (dom/span title))
               (ui/ui-table-cell {} (dom/span (if supported "Pending" "Not yet supported")))
               (ui/ui-table-cell {} "")))))))
       (ui/ui-active-panel
        {:title "Notes"}
        (div :.shadow-inner.p-4.text-sm
             (dom/span {:dangerouslySetInnerHTML {:__html notes}}))))
      (ui/ui-loading-screen {:dim false}))))

