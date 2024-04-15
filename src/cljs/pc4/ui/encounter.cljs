(ns pc4.ui.encounter
  (:require [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.ui.core :as ui]
            [pc4.ui.patients :as patients]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.algorithms.merge :as merge]))

(defn edit-form*
  [state encounter-id class form-id]
  (let [ident [:form/id form-id]]
    (cond-> state
      true (assoc-in [:t_encounter/id encounter-id :ui/editing-form] ident)
      class (fs/add-form-config* class ident {:destructive? true}))))

(defmutation edit-form
  [{:keys [encounter-id class form]}]
  (action
   [{:keys [state]}]
   (if-let [form-id (:form/id form)]
     (swap! state edit-form* encounter-id class form-id)
     (throw (ex-info "Missing form id" form)))))

(defn cancel-edit-form*
  [state encounter-id form-id]
  (cond->
   (-> state
       (fs/pristine->entity* [:form/id form-id])
       (update-in [:t_encounter/id encounter-id] dissoc :ui/editing-form))
    ;; if this is a temporary (newly created) form, delete it
    (tempid/tempid? form-id)
    (update :form/id dissoc form-id)))

(defmutation cancel-edit-form
  [{:keys [encounter-id form]}]
  (action
   [{:keys [state]}]
   (swap! state cancel-edit-form* encounter-id (:form/id form))))

(def edss-scores
  ["SCORE0_0" "SCORE1_0" "SCORE1_5" "SCORE2_0" "SCORE2_5" "SCORE3_0" "SCORE3_5"
   "SCORE4_0" "SCORE4_5" "SCORE5_0" "SCORE5_5" "SCORE6_0" "SCORE6_5" "SCORE7_0"
   "SCORE7_5" "SCORE8_0" "SCORE8_5" "SCORE9_0" "SCORE9_5" "SCORE10_0"
   "SCORE_LESS_THAN_4"])

