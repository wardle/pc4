(ns pc4.events
  "Main application events including
  - Database initialisation
  - Timers
  - Panel management / navigation"
  (:require [ajax.transit :as ajax-transit]
            [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [day8.re-frame.http-fx]                         ;; required for its side-effects in registering a re-frame "effect"
            [pc4.comp :as comp]
            [pc4.dates :as dates]
            [pc4.db :as db]
            [pc4.server :as server]
            [reitit.frontend.controllers :as rfc]
            [re-frame.core :as re-frame]
            [reitit.frontend.easy :as rfe]
            [taoensso.timbre :as log]))

(rf/reg-event-db
  ::initialize-db
  (fn [_ _]
    db/default-db))

;; call our timer events at the required intervals
(defonce do-timer (do
                    ;;(js/setInterval #(rf/dispatch [::timer-one-second (js/Date.)]) 1000)
                    (js/setInterval #(rf/dispatch [::time-10-seconds]) 10000)
                    #_(js/setInterval #(rf/dispatch [::timer-one-minute]) 60000)))

(rf/reg-event-db                                            ;; usage:  (rf/dispatch [:timer a-js-Date])
  ::timer-one-second
  (fn [db [_ new-time]]                                     ;; <-- notice how we de-structure the event vector
    (assoc db :current-time new-time)))

(rf/reg-event-fx
  ::time-10-seconds
  (fn [{db :db} [_]]
    {:fx [#_[:dispatch [::ping-server]]
          [:dispatch [::check-token]]]}))

#_(rf/reg-event-fx
    ::timer-one-minute
    (fn [{db :db} [_]]
      {:fx [#_[:dispatch [::ping-server]]
            [:dispatch [::check-token]]]}))

(rf/reg-event-db
  ::navigate
  (fn [db [_ new-match]]
    (let [old-match (:current-route db)
          controllers (rfc/apply-controllers (:controllers old-match) new-match)]
      (assoc db :current-route (assoc new-match :controllers controllers)))))

(re-frame/reg-fx
  :push-state
  (fn [route]
    (apply rfe/push-state route)))

(re-frame/reg-event-fx
  ::push-state
  (fn [_ [_ & route]]
    (log/debug "pushing route" route)
    {:push-state route}))

(re-frame/reg-fx
  :navigate-back
  (fn [_]
    (.back js/history)))

(re-frame/reg-event-fx
  ::navigate-back
  (fn [_ _]
    (log/debug "navigating back")
    {:navigate-back {}}))

(re-frame/reg-fx
  :push-query-params
  (fn [params]
    (apply rfe/set-query params)))

(re-frame/reg-event-fx
  ::push-query-params
  (fn [_ [_ & params]]
    {:push-query-params params}))

(rf/reg-event-db ::modal
  (fn [db [_ k data]]
    (assoc-in db [:modal k] data)))

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
  (fn [{db :db} [_ {:keys [username password]}]]
    (let [csrf-token js/pc4_network_csrf_token]
      (log/debug "performing login " username)
      (when-not csrf-token
        (log/warn "csrf token missing from HTML page"))
      {:db (-> db
               (dissoc :authenticated-user)
               (update-in [:errors] dissoc :user/login))
       :fx [[:http-xhrio {:method          :post
                          :uri             pc4.config/login-url
                          :timeout         5000
                          :format          (ajax-transit/transit-request-format {:handlers dates/transit-writers})
                          :response-format (ajax-transit/transit-response-format {:handlers dates/transit-readers})
                          :on-success      [::handle-login-response]
                          :on-failure      [::handle-login-failure]
                          :headers         {"X-CSRF-Token" csrf-token}
                          :params          {:system   "cymru.nhs.uk"
                                            :value    username
                                            :password password
                                            :query    user-query}}]]})))

;; Login success simply means the http request worked.
;; The pc4.users/login operation returns a user if the login was successful.
(rf/reg-event-fx ::handle-login-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [login]}]]
    (log/debug "user login transaction: response: " login)
    (if login
      {:db (assoc db :authenticated-user {:io.jwt/token (:io.jwt/token login)
                                          :practitioner (dissoc login :io.jwt/token)})
       :fx [[:push-state [:home]]]}
      {:db (assoc-in db [:errors :user/login] "Incorrect username or password")})))

