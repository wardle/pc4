(ns eldrix.pc4-ward.subs
    (:require [re-frame.core :as rf]))

(rf/reg-sub
 ::name
 (fn [db]
   (:name db)))

(rf/reg-sub ::active-panel
  (fn [db]
    (:active-panel db)))

(rf/reg-sub ::current-time
  (fn [db]
    (:current-time db)))