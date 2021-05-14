(ns eldrix.pc4-ward.subs
    (:require
     [re-frame.core :as rf]))

(rf/reg-sub
 ::name
 (fn [db]
   (:name db)))

