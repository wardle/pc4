(ns pc4.users
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div span li p]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]))


(defmutation perform-login
  "Performs a login action. If the server responds successfully (200) but there
  is no token, then we have invalid credentials."
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing login action" (assoc params :password "******"))
          (swap! state #(-> %
                            (update-in [:component/id :login] dissoc :ui/error)
                            (assoc-in [:component/id :login :ui/loading?] true))))

  (login [env]
         (js/console.log "Sending login action to remote" env)
         (-> env
             (m/returning 'pc4.ui.users/UserHomePage)
             (m/with-target [:session/authenticated-user])))
  (ok-action [{:keys [result state app] :as env}]
             (swap! state assoc-in [:component/id :login :ui/loading?] false)
             (let [token (get-in result [:body 'pc4.users/perform-login :io.jwt/token])]
               (js/console.log "response from remote: " result)
               (if token (swap! state update-in [:component/id :login] dissoc :ui/error)
                         (swap! state assoc-in [:component/id :login :ui/error] "Invalid username or password"))
               (dr/change-route! app ["home"])))
  (error-action [{:keys [state] :as env}]
                (swap! state #(-> %
                                  (assoc-in [:component/id :login :ui/loading?] false)
                                  (assoc-in [:component/id :login :ui/error] "Network error. Please try again.")))))

(defmutation logout
  [{:keys [message]}]
  (remote [_] true)
  (ok-action [{:keys [app state]}]
             (js/console.log "Performing logout action" message)
             (dr/change-route! app ["home"])
             (swap! state (fn [s]
                            (cond-> (assoc s :session/authenticated-user {})
                                    message
                                    (assoc-in [:component/id :login :ui/error] message))))
             (.reload js/window.location true)))

