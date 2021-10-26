(ns eldrix.pc4-ward.project.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::current
  (fn [db _]
    (:project/current db)))
