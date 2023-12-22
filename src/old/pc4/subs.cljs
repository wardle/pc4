(ns pc4.subs
    (:require [pc4.comp :as comp]
              [re-frame.core :as rf]))


(rf/reg-sub ::current-route
  (fn [db]
    (:current-route db)))

(rf/reg-sub ::current-time
  (fn [db]
    (:current-time db)))

(rf/reg-sub ::modal
  (fn [db [_ k]]
    (get-in db [:modal k])))

(rf/reg-sub ::token
  (fn [db]
    (get-in db [:authenticated-user :io.jwt/token])))

(rf/reg-sub ::authenticated-user
  (fn [db]
    (get-in db [:authenticated-user :practitioner])))

(rf/reg-sub ::authenticated-user-full-name
  (fn []
    (rf/subscribe [::authenticated-user]))
  (fn [user]
    (:urn:oid:2.5.4/commonName user)))

(rf/reg-sub ::must-change-password?
  (fn []
    (rf/subscribe [::authenticated-user]))
  (fn [user]
    (:t_user/must_change_password user)))

(rf/reg-sub ::login-error
  (fn [db]
    (get-in db [:errors :user/login])))

(rf/reg-sub ::ping-error
  (fn [db]
    (get-in db [:errors :ping])))

(rf/reg-sub ::change-password-error
  (fn [db]
    (get-in db [:errors :change-password])))

(rf/reg-sub ::active-projects
  (fn []
    (rf/subscribe [::authenticated-user]))
  (fn [user]
    (:t_user/active_projects user)))

(rf/reg-sub ::latest-news
  (fn []
    (rf/subscribe [::authenticated-user]))
  (fn [user]
    (:t_user/latest_news user)))

(rf/reg-sub ::loading
  (fn [db [_ id]]
    (if id
      (get (:loading db) id)
      (seq (:loading db)))))

(rf/reg-sub ::delayed
  (fn [db [_ id]]
    (if id
      (get (:delayed db) id)
      (seq (:delayed db)))))

(rf/reg-sub ::local-pull
  (fn [db [_ query targets]]
    (comp/pull-results (:entity-db db) {:tx query :targets targets})))

