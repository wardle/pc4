(ns eldrix.pc4-ward.events
    (:require
     [re-frame.core :as re-frame]
     [eldrix.pc4-ward.db :as db]
     ))

(re-frame/reg-event-db
 ::initialize-db
 (fn [_ _]
   db/default-db))
