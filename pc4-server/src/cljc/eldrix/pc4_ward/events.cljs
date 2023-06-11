(ns eldrix.pc4-ward.events
  "Main application events including
  - Database initialisation
  - Timers
  - Panel management / navigation"
  (:require
    [re-frame.core :as rf]
    [day8.re-frame.http-fx]                                 ;; required for its side-effects in registering a re-frame "effect"
    [eldrix.pc4-ward.db :as db]
    [eldrix.pc4-ward.user.events :as user-events]
    [eldrix.pc4-ward.server :as srv]
    [reitit.frontend.controllers :as rfc]
    [re-frame.core :as re-frame]
    [reitit.frontend.easy :as rfe]))

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
    {:fx [[:dispatch [::user-events/ping-server]]]}))

(rf/reg-event-fx
  ::timer-one-minute
  (fn [{db :db} [_]]
    {:fx [[:dispatch [::user-events/check-token]]]}))

(rf/reg-event-db
  ::navigate
  (fn [db [_ new-match]]
    (let [old-match (:current-route db)
          controllers (rfc/apply-controllers (:controllers old-match) new-match)]
      (assoc db :current-route (assoc new-match :controllers controllers)))))

(re-frame/reg-fx :push-state
  (fn [route]
    (apply rfe/push-state route)))

(re-frame/reg-event-fx
  ::push-state
  (fn [_ [_ & route]]
    {:push-state route}))
