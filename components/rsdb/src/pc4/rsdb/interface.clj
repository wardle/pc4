(ns pc4.rsdb.interface
  (:require
   [integrant.core :as ig]
   [pc4.log.interface :as log]
   [pc4.rsdb.auth :as auth]
   [pc4.rsdb.db :as db]
   [pc4.rsdb.migrations :as migrations]
   [pc4.rsdb.forms :as forms]
   [pc4.rsdb.demographics :as demog]
   [pc4.rsdb.patients :as patients]
   [pc4.rsdb.projects :as projects]
   [pc4.rsdb.results :as results]
   [pc4.rsdb.users :as users]
   [next.jdbc.connection :as connection])
  (:import (com.zaxxer.hikari HikariDataSource)))

(defmethod ig/init-key ::conn
  [_ params]
  (log/info "opening PatientCare EPR [rsdb] database connection" (dissoc params :password))
  (let [conn (connection/->pool HikariDataSource params)]
    ;; throw an exception if there are pending migrations
    (when-let [ms (seq (migrations/pending-list conn))]
      (throw (ex-info "pending migrations exist: aborting startup" {:pending ms})))
    (log/debug "no pending migrations")
    conn))

(defmethod ig/halt-key! ::conn
  [_ conn]
  (log/info "closing PatientCare EPR [rsdb] database connection")
  (.close conn))

(defmethod ig/init-key ::config
  [_ config]
  config)

;;

(defn migrate! [conn]
  (migrations/migrate conn))

(defmethod ig/init-key ::migrate
  [_ {:keys [conn]}]
  (if-let [ms (seq (migrations/pending-list conn))]
    (do (log/info "performing database migrations..." {:pending ms})
        (migrate! conn))
    (log/info "no pending migrations required")))
;;
;;

(defn execute!
  [conn sql]
  (db/execute! conn sql))

(defn execute-one!
  [conn sql]
  (db/execute! conn sql))

