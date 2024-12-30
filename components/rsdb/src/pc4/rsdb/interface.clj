(ns pc4.rsdb.interface
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.plan :as plan]
   [pc4.log.interface :as log]
   [pc4.ods.interface :as ods]
   [pc4.rsdb.auth :as auth]
   [pc4.rsdb.db :as db]
   [pc4.rsdb.migrations :as migrations]
   [pc4.rsdb.forms :as forms]
   [pc4.rsdb.demographics :as demog]
   [pc4.rsdb.msss :as msss]
   [pc4.rsdb.patients :as patients]
   [pc4.rsdb.projects :as projects]
   [pc4.rsdb.results :as results]
   [pc4.snomedct.interface :as hermes]
   [pc4.rsdb.users :as users]
   [pc4.wales-nadex.interface :as nadex]
   [next.jdbc.connection :as connection]
   [next.jdbc.specs])
  (:import (com.zaxxer.hikari HikariDataSource)))

(s/def ::conn :next.jdbc.specs/proto-connectable)
(s/def ::wales-nadex nadex/valid-service?)
(s/def ::hermes any?)
(s/def ::ods ods/valid-service?)
(s/def ::legacy-global-pseudonym-salt string?)

(s/def ::service-config
  (s/keys :req-un [::conn ::wales-nadex ::hermes ::ods ::legacy-global-pseudonym-salt]))

(defmethod ig/init-key ::svc
  [_ {:keys [conn] :as config}]
  (log/info "opening PatientCare EPR [rsdb] database connection" (if (map? conn) (dissoc conn :password) conn))
  (when-not (s/valid? ::service-config config)
    (throw (ex-info "invalid rsdb svc config" (s/explain-data ::service-config config))))
  (assoc config :conn (connection/->pool HikariDataSource conn)))

(defmethod ig/halt-key! ::svc
  [_ {:keys [conn]}]
  (log/info "closing PatientCare EPR [rsdb] database connection")
  (when conn (.close conn)))

(defn valid-service?
  [svc]
  (s/valid? ::service-config svc))

;;

(defn pending-migrations-list
  "Return a sequence of pending migrations."
  [{:keys [conn]}]
  (migrations/pending-list conn))

(defn perform-pending-migrations!
  [{:keys [conn] :as svc}]
  (when-let [pending (seq (pending-migrations-list svc))]
    (log/info "performing database migrations..." {:pending pending})
    (migrations/migrate conn)))

(defn execute!              ;;; TODO: remove
  [{:keys [conn]} sql]
  (db/execute! conn sql))

(defn execute-one!          ;;; TODO: remove
  [{:keys [conn]} sql]
  (db/execute! conn sql))

(defn plan!                 ;;; TODO: remove
  [{:keys [conn]} sql]
  (jdbc/plan conn sql))

(defn select!               ;;; TODO: remove
  [{:keys [conn]} cols sql]
  (plan/select! conn cols sql))

