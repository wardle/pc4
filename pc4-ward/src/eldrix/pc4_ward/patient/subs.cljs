(ns eldrix.pc4-ward.patient.subs
  "Subscriptions relating to patients."
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::current
  (fn [db]
    (get-in db [:patient/current :patient])))

(rf/reg-sub ::search-results
  (fn [db]
    (:patient/search-results db)))

(rf/reg-sub ::search-by-legacy-pseudonym-result
  (fn [db]
    (:patient/search-legacy-pseudonym db)))

(rf/reg-sub ::open-patient-error
  (fn [db]
    (get-in db [:errors :open-patient])))

;; return the hospital in which the patient is currently admitted
;; as our backend services do not yet know this information, we return nil
(rf/reg-sub ::hospital
  (fn [db]
    (get-in db [:patient/current :hospital])))

;; is the patient record loading data?
(rf/reg-sub ::loading?
  (fn [db]
    (get-in db [:patient/current :loading])))

