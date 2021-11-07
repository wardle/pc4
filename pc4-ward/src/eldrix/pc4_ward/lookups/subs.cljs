(ns eldrix.pc4-ward.lookups.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::all-ms-diagnoses
  (fn [db]
    (get-in db [:lookups :com.eldrix.rsdb/all-ms-diagnoses])))