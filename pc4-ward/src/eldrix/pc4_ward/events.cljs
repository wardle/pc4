(ns eldrix.pc4-ward.events
  "Main application events including
  - Database initialisation
  - Timers
  - Panel management / navigation"
  (:require
    [re-frame.core :as rf]
    [day8.re-frame.http-fx]                                 ;; required for its side-effects in registering a re-frame "effect"
    [eldrix.pc4-ward.db :as db]
    [eldrix.pc4-ward.rf.users :as users]
    [eldrix.pc4-ward.server :as srv]))

(rf/reg-event-db
  ::initialize-db
  (fn [_ _]
    db/default-db))

;; call our timer events at the required intervals
(defonce do-timer (do
                    ;;(js/setInterval #(rf/dispatch [::timer-one-second (js/Date.)]) 1000)
                    (js/setInterval #(rf/dispatch [::time-10-seconds]) 10000)
                    (js/setInterval #(rf/dispatch [::timer-one-minute]) 60000)))

(rf/reg-event-db                                            ;; usage:  (rf/dispatch [:timer a-js-Date])
  ::timer-one-second
  (fn [db [_ new-time]]                                     ;; <-- notice how we de-structure the event vector
    (assoc db :current-time new-time)))

(rf/reg-event-fx
  ::time-10-seconds
  (fn [{db :db} [_]]
    {:fx [[:dispatch [::users/ping-server]]]}))

;; every minute, we check our authentication tokens are valid, and refresh if necessary
;; we cannot refresh an expired login token ourselves, so give up and logout with a session expired notice
(rf/reg-event-fx                                            ;; usage:  (rf/dispatch [:timer a-js-Date])
  ::timer-one-minute
  (fn [{:keys [db]} [_]]
    (users/check-token-and-refresh db)))

(rf/reg-sub ::active-panel
  (fn [db]
    (:active-panel db)))

(rf/reg-sub ::current-time
  (fn [db]
    (:current-time db)))