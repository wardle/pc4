(ns pc4.ui.encounters
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
            [pc4.ui.snomed :as snomed]
            [taoensso.timbre :as log]))

(defsc Project [this params]
  {:ident :t_project/id
   :query [:t_project/id :t_project/title]})

(defsc EncounterTemplateTitle [this {:t_encounter_template/keys [title project]}]
  {:ident :t_encounter_template/id
   :query [:t_encounter_template/id
           :t_encounter_template/title
           {:t_encounter_template/project (comp/get-query Project)}]}
  (dom/span {:title (:t_project/title project)} title))

(def ui-encounter-template-title (comp/factory EncounterTemplateTitle))

(defsc EncounterListItem
  [this {:t_encounter/keys [id date_time encounter_template form_edss form_ms_relapse form_weight_height] :as encounter} computed-props]
  {:ident :t_encounter/id
   :query [:t_encounter/id
           :t_encounter/date_time
           :t_encounter/active
           {:t_encounter/encounter_template
            (comp/get-query EncounterTemplateTitle)}
           {:t_encounter/form_edss
            [:t_form_edss/id :t_form_edss/score]}
           {:t_encounter/form_ms_relapse
            [:t_form_ms_relapse/id :t_form_ms_relapse/in_relapse
             {:t_form_ms_relapse/ms_disease_course [:t_ms_disease_course/name]}]}
           {:t_encounter/form_weight_height
            [:t_form_weight_height/weight_kilogram]}]}
  (ui/ui-table-row computed-props
    (ui/ui-table-cell {} (ui/format-date date_time))
    (ui/ui-table-cell {} (ui-encounter-template-title encounter_template))
    (ui/ui-table-cell {} (str (:t_form_edss/score form_edss)))
    (ui/ui-table-cell {} (case (:t_form_ms_relapse/in_relapse form_ms_relapse) true "Yes" false "No" ""))
    (ui/ui-table-cell {} (get-in form_ms_relapse [:t_form_ms_relapse/ms_disease_course :t_ms_disease_course/name]))
    (ui/ui-table-cell {} (some-> (:t_form_weight_height/weight_kilogram form_weight_height) (str "kg")))))
(def ui-encounter-list-item (comp/computed-factory EncounterListItem {:keyfn :t_encounter/id}))

(defsc PatientEncounters
  [this {:t_patient/keys [id patient_identifier encounters] :as patient
         :>/keys         [banner]}]
  {:ident         :t_patient/patient_identifier
   :route-segment ["pt" :t_patient/patient_identifier "encounters"]
   :query         [:t_patient/patient_identifier
                   :t_patient/id
                   {:>/banner (comp/get-query patients/PatientBanner)}
                   {:t_patient/encounters (comp/get-query EncounterListItem)}]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientEncounters
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))}
  (when patient_identifier
    (patients/ui-layout
      {:banner (patients/ui-patient-banner banner)
       :menu   (patients/ui-patient-menu
                 patient
                 {:selected-id :encounters
                  :sub-menu    {:items [{:id      ::add
                                         :onClick #(println "add encounter")
                                         :content "Add encounter"}]}})}

      (ui/ui-table {}
        (ui/ui-table-head {}
          (ui/ui-table-row {}
            (map #(ui/ui-table-heading {:react-key %} %) ["Date/time" "Type" "EDSS" "In relapse?" "Disease course" "Weight"])))
        (ui/ui-table-body {}
          (for [{:t_encounter/keys [id] :as encounter} encounters]
            (ui-encounter-list-item encounter
                                    {:onClick #(dr/change-route! this ["encounter" id])
                                     :classes ["cursor-pointer" "hover:bg-gray-200"]})))))))
