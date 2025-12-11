(ns pc4.ui.encounters
  (:require
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :as dom :refer [div]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [pc4.ui.core :as ui]
    [pc4.ui.encounter :as encounter]
    [pc4.ui.ods :as ods]
    [pc4.ui.patients :as patients]
    [pc4.ui.select-user :as select-user]
    [pc4.route :as route])
  (:import [goog.date DateTime]))

(defsc Project
  [this params]
  {:ident :t_project/id
   :query [:t_project/id :t_project/title]})

(defsc EncounterTemplateTitle
  [this {:t_encounter_template/keys [title project]}]
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
           {:t_encounter/encounter_template (comp/get-query EncounterTemplateTitle)}
           {:t_encounter/form_edss
            [:t_form_edss/id :t_form_edss/score]}
           {:t_encounter/form_ms_relapse
            [:t_form_ms_relapse/id :t_form_ms_relapse/in_relapse
             {:t_form_ms_relapse/ms_disease_course [:t_ms_disease_course/name]}]}
           {:t_encounter/form_weight_height
            [:t_form_weight_height/weight_kilogram]}]}
  (ui/ui-table-row
    computed-props
    (ui/ui-table-cell {} (ui/format-date date_time))
    (ui/ui-table-cell {} (get-in encounter_template [:t_encounter_template/project :t_project/title]))
    (ui/ui-table-cell {} (ui-encounter-template-title encounter_template))
    (ui/ui-table-cell {} (str (:t_form_edss/score form_edss)))
    (ui/ui-table-cell {} (case (:t_form_ms_relapse/in_relapse form_ms_relapse) true "Yes" false "No" ""))
    (ui/ui-table-cell {} (get-in form_ms_relapse [:t_form_ms_relapse/ms_disease_course :t_ms_disease_course/name]))
    (ui/ui-table-cell {} (some-> (:t_form_weight_height/weight_kilogram form_weight_height) (str "kg")))))
(def ui-encounter-list-item (comp/computed-factory EncounterListItem {:keyfn :t_encounter/id}))

;; ============================================================================
;; Add Encounter Workflow - Step 1: Select Project & Template
;; ============================================================================

(defsc EncounterTemplateOption
  "Encounter template option for selection dropdown"
  [this params]
  {:ident :t_encounter_template/id
   :query [:t_encounter_template/id
           :t_encounter_template/title
           :t_encounter_template/is_deleted
           :t_encounter_template/can_change_hospital
           {:t_encounter_template/project [:t_project/id :t_project/title]}]})

(defsc ProjectWithTemplates
  "Project with its encounter templates for step 1 selection"
  [this params]
  {:ident :t_project/id
   :query [:t_project/id
           :t_project/title
           {:t_project/encounter_templates (comp/get-query EncounterTemplateOption)}]})

(defsc PatientEpisode
  "Patient episode for determining suitable projects"
  [this params]
  {:ident :t_episode/id
   :query [:t_episode/id :t_episode/project_fk :t_episode/date_discharge]})

;; Mutations for add encounter workflow

(defmutation start-add-encounter
  "Start the add encounter workflow - show step 1 modal."
  [{:keys [patient-identifier]}]
  (action [{:keys [state]}]
          (swap! state assoc-in [:t_patient/patient_identifier patient-identifier :ui/adding-encounter?] true)))

(defmutation cancel-add-encounter
  "Cancel step 1 - close modal without creating encounter"
  [{:keys [patient-identifier]}]
  (action [{:keys [state]}]
          (swap! state
                 (fn [s]
                   (-> s
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/adding-encounter?] false)
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-project-id] nil)
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-template] nil)
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-filter] nil))))))

(defmutation select-project
  "Select a project in step 1. Loads encounter templates for the project."
  [{:keys [patient-identifier project-id]}]
  (action [{:keys [state app]}]
          (swap! state
                 (fn [s]
                   (-> s
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-project-id] project-id)
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-template] nil))))
          ;; Load encounter templates for this project
          (df/load! app [:t_project/id project-id] ProjectWithTemplates)))

(defmutation select-template
  "Select an encounter template in step 1"
  [{:keys [patient-identifier template]}]
  (action [{:keys [state]}]
          (swap! state assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-template] template)))

