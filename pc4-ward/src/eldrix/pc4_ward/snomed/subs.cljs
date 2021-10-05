(ns eldrix.pc4-ward.snomed.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::search-results
  (fn [db [_ id]]
    (get-in db [:snomed/search-results id :results])))