(defsc FormEdss [this {:t_encounter/keys [form_edss form_ms_relapse] :as encounter} {:keys [onChange]}]
  (ui/ui-simple-form-item
   {:label "EDSS"}
   (div :.space-y-4
        (ui/ui-select-popup-button
         {:name "edss"
          :value         (:t_form_edss/edss_score form_edss)
          :options       edss-scores
          :sort?         true
          :onChange      #(when onChange (onChange (assoc-in encounter [:t_encounter/form_edss :t_form_edss/edss_score] %)))})
        (ui/ui-checkbox
         {:label    "In relapse?"
          :checked  (:t_form_ms_relapse/in_relapse form_ms_relapse)
          :onChange #(when onChange (onChange (assoc-in encounter [:t_encounter/form_ms_relapse :t_form_ms_relapse/in_relapse] %)))}))))

(def ui-form-edss (comp/computed-factory FormEdss))

(defsc EditFormEdss [this {:form_edss/keys [edss_score]}]
  {:ident       :form/id
   :query       [:form/id
                 :form_edss/edss_score
                 fs/form-config-join]
   :form-fields #{:form_edss/edss_score}}
  (ui/ui-simple-form-item
   {:label "EDSS (short-form)"}
   (ui/ui-select-popup-button
    {:name "edss"
     :value         edss_score
     :options       edss-scores
     :sort?         true
     :onChange      #(m/set-value! this :form_edss/edss_score %)})))

(def ui-edit-form-edss (comp/factory EditFormEdss))

(defsc EditFormWeightHeight
  [this {:form_weight_height/keys [weight_kilogram height_metres]}]
  {:ident :form/id
   :query [:form/id :form_weight_height/weight_kilogram :form_weight_height/height_metres
           fs/form-config-join]
   :form-fields #{:form_weight_height/weight_kilogram :form_weight_height/height_metres}}
  (comp/fragment
   (ui/ui-simple-form-item
    {:label "Weight (kilograms)"}
    (ui/ui-textfield {:value    weight_kilogram
                      :type     :number
                      :onChange #(m/set-value! this :form_weight_height/weight_kilogram %)}))
   (ui/ui-simple-form-item
    {:label "Height (metres)"}
    (ui/ui-textfield {:value    height_metres
                      :type     :number
                      :onChange #(m/set-value! this :form_weight_height/height_metres %)}))))

(def ui-edit-form-weight-height (comp/factory EditFormWeightHeight))

(defsc Layout [this {:keys [banner encounter menu]}]
  (let [{:t_encounter/keys [date_time is_deleted is_locked lock_date_time encounter_template]} encounter
        {:t_encounter_template/keys [title project]} encounter_template
        {project-title :t_project/title} project]
    (comp/fragment
     (patients/ui-patient-banner banner)
     (div :.grid.grid-cols-1.lg:grid-cols-6.gap-x-2.relative.pr-2
          (div :.col-span-1.p-2
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
               (div :.mt-2.italic.text-sm.text-center.bg-gray-100.p-2.border.border-gray-200.shadow.rounded
                    (if is_locked
                      "This encounter has been locked against editing"
                      (str "This encounter will lock at " (ui/format-date-time lock_date_time))))

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
             {:form/user (comp/get-query User)}])})

(def forms
  [{:nm "form_edss"
    :class EditFormEdss
    :view ui-edit-form-edss}
   {:nm "form_weight_height"
    :class EditFormWeightHeight
    :view ui-edit-form-weight-height}])

(def form-class-by-name
  (reduce (fn [acc {:keys [nm class]}] (assoc acc nm class)) {} forms))

(def form-view-by-name
  (reduce (fn [acc {:keys [nm view]}] (assoc acc nm view)) {} forms))

(defsc EditEncounter
  [this {encounter-id :t_encounter/id
         :t_encounter/keys [is_locked completed_forms available_form_types] :as encounter
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
                     {:t_encounter/completed_forms (comp/get-query Form)}
                     {:t_encounter/deleted_forms (comp/get-query Form)}
                     {:t_encounter/available_form_types (comp/get-query FormType)}
                     {:t_encounter/mandatory_form_types (comp/get-query FormType)}  ;; TODO: show mandatory form types as inline forms?
                     {:t_encounter/optional_form_types (comp/get-query FormType)}   ;; TODO: add quick list in a drop down for optional forms (takes one extra click to get)
                     {:t_encounter/encounter_template (comp/get-query EncounterTemplate)}
                     {:t_encounter/patient [{:>/banner (comp/get-query patients/PatientBanner)}]}
                     {:ui/editing-form ['*]}])
   :will-enter    (fn [app {:t_encounter/keys [id] :as route-params}]
                    (when-let [encounter-id (some-> id (js/parseInt))]
                      (dr/route-deferred [:t_encounter/id encounter-id]
                                         (fn []
                                           (df/load! app [:t_encounter/id encounter-id] EditEncounter
                                                     {:target               [:ui/current-encounter]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_encounter/id encounter-id]}})))))}
  (ui-layout
   {:banner    (-> encounter :t_encounter/patient :>/banner)
    :encounter encounter
    :menu      []}
   (when editing-form
     (if-let [view (form-view-by-name (get-in editing-form [:form/form_type :form_type/nm]))]
       (ui/ui-modal
        {:actions [{:id ::save :title "Save" :role :primary :onClick #(println "Save") :disabled? false}
                   {:id ::cancel :title "Cancel" :onClick #(comp/transact! this [(cancel-edit-form {:encounter-id encounter-id :form editing-form})])}]
         :onClose ::cancel}
        (view editing-form))
       (ui/ui-modal
        {:actions [{:id ::close :title "Close" :role :primary
                    :onClick #(comp/transact! this [(cancel-edit-form {:encounter-id encounter-id :form editing-form})])}]
         :onClose ::close}
        (ui/box-error-message {:title "Not implemented" :message "It is not yet possible to view or edit this form using this application."}))))
   (ui/ui-panel
    {}
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
                  form-class (form-class-by-name form-type-name)
                  title (:form_type/title form_type)]]
        (ui/ui-table-row
         {:onClick #(comp/transact! this [(edit-form {:encounter-id encounter-id :class form-class :form form})])
          :classes ["cursor-pointer" "hover:bg-gray-200"]}
         (ui/ui-table-cell {} (dom/span :.text-blue-500.underline title))
         (ui/ui-table-cell {} summary_result)
         (ui/ui-table-cell
          {}
          (dom/span :.hidden.lg:block (:t_user/full_name user))
          (dom/span :.block.lg:hidden {:title (:t_user/full_name user)} (:t_user/initials user)))))
      (when-not is_locked
        (for [{:form_type/keys [id title] :as form-type} available_form_types]
          (ui/ui-table-row
           {:onClick #(println "add form " form-type)
            :classes ["italic" "cursor-pointer" "hover:bg-gray-200"]}
           (ui/ui-table-cell {} (dom/span title))
           (ui/ui-table-cell {} (dom/span "Pending"))
           (ui/ui-table-cell {} "")))))))))
