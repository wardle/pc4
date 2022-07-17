(ns pc4.users
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div span li p]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [pc4.app :refer [SPA]]
    [pc4.session :as session]
    [pc4.route :as route]))


(defmutation login
  "Performs a login action. If the server responds successfully (200) but there
  is no token, then we have invalid credentials."
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing login action" params)
          (js/console.log "state:" (get-in @state [:component/id :login]))
          (swap! state #(-> %
                            (update-in [:component/id :login] dissoc :ui/error)
                            (assoc-in [:component/id :login :ui/loading?] true)))
          (js/console.log "state2:" (get-in @state [:component/id :login])))

  (login [env]
         (js/console.log "Sending login action to remote" env)
         (-> env
             (m/returning 'pc4.ui.users/UserHomePage)
             (m/with-target [:session/authenticated-user])))
  (ok-action [{:keys [result state] :as env}]
             (swap! state assoc-in [:component/id :login :ui/loading?] false)
             (let [token (get-in result [:body 'pc4.users/login :io.jwt/token])]
               (js/console.log "response from remote: " result)
               (if token (swap! state update-in [:component/id :login] dissoc :ui/error)
                         (swap! state assoc-in [:component/id :login :ui/error] "Invalid username or password"))
               (reset! session/authentication-token token)
               (route/route-to! ["home"])))
  (error-action [{:keys [state]}]
                (swap! state assoc-in [:component/id :login :ui/loading?] false)))

(defmutation logout
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing logout action" params)
          (swap! state dissoc :session/authenticated-user :session/error)
          (when (:message params)
            (swap! state assoc :session/error (:message params)))
          (route/route-to! ["home"])
          (reset! session/authentication-token nil)))

(defmutation refresh-token
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing refresh token"))
  (remote [env] true)
  (ok-action [{:keys [result] :as env}]
             (let [token (get-in result [:body 'pc4.users/refresh-token :io.jwt/token])]
               (reset! session/authentication-token token))))

(defmutation open-project [{:t_project/keys [id]}]
  (action [{:keys [state]}]
          (js/console.log "Opening project " id)
          (swap! state assoc :session/current-project [:t_project/id id])))

(defmutation close-project [params]
  (action [{:keys [state]}]
          (swap! state dissoc :session/current-project)))