(defn graph-resolvers
  "Return graph API resolvers for the rsdb module."
  []
  (requiring-resolve 'com.eldrix.pc4.rsdb/all-resolvers))

(s/def ::username string?)
(s/def ::email string?)
(s/def ::title string?)
(s/def ::first-names string?)
(s/def ::last-name string?)
(s/def ::custom-job-title string?)
(s/def ::job-title-id string?)
(s/def ::user-manager #{:NADEX})
(s/def ::project-ids (s/coll-of pos-int?))

(s/def ::create-local-user-request
  (s/keys :req-un [::username ::email ::title ::first-names ::last-name
                   ::custom-job-title ::job-title-id]
          :opt-un [::project-ids]))

(s/def ::create-managed-user-request
  (s/keys :req-un [::username ::user-manager]
          :opt-un [::project-ids]))

(s/def ::create-user-request
  (s/or :local ::create-local-user-request
        :managed ::create-managed-user-request))

(defn create-new-user
  "Create a new user."
  [{:keys [conn wales-nadex]} req]
  (let [req' (s/conform ::create-user-request req)]
    (if (= req' ::s/invalid)
      (throw (ex-info "invalid request" (s/explain-data ::create-user-request req)))
      (let [[mode {:keys [username email title first-names last-name custom-job-title job-title-id project-ids] :as data}] req'
            user (case mode
                   :local
                   (users/create-user conn {:t_user/username         username
                                            :t_user/email            email
                                            :t_user/title            title
                                            :t_user/first_names      first-names
                                            :t_user/last_name        last-name
                                            :t_user/job_title_fk     job-title-id
                                            :t_user/custom_job_title custom-job-title})
                   :managed
                   (throw (ex-info "managed user creation not yet implemented" {:managed data})))]
        (doseq [project-id project-ids]
          (users/register-user-to-project conn {:username username, :project-id project-id}))
        user))))

(comment
  (s/conform ::create-user-request {:username "ma090906" :user-manager :NADEX})
  (create-new-user conn {:username "wardle" :user-manager :NADEX})
  (s/conform ::CreateUserRequest {:username "ma090907" :user-manager :NADEX}))
;;
;;
;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: clean up rsdb API, remove redundancy, adopt more standard patterns, and simplify 
;; TODO: use request / response pattern as per HTTP type approach; this will be scalable
;; and independent of any particular implementation
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

(defn user-by-username
  ([{:keys [conn]} username]
   (users/fetch-user conn username))
  ([{:keys [conn]} username opts]
   (users/fetch-user conn username opts)))

(defn user-by-id [{:keys [conn]} user-id]
  (users/fetch-user-by-id conn user-id))

(defn user->display-names [user]
  (users/user->display-names user))

(defn ^:deprecated fetch-user-photo [{:keys [conn]} username]
  (users/fetch-user-photo conn username))

(defn user-id->photo [{:keys [conn]} user-id]
  (users/user-id->photo conn user-id))

(defn authenticate [{:keys [wales-nadex]} user password]
  (users/authenticate wales-nadex user password))

(defn save-password! [{:keys [conn]} user password]
  (users/save-password conn user password))

(defn perform-login!
  ([{:keys [conn wales-nadex]} username password]
   (users/perform-login! conn wales-nadex username password))
  ([{:keys [conn wales-nadex]} username password opts]
   (users/perform-login! conn wales-nadex username password opts)))

(defn user->roles
  ([{:keys [conn]} username]
   (users/roles-for-user conn username))
  ([{:keys [conn]} username opts]
   (users/roles-for-user conn username opts)))

(defn user->active-roles-by-project [{:keys [conn]} username]
  (users/active-roles-by-project-id conn username))

(defn username->authorization-manager
  "Create an authorization manager for the given user."
  [{:keys [conn]} username]
  (users/make-authorization-manager conn username))


(defn user->authorization-manager
  [user]
  (users/authorization-manager2 user))

(def projects-with-permission users/projects-with-permission)

(defn user->projects [{:keys [conn]} username]
  (users/projects conn username))

(defn user->common-concepts
  [{:keys [conn]} username]
  (users/common-concepts conn username))

(defn user->latest-news
  [{:keys [conn]} username]
  (users/fetch-latest-news conn username))

(defn user->job-title [user]
  (users/job-title user))

(defn user->count-unread-messages
  [{:keys [conn]} username]
  (users/count-unread-messages conn username))

(defn user->count-incomplete-messages
  [{:keys [conn]} username]
  (users/count-incomplete-messages conn username))

(defn create-user! [{:keys [conn]} username]
  (users/create-user conn username))

(defn register-user-to-project! [{:keys [conn]} params]
  (users/register-user-to-project conn params))

(defn reset-password! [{:keys [conn]} user]  ;;TODO: standardise to user id or username
  (users/reset-password! conn user))

(defn set-must-change-password! [{:keys [conn]} username]
  (users/set-must-change-password! conn username))
;;

;; patients
(defn fetch-patient
  [{:keys [conn]} patient]
  (patients/fetch-patient conn patient))

(defn patient-pk->patient-identifier
  [{:keys [conn]} patient-pk]
  (patients/pk->identifier conn patient-pk))

(defn patient-pks->patient-identifiers
  [{:keys [conn]} patient-pks]
  (patients/pks->identifiers conn patient-pks))

(defn patient-by-project-pseudonym [{:keys [conn]} project-id pseudonym]
  (projects/patient-by-project-pseudonym conn project-id pseudonym))

(defn ^:deprecated find-legacy-pseudonymous-patient [{:keys [conn]} params]
  (projects/find-legacy-pseudonymous-patient conn params))

(defn patient->active-project-identifiers [{:keys [conn]} patient-identifier]
  (patients/active-project-identifiers conn patient-identifier))

(defn patient-pk->hospitals [{:keys [conn]} patient-pk]
  (patients/patient-pk->hospitals conn patient-pk))

(defn patient-pk->crn-for-org
  [{:keys [conn ods]} patient-pk org-id]
  (patients/patient-pk->crn-for-org conn patient-pk ods org-id))

(defn patient->death-certificate [{:keys [conn]} patient]
  (patients/fetch-death-certificate conn patient))

;; TODO: turn into a transducer instead
(defn ^:private filtered-diagnoses
  "Returns a sequence of diagnoses for the patient filtered by
  the SNOMED ECL expression specified."
  ([hermes diagnoses ecl]
   (let [concept-ids (hermes/intersect-ecl hermes (map :t_diagnosis/concept_fk diagnoses) ecl)]
     (filter #(concept-ids (:t_diagnosis/concept_fk %)) diagnoses))))

(defn patient->diagnoses
  ([{:keys [conn]} patient-pk]
   (patients/diagnoses conn patient-pk))
  ([{:keys [conn hermes]} patient-pk {:keys [ecl]}]
   (if ecl
     (patients/diagnoses conn patient-pk)
     (let [diagnoses (patients/diagnoses conn patient-pk)]
       (filtered-diagnoses hermes diagnoses ecl)))))

(comment
  (require '[pc4.config.interface])
  (def rsdb (ig/init (pc4.config.interface/config :dev) [:pc4.rsdb.interface/svc])))

(def diagnosis-active? patients/diagnosis-active?)

(defn patient->summary-multiple-sclerosis
  [{:keys [conn]} patient-identifier]
  (patients/fetch-summary-multiple-sclerosis conn patient-identifier))

(defn patient->ms-events [{:keys [conn]} sms-id]
  (patients/fetch-ms-events conn sms-id))

(def ms-event-ordering-error->en-GB patients/ms-event-ordering-error->en-GB)
(def ms-event-ordering-errors patients/ms-event-ordering-errors)

(defn patient->medications-and-events
  ([{:keys [conn]} patient]
   (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
     (patients/fetch-medications-and-events txn patient)))
  ([{:keys [hermes] :as rsdb} patient {:keys [ecl]}]
   (if (str/blank? ecl)
     (patient->medications-and-events rsdb patient)
     (let [medications (patient->medications-and-events rsdb patient)
           medication-concept-ids (map :t_medication/medication_concept_fk medications)
           concept-ids (hermes/intersect-ecl hermes medication-concept-ids ecl)]
       (filter #(concept-ids (:t_medication/medication_concept_fk %)) medications)))))

(defn medication-by-id [{:keys [conn]} medication-id]
  (patients/medication-by-id conn medication-id))

(defn medications->events [{:keys [conn]} medication-ids]
  (patients/fetch-medication-events conn medication-ids))

(defn patient->addresses [{:keys [conn]} patient]
  (patients/fetch-patient-addresses conn patient))

(def address-for-date patients/address-for-date)

(defn patient->episodes
  ([{:keys [conn]} patient-pk project-id-or-ids]
   (patients/patient->episodes conn patient-pk project-id-or-ids))
  ([{:keys [conn]} patient-pk]
   (patients/patient->episodes conn patient-pk)))

(defn patient->encounters [{:keys [conn]} patient-pk]
  (patients/patient->encounters conn patient-pk))

(defn ^:deprecated ms-event->patient-identifier
  [{:keys [conn]} ms-event]
  (patients/patient-identifier-for-ms-event conn ms-event)) ;; TODO: remove!

(defn suggested-registrations
  "Given a user, and a patient, return a sequence of projects that could be
  suitable registrations. This could, in the future, use diagnoses and problems
  as a potential way to configure the choice."
  [rsdb {:t_user/keys [username]} {:t_patient/keys [patient_identifier]}]
  (let [roles (user->roles rsdb username)
        project-ids (patient->active-project-identifiers rsdb patient_identifier)]
    (->> (projects-with-permission roles :PATIENT_REGISTER)
         (filter :t_project/active?)                        ;; only return currently active projects
         (remove #(project-ids (:t_project/id %)))          ;; remove any projects to which patient already registered
         (map #(select-keys % [:t_project/id :t_project/title])))))  ;; only return data relating to projects

(defn register-patient!
  [{:keys [conn]} project-id user-id patient]
  (jdbc/with-transaction [txn conn {:isolation :serializable}]
    (projects/register-patient txn project-id user-id patient)))

(defn register-legacy-pseudonymous-patient!
  [{:keys [conn legacy-global-pseudonym-salt] :as rsdb}
   {:keys [user-id project-id nhs-number sex date-birth] :as registration}]
  (if legacy-global-pseudonym-salt
    (jdbc/with-transaction [txn conn {:isolation :serializable}]
      (projects/register-legacy-pseudonymous-patient conn (assoc registration :salt legacy-global-pseudonym-salt)))
    (throw (ex-info "missing rsdb configuration key: 'legacy-global-pseudonym-salt'" rsdb))))

(defn register-patient-project!
  [{:keys [conn]} project-id user-id patient]
  (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
    (projects/register-patient-project! txn project-id user-id patient)))

(defn update-legacy-pseudonymous-patient!
  [{:keys [conn legacy-global-pseudonym-salt]} patient-pk data]
  (jdbc/with-transaction [txn conn {:isolation :serializable}]
    (projects/update-legacy-pseudonymous-patient! txn legacy-global-pseudonym-salt patient-pk data)))

;; demographic matching
(defn exact-match-by-identifier [{:keys [conn]} fhir-patient]
  (demog/exact-match-by-identifier conn fhir-patient))

(defn exact-match-on-demography [{:keys [conn]} fhir-patient]
  (demog/exact-match-on-demography conn fhir-patient))

(defn update-patient
  ([{:keys [conn]} demographic-service patient]
   (demog/update-patient conn demographic-service patient))
  ([{:keys [conn]} demographic-service patient opts]
   (demog/update-patient conn demographic-service patient opts)))

(defn patient->results [{:keys [conn]} patient-identifier]
  (results/results-for-patient conn patient-identifier))

(defn encounter-by-id [{:keys [conn]} encounter-id]
  (patients/encounter-by-id conn encounter-id))

(defn ^:deprecated encounter->users [{:keys [conn]} encounter-id]
  (patients/encounter->users conn encounter-id))

(defn encounter-templates-by-ids [{:keys [conn]} encounter-template-ids]
  (patients/encounter-templates-by-ids conn encounter-template-ids))

(defn encounter-type-by-id
  [{:keys [conn]} encounter-type-id]
  (patients/encounter-type-by-id conn encounter-type-id))

(defn ^:deprecated encounter-ids->form-edss [{:keys [conn]} encounter-ids]
  (patients/encounter-ids->form-edss conn encounter-ids))

(defn ^:deprecated encounter-ids->form-edss-fs [{:keys [conn]} encounter-ids]
  (patients/encounter-ids->form-edss-fs conn encounter-ids))

(defn ^:deprecated encounter-ids->form-ms-relapse [{:keys [conn]} encounter-ids]
  (patients/encounter-ids->form-ms-relapse conn encounter-ids))

(defn ^:deprecated encounter-ids->form-weight-height
  [{:keys [conn]} encounter-ids]
  (patients/encounter-ids->form-weight-height conn encounter-ids))

(defn ^:deprecated encounter-id->form-smoking-history
  [{:keys [conn]} encounter-id]
  (forms/encounter->form_smoking_history conn encounter-id))

;; MSSS

(defn msss-lookup
  "Return MSSS lookup data of the specified type, or Roxburgh, by default."
  ([_]
   (msss/msss-lookup {:type :roxburgh}))
  ([{:keys [conn hermes]} {msss-type :type :as params}]
   (if (= :db msss-type)
     (msss/msss-lookup (assoc params :conn conn :ms-concept-ids (msss/multiple-sclerosis-concept-ids hermes)))
     (msss/msss-lookup params))))

(defn msss-for-duration-and-edss
  [msss-lookup disease-duration edss]
  (msss/msss-for-duration-and-edss msss-lookup disease-duration edss))

(defn derived-edss-over-time [msss-lookup params]
  (msss/derived-edss-over-time msss-lookup params))

;; value sets

(defn all-multiple-sclerosis-diagnoses
  [{:keys [conn]}]
  (patients/all-multiple-sclerosis-diagnoses conn))

(defn all-ms-event-types [{:keys [conn]}]
  (patients/all-ms-event-types conn))

(defn all-ms-disease-courses [{:keys [conn]}]
  (patients/all-ms-disease-courses conn))

(def ms-event-is-relapse? patients/ms-event-is-relapse?)

(def medication-reasons-for-stopping db/medication-reasons-for-stopping)

;;
;; forms
;;

(def edss-score->score forms/edss-score->score)

(defn encounter-id->forms-and-form-types [{:keys [conn]} encounter-id]
  (forms/forms-and-form-types-in-encounter conn encounter-id))

(defn encounter-id->forms
  ([{:keys [conn]} encounter-id]
   (forms/forms-for-encounter conn encounter-id))
  ([{:keys [conn]} encounter-id opts]
   (forms/forms-for-encounter conn encounter-id opts)))

;;
;;
;;

(defn mri-brains-for-patient
  [{:keys [conn]} patient-identifier]
  (results/mri-brains-for-patient conn patient-identifier))

;; projects

(defn episode-by-id [{:keys [conn]} episode-id]
  (patients/episode-by-id conn episode-id))

(defn project-by-id [{:keys [conn]} project-id]
  (projects/fetch-project conn project-id))

(defn project-by-name [{:keys [conn]} project-name]
  (projects/project-with-name conn project-name))

(def project->active? projects/active?)

(def episode-status projects/episode-status)

(defn projects->administrator-users
  [{:keys [conn]} project-ids]
  (users/administrator-users conn project-ids))

(defn episode-id->encounters
  [{:keys [conn]} episode-id]
  (patients/episode-id->encounters conn episode-id))

(defn projects->count-registered-patients
  ([{:keys [conn]} project-ids]
   (projects/count-registered-patients conn project-ids))
  ([{:keys [conn]} project-ids on-date]
   (projects/count-registered-patients conn project-ids on-date)))

(defn projects->count-pending-referrals
  ([{:keys [conn]} project-ids]
   (projects/count-pending-referrals conn project-ids))
  ([{:keys [conn]} project-ids on-date]
   (projects/count-pending-referrals conn project-ids on-date)))

(defn projects->count-discharged-episodes
  ([{:keys [conn]} project-ids]
   (projects/count-discharged-episodes conn project-ids))
  ([{:keys [conn]} project-ids on-date]
   (projects/count-discharged-episodes conn project-ids on-date)))

(defn project-title->slug [s]
  (projects/make-slug s))

(defn project->encounter-templates
  [{:keys [conn]} project-id]
  (projects/project->encounter-templates conn project-id))

(defn project->all-parent-ids
  [{:keys [conn]} project-id-or-ids]
  (projects/all-parents-ids conn project-id-or-ids))

(defn project->all-parents [{:keys [conn]} project-id]
  (projects/all-parents conn project-id))

(defn project->all-children-ids
  [{:keys [conn]} project-id-or-ids]
  (projects/all-children-ids conn project-id-or-ids))

(defn project->all-children
  [{:keys [conn]} project-id]
  (projects/all-children conn project-id))

(defn project->common-concepts
  [{:keys [conn]} project-id-or-ids]
  (projects/common-concepts conn project-id-or-ids))

(defn project->users
  [{:keys [conn]} project-id opts]
  (projects/fetch-users conn project-id opts))

(defn register-completed-episode!
  [{:keys [conn]} episode]
  (projects/register-completed-episode! conn episode))

(defn discharge-episode!
  [{:keys [conn]} user-id episode]
  (projects/discharge-episode! conn user-id episode))

(defn project-ids->patient-ids
  ([{:keys [conn]} project-ids]
   (patients/patient-ids-in-projects conn project-ids)))

(defn consented-patients
  [{:keys [conn]} consent-form-ids opts]
  (projects/consented-patients conn consent-form-ids opts))

;; mutations

(defn create-diagnosis!
  [{:keys [conn]} patient diagnosis]
  (patients/create-diagnosis! conn patient diagnosis))

(defn update-diagnosis!
  [{:keys [conn]} diagnosis]
  (patients/update-diagnosis conn diagnosis))

(defn upsert-medication!
  [{:keys [conn]} med]
  (jdbc/with-transaction [txn conn {:isolation :serializable}]
    (patients/upsert-medication! txn med)))

(defn delete-medication! [{:keys [conn]} medication]
  (patients/delete-medication! conn medication))

(defn save-ms-diagnosis!
  [{:keys [conn]} params]
  (patients/save-ms-diagnosis! conn params))

(defn save-pseudonymous-patient-lsoa!
  [{:keys [conn ods]} patient-identifier postcode]
  (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
    (if (str/blank? postcode)
      (patients/save-pseudonymous-patient-lsoa! txn {:t_patient/patient_identifier patient-identifier
                                                     :uk.gov.ons.nhspd/LSOA11      ""})
      (let [pc (ods/fetch-postcode ods postcode)]
        (patients/save-pseudonymous-patient-lsoa! txn {:t_patient/patient_identifier patient-identifier
                                                       :uk.gov.ons.nhspd/LSOA11      (get pc "LSOA11")})))))

(defn save-ms-event!
  [{:keys [conn]} ms-event]
  (patients/save-ms-event! conn ms-event))

(defn delete-ms-event! [{:keys [conn]} ms-event]
  (patients/delete-ms-event! conn ms-event))

(defn ^:deprecated save-encounter-and-forms!
  [{:keys [conn]} encounter]
  (forms/save-encounter-and-forms! conn encounter))

(defn delete-episode!
  [{:keys [conn]} episode-id]
  (projects/delete-episode! conn episode-id))

(defn delete-encounter! [{:keys [conn]} encounter-id]
  (patients/delete-encounter! conn encounter-id))

(defn unlock-encounter! [{:keys [conn]} encounter-id]
  (patients/unlock-encounter! conn encounter-id))

(defn lock-encounter! [{:keys [conn]} encounter-id]
  (patients/lock-encounter! conn encounter-id))

(defn create-form! [{:keys [conn]} params]
  (forms/create-form! conn params))

(defn save-form!
  [{:keys [conn]} form]
  (jdbc/with-transaction [txn conn]
    (forms/save-form! txn form)))

(defn delete-form! [{:keys [conn]} form]
  (forms/delete-form! conn form))

(defn undelete-form! [{:keys [conn]} form]
  (forms/undelete-form! conn form))

(def result-type-by-entity-name results/result-type-by-entity-name)

(defn save-result! [{:keys [conn]} result]
  (jdbc/with-transaction [txn conn]
    (results/save-result! txn result)))

(defn delete-result! [{:keys [conn]} result]
  (results/delete-result! conn result))

(defn set-date-death! [{:keys [conn]} patient]
  (patients/set-date-death conn patient))

(defn notify-death! [{:keys [conn]} params]
  (jdbc/with-transaction [txn conn]
    (patients/notify-death! txn conn)))

(defn patient->set-date-death!
  [{:keys [conn]} params]
  (patients/set-date-death conn params))

(defn save-admission!
  [{:keys [conn]} user-id episode]
  (projects/save-admission! conn user-id episode))

(defn ^:deprecated set-cav-authoritative-demographics!
  [{:keys [conn ods]} patient patient-hospital]
  (jdbc/with-transaction [txn conn]
    (patients/set-cav-authoritative-demographics! ods txn patient patient-hospital)))

