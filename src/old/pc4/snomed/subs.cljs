(ns pc4.snomed.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::search-results
  (fn [db [_ id]]
    (get-in db [:snomed/search-results id :results])))

(rf/reg-sub ::fetch-concept-result
  (fn [db [_ id]]
    (get-in db [:snomed/fetch-concept-result id])))