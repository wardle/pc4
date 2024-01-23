(ns pc4.ui.encounter
  (:require [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.ui.core :as ui]
            [pc4.ui.patients :as patients]))

(defsc NeuroinflammatoryComposite [this params])

(def ui-neuroinflammatory-composite (comp/computed-factory NeuroinflammatoryComposite))

(def composite-forms
  [{:name  "Neuroinflammatory"
    :forms #{"FormEdss" "FormMSRelapse" "FormSmokingHistory" "FormWeightHeight"}
    :class NeuroinflammatoryComposite}])

(def edss-scores
  ["SCORE0_0" "SCORE1_0" "SCORE1_5" "SCORE2_0" "SCORE2_5" "SCORE3_0" "SCORE3_5"
   "SCORE4_0" "SCORE4_5" "SCORE5_0" "SCORE5_5" "SCORE6_0" "SCORE6_5" "SCORE7_0"
   "SCORE7_5" "SCORE8_0" "SCORE8_5" "SCORE9_0" "SCORE9_5" "SCORE10_0"
   "SCORE_LESS_THAN_4"])

(defsc FormEdss [this {:t_encounter/keys [form_edss form_ms_relapse] :as encounter} {:keys [onChange]}]
  (ui/ui-simple-form-item {:label "EDSS"}
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

(defsc FormMsDiseaseCourse [this {:t_encounter/keys [form_ms_relapse]}])


(defsc Layout [this {:keys [banner encounter menu]}]
  (let [{:t_encounter/keys [date_time is_deleted encounter_template]} encounter
        {:t_encounter_template/keys [title project]} encounter_template
        {project-title :t_project/title} project]
    (comp/fragment
      (patients/ui-patient-banner banner)
      (div :.grid.grid-cols-1.lg:grid-cols-6.gap-x-4.relative.pr-2
        (div :.col-span-1.p-2
          (when (and date_time encounter_template)
            (div :.shadow.bg-gray-50
              (div :.pl-4.font-semibold.bg-gray-200.text-center.italic.text-gray-600
                (ui/format-date date_time))
              (div :.text-sm.p-2.pt-4.text-gray-600.italic.text-center {:style {:text-wrap "pretty"}}
                project-title)
              (div :.font-bold.text-lg.min-w-min.p-4.pt-0.text-center
                title)))
          (when is_deleted
            (div :.mt-4.font-bold.text-center.bg-red-100.p-4.border.border-red-600.rounded
              "Warning: this encounter has been deleted"))
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

(defsc EditEncounter
  [this {:t_encounter/keys [id] :as encounter}]
  {:ident         :t_encounter/id
   :route-segment ["encounter" :t_encounter/id]
   :query         [:t_encounter/id :t_encounter/date_time
                   {:t_encounter/encounter_template (comp/get-query EncounterTemplate)}
                   {:t_encounter/patient
                    [{:>/banner (comp/get-query patients/PatientBanner)}]}]
   :will-enter    (fn [app {:t_encounter/keys [id] :as route-params}]
                    (when-let [encounter-id (some-> id (js/parseInt))]
                      (dr/route-deferred [:t_encounter/id encounter-id]
                                         (fn []
                                           (df/load! app [:t_encounter/id encounter-id] EditEncounter
                                                     {:target               [:ui/current-encounter]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_encounter/id encounter-id]}})))))}
  (tap> encounter)
  (ui-layout {:banner    (-> encounter :t_encounter/patient :>/banner)
              :encounter encounter
              :menu      []}
             (ui/ui-panel {}
               (ui-form-edss encounter))))
