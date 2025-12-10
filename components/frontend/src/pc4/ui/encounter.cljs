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

(declare EditEncounter)

(defn edit-core-encounter*
  [state encounter-id]
  (println "editing encounter" encounter-id)
  (-> state
      (assoc-in [:t_encounter/id encounter-id :ui/editing-core?] true)
      (fs/add-form-config* EditEncounter [:t_encounter/id encounter-id])))

(defmutation edit-core-encounter
  [{:keys [encounter-id] :as params}]
  (action
    [{:keys [state]}]
    (println "edit encounter " params)
    (swap! state edit-core-encounter* encounter-id)))

(defn cancel-edit-core-encounter*
  [state encounter-id]
  (println "cancelling editing encounter" encounter-id)
  (-> state
      (assoc-in [:t_encounter/id encounter-id :ui/editing-core?] false)
      (fs/pristine->entity* [:t_encounter/id encounter-id])))

(defmutation cancel-edit-core-encounter
  [{:keys [encounter-id] :as params}]
  (action
    [{:keys [state]}]
    (println "edit encounter " params)
    (swap! state cancel-edit-core-encounter* encounter-id)))

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

(defsc ConsultantUser
  "Component for displaying the consultant/responsible user in the encounter sidebar."
  [this {:t_user/keys [full_name]}]
  {:ident :t_user/id
   :query [:t_user/id :t_user/full_name]}
  (when full_name
    (div :.text-sm.text-gray-600.italic.text-center.pb-4
      full_name)))

(def ui-consultant-user (comp/factory ConsultantUser {:keyfn :t_user/id}))

(defsc EncounterUserListItem
  "Component for displaying a user in the encounter users list."
  [this {:t_user/keys [id full_name initials]}]
  {:ident :t_user/id
   :query [:t_user/id :t_user/full_name :t_user/initials]}
  (dom/li {:key id}
    (dom/span :.hidden.sm:inline full_name)
    (dom/span :.sm:hidden {:title full_name} initials)))

(def ui-encounter-user-list-item (comp/factory EncounterUserListItem {:keyfn :t_user/id}))

(defsc Layout
  [this {:keys [banner encounter menu]}]
  (let [{:t_encounter/keys [id patient date_time is_deleted is_locked lock_date_time hospital_crn
                            encounter_template consultant_user users]} encounter
        {:t_encounter_template/keys [title project]} encounter_template
        {project-title :t_project/title} project
        {:t_patient/keys [patient_identifier permissions]} patient]
    (comp/fragment
      (patients/ui-patient-banner banner {:hospital-identifier hospital_crn})
      (div :.flex.flex-col.md:flex-row.gap-x-4.mx-4.mb-2
        (div :.w-full.md:w-64.md:shrink-0.p-2.space-y-2
          (dom/button :.w-full.inline-flex.justify-center.py-2.px-4.border.border-gray-300.shadow-sm.text-sm.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
            {:onClick #(.back js/history)}
            "Back")

          (when (and date_time encounter_template)
            (div :.shadow.bg-gray-50
              (div :.font-semibold.bg-gray-200.text-center.italic.text-gray-600.pt-2.pb-2
                (ui/format-date-time date_time))
              (div :.text-sm.p-2.pt-4.text-gray-600.italic.text-center {:style {:textWrap "pretty"}}
                project-title)
              (div :.font-bold.text-lg.min-w-min.pt-0.text-center.pb-4
                title)
              (when consultant_user
                (ui-consultant-user consultant_user))))

          (when (seq users)
            (div :.shadow.bg-gray-50.p-2
              (dom/ul :.text-sm.text-gray-700.text-center
                (map ui-encounter-user-list-item users))))
          (when is_deleted
            (div :.mt-4.font-bold.text-center.bg-red-100.p-4.border.border-red-600.rounded
              "Warning: this encounter has been deleted"))
          (when (and (not is_deleted) (not is_locked) (permissions :PATIENT_EDIT))
            (dom/button :.w-full.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-blue-600.hover:bg-blue-700
              {:onClick #(comp/transact! this [(edit-core-encounter {:encounter-id id})])}
              "Edit"))
          (when (or is_locked lock_date_time (and is_locked (permissions :PATIENT_EDIT)))
            (div :.mt-2.italic.text-sm.text-center.bg-gray-100.p-2.border.border-gray-200.shadow.rounded {:style {:textWrap "pretty"}}
              (if is_locked
                (div :.grid.grid-cols-1.gap-2 "This encounter has been locked against editing"
                  (when (and (not is_deleted) (permissions :PATIENT_EDIT))
                    (dom/button :.w-full.inline-flex.justify-center.py-1.px-2.border.border-gray-300.shadow-sm.text-xs.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
                      {:onClick #(comp/transact! this [(list 'pc4.rsdb/unlock-encounter
                                                             {:t_encounter/id               id
                                                              :t_patient/patient_identifier patient_identifier})])}
                      "Unlock")))
                (div :.grid.grid-cols-1.gap-2
                  (when lock_date_time
                    (dom/span "This encounter will lock at " (dom/br) (ui/format-date-time lock_date_time)))
                  (when (permissions :PATIENT_EDIT)
                    (dom/button :.w-full.inline-flex.justify-center.py-1.px-2.border.border-gray-300.shadow-sm.text-xs.font-medium.rounded-md.text-gray-700.bg-white.hover:bg-gray-50
                      {:onClick #(comp/transact! this [(list 'pc4.rsdb/lock-encounter
                                                             {:t_encounter/id               id
                                                              :t_patient/patient_identifier patient_identifier})])}
                      "Lock encounter now"))))))

          menu)
        (div :.flex-1.pt-2
          (comp/children this))))))

