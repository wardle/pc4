(ns com.eldrix.pc4.rsdb.projects
  "Support for rsdb projects. These represent logical clinical services or
   clinical research projects. They are fundamental in supporting the
   finely-grained security model; linked to patients by virtue of an 'episode'
   and linked to professionals by virtue of user registration.

   t_patient <<->> t_episode <<-> t_project <<-> t_project_user <-> t_user

   Many projects are long-lived and keep patients registered for the long-term.
   Others are configured to automatically discharge a patient after an interval.
   Many users are authorised to register and discharge patients from projects
   but such authorisation is on a per-project basis."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.nhs-number :as nhs-number]
            [com.eldrix.pc4.rsdb.db :as db]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.plan]
            [next.jdbc.sql]
            [clojure.set :as set])
  (:import (java.sql Connection)
           (java.time LocalDate)
           (java.text Normalizer Normalizer$Form)
           (java.time.format DateTimeFormatter)
           (org.apache.commons.codec.digest DigestUtils)))


(s/def ::date-birth
  (s/with-gen #(instance? LocalDate %)
              #(gen/fmap (fn [days] (.minusDays (LocalDate/now) days))
                         (s/gen (s/int-in 0 (* 365 100))))))

(s/def ::nhs-number com.eldrix.concierge.nhs-number/valid?)
(s/def ::salt string?)
(s/def ::project-id pos-int?)
(s/def ::user-id pos-int?)
(s/def ::sex #{:MALE :FEMALE :UNKNOWN "MALE" "FEMALE" "UNKNOWN"})
(s/def ::project-name string?)
(s/def ::adopt-pending? boolean?)
(s/def ::pseudonym string?)
(s/def ::group-by #{:none :user})
(s/def ::active? boolean?)


(defn role-active?
  "Determine the status of the role as of now, or on the specified date."
  ([role] (role-active? role (LocalDate/now)))
  ([{:t_project_user/keys [^LocalDate date_from ^LocalDate date_to]} ^LocalDate on-date]
   (and (or (nil? date_from)
            (.equals on-date date_from)
            (.isAfter on-date date_from))
        (or (nil? date_to)
            (.isBefore on-date date_to)))))

(defn- fetch-users*
  "Fetch all users for the project specified, with an individual potentially
  being listed more than once if they have more than one 'role'."
  [conn project-id]
  (db/execute! conn (sql/format
                      {:select    [:t_user/id :role :date_from :date_to :title
                                   :first_names :last_name :email :username
                                   :t_job_title/name :custom_job_title]
                       :from      [:t_project_user]
                       :left-join [:t_user [:= :user_fk :t_user/id]
                                   :t_job_title [:= :job_title_fk :t_job_title/id]]
                       :where     [:= :project_fk project-id]
                       :order-by  [:last_name :first_names]})))

(s/fdef fetch-users
  :args (s/cat :conn ::db/conn :project-id ::project-id :params (s/? (s/keys :opt-un [::group-by ::active?]))))
(defn fetch-users
  "Fetch users for the project specified. An individual may be listed more than
  once if they have more than one 'role' within the project although this
  depends on the :group-by parameter. For backwards-compatibility, no grouping
  is used, by default. However, it would be usual to use {:group-by :user} for
  most use cases.
  Parameters:
  - conn       : database connection
  - project-id : project identifier
  - params     : optional parameters:
                 |- :active   - true, false or nil
                       |- true  : only active records are returned.
                       |- false : only inactive records are returned
                       |- nil   : all records are returned
                 |- :on-date   - date on which 'active' will be determined
                 |- :group-by - one of :none or :user.

  The operation of :active depends on whether records are grouped by user. "
  ([conn project-id] (fetch-users conn project-id {}))
  ([conn project-id {grp-by :group-by, active :active, on-date :on-date, :or {grp-by :none}}]
   (let [on-date' (or on-date (LocalDate/now))
         users (->> (fetch-users* conn project-id) (map #(assoc % :t_project_user/active? (role-active? % on-date'))))]
     (case grp-by
       :user                                                ;; if we are grouping by user, implement our own 'group-by', returning :t_user/roles containing all roles for that user
       (cond->> (vals (reduce (fn [acc {id :t_user/id ractive :t_project_user/active? :as user}]
                                (let [roles (conj (get-in acc [id :t_user/roles]) (select-keys user [:t_project_user/date_to :t_project_user/date_from :t_project_user/role :t_project_user/active?]))]
                                  (assoc acc id (-> user
                                                    (update :t_user/active? #(or % ractive)) ;; a user is active if any of their roles for this project are active
                                                    (dissoc :t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?)
                                                    (assoc :t_user/roles roles))))) {} users))
                (true? active) (filter :t_user/active?)
                (false? active) (remove :t_user/active?))
       (cond->> users
                (true? active) (filter :t_project_user/active?)
                (false? active) (remove :t_project_user/active?))))))


(def consented-patients-sql
  "SQL returning patient identifiers for those who have consented using the
  consent form with an identifier in `consent-form`ids`. This looks for the most
  recent consent forms of those identifiers for each patient and then looks to
  see whether any of the responses of behaviour 'PARTICIPATE' is of value `response`"
  {:with   [[:consent-forms {:select-distinct-on [[:patient_identifier] :patient_identifier :t_form_consent/id :t_encounter/date_time]
                             :from               [:t_form_consent :t_encounter :t_patient]
                             :where              [:and
                                                  [:= :encounter_fk :t_encounter/id]
                                                  [:= :patient_fk :t_patient/id]
                                                  [:= :t_form_consent/is_deleted "false"]
                                                  [:= :t_encounter/is_deleted "false"]
                                                  [:in :consent_form_fk :?consent-form-ids]]
                             :order-by           [[:patient_identifier :asc] [:date_time :desc]]}]
            [:consent-items {:select :* :from :t_consent_item :where [:and [:in :consent_form_fk :?consent-form-ids]
                                                                      [:= :behaviour "PARTICIPATE"]]}]]
   :select :consent-forms/patient_identifier :from [:t_consent_item_response :consent-items :consent-forms]
   :where  [:and [:= :consent_item_fk :consent-items/id]
            [:= :form_consent_fk :consent-forms/id]
            [:= :t_consent_item_response/response :?response]]})

(defn consented-patients
  "Return a set of patient identifiers who have given the specified response
  to participate on the most recent completion of any of the consent forms
  specified."
  [conn consent-form-ids {:keys [response] :or {response "AGREE"}}]
  (into #{} (map :t_patient/patient_identifier)
        (jdbc/plan conn (sql/format consented-patients-sql {:params {:consent-form-ids consent-form-ids
                                                                     :response response}}))))


(comment
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (require '[clojure.spec.test.alpha :as stest])
  (clojure.spec.test.alpha/instrument)
  (fetch-users conn 5))

(defn fetch-project-sql [project-id]
  (sql/format {:select :* :from :t_project
               :where  [:= :t_project/id project-id]}))

(defn fetch-projects-sql [ids]
  (sql/format {:select :* :from :t_project :where [:in :id ids]}))

(defn all-children-sql [project-id]
  (sql/format {:with-recursive
               [[:children
                 {:union-all [{:select [:t_project/id :t_project/parent_project_fk] :from :t_project
                               :where  [:= :id project-id]}
                              {:select [:t_project/id :t_project/parent_project_fk] :from :t_project
                               :join   [:children [:= :t_project/parent_project_fk :children.id]]}]}]]
               :select :children/id
               :from   :children
               :where  [:!= :id project-id]}))

(defn all-parents-sql [project-id]
  (sql/format {:with-recursive
               [[:parents
                 {:union-all [{:select [:t_project/id :t_project/parent_project_fk] :from :t_project
                               :where  [:= :id project-id]}
                              {:select [:t_project/id :t_project/parent_project_fk] :from :t_project
                               :join   [:parents [:= :parents/parent_project_fk :t_project/id]]}]}]]
               :select :parents/id
               :from   :parents
               :where  [:!= :id project-id]}))

(defn all-children-ids
  "Return a set of project identifiers representing the children of the given
  project(s)."
  [conn project-id-or-project-ids]
  (set (cond (number? project-id-or-project-ids)
             (next.jdbc.plan/select! conn :id (all-children-sql project-id-or-project-ids))
             (coll? project-id-or-project-ids)
             (mapcat #(next.jdbc.plan/select! conn :id (all-children-sql %)) project-id-or-project-ids))))

(defn all-parents-ids
  "Return a set of project ids representing the parents of the given project(s)."
  [conn project-id-or-project-ids]
  (set (cond (number? project-id-or-project-ids)
             (next.jdbc.plan/select! conn :id (all-parents-sql project-id-or-project-ids))
             (coll? project-id-or-project-ids)
             (mapcat #(next.jdbc.plan/select! conn :id (all-parents-sql %)) project-id-or-project-ids))))

(defn all-children [conn project-id]
  (when-let [children-ids (seq (all-children-ids conn project-id))]
    (db/execute! conn (fetch-projects-sql children-ids))))

(defn all-parents [conn project-id]
  (when-let [parent-ids (seq (all-parents-ids conn project-id))]
    (db/execute! conn (fetch-projects-sql parent-ids))))

(defn fetch-project [conn project-id]
  (db/execute-one! conn (fetch-project-sql project-id)))

(defn common-concepts
  "Return a set of common concept ids for the project(s) and its ancestors."
  [conn project-id-or-project-ids]
  (let [all-parents (all-parents-ids conn project-id-or-project-ids)
        project-ids (cond (number? project-id-or-project-ids)
                          (conj all-parents project-id-or-project-ids)
                          (coll? project-id-or-project-ids)
                          (set/union (all-parents-ids conn project-id-or-project-ids) (set project-id-or-project-ids)))]
    (into #{} (map :conceptconceptid)
          (jdbc/plan conn (sql/format {:select-distinct :conceptconceptid
                                       :from            :t_project_concept
                                       :where           [:in :projectid project-ids]})))))

(defn project-with-name [conn nm]
  (db/execute-one! conn (sql/format {:select :* :from :t_project
                                     :where  [:= :t_project/name nm]})))


(defn count-registered-patients
  ([conn project-ids] (count-registered-patients conn project-ids (LocalDate/now)))
  ([conn project-ids on-date]
   (:count (db/execute-one! conn
                            (sql/format {:select [[[:count [:distinct :patient_fk]]]]
                                         :from   :t_episode
                                         :where  [:and
                                                  [:in :project_fk project-ids]
                                                  [:or
                                                   [:is :t_episode/date_discharge nil]
                                                   [:> :date_discharge on-date]]
                                                  [:or
                                                   [:is :date_registration nil]
                                                   [:< :date_registration on-date]
                                                   [:= :date_registration on-date]]]})))))
(defn count-discharged-episodes
  "Return a count of discharged episodes.
  Note: this is not the same as number of discharged patients."
  ([conn project-ids] (count-discharged-episodes conn project-ids (LocalDate/now)))
  ([conn project-ids on-date]
   (:count (db/execute-one! conn
                            (sql/format {:select [:%count.id]
                                         :from   :t_episode
                                         :where  [:and
                                                  [:in :project_fk project-ids]
                                                  [:or
                                                   [:= :t_episode/date_discharge on-date]
                                                   [:< :date_discharge on-date]]]})))))
(defn count-pending-referrals
  "Returns the number of referrals pending for the projects"
  ([conn project-ids] (count-pending-referrals conn project-ids (LocalDate/now)))
  ([conn project-ids on-date]
   (:count (db/execute-one! conn
                            (sql/format {:select [[[:count [:distinct :patient_fk]]]]
                                         :from   :t_episode
                                         :where  [:and
                                                  [:in :project_fk project-ids]
                                                  [:or
                                                   [:is :t_episode/date_discharge nil]
                                                   [:> :date_discharge on-date]]
                                                  [:or
                                                   [:= :date_referral on-date]
                                                   [:< :date_referral on-date]]
                                                  [:or
                                                   [:is :date_registration nil]
                                                   [:> :date_registration on-date]]]})))))

(defn active?
  "Is this project active?"
  ([project] (active? project (LocalDate/now)))
  ([{:t_project/keys [^LocalDate date_to ^LocalDate date_from]} ^LocalDate date]
   (and (or (nil? date_from)
            (.isEqual date date_from)
            (.isAfter date date_from))
        (or (nil? date_to)
            (.isBefore date date_to)))))

(defn episode-status
  "Determine the status of the episode as of now, or on the specified date.
  Parameters:
  - episode - a map containing:
      :t_episode/date_referral
      :t_episode/date_registration
      :t_episode/date_discharge
  - on-date : (optional) the date on which to derive status, default `now`

  Result one of :discharged :registered :referred or nil"
  ([episode] (episode-status episode (LocalDate/now)))
  ([{:t_episode/keys [^LocalDate date_referral date_registration date_discharge]} ^LocalDate on-date]
   (cond
     (and date_discharge (or (.isEqual on-date date_discharge) (.isAfter on-date date_discharge)))
     :discharged
     (and date_registration (or (.isEqual on-date date_registration) (.isAfter on-date date_registration)))
     :registered
     (and date_referral (or (.isEqual on-date date_referral) (.isAfter on-date date_referral)))
     :referred)))

(defn active-episode?
  "Is the episode active?"
  ([episode] (active-episode? episode (LocalDate/now)))
  ([episode ^LocalDate on-date]
   (contains? #{:registered :referred} (episode-status episode on-date))))

(defn make-hash-pseudonym
  "Create a legacy-compatible pseudonym using the identifiers specified.
  Include a salt as one of the identifiers to mitigate dictionary / brute-force
  attacks."
  [& identifiers]
  (DigestUtils/sha256Hex ^String (apply str identifiers)))


(s/fdef calculate-project-pseudonym
  :args (s/cat :project-name string? :identifier string? :date-birth ::date-birth))
(defn ^:deprecated calculate-project-pseudonym
  "Generate a legacy project-based pseudonym, based on:
   - the name of the project
   - the identifier ( e.g. nhs number)
   - the date of birth.

   For modern use, an alternate pseudonymisation strategy is recommended as
   this does not use a cryptographically secure salt but one derived from the
   project itself.

   This is compatible with the legacy project-specific pseudonymous strategy
   from RSNews/rsdb from 2008.

   TODO: Add a flexible configurable pseudonymous identifier strategy for each
   project - with versioned identifiers based on the strategy used. Add
   cryptographically secure salt to each project for v2 identifiers."
  [project-name identifier ^LocalDate date-birth]
  (make-hash-pseudonym (DigestUtils/md5Hex ^String project-name) identifier (.format date-birth (DateTimeFormatter/ISO_LOCAL_DATE))))

(s/fdef calculate-global-pseudonym
  :args (s/cat :salt string? :nhs-number string? :date-birth ::date-birth))
(defn calculate-global-pseudonym
  "Generate a legacy rsdb global pseudonym; the salt must match the secret
  salt used in the legacy rsdb application. For other uses, use a
  cryptographically secure salt and keep it secret."
  [salt nhs-number ^LocalDate date-birth]
  (make-hash-pseudonym salt nhs-number (.format date-birth (DateTimeFormatter/ISO_LOCAL_DATE))))

(def fetch-pseudonym-patient-properties
  [:t_patient/id :t_patient/patient_identifier :t_patient/first_names :t_patient/last_name
   :t_patient/date_birth :t_patient/sex :t_patient/nhs_number])

(defn fetch-by-global-pseudonym [conn pseudonym]
  (db/execute-one! conn (sql/format {:select fetch-pseudonym-patient-properties
                                     :from   :t_patient
                                     :where  [:= :stored_global_pseudonym pseudonym]})))

(defn fetch-by-project-pseudonym [conn project-name pseudonym]
  (db/execute-one! conn (sql/format {:select    fetch-pseudonym-patient-properties
                                     :from      :t_episode
                                     :left-join [:t_project [:= :project_fk :t_project/id]
                                                 :t_patient [:= :patient_fk :t_patient/id]]
                                     :where     [:and
                                                 [:= :t_project/name project-name]
                                                 [:= :stored_pseudonym pseudonym]]})))

(defn search-by-project-pseudonym
  "Search for a pseudonym in the specified project.
  This is a prefix search, with a minimum of three characters.
  A single result will only be returned if there is a single match using the
  search string specified."
  [conn project-id pseudonym]
  (when (>= (count pseudonym) 3)
    (let [results (jdbc/execute!
                    conn
                    (sql/format {:select    (into fetch-pseudonym-patient-properties [:t_episode/stored_pseudonym :t_episode/project_fk])
                                 :from      :t_episode
                                 :left-join [:t_project [:= :project_fk :t_project/id]
                                             :t_patient [:= :patient_fk :t_patient/id]]
                                 :where     [:and
                                             [:= :t_project/id project-id]
                                             [:like :stored_pseudonym (str pseudonym "%")]]
                                 :limit     2}))]
      (when (= 1 (count results))
        (db/parse-entity (first results))))))

(defn fetch-by-nhs-number [conn nnn]
  (db/execute-one! conn (sql/format {:select fetch-pseudonym-patient-properties
                                     :from   :t_patient
                                     :where  [:= :nhs_number nnn]})))

(defn fetch-episode [conn episode-id]
  (db/execute! conn (sql/format {:select [:*] :from :t_episode :where [:= :id episode-id]})))

(defn episodes-for-patient-in-project
  "Returns episodes for patient related to the specific project.
  Parameters:
  - conn        : database connection or pool
  - patient-pk  : patient primary key (NB: not the same as `patient-identifier`)
  - project-id  : project id"
  [conn patient-identifier project-id]
  (db/execute! conn (sql/format {:select     [:t_episode/*]
                                 :from       :t_episode
                                 :inner-join [:t_patient [:= :t_patient/id :t_episode/patient_fk]]
                                 :where      [:and
                                              [:= :patient_identifier patient-identifier]
                                              [:= :project_fk project-id]]})))


(s/fdef find-legacy-pseudonymous-patient
  :args (s/cat :conn ::db/conn
               :params (s/keys* :req-un [::salt (or ::project-id ::project-name)
                                         ::nhs-number ::date-birth])))
(defn ^:deprecated find-legacy-pseudonymous-patient
  "Attempts to identify a patient using the legacy rsdb pseudonym registration.
  Parameters:
  - conn - rsdb database connection
  - salt - salt to use for global pseudonym
  - project-id / project-name - specify either for project
  - nhs-number - NHS number
  - date-birth - java.time.LocalDate of date of birth

  Returns a patient record with keys:
  * global-pseudonym
  * project-pseudonym
  And with these additional keys, if a patient was found matching the search:
  * t_patient/id
  * t_patient/patient_identifier
  * t_patient/first_names
  * t_patient/last_name
  * t_patient/date_birth
  * t_patient/sex
  * t_patient/nhs_number"
  [conn & {:keys [salt project-id project-name nhs-number date-birth validate?] :or {validate? true} :as params}]
  (let [project-name (if project-name project-name (:t_project/name (fetch-project conn project-id)))
        project-pseudonym (calculate-project-pseudonym project-name nhs-number date-birth)
        global-pseudonym (calculate-global-pseudonym salt nhs-number date-birth)]
    (let [patient (or (fetch-by-project-pseudonym conn project-name project-pseudonym)
                      (fetch-by-global-pseudonym conn global-pseudonym))]
      (when (and validate? (not patient) (fetch-by-nhs-number conn nhs-number))
        (throw (ex-info "NHS number match but other details do not." {:params            params
                                                                      :patient           patient
                                                                      :pseudonyms        {:project           {:project-id   project-id
                                                                                                              :project-name project-name}
                                                                                          :project-pseudonym project-pseudonym
                                                                                          :global-pseudonym  global-pseudonym}
                                                                      :existing          (fetch-by-nhs-number conn nhs-number)
                                                                      :global-pseudonym  global-pseudonym
                                                                      :project-pseudonym project-pseudonym})))
      (merge (db/parse-entity patient)
             {:project-pseudonym          project-pseudonym
              :t_episode/stored_pseudonym project-pseudonym
              :t_episode/project_fk       project-id
              :global-pseudonym           global-pseudonym}))))

(s/fdef register-episode!
  :args (s/cat :conn ::db/conn :user-id pos-int? :episode (s/keys :req-un [:t_episode/id])))
(defn register-episode!
  "Sets the episode specified as registered as of now, by the user specified.
  Returns the updated episode.
  We do not worry about optimistic locking here, by design."
  [conn user-id {episode-id :t_episode/id}]
  (db/execute-one! conn
                   (sql/format {:update :t_episode
                                :set    {:t_episode/date_registration    (LocalDate/now)
                                         :t_episode/registration_user_fk user-id}
                                :where  [:= :t_episode/id episode-id]})
                   {:return-keys true}))

(s/fdef discharge-episode!
  :args (s/cat :conn ::db/conn :user-id pos-int? :episode (s/keys :req [:t_episode/id])))
(defn discharge-episode!
  "Sets the episode specified as discharged. Returns the updated episode.
  We do not worry about optimistic locking here, by design."
  [conn user-id {episode-id :t_episode/id}]
  (db/execute-one! conn
                   (sql/format {:update :t_episode
                                :set    {:t_episode/date_discharge    (LocalDate/now)
                                         :t_episode/discharge_user_fk user-id}
                                :where  [:= :t_episode/id episode-id]})
                   {:return-keys true}))

(s/fdef create-episode!
  :args (s/cat :conn ::db/conn
               :episode (s/keys :req [:t_episode/date_referral :t_episode/patient_fk :t_episode/project_fk :t_episode/referral_user_fk]
                                :opt [:t_episode/date_registration :t_episode/registration_user_fk
                                      :t_episode/date_discharge :t_episode/discharge_user_fk
                                      :t_episode/notes :t_episode/stored_pseudonym :t_episode/external_identifier])))
(defn create-episode!
  "Create a new episode, returning the data."
  [conn episode]
  (db/execute-one! conn
                   (sql/format {:insert-into [:t_episode]
                                :values      [episode]})
                   {:return-keys true}))

(s/fdef create-patient!
  :args (s/cat :txn ::db/txn :patient map?))
(defn create-patient!
  "Creates a patient.

  Frustratingly, the original designer of rsdb decided to have a default family
  identifier that was a textual representation of the primary key, by default.
  That's easy using WebObjects, because you can access the primary key in
  transaction. Here we fake it by getting the next value in the sequence and
  using that. Also, all patients need to be linked to a family, which seemed
  sensible at the time, and belies its genetic research database origins."
  [txn patient]
  (let [{patient-id :nextval} (jdbc/execute-one! txn ["select nextval('t_patient_seq')"])
        {family-id :nextval} (jdbc/execute-one! txn ["select nextval('t_family_seq')"])
        patient' (assoc patient
                   :t_patient/id patient-id
                   :t_patient/patient_identifier patient-id
                   :t_patient/family_fk family-id)]
    (jdbc/execute-one! txn (sql/format {:insert-into [:t_family]
                                        :values      [{:t_family/family_identifier (str family-id)
                                                       :t_family/id                family-id}]}))
    (db/execute-one! txn
                     (sql/format {:insert-into [:t_patient]
                                  :values      [patient']})
                     {:return-keys true})))

(s/fdef register-completed-episode!
  :args (s/cat :txn ::db/serializable-txn
               :episode (s/keys :req [:t_episode/patient_fk
                                      (or :t_episode/user_fk (and :t_episode/referral_user_fk :t_episode/registration_user_fk :t_episode/discharge_user_fk))
                                      :t_episode/date_registration :t_episode/date_discharge]
                                :opt [:t_episode/date_referral
                                      :t_episode/notes])))
(defn register-completed-episode!
  "Register a completed episode with start and end dates.
  Returns the newly created episode, or the existing episode if already exists.
  This operation is idempotent, by design."
  [txn {:t_episode/keys [patient_fk user_fk project_fk _date_referral date_registration date_discharge] :as episode}]
  (when (or (nil? date_registration) (nil? date_discharge) (.isAfter date_registration date_discharge))
    (throw (ex-info "Date of registration cannot be after date of discharge" episode)))
  (let [episode' (merge {:t_episode/referral_user_fk     user_fk
                         :t_episode/registration_user_fk user_fk
                         :t_episode/discharge_user_fk    user_fk
                         :t_episode/date_referral        date_registration}
                        (dissoc episode :t_episode/user_fk))]
    (if-let [existing (db/execute-one! txn (sql/format {:select :* :from :t_episode :where
                                                        [:and
                                                         [:= :patient_fk patient_fk] [:= :project_fk project_fk]
                                                         [:= :date_registration date_registration]
                                                         [:= :date_discharge date_discharge]]}))]
      existing
      (create-episode! txn episode'))))


(s/fdef register-patient-project!
  :args (s/cat :txn ::db/repeatable-read-txn
               :project-id pos-int? :user-id pos-int?
               :patient (s/keys :req [:t_patient/id])
               :opts (s/keys* :opt-un [::adopt-pending? ::pseudonym])))
(defn register-patient-project!
  "Register a patient to a project. Safe to use if patient already registered.

  Returns the new, or existing episode.

  Does not check that the given user has permission to register users against
  the project in question.

  If a user has a pending referral for the project in question, we adopt that
  pending referral by default. Use `adopt-pending?` false, to change this
  behaviour. If `adopt-pending?` is false, a new episode will be created.

  The pseudonym will only be added to newly created episodes, by design.

  There is a potential race condition here, as it is not possible to use
  database constraints as a patient may quite appropriately have multiple
  episodes for the same project; it is simply that a patient should not have
  more than one active episode for a project that must be enforced. As such,
  this must be processed within a read-repeatable transaction."
  [txn project-id user-id {patient-id :t_patient/id} & {:keys [adopt-pending? pseudonym] :or {adopt-pending? true}}]
  (let [episodes (->> (episodes-for-patient-in-project txn patient-id project-id)
                      (sort-by :t_episode/date_registration)
                      (group-by episode-status))
        registered (first (:registered episodes))
        pending (first (:referred episodes))]
    (cond
      ;; already registered? -> simply return that episode
      registered
      registered
      ;; has a pending referral for this project? -> register and return it
      (and adopt-pending? pending)
      (register-episode! txn user-id pending)
      ;; no current or pending referral, or we're not adopting existing referrals, so create a new episode:
      :else
      (create-episode! txn (cond-> {:t_episode/project_fk           project-id
                                    :t_episode/patient_fk           patient-id
                                    :t_episode/registration_user_fk user-id
                                    :t_episode/referral_user_fk     user-id
                                    :t_episode/date_referral        (LocalDate/now)
                                    :t_episode/date_registration    (LocalDate/now)}
                                   pseudonym (assoc :t_episode/stored_pseudonym pseudonym))))))

(s/fdef register-legacy-pseudonymous-patient
  :args (s/cat :txn ::db/repeatable-read-txn
               :patient (s/keys :req-un [::salt ::user-id ::project-id ::nhs-number ::sex ::date-birth])))
(defn ^:deprecated register-legacy-pseudonymous-patient
  "Registers a pseudonymous patient using the legacy rsdb registration.
  This simply looks for an existing patient record, or creates a new patient
  record, and then registers to the project specified.

  The search for an existing patient record uses both project-specific and
  'global' (rsdb)-specific pseudonyms, as well as a check via NHS number. Thus
  we avoid creating duplicate records.

  Parameters:
  - conn         : database connection or connection pool
  - salt         : a salt to be used for global pseudonym generation
  - user-id      : id of the user performing the registration
  - project-id   : id of the project to which to register
  - nhs-number   : NHS number of the patient; must be valid.
  - sex          : sex, :MALE, :FEMALE, :UNKNOWN, \"MALE\", \"FEMALE\" or \"UNKNOWN\"
  - date-birth   : java.time.LocalDate representing date of birth

  If a patient exists, the month and year of birth, as well as sex, must match.
  If the existing record has an NHS number, then that must match as well."
  [txn {:keys [_salt user-id project-id nhs-number sex date-birth] :as registration}]
  (when-not (nhs-number/valid? nhs-number)
    (throw (ex-info "Invalid NHS number" registration)))
  (let [existing (find-legacy-pseudonymous-patient txn registration)]
    (if (:t_patient/id existing)
      ;; existing patient - so check matching core demographics, and proceed
      (if (and (.isEqual (.withDayOfMonth date-birth 1) (.withDayOfMonth (:t_patient/date_birth existing) 1))
               (= sex (:t_patient/sex existing))
               (= nhs-number (or (:t_patient/nhs_number existing) nhs-number)))
        (do (register-patient-project! txn project-id user-id existing :pseudonym (:project-pseudonym existing))
            existing)
        (throw (ex-info "Mismatch in patient demographics" {:expected registration :existing existing})))
      ;; no existing patient, so register a new patient and proceed
      (let [patient (create-patient! txn {:t_patient/sex                     (name sex)
                                          :t_patient/date_birth              (.withDayOfMonth date-birth 1)
                                          :t_patient/first_names             "******"
                                          :t_patient/last_name               "******"
                                          :t_patient/title                   "**"
                                          :t_patient/nhs_number              nhs-number
                                          :t_patient/stored_global_pseudonym (:global-pseudonym existing)
                                          :t_patient/status                  "PSEUDONYMOUS"})]
        (log/info "created patient " patient)
        (let [episode (register-patient-project! txn project-id user-id patient :pseudonym (:project-pseudonym existing))]
          (log/info "created episode for patient" episode))
        (assoc patient
          :t_episode/project_fk project-id
          :t_episode/stored_pseudonym (:project-pseudonym existing))))))

(s/fdef update-legacy-pseudonymous-patient!
  :args (s/cat :txn ::db/repeatable-read-txn
               :salt ::salt
               :patient-pk int?
               :updated (s/keys :req-un [::nhs-number ::sex ::date-birth])))
(defn update-legacy-pseudonymous-patient!
  "DANGER: updates the demographic details for the given patient. This is
  designed to correct incorrectly entered patient registration information.
  Must be performed in a serializable transaction."
  [txn salt patient-pk {:keys [nhs-number date-birth sex] :as new-details}]
  (doseq [project-id (map :t_episode/project_fk (jdbc/execute! txn (sql/format {:select-distinct :project_fk
                                                                                :from            :t_episode
                                                                                :where           [:and
                                                                                                  [:= :patient_fk patient-pk]
                                                                                                  [:<> :stored_pseudonym nil]]})))]
    (let [updated (find-legacy-pseudonymous-patient txn (assoc new-details :salt salt :project-id project-id :validate? false))]
      (if (and (:t_patient/id updated) (not= (:t_patient/id updated) patient-pk))
        (throw (ex-info "Patient already found with details:" new-details))
        (do
          (next.jdbc.sql/update! txn :t_patient
                                 {:sex                     (name sex)
                                  :date_birth              (.withDayOfMonth date-birth 1)
                                  :nhs_number              nhs-number
                                  :stored_global_pseudonym (:global-pseudonym updated)}
                                 {:id patient-pk})
          (next.jdbc.sql/update! txn :t_episode
                                 {:stored_pseudonym (:project-pseudonym updated)}
                                 {:patient_fk patient-pk
                                  :project_fk project-id}))))))


(defn make-slug
  [s]
  (str/join "-" (-> s
                    (Normalizer/normalize Normalizer$Form/NFD)
                    (str/replace #"[\P{ASCII}]+" "")
                    (str/replace #"'s\s+" " ")
                    str/trim
                    str/lower-case
                    (str/split #"[\p{Space}\p{P}]+"))))

(comment
  (require '[next.jdbc.connection])
  (require '[next.jdbc.date-time])
  (next.jdbc.date-time/read-as-local)

  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 2}))

  (make-slug "MND Cwm Taf")

  (group-by :t_project/type (all-children conn 5))
  (all-parents conn 5)
  (time (all-children conn 5))
  (all-children conn 2)
  (into #{} (map :id (db/execute! conn (all-children-sql 5))))

  (count-registered-patients conn [5])
  ;; check we have legacy implementation compatibility.
  (= "c2f5699061566a5e6ab05c169848435028568f7eb87e098c5a740c28720bb52a"
     (calculate-project-pseudonym "CAMBRIDGEMS" "1111111111" (LocalDate/of 1975 1 1)))

  (def global-salt "123")
  (find-legacy-pseudonymous-patient conn
                                    :salt global-salt
                                    :project-name "NINFLAMMCARDIFF"
                                    :nhs-number "4965575768"
                                    :date-birth (LocalDate/of 1975 1 1))

  (project-with-name conn "")
  (search-by-project-pseudonym conn 84 "24e")

  (fetch-by-nhs-number conn "4965575768")

  (register-legacy-pseudonymous-patient conn
                                        {:salt         global-salt
                                         :user-id      1
                                         :project-name "COVIDDREAMS"
                                         :nhs-number   "1111111111"
                                         :sex          "MALE"
                                         :date-birth   (LocalDate/of 1973 10 1)})

  (search-by-project-pseudonym conn 124 "e657")
  (map episode-status (com.eldrix.pc4.rsdb.patients/fetch-episodes conn 14032))

  (group-by :t_episode/status (map #(assoc % :t_episode/status (episode-status %)) (com.eldrix.pc4.rsdb.patients/fetch-episodes conn 43518)))
  (group-by :t_episode/status (map #(assoc % :t_episode/status (episode-status %)) (episodes-for-patient-in-project conn 43518 37)))
  (discharge-episode! conn 1 {:t_episode/id 46540})
  (register-patient-project! conn 18 2 {:t_patient/id 14031})
  (register-episode! conn 1 {:t_episode/id 46538})


  (jdbc/execute!
    conn
    (sql/format
      {:select    [:t_user/id :role :date_from :date_to :title :first_names :last_name :email :username
                   :t_job_title/name :custom_job_title]
       :from      [:t_project_user]
       :left-join [:t_user [:= :user_fk :t_user/id]
                   :t_job_title [:= :job_title_fk :t_job_title/id]]
       :where     [:= :project_fk 1]
       :order-by  [:last_name :first_names]}))

  (fetch-project-sql 1)
  (jdbc/execute! conn (fetch-project-sql 2)))

