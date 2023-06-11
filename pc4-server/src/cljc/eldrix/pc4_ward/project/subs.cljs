(ns eldrix.pc4-ward.project.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::current
  (fn [db _]
    (:project/current db)))


(rf/reg-sub ::active-encounter-templates
  (fn []
    (rf/subscribe [::current]))
  (fn [current-project]
    (->> (:t_project/encounter_templates current-project)
         (remove :t_encounter_template/is_deleted))))

(rf/reg-sub ::default-encounter-template
  (fn []
    (rf/subscribe [::active-encounter-templates]))
  (fn [encounter-templates]
    (->> encounter-templates
         (sort-by :t_encounter_template/title)
         first)))
