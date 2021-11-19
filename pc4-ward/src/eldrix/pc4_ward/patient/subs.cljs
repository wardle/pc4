(ns eldrix.pc4-ward.patient.subs
  "Subscriptions relating to patients."
  (:require [re-frame.core :as rf])
  (:import [goog.date Date]))

(rf/reg-sub ::current
  (fn [db _]
    (get-in db [:patient/current :patient])))

(rf/reg-sub ::search-results
  (fn [db _]
    (:patient/search-results db)))

(rf/reg-sub ::search-by-legacy-pseudonym-result
  (fn [db _]
    (:patient/search-legacy-pseudonym db)))

(rf/reg-sub ::open-patient-error
  (fn [db _]
    (get-in db [:errors :open-patient])))

;; return the hospital in which the patient is currently admitted
;; as our backend services do not yet know this information, we return nil
(rf/reg-sub ::hospital
  (fn [db]
    (get-in db [:patient/current :hospital])))

;; is the patient record loading data?
(rf/reg-sub ::loading?
  (fn [db]
    (:patient/loading? db)))

(rf/reg-sub ::diagnoses
  (fn [_]
    (rf/subscribe [::current]))
  (fn [current-patient]
    (:t_patient/diagnoses current-patient)))

(rf/reg-sub ::current-diagnosis
  (fn [db]
    (get-in db [:patient/current :current-diagnosis])))

(rf/reg-sub ::medications
  (fn [_]
    (rf/subscribe [::current]))
  (fn [current-patient]
    (:t_patient/medications current-patient)))

(rf/reg-sub ::current-medication
  (fn [db _]
    (get-in db [:patient/current :current-medication])))

(rf/reg-sub ::ms-events
  (fn [_]
    (rf/subscribe [::current]))
  (fn [current-patient]
    (get-in current-patient [:t_patient/summary_multiple_sclerosis :t_summary_multiple_sclerosis/events])))

(rf/reg-sub ::encounters
  (fn [_]
    (rf/subscribe [::current]))
  (fn [current-patient]
    (:t_patient/encounters current-patient)))

(rf/reg-sub ::active-episodes
  (fn [_]
    (rf/subscribe [::current]))
  (fn [current-patient]
    (->> (:t_patient/episodes current-patient)
         (filter #(#{:registered :referred} (:t_episode/status %))))))

(rf/reg-sub ::active-episode-for-project
  (fn [_]
    (rf/subscribe [::active-episodes]))
  (fn [episodes [_ project-id]]
    (when project-id
      (first (filter #(= project-id (:t_episode/project_fk %)) episodes)))))

(rf/reg-sub ::all-edss
  (fn [_]
    (rf/subscribe [::current]))
  (fn [current-patient]
    (:t_patient/t_form_edss current-patient)))