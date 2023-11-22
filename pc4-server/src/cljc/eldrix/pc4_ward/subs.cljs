(ns eldrix.pc4-ward.subs
    (:require [re-frame.core :as rf]))

(rf/reg-sub ::current-route
  (fn [db]
    (:current-route db)))

(rf/reg-sub ::loading
  (fn [db]
    (:loading db)))

(rf/reg-sub ::current-time
  (fn [db]
    (:current-time db)))


(rf/reg-sub ::modal
  (fn [db [_ k]]
    (get-in db [:modal k])))
