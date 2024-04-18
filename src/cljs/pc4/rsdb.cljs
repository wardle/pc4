(ns pc4.rsdb
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.algorithms.merge :as merge]
            [com.fulcrologic.fulcro.components :as comp]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [pc4.route :as route]
            [pc4.ui.ninflamm]
            [pc4.ui.encounters]
            [taoensso.timbre :as log]))

(defmutation search-patient-by-pseudonym
  [{:keys [project-id pseudonym]}]
  (action [{:keys [state] :as env}]
          (when (str/blank? pseudonym)
            (swap! state update-in [:t_project/id project-id] dissoc :ui/search-patient-pseudonymous)))
  (remote [{:keys [ref] :as env}]
          (println "search" {:s pseudonym, :project-id project-id})
          (when-not (str/blank? pseudonym)
            (-> env
                (m/with-target [:t_project/id project-id :ui/search-patient-pseudonymous])
                (m/returning 'pc4.ui.patients/PatientBanner)))))

(defmutation register-patient
  [{:keys [project-id]}]
  (remote
   [env]
   (m/returning env 'pc4.ui.patients/PatientDemographics))
  (ok-action
   [{:keys [app state ref] :as env}]
   (tap> {:mutation-env env})                              ;; ref = ident of the component
   (if-let [patient-identifier (get-in env [:result :body 'pc4.rsdb/register-patient :t_patient/patient_identifier])]
     (do (log/debug "register patient : patient id: " patient-identifier)
         (comp/transact! app [(pc4.route/route-to {:handler ::route/project-patient
                                                   :params  {:project-id         project-id
                                                             :patient-identifier patient-identifier}})]))
     (do (log/debug "failed to register patient:" env)
         (swap! state update-in ref assoc :ui/error "Unable to register patient.")))))

(defmutation register-patient-by-pseudonym
  [{:keys [project-id] :as params}]
  (remote
   [env]
   (log/debug "Registering pseudonymous patient:" env)
   (m/returning env 'pc4.ui.patients/PatientDemographics))
  (ok-action
   [{:keys [app state ref] :as env}]
   (tap> {:mutation-env env})                              ;; ref = ident of the component
   (if-let [patient-identifier (get-in env [:result :body 'pc4.rsdb/register-patient-by-pseudonym :t_patient/patient_identifier])]
     (do (log/debug "register patient : patient id: " patient-identifier)
         (comp/transact! app [(pc4.route/route-to {:handler ::route/project-patient
                                                   :params  {:project-id         project-id
                                                             :patient-identifier patient-identifier}})]))

     (do (log/debug "failed to register patient:" env)
         (swap! state update-in ref assoc :ui/error "Incorrect patient demographics")))))

(defmutation save-diagnosis
  [{:t_diagnosis/keys [id] :t_patient/keys [patient_identifier] :as diagnosis}]
  (action [{:keys [state]}]
          (swap! state fs/entity->pristine* [:t_diagnosis/id id]))
  (remote [env]
          (println "save diagnosis: " (:ast env))
          true)
  (ok-action                                                ;; we simply close the modal dialog once we have confirmed the save...
   [{:keys [ref state result mutation-return-value]}]
   (tap> {:ok-save-diag {:result result :mut-ret mutation-return-value}})
   (swap! state (fn [s]
                  (assoc-in s [:t_patient/patient_identifier patient_identifier :ui/editing-diagnosis] {})))))

(defmutation save-medication
  [{:t_medication/keys [id] :t_patient/keys [patient_identifier] :as medication}]
  (action [{:keys [state]}]
          (swap! state fs/entity->pristine* [:t_medication/id id]))
  (remote [env]
          (log/info "saving medication:" (:ast env))
          true)
  (ok-action                                                ;; we simply close the modal dialog once we have confirmed the save...
   [{:keys [ref state] :as env}]
   (swap! state assoc-in [:t_patient/patient_identifier patient_identifier :ui/editing-medication] {})))

(defmutation delete-medication
  [{:t_medication/keys [id] :as medication, :t_patient/keys [patient_identifier]}]
  (remote [env] true)
  (ok-action
   [{:keys [state]}]
   (swap! state (fn [s]
                  (-> s
                      (merge/remove-ident* [:t_medication/id id] [:t_patient/patient_identifier patient_identifier :t_patient/medications])
                      (assoc-in [:t_patient/patient_identifier patient_identifier :ui/editing-medication] {}))))))

(defmutation save-form
  [{:keys [patient-identifier form class] :as params}]
  (action
   [{:keys [state]}]
   (swap! state fs/entity->pristine* [:form/id (:form/id form)]))
  (remote
   [env]
   (-> env
       (m/with-params (-> params
                          (dissoc :class)
                          (update :form dissoc ::fs/config)))
       (m/returning class)))
  (ok-action
   [{:keys [component ref state]}]
   (df/refresh! component)                                  ;; refresh encounters list page with updated results from server
   (swap! state assoc-in (conj ref :ui/editing-form) nil))) ;; close editing modal dialog

(defmutation create-admission
  [{:t_episode/keys [id] :as episode}]
  (action
   [{:keys [ref state]}]
   (let [ident [:t_episode/id id]]
     (swap! state (fn [s]
                    (-> s
                        (update-in ref assoc :ui/editing-admission episode)
                        (update-in (conj ref :t_patient/episodes) conj ident)))))))

(defmutation save-admission
  [{:t_episode/keys [id] :as params}]
  (remote
   [{:keys [ref state] :as env}]
   (m/returning env 'pc4.ui.admissions/EpisodeListItem))
  (ok-action                                                ;; once admission is saved, close modal editing form
   [{:keys [ref state] :as env}]
   (swap! state assoc-in (conj ref :ui/editing-admission) {})))

(defmutation delete-admission
  [{:t_episode/keys [id patient_fk]}]
  (remote [_]
          (println "Deleting admission" id "patient pk" patient_fk)
          true)
  (ok-action                                                ;; once admission is deleted, close modal editing form
   [{:keys [ref state mutation-return-value] :as env}]
   (when-not (:com.wsscode.pathom3.connect.runner/mutation-error mutation-return-value) ;; TODO: show an error
     (swap! state (fn [s] (-> s
                              (update :t_episode/id dissoc id)
                              (merge/remove-ident* [:t_episode/id id] (conj ref :t_patient/episodes))
                              (assoc-in (conj ref :ui/editing-admission) {})))))))

(defmutation save-ms-diagnosis
  [params]
  (action [{:keys [ref state]}]
          (let [path (conj ref :t_summary_multiple_sclerosis/ms_diagnosis)]
            (swap! state assoc-in path (select-keys params [:t_ms_diagnosis/id :t_ms_diagnosis/name]))))
  (remote [env] (returning env pc4.ui.ninflamm/PatientNeuroInflammatory)))

(defmutation save-ms-event
  [{:t_ms_event/keys [id summary_multiple_sclerosis_fk] :as ms-event :t_patient/keys [patient_identifier]}]
  (action [{:keys [ref state]}]
          (println "saving ms event; ref:" ref)
          (println "ms event " ms-event)
          (swap! state fs/entity->pristine* [:t_ms_event/id id]))
  (remote [env] true)
  (ok-action                                                ;; once ms event saved, close modal form
   [{:keys [ref state app] :as env}]
   (swap! state assoc-in [:t_patient/patient_identifier patient_identifier :ui/editing-ms-event] {})))

(defn delete-ms-event*
  [state summary-multiple-sclerosis-id ms-event-id]
  (-> state
      (update :t_ms_event/id dissoc ms-event-id)
      (merge/remove-ident* [:t_ms_event/id ms-event-id] [:t_summary_multiple_sclerosis/id summary-multiple-sclerosis-id :t_summary_multiple_sclerosis/events])))

(defmutation delete-ms-event
  [{:t_ms_event/keys [id summary_multiple_sclerosis_fk]}]
  (action [{:keys [state]}]
          (swap! state delete-ms-event* summary_multiple_sclerosis_fk id))
  (remote [env] true))

(def result-properties
  #{:t_result/id :t_result/date :t_result/summary
    :t_result_type/id :t_result_type/name :t_result_type/result_entity_name})

(defmutation save-result
  [{:keys [patient-identifier result]}]
  (action
   [{:keys [state]}]
   (swap! state fs/entity->pristine* [:t_result/id (:t_result/id result)]))
  (remote
   [{:keys [component] :as env}]
   (m/returning env (comp/get-class component)))

  (ok-action
   [{:keys [state result component mutation-return-value] :as env}]
   (tap> {:ok-save-result env})
   (swap! state update-in [:t_patient/patient_identifier patient-identifier] dissoc :ui/editing-result)))

(defn delete-result*
  [state patient-identifier result-id]
  (-> state
      (update-in [:t_patient/patient_identifier patient-identifier] dissoc :ui/editing-result)
      (update :t_result/id dissoc result-id)
      (merge/remove-ident* [:t_result/id result-id] [:t_patient/patient_identifier patient-identifier :t_patient/results])))

(defmutation delete-result
  [{:keys [patient-identifier result]}]
  (remote [env] true)
  (ok-action [{:keys [state]}]
             (swap! state delete-result* patient-identifier (:t_result/id result))))

(defmutation save-pseudonymous-patient-postal-code
  [params]
  (remote [env] true)
  (ok-action [{:keys [component]}]
             (df/refresh! component)))

(defmutation notify-death
  [{:t_patient/keys [patient_identifier date_death] :t_death_certificate/keys [id part1a part1b part1c part2]}]
  (action
   [{:keys [state]}]
   (when (nil? date_death)
     (swap! state (fn [st]
                    (-> st
                        (assoc-in [:t_patient/patient_identifier patient_identifier :t_death_certificate/part1a] nil)
                        (assoc-in [:t_patient/patient_identifier patient_identifier :t_death_certificate/part1b] nil)
                        (assoc-in [:t_patient/patient_identifier patient_identifier :t_death_certificate/part1c] nil)
                        (assoc-in [:t_patient/patient_identifier patient_identifier :t_death_certificate/part2] nil))))))
  (remote
   [env]
   (m/returning env 'pc4.ui.patients/PatientDemographics))

  (ok-action
   [{:keys [state]}]
   (swap! state (fn [st]
                  (-> st
                      (update-in [:t_patient/patient_identifier patient_identifier :ui/editing-demographics] not)
                      (assoc-in [:t_patient/patient_identifier patient_identifier :ui/change-registration-data] false))))))

(defmutation change-pseudonymous-registration
  [{:t_patient/keys [id patient_identifier nhs_number date_birth sex date_death] :as patient}]
  (action
   [env]
   (log/info "changing pseudonymous registration data" (select-keys patient [:t_patient/id :t_patient/patient_identifier :t_patient/date_birth :t_patient/date_death :t_patient/sex :t_patient/nhs_number])))
  (remote
   [env]
   (m/returning env 'pc4.ui.patients/PatientDemographics))
  (ok-action
   [{:keys [app component state]}]
   (swap! state (fn [st]
                  (-> st
                      (update-in [:t_patient/patient_identifier patient_identifier :ui/editing-demographics] not)
                      (assoc-in [:t_patient/patient_identifier patient_identifier :ui/change-registration-data] false))))
   (df/refresh! component)))

(defmutation register-patient-to-project
  [{:keys [patient project-id] :as params}]
  (action [env]
          (log/info "registering patient to project" params))
  (remote [env] true)
  (ok-action
   [{:keys [component state]}]
   (df/refresh! component)))

(defmutation break-glass
  [{:keys [patient-identifier]}]
  (action
   [env]
   (log/info "breakglass for patient" patient-identifier))
  (remote [env] true)
  (ok-action
   [{:keys [component]}]
   (df/refresh! component)))

(defmutation change-password
  [{:t_user/keys [id username password new_password]}]
  (remote [_] true)
  (ok-action
   [{:keys [result state]}]
   (if-let [err (get-in result [:body 'pc4.rsdb/change-password :com.wsscode.pathom3.connect.runner/mutation-error])]
     (swap! state #(-> % (assoc-in [:component/id :change-password :ui/error] err)))
     (do (swap! state (fn [st]
                        (-> st
                            (assoc-in [:t_user/id id :t_user/must_change_password] false))))
         (route/route-to! ::route/home))))  ;; TODO: probably should execute using comp/transact rather than direct?
  (error-action
   [{:keys [state]}]
   (swap! state #(-> % (assoc-in [:component/id :change-password :ui/error] "Error. Please try again.")))))