(defmutation select-filter
  "Select project filter in step 1 (suitable vs all projects)"
  [{:keys [patient-identifier filter]}]
  (action [{:keys [state]}]
          (swap! state
                 (fn [s]
                   (-> s
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-filter] filter)
                       ;; Clear selected project and template when filter changes
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-project-id] nil)
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-template] nil))))))

(defn proceed-to-step2*
  "Create a temp encounter and transition to step 2"
  [state patient-identifier patient-id template date-birth]
  (let [temp-id (tempid/tempid)
        encounter {:t_encounter/id                    temp-id
                   :t_encounter/patient_fk            patient-id
                   :t_encounter/encounter_template_fk (:t_encounter_template/id template)
                   :t_encounter/encounter_template    template
                   :t_encounter/date_time             (DateTime.)
                   :t_encounter/active                true ;; new encounters are active
                   :t_encounter/patient               {:t_patient/patient_identifier patient-identifier
                                                       :t_patient/date_birth         date-birth}
                   :ui/select-hospital                (comp/get-initial-state ods/SelectOrg {:id (str "new-encounter-hospital-" temp-id)})
                   :ui/select-consultant              (comp/get-initial-state select-user/SelectUser {:id (str "new-encounter-consultant-" temp-id)})
                   :ui/select-users                   (comp/get-initial-state select-user/SelectUsers {:id (str "new-encounter-users-" temp-id)})}]
    (-> state
        (assoc-in [:t_encounter/id temp-id] encounter)
        (assoc-in [:t_patient/patient_identifier patient-identifier :ui/adding-encounter?] false)
        (assoc-in [:t_patient/patient_identifier patient-identifier :ui/creating-encounter] [:t_encounter/id temp-id])
        (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-project-id] nil)
        (assoc-in [:t_patient/patient_identifier patient-identifier :ui/selected-template] nil)
        (fs/add-form-config* encounter/EditEncounter [:t_encounter/id temp-id]))))

(defmutation proceed-to-step2
  "Proceed from step 1 to step 2 - create temp encounter"
  [{:keys [patient-identifier patient-id template date-birth]}]
  (action [{:keys [state]}]
          (swap! state proceed-to-step2* patient-identifier patient-id template date-birth)))

(defn cancel-create-encounter*
  "Cancel step 2 - remove temp encounter from state"
  [state patient-identifier encounter-id]
  (-> state
      (update :t_encounter/id dissoc encounter-id)
      (assoc-in [:t_patient/patient_identifier patient-identifier :ui/creating-encounter] nil)))

(defmutation cancel-create-encounter
  "Cancel creating a new encounter - remove temp encounter from state"
  [{:keys [patient-identifier encounter-id]}]
  (action [{:keys [state]}]
          (swap! state cancel-create-encounter* patient-identifier encounter-id)))

(defmutation load-project-templates
  "Load encounter templates for a project if not already loaded."
  [{:keys [project-id]}]
  (action [{:keys [state app]}]
          (when project-id
            (let [project (get-in @state [:t_project/id project-id])
                  has-templates? (contains? project :t_project/encounter_templates)]
              (when-not has-templates?
                (df/load! app [:t_project/id project-id] ProjectWithTemplates))))))

