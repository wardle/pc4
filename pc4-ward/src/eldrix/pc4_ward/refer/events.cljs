(ns eldrix.pc4-ward.refer.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-db ::update-referral
  []
  (fn [db [_ referral]]
    (assoc-in db [:patient/current :referral] referral)))

(rf/reg-event-db ::set-stage
  []
  (fn [db [_ stage]]
    (assoc-in db [:patient/current :referral :current-stage] stage)))