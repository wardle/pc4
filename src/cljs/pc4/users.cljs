(ns pc4.users
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div span li p]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [taoensso.timbre :as log]))


(defn update-login-state*
  [state {:keys [error loading]}]
  (-> state
      (assoc-in [:component/id :login :ui/error] error)
      (assoc-in [:component/id :login :ui/loading?] loading)))

(defmutation perform-login
  "Performs a login action. If the server responds successfully (200) but there
  is no user, then we have invalid credentials."
  [params]
  (action [{:keys [state]}]
          (log/info "Performing login action" (assoc params :password "******"))
          (swap! state update-login-state* {:error nil :loading false}))

  (login [env]
         (js/console.log "Sending login action to remote" env)
         (-> env
             (m/returning 'pc4.ui.users/UserHomePage)
             (m/with-target [:session/authenticated-user])))
  (ok-action [{:keys [result state app] :as env}]
             (let [user-id (get-in result [:body 'pc4.users/perform-login :t_user/id])]
               (log/debug "response from remote: " result)
               (swap! state update-login-state* {:error (when-not user-id "Invalid username or password") :loading false})
               #_(dr/change-route! app ["home"])))
  (error-action [{:keys [state] :as env}]
                (swap! state #(-> %
                                  (assoc-in [:component/id :login :ui/loading?] false)
                                  (assoc-in [:component/id :login :ui/error] "Network error. Please try again.")))))

(defmutation logout
  [{:keys [message]}]
  (ok-action [{:keys [app state]}]
             (js/console.log "Performing logout action" message)
             (swap! state (fn [s]
                            (cond-> (assoc s :session/authenticated-user {})
                                    message
                                    (assoc-in [:component/id :login :ui/error] message))))
             (pc4.route/route-to! :home)
             #_(dr/change-route! app ["login"])
             (.reload js/window.location true))
  (error-action [_]
                (.reload js/window.location true))
  (remote [_] true))