;; Step 1 Modal Component
(defsc AddEncounterStep1
  "Step 1 of add encounter: Select project and encounter template.
  Includes a filter dropdown to switch between 'Suitable projects' (intersection of
  patient's active episodes and user's projects) and 'My projects' (all user's projects)."
  [this {:keys [patient-identifier patient-id date-birth episodes user-projects
                selected-project-id selected-template selected-filter]
         :or   {selected-filter :suitable}}]
  (let [;; Get patient's active project IDs (episodes with no discharge date)
        active-episode-project-ids (->> episodes
                                        (remove :t_episode/date_discharge)
                                        (map :t_episode/project_fk)
                                        set)
        ;; Calculate suitable projects (intersection of user's projects AND patient's active episodes)
        suitable-projects (->> user-projects
                               (filter #(active-episode-project-ids (:t_project/id %)))
                               (sort-by :t_project/title))
        ;; Apply filter to get displayed projects
        filtered-projects (case selected-filter
                            :suitable suitable-projects
                            :all (sort-by :t_project/title user-projects)
                            suitable-projects)
        ;; Filter options for dropdown
        filter-options (cond-> []
                         (seq suitable-projects) (conj {:id :suitable :label "Suitable projects"})
                         (seq user-projects) (conj {:id :all :label "My projects"}))
        ;; Get selected project or first available from filtered list
        project-ids (set (map :t_project/id filtered-projects))
        project-id (or (when (and selected-project-id (project-ids selected-project-id)) selected-project-id)
                       (:t_project/id (first filtered-projects)))
        selected-project (first (filter #(= project-id (:t_project/id %)) filtered-projects))
        ;; Get templates for selected project
        templates (->> (:t_project/encounter_templates selected-project)
                       (remove :t_encounter_template/is_deleted)
                       (sort-by :t_encounter_template/title))
        has-templates? (seq templates)
        templates-loading? (and project-id (not (contains? selected-project :t_project/encounter_templates)))
        can-proceed? (and has-templates? (some? selected-template))]
    ;; Trigger load for templates if needed (on first render or when project changes)
    (when templates-loading?
      (comp/transact! this [(load-project-templates {:project-id project-id})]))
    (ui/ui-modal
      {:title   "Add encounter"
       :actions [{:id       :cancel
                  :title    "Cancel"
                  :onClick  #(comp/transact! this [(cancel-add-encounter {:patient-identifier patient-identifier})])}
                 {:id        :next
                  :title     "Next »"
                  :role      :primary
                  :disabled? (not can-proceed?)
                  :onClick   #(comp/transact! this [(proceed-to-step2 {:patient-identifier patient-identifier
                                                                       :patient-id         patient-id
                                                                       :date-birth         date-birth
                                                                       :template           selected-template})])}]
       :onClose :cancel}
      (ui/ui-simple-form
        {}
        ;; Filter dropdown (only shown if more than one option)
        (when (> (count filter-options) 1)
          (ui/ui-simple-form-item
            {:label "Filter"}
            (ui/ui-select-popup-button
              {:name        "project-filter"
               :value       (first (filter #(= selected-filter (:id %)) filter-options))
               :options     filter-options
               :id-key      :id
               :display-key :label
               :sort?       false
               :onChange    #(comp/transact! this [(select-filter {:patient-identifier patient-identifier
                                                                   :filter             (:id %)})])})))
        ;; Project dropdown
        (ui/ui-simple-form-item
          {:label "Project"}
          (if (seq filtered-projects)
            (ui/ui-select-popup-button
              {:name        "project-id"
               :value       selected-project
               :options     filtered-projects
               :id-key      :t_project/id
               :display-key :t_project/title
               :sort?       false
               :onChange    #(comp/transact! this [(select-project {:patient-identifier patient-identifier
                                                                    :project-id         (:t_project/id %)})])})
            (ui/box-error-message
              {:title   "No projects available"
               :message (if (seq user-projects)
                          "Patient has no active registrations in projects you have access to."
                          "You do not have access to any projects.")})))
        ;; Encounter type selection
        (ui/ui-simple-form-item
          {:label "Encounter type"}
          (cond
            templates-loading?
            (ui/ui-loading {})

            (not has-templates?)
            (ui/box-error-message
              {:title   "No encounter types"
               :message "This project does not have any encounter templates configured."})

            :else
            (ui/ui-select-popup-button
              {:name               "encounter-template"
               :value              selected-template
               :options            templates
               :id-key             :t_encounter_template/id
               :display-key        :t_encounter_template/title
               :no-selection-string "Select encounter type..."
               :sort?              false
               :onChange           #(comp/transact! this [(select-template {:patient-identifier patient-identifier
                                                                            :template           %})])})))))))

(def ui-add-encounter-step1 (comp/factory AddEncounterStep1))

(defmutation set-encounter-field
  "Set a single field on an encounter"
  [{:keys [encounter-id field value]}]
  (action [{:keys [state]}]
          (swap! state assoc-in [:t_encounter/id encounter-id field] value)))

