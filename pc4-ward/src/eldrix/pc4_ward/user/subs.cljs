(ns eldrix.pc4-ward.user.subs
  "Subscriptions relating to users.
  * Login and logout
  * Session timeout
  * Token management, including refresh"
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::authenticated-user
  (fn [db]
    (get-in db [:authenticated-user :practitioner])))

(rf/reg-sub ::authenticated-user-full-name
  (fn []
    (rf/subscribe [::authenticated-user]))
  (fn [user]
    (:urn.oid.2.5.4/commonName user)))

(rf/reg-sub ::login-error
  (fn [db]
    (get-in db [:errors ::login])))

(rf/reg-sub ::ping-error
  (fn [db]
    (get-in db [:errors ::ping])))


