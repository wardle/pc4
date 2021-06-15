(ns eldrix.pc4-ward.user.events
  "Events relating to users:
  * Login and logout
  * Session timeout
  * Token management, including refresh"
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.db :as db]
            [eldrix.pc4-ward.server :as srv]))

(rf/reg-event-fx
  ::do-login []
  (fn [{db :db} [_ namespace username password]]
    (js/console.log "performing login " username)
    {:db (update-in db [:errors] dissoc :user/login)
     :fx [[:http-xhrio (srv/make-xhrio-request {:params     (srv/make-login-op {:system namespace :value username :password password})
                                                :on-success [::handle-login-response]
                                                :on-failure [::handle-login-failure]})]]}))

;; Login success simply means the http request worked.
;; The pc4.users/login operation returns a user if the login was successful.
(rf/reg-event-fx ::handle-login-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [login]}]]
    (js/console.log "user login transaction: response: " login)
    (if login
      {:db (assoc db :authenticated-user {:io.jwt/token (:io.jwt/token login)
                                          :practitioner (dissoc login :io.jwt/token)})}
      {:db (assoc-in db [:errors :user/login] "Incorrect username or password")})))

(rf/reg-event-fx ::handle-login-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "User login failure: response " response)
    {:db (-> db
             (dissoc :authenticated-user)
             (assoc-in [:errors :user/login] "Failed to login: unable to connect to server. Please check your connection and retry."))}))

(rf/reg-event-db ::do-session-expire
  []
  (fn [_ [_]]
    (-> db/default-db
        (assoc-in [:errors :user/login] "Your session expired. Please login again"))))

(rf/reg-event-db ::do-logout
  []
  (fn [_db [_ user]]
    (js/console.log "Logging out user" user)
    db/default-db))

(rf/reg-event-fx ::ping-server
  []
  (fn [_ _]
    {:http-xhrio
     (srv/make-xhrio-request {:params     (srv/make-ping-op {:uuid (random-uuid)})
                              :on-success [::handle-ping-response]
                              :on-failure [::handle-ping-failure]
                              :timeout    1000})}))

(rf/reg-event-fx ::handle-ping-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [ping]}]]
    (js/console.log "Ping success: response: " ping)
    {:db (update-in db [:errors] dissoc ::ping)}))

(rf/reg-event-fx ::handle-ping-failure
  (fn [{db :db} response]
    (js/console.log "Ping failure :" response)
    {:db (assoc-in db [:errors :ping] response)}))

(rf/reg-event-fx ::refresh-token
  []
  (fn [{:keys [db]} [_ token]]
    (js/console.log "performing token refresh" token)
    {:fx [[:http-xhrio (srv/make-xhrio-request {:token      token
                                                :params     (srv/make-refresh-token-op token)
                                                :on-success [::handle-refresh-token-response]
                                                :on-failure [::handle-refresh-token-failure]})]]}))

(rf/reg-event-fx ::handle-refresh-token-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [refresh-token]}]]
    (js/console.log "Refresh token success: response: " refresh-token)
    {:db (assoc-in db [:authenticated-user :io.jwt/token] refresh-token)}))

(rf/reg-event-fx ::handle-refresh-token-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "User token refresh failure: response " response)))

(rf/reg-event-fx
  ::check-token
  (fn [{db :db} [_]]
    (cond
      ;; no authenticated user -> explicitly do nothing... without this, the next condition will be thrown
      (nil? (:authenticated-user db))
      (js/console.log "no current logged in user")

      ;; user token expired - we have to force end to our session
      (srv/jwt-expires-in-seconds? (get-in db [:authenticated-user :io.jwt/token]) 0)
      (do (js/console.log "session expired for user" (get-in db [:authenticated-user :practitioner :urn.oid.1.2.840.113556.1.4/sAMAccountName]))
          {:fx [[:dispatch [::do-session-expire]]]})

      ;; user token expiring soon - we still have chance to refresh our token without needing to ask for credentials again
      (srv/jwt-expires-in-seconds? (get-in db [:authenticated-user :io.jwt/token]) 90)
      (do (js/console.log "session expiring in " (srv/jwt-expires-seconds (get-in db [:authenticated-user :io.jwt/token])) " seconds; refreshing token")
          {:fx [[:dispatch [::refresh-token {:token (get-in db [:authenticated-user :io.jwt/token])}]]]})

      ;; we have an active session
      :else (js/console.log "active session; token expires in " (srv/jwt-expires-seconds (get-in db [:authenticated-user :io.jwt/token])) "seconds"))))
