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

(rf/reg-sub ::current-result
  (fn [db _]
    (get-in db [:patient/current :current-result])))

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

(rf/reg-sub ::active-encounters
  (fn [_]
    (rf/subscribe [::encounters]))
  (fn [encounters]
    (->> encounters
         (sort-by #(if-let [date (:t_encounter/date_time %)] (.valueOf date) 0))
         (filter :t_encounter/active)
         reverse)))

(rf/reg-sub ::most-recent-edss-encounter
  (fn [_]
    (rf/subscribe [::active-encounters]))
  (fn [encounters]
    (->> encounters
         (filter #(or (:t_encounter/form_edss %) (:t_encounter/form_edss_fs %)))
         reverse
         first)))

(rf/reg-sub ::results
  (fn [_]
    (rf/subscribe [::current]))
  (fn [patient]
    (->> (:t_patient/results patient)
         (sort-by #(if-let [date (:t_result/date %)] (.valueOf date) 0))
         reverse)))

(rf/reg-sub ::results-mri-brains
  (fn [[_ {:keys [before-date]}]]
    (rf/subscribe [::results]))
  (fn [results [_ {:keys [before-date]}]]
    (let [scans (filter #(= "ResultMriBrain" (:t_result_type/result_entity_name %)) results)]
      (if-not before-date
        scans
        (filter #(pos? (Date/compare before-date (:t_result/date %))) scans)))))

(rf/reg-sub ::episodes
  (fn [_]
    (rf/subscribe [::current]))
  (fn [patient]
    (->> (:t_patient/episodes patient)
         (sort-by #(if-let [date (:t_episode/date_registration %)] (.valueOf date) 0))
         reverse)))

;; TODO: don't use a hardcoded project name here, but create an episode type,
;; or better still, project type, reflecting an inpatient stay. That then means
;; we can determine whether encounters, episodes etc.. are related to inpatient
;; stays or not
(rf/reg-sub ::admission-episodes
  (fn [_]
    (rf/subscribe [::episodes]))
  (fn [episodes]
    (filter #(= "ADMISSION" (get-in % [:t_episode/project :t_project/name])) episodes))) ;;; TODO: this is hacky - hardcoded project NAME!