(defn graph-resolvers []
  (requiring-resolve 'com.eldrix.pc4.rsdb/all-resolvers))

;;
;;
;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: clean up rsdb API, remove redundancy, adopt more standard patterns, and simplify 
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;
;;
;;

;; authorisation / permissions 

(def all-permissions auth/all-permissions)
(def permission-sets auth/permission-sets)
(def expand-permission-sets auth/expand-permission-sets)
(def authorized? auth/authorized?)
(def authorized-any? auth/authorized-any?)
(def authorization-manager users/authorization-manager2)

;; users

(def user-by-username users/fetch-user)
(defn user-by-id [conn user-id]
  (users/fetch-user-by-id conn user-id))
(defn fetch-user-photo [conn username]
  (users/fetch-user-photo conn username))
(def authenticate users/authenticate)
(def save-password! users/save-password)
(def perform-login! users/perform-login!)
(def user->roles users/roles-for-user)
(def projects-with-permission users/projects-with-permission)
(def user->projects users/projects)
(def user->common-concepts users/common-concepts)
(def user->latest-news users/fetch-latest-news)
(def user->job-title users/job-title)
(def user->count-unread-messages users/count-unread-messages)
(def user->count-incomplete-messages users/count-incomplete-messages)
(def create-user! users/create-user)
(def register-user-to-project! users/register-user-to-project)
(def reset-password! users/reset-password!)
(def set-must-change-password! users/set-must-change-password!)
;;

;; patients  TODO: rationalise the duplication here
(def patient-by-identifier patients/patient-by-identifier)
(def patient-by-pk patients/patient-by-pk)
(def fetch-patient patients/fetch-patient)
(def create-patient patients/create-diagnosis!)
(def patient-pk->patient-identifier patients/pk->identifier)
(def patient-pks->patient-identifiers patients/pks->identifiers)
(def patient-by-project-pseudonym projects/patient-by-project-pseudonym)
(def find-legacy-pseudonymous-patient projects/find-legacy-pseudonymous-patient)
(def patient->active-project-identifiers patients/active-project-identifiers)
(def patient-pk->hospitals patients/patient-pk->hospitals)
(def patient-pk->crn-for-org patients/patient-pk->crn-for-org)
(def patient->death-certificate patients/fetch-death-certificate)
(def patient->diagnoses patients/diagnoses)
(def diagnosis-active? patients/diagnosis-active?)
(def patient->summary-multiple-sclerosis patients/fetch-summary-multiple-sclerosis)
(def patient->ms-events patients/fetch-ms-events)
(def ms-event-ordering-error->en-GB patients/ms-event-ordering-error->en-GB)
(def ms-event-ordering-errors patients/ms-event-ordering-errors)
(def patient->medications-and-events patients/fetch-medications-and-events)
(def medication-by-id patients/medication-by-id)
(def medications->events patients/fetch-medication-events)
(def patient->addresses patients/fetch-patient-addresses)
(def address-for-date patients/address-for-date)
(def patient->episodes patients/patient->episodes)
(def patient->encounters patients/patient->encounters)
(def ^:deprecated ms-event->patient-identifier patients/patient-identifier-for-ms-event) ;; TODO: remove!

(def register-patient! projects/register-patient)
(def register-legacy-pseudonymous-patient! projects/register-legacy-pseudonymous-patient)
(def register-patient-project! projects/register-patient-project!)
(def update-legacy-pseudonymous-patient! projects/update-legacy-pseudonymous-patient!)

;; demographic matching
(def exact-match-by-identifier demog/exact-match-by-identifier)
(def exact-match-on-demography demog/exact-match-on-demography)
(def update-patient demog/update-patient)

(def patient->results results/results-for-patient)
(def encounter-by-id patients/encounter-by-id)
(def encounter->users patients/encounter->users)
(def encounter-templates-by-ids patients/encounter-templates-by-ids)
(def encounter-type-by-id patients/encounter-type-by-id)
(def encounter-ids->form-edss patients/encounter-ids->form-edss)
(def encounter-ids->form-edss-fs patients/encounter-ids->form-edss-fs)
(def encounter-ids->form-ms-relapse patients/encounter-ids->form-ms-relapse)
(def encounter-ids->form-weight-height patients/encounter-ids->form-weight-height)
(def ^:deprecated encounter-id->form-smoking-history forms/encounter->form_smoking_history)

;; value sets

(def all-multiple-sclerosis-diagnoses patients/all-multiple-sclerosis-diagnoses)
(def all-ms-event-types patients/all-ms-event-types)
(def all-ms-disease-courses patients/all-ms-disease-courses)
(def ms-event-is-relapse? patients/ms-event-is-relapse?)
(def medication-reasons-for-stopping db/medication-reasons-for-stopping)

;;
;; forms
;;

(def edss-score->score forms/edss-score->score)
(def encounter-id->forms-and-form-types forms/forms-and-form-types-in-encounter)
(def encounter-id->forms forms/forms-for-encounter)

;;
;;
;;

(def mri-brains-for-patient results/mri-brains-for-patient)

;; projects

(def episode-by-id patients/episode-by-id)
(def project-by-id projects/fetch-project)
(def project-by-name projects/project-with-name)
(def project->active? projects/active?)
(def episode-status projects/episode-status)
(def projects->administrator-users users/administrator-users)
(def episode-id->encounters patients/episode-id->encounters)
(def projects->count-registered-patients projects/count-registered-patients)
(def projects->count-pending-referrals projects/count-pending-referrals)
(def projects->count-discharged-episodes projects/count-discharged-episodes)
(def project-title->slug projects/make-slug)
(def project->encounter-templates projects/project->encounter-templates)
(def project->all-parent-ids projects/all-parents-ids)
(def project->all-parents projects/all-parents)
(def project->all-children-ids projects/all-children-ids)
(def project->all-children projects/all-children)
(def project->common-concepts projects/common-concepts)
(def project->users projects/fetch-users)
(def register-completed-episode! projects/register-completed-episode!)
(def discharge-episode! projects/discharge-episode!)
(def project-ids->patient-ids patients/patient-ids-in-projects)
(def consented-patients projects/consented-patients)

;; mutations

(def create-diagnosis! patients/create-diagnosis!)
(def update-diagnosis! patients/update-diagnosis)
(def upsert-medication! patients/upsert-medication!)
(def delete-medication! patients/delete-medication!)
(def save-ms-diagnosis! patients/save-ms-diagnosis!)
(def save-pseudonymous-patient-lsoa! patients/save-pseudonymous-patient-lsoa!)
(def save-ms-event! patients/save-ms-event!)
(def delete-ms-event! patients/delete-ms-event!)
(def ^:deprecated save-encounter-and-forms! forms/save-encounter-and-forms!)
(def delete-episode! projects/delete-episode!)
(def delete-encounter! patients/delete-encounter!)
(def unlock-encounter! patients/unlock-encounter!)
(def lock-encounter! patients/lock-encounter!)
(def create-form! forms/create-form!)
(def save-form! forms/save-form!)
(def delete-form! forms/delete-form!)
(def undelete-form! forms/undelete-form!)
(def result-type-by-entity-name results/result-type-by-entity-name)
(def save-result! results/save-result!)
(def delete-result! results/delete-result!)
(def set-date-death! patients/set-date-death)
(def notify-death! patients/notify-death!)
(def patient->set-date-death! patients/set-date-death)
(def save-admission! projects/save-admission!)
(def ^:deprecated set-cav-authoritative-demographics! patients/set-cav-authoritative-demographics!)

