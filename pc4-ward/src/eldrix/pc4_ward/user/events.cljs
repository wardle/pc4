(ns eldrix.pc4-ward.user.events
  "Events relating to users:
  * Login and logout
  * Session timeout
  * Token management, including refresh"
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.config]
            [eldrix.pc4-ward.db :as db]
            [eldrix.pc4-ward.server :as srv]
            [ajax.transit :as ajax-transit]
            [com.eldrix.pc4.commons.dates :as dates]))

(def user-query
  [:t_user/username
   :t_user/must_change_password
   :urn:oid:1.2.840.113556.1.4/sAMAccountName
   :io.jwt/token
   :urn:oid:2.5.4/givenName
   :urn:oid:2.5.4/surname
   :urn:oid:0.9.2342.19200300.100.1.3
   :urn:oid:2.5.4/commonName
   :urn:oid:2.5.4/title
   :urn:oid:2.5.4/telephoneNumber
   :org.hl7.fhir.Practitioner/telecom
   :org.hl7.fhir.Practitioner/identifier
   {:t_user/active_projects                                 ;;; iff the user has an rsdb account, this will be populated
    [:t_project/id :t_project/name :t_project/title :t_project/slug
     :t_project/is_private
     :t_project/long_description :t_project/type
     :t_project/virtual]}
   {:org.hl7.fhir.Practitioner/name
    [:org.hl7.fhir.HumanName/use
     :org.hl7.fhir.HumanName/prefix
     :org.hl7.fhir.HumanName/family
     :org.hl7.fhir.HumanName/given
     :org.hl7.fhir.HumanName/suffix]}
   :t_user/latest_news])

(rf/reg-event-fx
  ::do-login []
  (fn [{db :db} [_ namespace username password]]
    (js/console.log "performing login " username)
    {:db (-> db
             (dissoc :authenticated-user)
             (update-in [:errors] dissoc :user/login))
     :fx [[:http-xhrio {:method          :post
                        :uri             eldrix.pc4-ward.config/login-url
                        :timeout         5000
                        :format          (ajax-transit/transit-request-format {:handlers dates/transit-writers})
                        :response-format (ajax-transit/transit-response-format {:handlers dates/transit-readers})
                        :on-success      [::handle-login-response]
                        :on-failure      [::handle-login-failure]
                        :params          {:system   namespace
                                          :value    username
                                          :password password
                                          :query    user-query}}]]}))

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
             (assoc-in [:errors :user/login] "Failed to login: unable to connect to API server. Please try again later."))}))

(rf/reg-event-fx ::do-session-expire
  []
  (fn [{db :db} [_]]
    {:db         (-> (db/reset-database db)
                     (assoc-in [:errors :user/login] "Your session expired. Please login again"))
     :push-state [:home]}))

(rf/reg-event-fx ::do-logout
  []
  (fn [{db :db} [_ user]]
    (js/console.log "Logging out user" user)
    {:db         (db/reset-database db)
     :push-state [:home]}))

(rf/reg-event-fx ::ping-server
  []
  (fn [{db :db} _]
    (when-let [token (get-in db [:authenticated-user :io.jwt/token])]
      {:pathom {:token      token
                :params     (srv/make-ping-op {:uuid (random-uuid)})
                :on-success [::handle-ping-response]
                :on-failure [::handle-ping-failure]}})))

(rf/reg-event-fx ::handle-ping-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [ping]}]]
    (js/console.log "Ping success: response: " ping)
    {:db (update-in db [:errors] dissoc :ping)}))

(rf/reg-event-fx ::handle-ping-failure
  (fn [{db :db} response]
    (js/console.log "Ping failure :" response)
    {:db (assoc-in db [:errors :ping] response)
     ;:fx [[:dispatch-later [{:ms 10000 :dispatch [::ping-server]}]]]
     }))

(rf/reg-event-db ::clear-ping-failure
  []
  (fn [db _]
    (update-in db [:errors] dissoc :ping)))

(rf/reg-event-fx ::refresh-token
  []
  (fn [{:keys [db]} _]
    (let [token (get-in db [:authenticated-user :io.jwt/token])]
      (js/console.log "performing token refresh using token " token)
      {:fx [[:pathom {:token      token
                      :params     (srv/make-refresh-token-op {:token token})
                      :on-success [::handle-refresh-token-response]
                      :on-failure [::handle-refresh-token-failure]}]]})))

(rf/reg-event-fx ::handle-refresh-token-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [refresh-token]}]]
    (js/console.log "Refresh token success: response: " (:io.jwt/token refresh-token))
    {:db (assoc-in db [:authenticated-user :io.jwt/token] (:io.jwt/token refresh-token))}))

(rf/reg-event-fx ::handle-refresh-token-failure
  []
  (fn [{:keys [_db]} [_ response]]
    (js/console.log "User token refresh failure: response " response)))


(rf/reg-event-fx ::change-password
  []
  (fn [{db :db} [_ {username     :t_user/username
                    password     :t_user/password
                    new-password :t_user/new_password :as params}]]
    {:db (update-in db [:errors] dissoc :change-password)
     :fx [[:pathom {:params     [(list 'pc4.rsdb/change-password params)]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-change-password-success]
                    :on-failure [::handle-change-password-failure]}]]}
    ))

(rf/reg-event-fx ::handle-change-password-success
  []
  (fn [{db :db} _]
    {:db (update-in db [:authenticated-user :practitioner] assoc :t_user/must_change_password false)
     :fx [[:push-state [:home]]]}))

(rf/reg-event-fx ::clear-change-password-error
  []
  (fn [{db :db} _]
    {:db (update-in db [:errors] dissoc :change-password)}))

(rf/reg-event-fx ::handle-change-password-failure
  []
  (fn [{db :db} [_ response]]
    (tap> {:password-change-error response})
    {:db (assoc-in db [:errors :change-password] (get-in response [:response :message]))}))

(rf/reg-event-fx
  ::check-token
  (fn [{db :db} [_]]
    (let [token (get-in db [:authenticated-user :io.jwt/token])]
      (cond
        ;; no authenticated user -> explicitly do nothing... without this, the next condition will be thrown
        (nil? token)
        (js/console.log "no current logged in user")

        ;; user token expired - we have to force end to our session
        (srv/jwt-expires-in-seconds? token 0)
        (do (js/console.log "session expired for user" (get-in db [:authenticated-user :practitioner :urn:oid:1.2.840.113556.1.4/sAMAccountName]))
            {:fx [[:dispatch [::do-session-expire]]]})

        ;; user token expiring soon - we still have chance to refresh our token without needing to ask for credentials again
        (srv/jwt-expires-in-seconds? token 90)
        (do (js/console.log "session expiring in " (srv/jwt-expires-seconds token) " seconds; refreshing token")
            {:fx [[:dispatch [::refresh-token]]]})

        ;; we have an active session
        :else (js/console.log "active session; token expires in " (srv/jwt-expires-seconds token) "seconds")))))


(comment
  (rf/dispatch-sync [::do-login "wales.nhs.uk" "ma090906" "password"])
  (rf/dispatch-sync [::do-logout])
  @(rf/subscribe [:eldrix.pc4-ward.user.subs/login-error])
  (tap> {:authenticated-user @(rf/subscribe [:eldrix.pc4-ward.user.subs/authenticated-user])})
  @(rf/subscribe [:eldrix.pc4-ward.user.subs/token])
  (rf/dispatch-sync [::ping-server])
  (rf/dispatch-sync [::check-token])
  (rf/dispatch-sync [::refresh-token])

  )