(rf/reg-event-fx ::handle-login-failure
  []
  (fn [{:keys [db]} [_ response]]
    (log/error "user login failure: " response)
    {:db (-> db
             (dissoc :authenticated-user)
             (assoc-in [:errors :user/login] "Failed to login: unable to connect to API server. Please try again later."))}))

(rf/reg-event-fx ::do-session-expire
  []
  (fn [{db :db} [_]]
    (log/info "session expired")
    {:db         (-> (db/reset-database db)
                     (assoc-in [:errors :user/login] "Your session expired. Please login again"))
     :push-state [:login]}))

(rf/reg-event-fx ::do-logout
  []
  (fn [{db :db} [_ user]]
    (log/debug "logging out user" user)
    {:db         (db/reset-database db)
     :push-state [:login]}))

(rf/reg-event-fx ::ping-server
  []
  (fn [{db :db} _]
    (when-let [token (get-in db [:authenticated-user :io.jwt/token])]
      {:pathom {:token      token
                :params     (server/make-ping-op {:uuid (random-uuid)})
                :on-success [::handle-ping-response]
                :on-failure [::handle-ping-failure]}})))

(rf/reg-event-fx ::handle-ping-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [ping]}]]
    (log/debug "remote ping success: " ping)
    {:db (update-in db [:errors] dissoc :ping)}))

(rf/reg-event-fx ::handle-ping-failure
  (fn [{db :db} response]
    (log/error "remote ping failure: " response)
    {:db (assoc-in db [:errors :ping] response)}))
;:fx [[:dispatch-later [{:ms 10000 :dispatch [::ping-server]}]]]


(rf/reg-event-db ::clear-ping-failure
  []
  (fn [db _]
    (update-in db [:errors] dissoc :ping)))

(rf/reg-event-fx ::refresh-token
  []
  (fn [{:keys [db]} _]
    (let [token (get-in db [:authenticated-user :io.jwt/token])]
      (log/debug "performing token refresh using token " token)
      {:fx [[:pathom {:token      token
                      :params     (server/make-refresh-token-op {:token token})
                      :on-success [::handle-refresh-token-response]
                      :on-failure [::handle-refresh-token-failure]}]]})))

(rf/reg-event-fx ::handle-refresh-token-response
  []
  (fn [{db :db} [_ {:pc4.users/syms [refresh-token]}]]
    (log/debug "refresh token success: " (:io.jwt/token refresh-token))
    {:db (assoc-in db [:authenticated-user :io.jwt/token] (:io.jwt/token refresh-token))}))

(rf/reg-event-fx ::handle-refresh-token-failure
  []
  (fn [{:keys [_db]} [_ response]]
    (log/error "user token refresh failure: response " response)))


(rf/reg-event-fx ::change-password
  []
  (fn [{db :db} [_ {username     :t_user/username
                    password     :t_user/password
                    new-password :t_user/new_password :as params}]]
    {:db (update-in db [:errors] dissoc :change-password)
     :fx [[:pathom {:params     [(list 'pc4.rsdb/change-password params)]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-change-password-success]
                    :on-failure [::handle-change-password-failure]}]]}))


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
        (log/trace "no current logged in user")

        ;; user token expired - we have to force end to our session
        (server/jwt-expired? token)
        (do (log/debug "session expired for user" (get-in db [:authenticated-user :practitioner :urn:oid:1.2.840.113556.1.4/sAMAccountName]))
            {:fx [[:dispatch [::do-session-expire]]]})

        ;; user token expiring soon - we still have chance to refresh our token without needing to ask for credentials again
        (server/jwt-expires-in-seconds? token 90)
        (do (log/debug "session expiring in" (server/jwt-expires-seconds token) "seconds => refreshing token")
            {:fx [[:dispatch [::refresh-token]]]})

        ;; we have an active session
        :else (log/trace "active session; token expires in" (server/jwt-expires-seconds token) "seconds")))))



