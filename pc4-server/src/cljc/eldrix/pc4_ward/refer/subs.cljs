(ns eldrix.pc4-ward.refer.subs
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.refer.core :as refer]
            [eldrix.pc4-ward.patient.subs :as patient-subs]
            [eldrix.pc4-ward.user.subs :as user-subs]))

;; return the current in-process referral
;; this takes care to ensure that the referral has the current patient
;; and current user, if either are set
(rf/reg-sub ::referral
  (fn []
    [re-frame.db/app-db
     (rf/subscribe [::patient-subs/current])
     (rf/subscribe [::user-subs/authenticated-user])])
  (fn [[db current-patient user]]
    (let [ref (get-in db [:patient/current :referral])]
      (cond-> ref
              (and current-patient (not (::refer/patient ref)))
              (assoc ::refer/patient current-patient)

              (and user (not (get-in ref [::refer/referrer ::refer/practitioner])))
              (->
                (assoc-in [::refer/referrer ::refer/practitioner] user)
                (assoc-in [::refer/referrer ::refer/job-title] (:urn:oid:2.5.4/title user))
                (assoc-in [::refer/referrer ::refer/contact-details] (:urn:oid:2.5.4/telephoneNumber user)))))))

(rf/reg-sub ::available-stages
  (fn []
    (rf/subscribe [::referral]))
  (fn [referral]
    (refer/available-stages referral)))

(rf/reg-sub ::completed-stages
  (fn []
    (rf/subscribe [::referral]))
  (fn [referral]
    (refer/completed-stages referral)))

(rf/reg-sub ::current-stage
  (fn []
    [(rf/subscribe [::referral])
     (rf/subscribe [::available-stages])])
  (fn [[referral available]]
    (get available (:current-stage referral) :clinician)))
