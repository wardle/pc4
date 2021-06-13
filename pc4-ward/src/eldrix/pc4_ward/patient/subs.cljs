(ns eldrix.pc4-ward.patient.subs
  "Subscriptions relating to patients."
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::current
  (fn [db]
    (get-in db [:patient/current :patient])))

(rf/reg-sub ::search-results
            (fn [db]
              (:patient/search-results db)))

