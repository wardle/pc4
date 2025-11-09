(ns pc4.rsdb.interface
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [honey.sql :as sql]
    [integrant.core :as ig]
    [next.jdbc :as jdbc]
    [next.jdbc.plan :as plan]
    [pc4.demographic.interface :as demographic]
    [pc4.log.interface :as log]
    [pc4.ods.interface :as ods]
    [pc4.rsdb.auth :as auth]
    [pc4.rsdb.db :as db]
    [pc4.rsdb.encounters :as encounters]
    [pc4.rsdb.migrations :as migrations]
    [pc4.rsdb.nform.api :as nf]
    [pc4.rsdb.forms :as forms]
    [pc4.rsdb.demographics :as demog]
    [pc4.rsdb.jobs]                                         ;; register queue job handlers
    [pc4.rsdb.messages :as messages]
    [pc4.rsdb.msss :as msss]
    [pc4.rsdb.nform.impl.forms.araf :as araf]
    [pc4.rsdb.nform.impl.registry :as registry]
    [pc4.rsdb.patients :as patients]
    [pc4.rsdb.projects :as projects]
    [pc4.rsdb.results :as results]
    [pc4.rsdb.users :as users]
    [pc4.snomed.interface :as hermes]
    [pc4.wales-nadex.interface :as nadex]
    [next.jdbc.connection :as connection]
    [next.jdbc.specs])
  (:import (com.zaxxer.hikari HikariDataSource)))

(s/def ::conn :next.jdbc.specs/proto-connectable)
(s/def ::form-store nf/form-store?)
(s/def ::wales-nadex nadex/valid-service?)
(s/def ::hermes any?)
(s/def ::ods ods/valid-service?)
(s/def ::demographic any?)
(s/def ::legacy-global-pseudonym-salt string?)

(s/def ::service-config
  (s/keys :req-un [::conn ::wales-nadex ::hermes ::ods ::demographic ::legacy-global-pseudonym-salt]))

(defmethod ig/init-key ::conn
  [_ config]
  (log/info "opening PatientCare EPR [rsdb] database connection" (if (map? config) (dissoc config :password) config))
  (connection/->pool HikariDataSource config))

(defmethod ig/halt-key! ::conn
  [_ conn]
  (log/info "closing PatientCare EPR [rsdb] database connection")
  (when conn (.close conn)))

(defmethod ig/init-key ::svc
  [_ {:keys [conn] :as config}]
  (when-not (s/valid? ::service-config config)
    (throw (ex-info "invalid rsdb svc config" (s/explain-data ::service-config config))))
  (assoc config :form-store (nf/make-form-store conn)))

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

(defn execute!                                              ;;; TODO: remove
  [{:keys [conn]} sql]
  (db/execute! conn sql))

(defn execute-one!                                          ;;; TODO: remove
  [{:keys [conn]} sql]
  (db/execute! conn sql))

(defn plan!                                                 ;;; TODO: remove
  [{:keys [conn]} sql]
  (jdbc/plan conn sql))

