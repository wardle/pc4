(ns eldrix.pc4-ward.users
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.db :as db]
            [eldrix.pc4-ward.server :as srv]))

(rf/reg-event-fx
  ::do-login []
  (fn [{db :db} [_ namespace username password]]
    (js/console.log "performing login " username)
    {:db         (update-in db [:errors] dissoc ::login)
     :http-xhrio (srv/make-xhrio-request {:params     (srv/make-login-op {:system namespace :value username :password password})
                                          :on-success [::handle-login-response]
                                          :on-failure [::handle-login-failure]})}))

;; Login success simply means the http request worked.
;; The pc4.users/login operation returns a user if the login was successful.
(rf/reg-event-fx
  ::handle-login-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [login]}]]
    (js/console.log "user login transaction: response: " login)
    (if login
      {:db (assoc db :authenticated-user login)}
      {:db (assoc-in db [:errors ::login] "Incorrect username or password")})))

(rf/reg-event-fx
  ::handle-login-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "User login failure: response " response)
    {:db (dissoc db :authenticated-user)}))

(rf/reg-event-db
  ::expire-session
  []
  (fn [_ [_]]
    (-> db/default-db
        (assoc-in [:errors ::login] "Your session expired. Please login again"))))

(rf/reg-event-db
  ::do-logout
  []
  (fn [db [_ user]]
    (js/console.log "Logging out user" user)
    db/default-db))

(rf/reg-event-fx
  ::refresh-token
  []
  (fn [{:keys [db]} [_ token]]
    (js/console.log "performing token refresh" token)
    {:http-xhrio (srv/make-xhrio-request {:token      token
                                          :params     (srv/make-refresh-token-op token)
                                          :on-success [::handle-refresh-token-response]
                                          :on-failure [::handle-refresh-token-failure]})}))

(rf/reg-event-fx
  ::handle-refresh-token-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [refresh-token]}]]
    (js/console.log "Refresh token success: response: " refresh-token)
    {:db (assoc-in db [:authenticated-user :io.jwt/token] refresh-token)}))

(rf/reg-event-fx
  ::handle-refresh-token-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "User token refresh failure: response " response)))

(rf/reg-sub
  ::authenticated-user
  (fn [db]
    (:authenticated-user db)))

(rf/reg-sub
  ::login-error
  (fn [db]
    (get-in db [:errors ::login])))

(rf/reg-sub
  ::active-panel
  (fn [db]
    (:active-panel db)))

(comment
  (make-login-op {:system "cymru.nhs.uk" :value "ma090906" :password "password"})
  (rf/dispatch [::do-login "cymru.nhs.uk" "ma090906" "password"])
  (rf/dispatch [::do-logout])
  )
