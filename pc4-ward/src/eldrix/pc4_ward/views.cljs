(ns eldrix.pc4-ward.views
    (:require
     [re-frame.core :as rf]
     [eldrix.pc4-ward.subs :as subs]
     ))

(defn main-panel []
  (let [name (rf/subscribe [::subs/name])]
    [:div
     [:h1 "Hello from " @name]
     [:button.button {:class    ["is-primary"]
                      :on-click #(rf/dispatch [:user/user-login-do "cymru.nhs.uk" "username" "password"])} " Login "]
     ]))
