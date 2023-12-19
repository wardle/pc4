(ns pc4.users
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div span li p]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [pc4.session :as session]))


(defmutation login
  "Performs a login action. If the server responds successfully (200) but there
  is no token, then we have invalid credentials."
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing login action" params)
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
             (let [token (get-in result [:body 'pc4.users/login :io.jwt/token])]
               (js/console.log "response from remote: " result)
               (if token (swap! state update-in [:component/id :login] dissoc :ui/error)
                         (swap! state assoc-in [:component/id :login :ui/error] "Invalid username or password"))
               (reset! session/authentication-token token)
               (dr/change-route! app ["home"])))
  (error-action [{:keys [state] :as env}]
                (swap! state #(-> %
                                  (assoc-in [:component/id :login :ui/loading?] false)
                                  (assoc-in [:component/id :login :ui/error] "Network error. Please try again.")))))

(defmutation logout
  [{:keys [message]}]
  (action [{:keys [app state]}]
          (js/console.log "Performing logout action" message)
          #_(dr/change-route! app ["home"])
          #_(reset! session/authentication-token nil)
          #_(swap! state (fn [s]
                           (cond-> (assoc s :session/authenticated-user {})
                                   message
                                   (assoc-in [:component/id :login :ui/error] message))))
          (.reload js/window.location true)))

(defmutation refresh-token
  [_]
  (action [{:keys [state]}]
          (js/console.log "Performing refresh token"))
  (remote [env] true)
  (ok-action [{:keys [result] :as env}]
             (let [token (get-in result [:body 'pc4.users/refresh-token :io.jwt/token])]
               (reset! session/authentication-token token))))

(defmutation open-project [{:t_project/keys [id]}]
  (action [{:keys [state]}]
          (js/console.log "Opening project " id)
          (swap! state assoc :ui/current-project [:t_project/id id])))

(defmutation close-project [params]
  (action [{:keys [state]}]
          (swap! state dissoc :ui/current-project)))

(defmutation close-patient [params]
  (action [{:keys [state]}]
          (swap! state dissoc :ui/current-patient)))