(def ui-layout (comp/factory Layout))

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

(defsc FormDefinition [this params]
  {:ident :form_definition/id
   :query [:form_definition/id
           :form_definition/multiple
           :form_definition/title]})

(defsc Form [this params]
  {:ident :form/id
   :query [:form/id
           :form/type
           {:form/definition (comp/get-query FormDefinition)}
           :form/is_deleted
           :form/summary
           {:form/user (comp/get-query User)}
           {:form/encounter (comp/get-query Encounter)}]})

(def form-types
  {:edss/v1            forms/EditFormEdss
   :relapse/v1         forms/EditFormMsRelapse
   :weight-height/v1   forms/EditFormWeightHeight
   :smoking-history/v1 forms/EditFormSmoking})

(def supported-form-types (set (keys form-types)))

(defsc EncounterPatient [_ _]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           :t_patient/permissions
           {:>/banner (comp/get-query patients/PatientBanner)}]})

(defsc EditEncounter*
  [this {encounter-id      :t_encounter/id
         :t_encounter/keys [date_time is_deleted notes encounter_template] :as encounter}
   {:keys [onChange]}]
  (println date_time)
  (ui/ui-simple-form
    {}
    (ui/ui-simple-form-title
      {:subtitle (get-in encounter [:t_encounter/encounter_template :t_encounter_template/project :t_project/title])
       :title    (:t_encounter_template/title encounter_template)})
    (ui/ui-simple-form-item
      {:label "Date / time"}
      (ui/ui-local-date-time {:value    date_time
                              :onChange #(onChange :t_encounter/date_time %)}))
    (ui/ui-simple-form-item
      {:label "Notes"}
      (ui/ui-edit-html {:value       notes
                        :placeholder "Enter encounter notes..."
                        :rows        8
                        :onChange    #(onChange :t_encounter/notes %)}))))

(def ui-edit-encounter* (comp/computed-factory EditEncounter*))

(defsc EditEncounter
  [this {encounter-id      :t_encounter/id
         :t_encounter/keys [patient notes is_locked completed_forms available_form_types duplicated_form_type_ids] :as encounter
         :ui/keys          [editing-core?]}]
  {:ident         :t_encounter/id
   :route-segment ["encounter" :t_encounter/id]
   :query         (fn []
                    [:t_encounter/id
                     :t_encounter/date_time
                     :t_encounter/is_deleted
                     :t_encounter/hospital_crn
                     :t_encounter/lock_date_time
                     :t_encounter/is_locked
                     :t_encounter/notes
                     {:t_encounter/consultant_user (comp/get-query ConsultantUser)}
                     {:t_encounter/users (comp/get-query EncounterUserListItem)}
                     {:t_encounter/completed_forms (comp/get-query Form)}
                     {:t_encounter/deleted_forms (comp/get-query Form)}
                     {:t_encounter/available_form_types (comp/get-query FormDefinition)}
                     {:t_encounter/mandatory_form_types (comp/get-query FormDefinition)}
                     {:t_encounter/optional_form_types (comp/get-query FormDefinition)}
                     {:t_encounter/duplicated_form_types (comp/get-query FormDefinition)}
                     {:t_encounter/encounter_template (comp/get-query EncounterTemplate)}
                     {:t_encounter/patient (comp/get-query EncounterPatient)}
                     :ui/editing-core?
                     [df/marker-table :encounter]
                     fs/form-config-join])
   :form-fields   #{:t_encounter/date_time :t_encounter/notes}
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
        can-edit-patient? (when permissions (permissions :PATIENT_EDIT)) ;; TODO: also take into account lock date time client side in order to autolock if time elapses
        can-edit-encounter? (and can-edit-patient? (not is_locked))] ;; TODO: or, keep checking server - e.g. another user may lock - or better handle of concurrent access through ws
    (println "DEBUG: completed_forms:" (pr-str completed_forms))
    (println "DEBUG: available_form_types:" (pr-str available_form_types))
    (cond
      (= :loading (:status loading-marker))
      (ui/ui-loading-screen {:dim false})
      editing-core?
      (ui/ui-modal
        {:title   "" #_(get-in encounter [:t_encounter/encounter_template :t_encounter_template/project :t_project/title])
         :actions [{:id :cancel :title "Cancel" :onClick #(comp/transact! this [(cancel-edit-core-encounter {:encounter-id encounter-id})])}]
         :onClose :cancel}
        (ui-edit-encounter*                                 ;; show 'core' encounter editing form
          encounter
          {:onChange (fn [k v] (m/set-value! this k v))}))
      :else
      (ui-layout
        {:banner    (-> encounter :t_encounter/patient :>/banner)
         :encounter encounter
         :menu      []}
        (ui/ui-panel
          {}
          (when (seq duplicated_form_type_ids)              ; the legacy application permits duplicate forms if created concurrently... flag any to user
            (ui/box-error-message
              {:title   "Warning: Duplicated forms"
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
              (for [{:form/keys [id type definition summary user] :as form} completed_forms
                    :let [_ (println "DEBUG: form keys:" (keys form) "type:" type "definition:" definition)
                          supported (supported-form-types (:form_definition/id definition))
                          title (:form_definition/title definition)]]
                (ui/ui-table-row
                  {:key     id
                   :onClick (when supported #(comp/transact! this [(route/route-to {:handler ::route/form
                                                                                    :params  {:encounter-id   encounter-id
                                                                                              :form-type-name nil
                                                                                              :form/id        (forms/form-id->str id)}})]))
                   :classes (if supported ["cursor-pointer" "hover:bg-gray-200"] ["cursor-not-allowed"])}
                  (ui/ui-table-cell {} (dom/span {:classes ["text-blue-500" "underline"]} title))
                  (ui/ui-table-cell {} summary)
                  (ui/ui-table-cell
                    {}
                    (dom/span :.hidden.lg:block (:t_user/full_name user))
                    (dom/span :.block.lg:hidden {:title (:t_user/full_name user)} (:t_user/initials user)))))
              (when can-edit-encounter?
                (for [{:form_definition/keys [id title] :as form-type} available_form_types
                      :let [form-type-name (name id)
                            supported (supported-form-types form-type-name)]]
                  (ui/ui-table-row
                    {:onClick (when supported #(comp/transact! this [(list 'pc4.rsdb/create-form {:patient-identifier patient-identifier
                                                                                                  :encounter-id       encounter-id
                                                                                                  :form-type-name     form-type-name
                                                                                                  :component-class    (get form-types form-type-name)
                                                                                                  :form-type-id       id
                                                                                                  :on-success-tx      []})]))
                     :classes (if supported ["italic" "cursor-pointer" "hover:bg-gray-200"] ["italic" "cursor-not-allowed"])}
                    (ui/ui-table-cell {} (dom/span title))
                    (ui/ui-table-cell {} (dom/span "Pending"))
                    (ui/ui-table-cell {} "")))))))
        (ui/ui-active-panel
          {:title (if can-edit-encounter? (dom/a {:onClick #(comp/transact! this [(edit-core-encounter {:encounter-id encounter-id})])
                                                  :classes ["cursor-pointer" "text-blue-500" "underline"]}
                                            "Notes")
                                          "Notes")}
          (div :.shadow-inner.p-4.text-sm
            (dom/span {:dangerouslySetInnerHTML {:__html notes}})))))))

