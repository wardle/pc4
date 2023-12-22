(ns pc4.project.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::search-by-legacy-pseudonym-result
  (fn [db _]
    (:patient/search-legacy-pseudonym db)))

(rf/reg-sub ::register-patient-error
  (fn [db _]
    (get-in db [:errors :open-patient])))
