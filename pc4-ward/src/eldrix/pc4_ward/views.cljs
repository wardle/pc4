(ns eldrix.pc4-ward.views
    (:require
     [re-frame.core :as re-frame]
     [eldrix.pc4-ward.subs :as subs]
     ))

(defn main-panel []
  (let [name (re-frame/subscribe [::subs/name])]
    [:div
     [:h1 "Hello from " @name]
     ]))