(s/def ::id keyword?)
(s/def ::tx (s/or :fn fn? :vector vector? :map map?))
(s/def ::targets (s/nilable vector?))
(s/def ::on-success-fx (s/or :fn fn? :vector vector?))
(s/def ::on-failure-fx (s/or :fn fn? :vector vector?))
(s/def ::remote-config (s/keys :req-un [::id ::tx]
                               :opt-un [::targets ::on-success-fx ::on-failure-fx]))
;;
;; Perform a remote transaction
;; We have to take care that, if the same transaction is run repeatedly
;; (e.g. during a faceted search), that the results of an older transaction does
;; overwrite the results from a newer transaction. The last-updated records
;; could be used to implement caching so that a repeated load is only made if
;; the data is older than a certain limit, configured per-route.
(rf/reg-event-fx ::remote
  (fn [{:keys [db]} [_ {:keys [id tx] :as config}]]
    (log/debug "performing remote transaction:" {:id id :tx tx})
    (when (and pc4.config/debug? (not (s/valid? ::remote-config config)))
      (throw (ex-info "invalid remote transaction" (s/explain-data ::remote-config config))))
    {:db (update db :loading (fnil conj #{}) id)                           ;; we're starting some network loading
     :fx [[:pathom {:params     tx
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-remote-success config (js/Date.)]
                    :on-failure [::handle-remote-failure config]}]]}))

(rf/reg-event-fx ::handle-remote-success                      ;; HTTP success, but the response may contain an error
  (fn [{db :db} [_ {:keys [id failed? on-success-fx on-failure-fx] :as config} request-date response]]
    (when-not id
      (throw (ex-info "missing id" config)))
    (let [failed? (or failed? (constantly false))]
      (log/debug "remote transaction response" response)
      (tap> {:load-response response})
      (if (or (:error response) (failed? response))
        (do (log/debug "remote load error" (-> response :error :cause))
            (cond-> {:db (update db :loading disj id)}
                    (fn? on-failure-fx)
                    (assoc :fx (on-failure-fx response))
                    (vector? on-failure-fx)
                    (assoc :fx on-failure-fx)))
        (let [path [:last-updated id]
              last-updated (get-in db path)]
          (if (or (not last-updated) (> request-date last-updated))
            (cond-> {:db (let [{entity-db :db} (comp/target-results (:entity-db db) config response)]
                           (-> db
                               (update :loading disj id)
                               (assoc :entity-db entity-db,)
                               (assoc-in path request-date)))}
                    (fn? on-success-fx)
                    (assoc :fx (on-success-fx response))
                    (vector? on-success-fx)
                    (assoc :fx on-success-fx))
            (log/debug "out of order remote response ignored")))))))

(rf/reg-event-fx ::handle-remote-failure
  (fn [{:keys [db]} [_ config response]]
    (log/error "remote load network error" response)
    (tap> {::handle-remote-failure {:config config :response response}})))

(rf/reg-event-db ::local-push
  (fn [db [_ response]]
    (log/trace "local push" response)
    (let [{entity-db :db} (comp/target-results (:entity-db db) {} response)]
      (assoc db :entity-db entity-db))))

(rf/reg-event-db ::local-delete
  (fn [db [_ ident]]
    (assoc db :entity-db (comp/delete (:entity-db db) ident))))

(rf/reg-event-db ::local-init
  (fn [db [_ init-data]]
    (assoc db :entity-db (comp/target-init (:entity-db db) init-data))))


(comment
  (rf/dispatch-sync [::do-login {:username "system", :password "password"}])
  (rf/dispatch-sync [::do-logout])
  @(rf/subscribe [:pc4.subs/login-error])
  (tap> {:authenticated-user @(rf/subscribe [:pc4.subs/authenticated-user])})
  @(rf/subscribe [:pc4.subs/token])
  (rf/dispatch-sync [::ping-server])
  (rf/dispatch-sync [::check-token])
  (rf/dispatch-sync [::refresh-token]))