;; Step 2 Modal uses EditEncounter* from encounter.cljs
(defn ui-add-encounter-step2
  "Step 2 of add encounter: Edit encounter details"
  [this patient-identifier encounter]
  (let [encounter-id (:t_encounter/id encounter)]
    (ui/ui-modal
      {:actions [{:id      :cancel
                  :title   "Cancel"
                  :onClick #(comp/transact! this [(cancel-create-encounter {:patient-identifier patient-identifier
                                                                            :encounter-id       encounter-id})])}
                 {:id      :save
                  :title   "Create"
                  :role    :primary
                  :onClick #(comp/transact! this [(encounter/save-encounter {:encounter-id       encounter-id
                                                                             :patient-identifier patient-identifier
                                                                             :mode               :create})])}]
       :onClose :cancel}
      (encounter/ui-edit-encounter*
        encounter
        {:onChange (fn [k v]
                     (case k
                       :t_encounter/users
                       (comp/transact! this [(encounter/set-encounter-users {:encounter-id encounter-id :users v})])
                       :t_encounter/consultant_user
                       (comp/transact! this [(encounter/set-encounter-consultant {:encounter-id encounter-id :user v})])
                       ;; Default: use mutation for simple fields
                       (comp/transact! this [(set-encounter-field {:encounter-id encounter-id
                                                                   :field        k
                                                                   :value        v})])))}))))

(defsc PatientEncounters
  [this {:t_patient/keys [patient_identifier id date_birth encounters episodes permissions]
         :>/keys         [layout]
         :ui/keys        [adding-encounter? creating-encounter selected-project-id selected-template selected-filter]
         :keys           [session/authenticated-user]}]
  {:ident         :t_patient/patient_identifier
   :route-segment ["pt" :t_patient/patient_identifier "encounters"]
   :query         [:t_patient/patient_identifier
                   :t_patient/id
                   :t_patient/date_birth
                   :t_patient/permissions
                   {:>/layout (comp/get-query patients/Layout)}
                   {:t_patient/encounters (comp/get-query EncounterListItem)}
                   {:t_patient/episodes (comp/get-query PatientEpisode)}
                   :ui/adding-encounter?
                   {:ui/creating-encounter (comp/get-query encounter/EditEncounter)}
                   :ui/selected-project-id
                   :ui/selected-template
                   :ui/selected-filter
                   {[:session/authenticated-user '_]
                    [{:t_user/active_projects (comp/get-query ProjectWithTemplates)}]}]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier]}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (df/load! app [:t_patient/patient_identifier patient-identifier] PatientEncounters
                                {:target [:ui/current-patient]
                                 :marker :patient})
                      (dr/route-immediate [:t_patient/patient_identifier patient-identifier])))}
  (let [can-edit? (get permissions :PATIENT_EDIT)
        user-projects (:t_user/active_projects authenticated-user)]
    (comp/fragment
      ;; Step 1 Modal: Select project and template
      (when adding-encounter?
        (ui-add-encounter-step1
          {:patient-identifier  patient_identifier
           :patient-id          id
           :date-birth          date_birth
           :episodes            episodes
           :user-projects       user-projects
           :selected-project-id selected-project-id
           :selected-template   selected-template
           :selected-filter     (or selected-filter :suitable)}))

      ;; Step 2 Modal: Edit encounter details
      ;; creating-encounter is the actual encounter data (merged via query), not an ident
      (when creating-encounter
        (ui-add-encounter-step2 this patient_identifier creating-encounter))

      ;; Main content
      (patients/ui-layout
        layout
        {:selected-id :encounters
         :sub-menu    (when can-edit?
                        [{:id      ::add
                          :onClick #(comp/transact! this [(start-add-encounter {:patient-identifier patient_identifier})])
                          :content "Add encounter..."}])}
        (ui/ui-table
          {}
          (ui/ui-table-head
            {}
            (ui/ui-table-row
              {}
              (map #(ui/ui-table-heading {:react-key %} %) ["Date/time" "Project/service" "Type" "EDSS" "In relapse?" "Disease course" "Weight"])))
          (ui/ui-table-body
            {}
            (for [{:t_encounter/keys [id] :as encounter} (filter :t_encounter/active encounters)]
              (ui-encounter-list-item
                encounter
                {:onClick #(route/route-to! ::route/encounter {:encounter-id id})
                 :classes ["cursor-pointer" "hover:bg-gray-200"]}))))))))
