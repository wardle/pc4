(ns pc4.ui.encounter
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            ["big.js" :as Big]
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

(def edss-scores*
  [{:id "SCORE0_0" :s "0.0" :d "Normal neurological exam, no disability in any FS"}
   {:id "SCORE1_0" :s "1.0" :d "No disability, minimal signs in one FS"}
   {:id "SCORE1_5" :s "1.5" :d "No disability, minimal signs in more than one FS"}
   {:id "SCORE2_0" :s "2.0" :d "Minimal disability in one FS"}
   {:id "SCORE2_5" :s "2.5" :d "Mild disability in one FS or minimal disability in two FS"}
   {:id "SCORE3_0" :s "3.0" :d "Moderate disability in one FS, or mild disability in three or four FS. No impairment to walking"}
   {:id "SCORE3_5" :s "3.5" :d "Moderate disability in one FS and more than minimal disability in several others. No impairment to walking"}
   {:id "SCORE4_0" :s "4.5" :d "Significant disability but self-sufficient and up and about some 12 hours a day. Able to walk without aid or rest for 500m"}
   {:id "SCORE_LESS_THAN_4" :s "<4.0" :d "No impairment to walking but neurological examination not performed"}
   {:id "SCORE4_5" :s "4.5" :d "Significant disability but up and about much of the day, able to work a full day, may otherwise have some limitation of full activity or require minimal assistance. Able to walk without aid or rest for 300m"}
   {:id "SCORE5_0" :s "5.0" :d "Disability severe enough to impair full daily activities and ability to work a full day without special provisions. Able to walk without aid or rest for 200m"}
   {:id "SCORE5_5" :s "5.5" :d "Disability severe enough to preclude full daily activities. Able to walk without aid or rest for 100m"}
   {:id "SCORE6_0" :s "6.0" :d " Requires a walking aid – cane, crutch, etc. – to walk about 100m with or without resting"}
   {:id "SCORE6_5" :s "6.5" :d "Requires two walking aids – pair of canes, crutches, etc. – to walk about 20m without resting"}
   {:id "SCORE7_0" :s "7.0" :d "Unable to walk beyond approximately 5m even with aid. Essentially restricted to wheelchair; though wheels self in standard wheelchair and transfers alone. Up and about in wheelchair some 12 hours a day"}
   {:id "SCORE7_5" :s "7.5" :d "Unable to take more than a few steps. Restricted to wheelchair and may need aid in transfering. Can wheel self but cannot carry on in standard wheelchair for a full day and may require a motorised wheelchair"}
   {:id "SCORE8_0" :s "8.0" :d "Essentially restricted to bed or chair or pushed in wheelchair. May be out of bed itself much of the day. Retains many self-care functions. Generally has effective use of arms"}
   {:id "SCORE8_5" :s "8.5" :d "Essentially restricted to bed much of day. Has some effective use of arms retains some self-care functions"}
   {:id "SCORE9_0" :s "9.0" :d "Confined to bed. Can still communicate and eat"}
   {:id "SCORE9_5" :s "9.5" :d "Confined to bed and totally dependent. Unable to communicate effectively or eat/swallow"}
   {:id "SCORE10_0" :s "10.0" :d "Death due to MS"}])

(def edss-scores (mapv :id edss-scores*))

(def edss-score-display
  (reduce (fn [acc {:keys [id s d]}]
            (assoc acc id (div
                           (dom/span :.text-base.font-bold.text-gray-800 s)
                           (dom/span :.ms-4.text-sm.text-gray-600.italic d)))) {} edss-scores*))

(defsc EditFormEdss [this {:form_edss/keys [edss_score]}]
  {:ident       :form/id
   :query       [:form/id :form_edss/edss_score
                 fs/form-config-join]
   :form-fields #{:form_edss/edss_score}}
  (comp/fragment
   (ui/ui-simple-form-title
    {:title "EDSS (short-form)"})
   (ui/ui-radio-button
    {:name        "edss"
     :value       edss_score
     :options     edss-scores
     :display-key edss-score-display
     :onChange #(m/set-value! this :form_edss/edss_score %)})))

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
                      :onChange #(m/set-value! this :form_weight_height/weight_kilogram (when-not (str/blank? %) (Big. %)))}))
   (ui/ui-simple-form-item
    {:label "Height (metres)"}
    (ui/ui-textfield {:value    height_metres
                      :type     :number
                      :onChange #(m/set-value! this :form_weight_height/height_metres (when-not (str/blank? %) (Big. %)))}))
   (ui/ui-simple-form-item                                   ;; TODO: should only show this for adults
    {:label "Body mass index"}
    (div :.mt-2.text-gray-600.italic
         (when (and weight_kilogram height_metres)
           (str (.round (.div ^Big weight_kilogram (.pow height_metres 2)) 1) " kg/m²"))))))

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
  (reduce (fn [acc {:keys [nm view class]}] (assoc acc nm (or view (comp/factory class)))) {} forms))

(defsc EditEncounter
  [this {encounter-id :t_encounter/id
         :t_encounter/keys [patient notes is_locked completed_forms available_form_types] :as encounter
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
                     {:t_encounter/encounter_template (comp/get-query EncounterTemplate)}
                     {:t_encounter/patient [:t_patient/patient_identifier
                                            {:>/banner (comp/get-query patients/PatientBanner)}]}
                     {:ui/editing-form ['*]}])
   :will-enter    (fn [app {:t_encounter/keys [id] :as route-params}]
                    (when-let [encounter-id (some-> id (js/parseInt))]
                      (dr/route-deferred
                       [:t_encounter/id encounter-id]
                       (fn [] (df/load! app
                                        [:t_encounter/id encounter-id] EditEncounter
                                        {:target               [:ui/current-encounter]
                                         :post-mutation        `dr/target-ready
                                         :post-mutation-params {:target [:t_encounter/id encounter-id]}})))))}
  (ui-layout
   {:banner    (-> encounter :t_encounter/patient :>/banner)
    :encounter encounter
    :menu      []}
   (when editing-form
     (let [form-name (get-in editing-form [:form/form_type :form_type/nm])
           class (form-class-by-name form-name)
           view (form-view-by-name form-name)]
       (if view
         (ui/ui-modal
          {:actions [{:id ::save :title "Save" :role :primary
                      :onClick #(do (println "Save" editing-form)
                                    (comp/transact! this [(list 'pc4.rsdb/save-form {:patient-identifier (:t_patient/patient_identifier patient)
                                                                                     :form editing-form
                                                                                     :class class})])) :disabled? false}
                     {:id ::cancel :title "Cancel" :onClick #(comp/transact! this [(cancel-edit-form {:encounter-id encounter-id :form editing-form})])}]
           :onClose ::cancel}
          (view editing-form))
         (ui/ui-modal
          {:actions [{:id ::close :title "Close" :role :primary
                      :onClick #(comp/transact! this [(cancel-edit-form {:encounter-id encounter-id :form editing-form})])}]
           :onClose ::close}
          (ui/box-error-message {:title "Not implemented" :message "It is not yet possible to view or edit this form using this application."})))))
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
          :classes (if form-class ["cursor-pointer" "hover:bg-gray-200"] ["cursor-not-allowed"])}
         (ui/ui-table-cell {} (dom/span {:classes (when form-class ["text-blue-500" "underline"])} title))
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
           (ui/ui-table-cell {} "")))))))
   (ui/ui-active-panel
    {:title "Notes"}
    (div :.shadow-inner.p-4.text-sm
         (dom/span {:dangerouslySetInnerHTML {:__html notes}})))))

