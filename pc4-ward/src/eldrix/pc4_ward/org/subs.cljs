(ns eldrix.pc4-ward.org.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::search-results
  (fn [db [_ id]]
    (get-in db [:organization/search-results id])))
