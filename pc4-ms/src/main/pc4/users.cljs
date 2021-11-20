(ns pc4.users
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [taoensso.timbre :as log]
    [pc4.session :as session]
    [com.fulcrologic.fulcro.dom :as dom]))


(defsc User [this {:t_user/keys [id first_names last_name] :as user token :io.jwt/token}]
  {:query [:t_user/id :t_user/first_names :t_user/last_name :t_user/projects :io.jwt/token]
   :ident :t_user/id}
  (when user
    (dom/div
      (dom/p "User " id " " first_names " " last_name))))

(def ui-user (comp/factory User))

(defmutation login
  "Performs a login action. If the server responds successfully (200) but there
  is no token, then we have invalid credentials."
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing login action" params))
  (login [env]
         (js/console.log "Sending login action to remote" env)
         (-> env
             (m/with-target [:authenticated-user])
             (m/returning User)))
  (ok-action [{:keys [result] :as env}]
             (let [token (get-in result [:body 'pc4.users/login :io.jwt/token])]
               (js/console.log "success from remote: " token)
               (reset! session/authentication-token token))))

(defmutation refresh-token
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing refresh token"))
  (remote [env] true)
  (ok-action [{:keys [result] :as env}]
             (let [token (get-in result [:body 'pc4.users/refresh-token :io.jwt/token])]
               (reset! session/authentication-token token))))