(defn select!                                               ;;; TODO: remove
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
;; TODO: and independent of any particular implementation
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

(defn user-by-id
  ([{:keys [conn]} user-id]
   (users/fetch-user-by-id conn user-id))
  ([{:keys [conn]} user-id {:keys [with-credentials] :as opts}]
   (users/fetch-user-by-id conn user-id opts)))

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

(defn user->hospitals
  [{:keys [conn]} user-id]
  (users/user->hospitals conn user-id))

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

(defn reset-password! [{:keys [conn]} user]                 ;;TODO: standardise to user id or username
  (users/reset-password! conn user))

(defn set-must-change-password! [{:keys [conn]} username]
  (users/set-must-change-password! conn username))

(defn search-users
  "Search for users by name or username."
  [{:keys [conn]} s opts]
  (users/search-users conn s opts))

(defn fetch-users
  "Fetch users for a project."
  [{:keys [conn]} project-id params]
  (projects/fetch-users conn project-id params))
;;

;; patients

(defn patients
  [{:keys [conn]} params]
  (patients/search conn params))

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

(defn patient-pk->professionals [{:keys [conn]} patient-pk]
  (patients/patient-pk->professionals conn patient-pk))

(defn patient-pk->crn-for-org
  [{:keys [conn ods]} patient-pk org-id]
  (patients/patient-pk->crn-for-org conn patient-pk ods org-id))

(defn make-best-hospital-crn-fn
  [{:keys [conn ods]} project-id]
  (patients/best-patient-crn-fn conn ods project-id))

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

(defn diagnosis-by-id
  [{:keys [conn]} diagnosis-id]
  (patients/diagnosis-by-id conn diagnosis-id))

(comment
  (require '[pc4.config.interface])
  (def rsdb (ig/init (pc4.config.interface/config :dev) [:pc4.rsdb.interface/svc])))

(def diagnosis-active? patients/diagnosis-active?)

(defn patient->summary-multiple-sclerosis
  [{:keys [conn]} patient-identifier]
  (patients/fetch-summary-multiple-sclerosis conn patient-identifier))

(defn patient->ms-events [{:keys [conn]} sms-id]
  (patients/fetch-ms-events conn sms-id))

(defn ms-event-by-id [{:keys [conn]} ms-event-id]
  (patients/fetch-ms-event conn ms-event-id))

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

(defn calculate-total-daily-dose
  [medication]
  (patients/calculate-medication-daily-dose medication))

(defn patient->addresses [{:keys [conn]} patient]
  (patients/fetch-patient-addresses conn patient))

(def address-for-date patients/address-for-date)

(defn patient->episodes
  ([{:keys [conn]} patient-pk project-id-or-ids]
   (patients/patient->episodes conn patient-pk project-id-or-ids))
  ([{:keys [conn]} patient-pk]
   (patients/patient->episodes conn patient-pk)))

(defn list-encounters
  [{:keys [conn]} params]
  (jdbc/execute! conn (sql/format (encounters/q-encounters params))))

(defn patient->encounters [{:keys [conn]} patient-pk]
  (patients/patient->encounters conn patient-pk))

(defn patient->active-encounter-ids
  "Return a set of active encounter ids for the given patient."
  [{:keys [conn]} patient]
  (patients/patient->active-encounter-ids conn patient))

(defn ^:deprecated ms-event->patient-identifier
  [{:keys [conn]} ms-event]
  (patients/patient-identifier-for-ms-event conn ms-event)) ;; TODO: remove!

(defn suggested-registrations
  "Given a user, and a patient, return a sequence of projects that could be
  suitable registrations. This could, in the future, use diagnoses and problems
  as a potential way to configure the choice."
  [rsdb {:t_user/keys [username]} {:t_patient/keys [patient_identifier] :as params}]
  (when (or (nil? username) (nil? patient_identifier))
    (throw (ex-info "missing username or password" params)))
  (let [roles (user->roles rsdb username)
        project-ids (patient->active-project-identifiers rsdb patient_identifier)]
    (->> (projects-with-permission roles :PATIENT_REGISTER)
         (filter :t_project/active?)                        ;; only return currently active projects
         (remove #(project-ids (:t_project/id %)))          ;; remove any projects to which patient already registered
         (map #(select-keys % [:t_project/id :t_project/title]))))) ;; only return data relating to projects

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

;; demographic matching
(defn exact-match-by-identifier [{:keys [conn]} fhir-patient]
  (demog/exact-match-by-identifier conn fhir-patient))

(defn exact-match-on-demography [{:keys [conn]} fhir-patient]
  (demog/exact-match-on-demography conn fhir-patient))

(defn project-patient-search
  "Search for a patient by identifier for project registration.

  Parameters:
  - :system - identifier system
  - :value - identifier value
  - :project-id - project context
  - :provider-id - (optional) external provider to search if not found locally

  Returns:
  - :outcome - :local-match | :local-multiple | :external-match | :external-multiple | :not-found
  - :patient-pk - when single local match
  - :patient-identifier - when single local match
  - :banner - when single local match
  - :already-registered? - when single local match
  - :fhir-patient - when external match
  - :provider-id - when external match or not found
  - :provider-title - when external match or not found
  - :system - when external match or not found
  - :value - when external match or not found
  - :duplicate-ids - when external match (may be empty)
  - :fhir-patients - when multiple external matches
  - :patient-pks - when multiple local matches"
  [{:keys [conn] :as svc} demographic-svc {:keys [system value project-id provider-id]}]
  (let [search-patient {:org.hl7.fhir.Patient/identifier
                        [{:org.hl7.fhir.Identifier/system system
                          :org.hl7.fhir.Identifier/value  value}]}
        local-matches (exact-match-by-identifier svc search-patient)]
    (cond
      (> (count local-matches) 1)
      {:outcome :local-multiple :patient-pks local-matches}

      (= (count local-matches) 1)
      (let [patient-pk (first local-matches)
            patient-identifier (patients/pk->identifier conn patient-pk)
            episodes (projects/episodes-for-patient-in-project conn {:t_patient/id patient-pk} project-id)
            already-registered? (some #(= :registered (projects/episode-status %)) episodes)]
        {:outcome :local-match
         :patient-pk patient-pk
         :patient-identifier patient-identifier
         :already-registered? already-registered?})

      provider-id
      (let [provider (demographic/provider-by-id demographic-svc provider-id)
            provider-title (:title provider)
            fhir-patients (demographic/patients-by-identifier demographic-svc system value {:provider-id provider-id})
            n-results (count fhir-patients)]
        (cond
          (zero? n-results)
          {:outcome :not-found
           :provider-id provider-id
           :provider-title provider-title
           :value value}

          (> n-results 1)
          {:outcome :external-multiple
           :fhir-patients fhir-patients
           :provider-id provider-id
           :provider-title provider-title
           :system system
           :value value}

          :else
          (let [fhir-patient (first fhir-patients)
                exact-by-identifier (or (exact-match-by-identifier svc fhir-patient) #{})
                exact-by-demography (or (exact-match-on-demography svc fhir-patient) #{})
                duplicate-ids (into exact-by-identifier exact-by-demography)]
            {:outcome :external-match
             :fhir-patient fhir-patient
             :provider-id provider-id
             :provider-title provider-title
             :system system
             :value value
             :duplicate-ids duplicate-ids})))

      :else
      {:outcome :not-found :value value})))

(defn project-patient-register
  "Register a patient to a project.

  Takes either an existing patient-pk or FHIR patient data.
  If FHIR data provided, upserts patient first.

  Parameters:
  - :patient-pk - (optional) existing patient primary key
  - :fhir-patient - (optional) FHIR patient data to upsert
  - :project-id - project identifier
  - :user-id - user performing registration

  Returns patient primary key."
  [{:keys [conn] :as svc} {:keys [patient-pk fhir-patient project-id user-id] :as params}]
  (cond
    patient-pk
    (do
      (register-patient-project! svc project-id user-id {:t_patient/id patient-pk})
      patient-pk)
    fhir-patient
    (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
      (let [{:keys [patient-pk]} (patients/create-patient-from-fhir! txn fhir-patient)]
        (projects/register-patient-project! txn project-id user-id {:t_patient/id patient-pk})
        patient-pk))
    :else
    (throw (ex-info "must specify either patient-pk or fhir-patient" params ))))

(defn update-legacy-pseudonymous-patient!
  [{:keys [conn legacy-global-pseudonym-salt]} patient-pk data]
  (jdbc/with-transaction [txn conn {:isolation :serializable}]
    (projects/update-legacy-pseudonymous-patient! txn legacy-global-pseudonym-salt patient-pk data)))

;; demographic updates
(defn update-patient-from-authority! [{:keys [conn demographic]} patient-pk]
  (patients/update-patient-from-authority! conn demographic patient-pk))

(defn create-patient-from-fhir! [{:keys [conn]} fhir-patient]
  (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
    (patients/create-patient-from-fhir! txn fhir-patient)))

(defn upsert-patient-from-fhir! [{:keys [conn]} fhir-patient]
  (jdbc/with-transaction [txn conn {:isolation :serializable}]
    (patients/upsert-patient-from-fhir! txn fhir-patient)))

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

(def ^:deprecated edss-score->score forms/edss-score->score)

(defn ^:deprecated encounter-id->forms-and-form-types [{:keys [conn]} encounter-id]
  (forms/forms-and-form-types-in-encounter conn encounter-id))

(defn ^:deprecated encounter-id->forms
  ([{:keys [conn]} encounter-id]
   (forms/forms-for-encounter conn encounter-id))
  ([{:keys [conn]} encounter-id opts]
   (forms/forms-for-encounter conn encounter-id opts)))


;;
;; new forms - upsert / fetch one / fetch multiple
;;

(defn upsert-form [{:keys [form-store]} form]
  (nf/upsert! form-store form))

(defn form [{:keys [form-store]} id]
  (nf/form form-store id))

(defn forms
  "Fetch all forms matching the parameters specified.
  - :id           - form identifier
  - :patient-pk   - patient pk
  - :encounter-id - encounter id
  - :encounter-ids
  - :form-type    - forms of this type
  - :form-types   - forms of these type
  - :is-deleted   - whether to include deleted forms (true, false or nil)
  - :select       - optional data to return
                    |- :date-time  - include encounter date time"
  [{:keys [form-store]} params]
  (nf/forms form-store params))

(s/fdef araf-outcome
  :args (s/cat :svc ::svc :programme ::araf/programme :patient-pk :t_patient/id))
(defn araf-outcome
  "Return 'outcome' summary for the specified patient within the given ARAF
  programme."
  [svc programme patient-pk]
  (let [forms (forms svc {:patient-pk patient-pk
                          :is-deleted false
                          :form-types (araf/forms-for-programme programme)})]
    (araf/outcome programme forms {})))

(s/fdef araf-outcome-with-forms
  :args (s/cat :svc ::svc :programme ::araf/programme :patient-pk :t_patient/id))
(defn araf-outcome-with-forms
  "Return the 'outcome' summary, the forms used to compute it, and available forms
  for the specified patient within the given ARAF programme.
  Returns a map with :outcome, :forms, and :available keys."
  [svc programme patient-pk]
  (let [form-types (araf/forms-for-programme programme)
        forms (forms svc {:patient-pk  patient-pk
                          :is-deleted  false
                          :select      #{:date-time}
                          :form-types  form-types})
        outcome (araf/outcome programme forms {})
        available (map registry/form-definition-by-form-type form-types)]
    {:outcome outcome
     :forms   forms
     :available available}))

(s/fdef araf-programme-outcome
  :args (s/cat :svc ::svc :programme ::araf/programme :project-id :t_project/id))
(defn araf-programme-outcome
  "Return a sequence of patients in the given project with outcome information in the given 'programme'.
  For example,
  ```
  (araf-programme-outcome rsdb :valproate-f 15)
  =>
  [{:t_patient/patient_identifier ...
   :valproate-f {:excluded :permanent, :completed true, :expires nil}}
   ... ]
  ```"
  [rsdb programme project-id]
  (->> (patients rsdb {:query {:select [:*] :from :t_patient}
                            :address? true
                            :hospital-identifier true
                            :project-ids [project-id]})
       (map (fn [{patient-pk :t_patient/id, :as pt}]
              (assoc pt programme (araf-outcome rsdb programme patient-pk))))))

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

(defn project->default-hospital-orgs
  [{:keys [conn]} project-id]
  (projects/project->default-hospital-org-ids conn project-id))

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

(defn save-encounter!
  "Save an encounter. Creates a new encounter if no :t_encounter/id is provided,
  otherwise updates existing encounter. Automatically sets lock_date_time to 12 hours
  from encounter date_time on creation."
  [{:keys [conn]} encounter]
  (patients/save-encounter! conn encounter))

(defn delete-episode!
  [{:keys [conn]} episode-id]
  (projects/delete-episode! conn episode-id))

(defn delete-encounter! [{:keys [conn]} encounter-id]
  (patients/delete-encounter! conn encounter-id))

(defn unlock-encounter! [{:keys [conn]} encounter-id]
  (patients/unlock-encounter! conn encounter-id))

(defn lock-encounter! [{:keys [conn]} encounter-id]
  (patients/lock-encounter! conn encounter-id))

(defn set-encounter-users! [{:keys [conn]} encounter-id user-ids]
  (patients/set-encounter-users! conn encounter-id user-ids))

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

(defn send-message
  [conn to-user-id from-user-id patient-identifier subject body]
  (messages/send-message conn to-user-id from-user-id patient-identifier subject body))
