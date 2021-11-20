(ns pc4.users
  (:require [com.fulcrologic.fulcro.mutations :refer [defmutation]]
            [taoensso.timbre :as log]
            [pc4.session :as session]))


(defmutation login
  "Performs a login action. If the server responds successfully (200) but there
  is no token, then we have invalid credentials."
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing login action" params))
  (login [env]
         (js/console.log "Sending login action to remote" env)
         env)
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
