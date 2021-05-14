(ns eldrix.pc4-ward.events
  (:require
    [re-frame.core :as rf]
    [day8.re-frame.http-fx]                                 ;; required for its side-effects in registering a re-frame "effect"
    [eldrix.pc4-ward.db :as db]
    [eldrix.pc4-ward.users :as users]
    [eldrix.pc4-ward.server :as srv]))

(rf/reg-event-db
  ::initialize-db
  (fn [_ _]
    db/default-db))

;; our 1s timer
(defn dispatch-timer-1s-event []
  (let [now (js/Date.)]
    (rf/dispatch [::timer-one-second now])))                ;; <-- dispatch used

;; our 5 min timer
(defn dispatch-timer-1m-event [] (rf/dispatch [::timer-one-minute]))

;; call our timer events at the required intervals
(defonce do-timer (do
                    (js/setInterval dispatch-timer-1s-event 1000)
                    (js/setInterval dispatch-timer-1m-event 60000)))


(rf/reg-event-db                                            ;; usage:  (rf/dispatch [:timer a-js-Date])
  ::timer-one-second
  (fn [db [_ new-time]]                                     ;; <-- notice how we de-structure the event vector
    (assoc db :current-time new-time)))

;; every minute, we check our authentication tokens are valid, and refresh if necessary
;; we cannot refresh an expired login token ourselves, so give up and logout with a session expired notice
(rf/reg-event-fx                                            ;; usage:  (rf/dispatch [:timer a-js-Date])
  ::timer-one-minute
  (fn [{db :db} [_]]                                        ;; <-- notice how we de-structure the event vector
    (cond
      ;; no authenticated user -> explicitly do nothing... without this, the next condition will be thrown
      (nil? (:authenticated-user db))
      (do (js/console.log "no current logged in user") {})

      ;; user token expired - we have to force end to our session
      (not (srv/jwt-valid? (get-in db [:authenticated-user :io.jwt/token])))
      (do (js/console.log "session expired") {:dispatch [::users/do-session-expire]})

      ;; user token expiring soon - we still have chance refresh our token without needing to ask for credentials again
      (srv/jwt-expires-in-seconds? (get-in db [:authenticated-user :io.jwt/token]) 60)
      {:dispatch [:user/refresh-token {:token (get-in db [:authenticated-user :io.jwt/token])}]}

      ;; we have an active session
      :else (js/console.log "active session"))))
