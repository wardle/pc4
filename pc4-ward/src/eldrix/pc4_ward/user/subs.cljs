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
    (get-in db [:errors :user/login])))

(rf/reg-sub ::ping-error
  (fn [db]
    (get-in db [:errors :ping])))

;; we currently hardcode the common hospitals for a user,
;; but we could derive based on where they work or a user-configured list
;; after demo release.
(rf/reg-sub ::common-hospitals
  (fn [db]
    ({:urn.oid.2.16.840.1.113883.2.1.3.2.4.18.48/id "7A4BV"
      :org.w3.2004.02.skos.core/prefLabel "UNIVERSITY HOSPITAL OF WALES"})))

