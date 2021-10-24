(ns com.eldrix.pc4.server.rsdb.projects
  "Support for rsdb projects. These represent logical clinical services or
   clinical research projects. They are fundamental in supporting the
   finely-grained security model; linked to patients by virtue of an 'episode'
   and linked to professionals by virtue of user registration.

   t_patient <<->> t_episode <<-> t_project <<-> t_project_user <-> t_user

   Many projects are long-lived and keep patients registered for the long-term.
   Others are configured to automatically discharge a patient after an interval.
   Many users are authorised to register and discharge patients from projects
   but such authorisation is on a per-project basis."
  (:require [clojure.string :as str]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [next.jdbc :as jdbc]
            [honey.sql :as sql])
  (:import (java.time LocalDate)
           (java.text Normalizer Normalizer$Form)
           (java.time.format DateTimeFormatter)
           (org.apache.commons.codec.digest DigestUtils)))

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

(defn ^:deprecated calculate-project-pseudonym
  "Generate a legacy project-based pseudonym, based on:
   - the name of the project
   - the identifier ( e.g. nhs number)
   - the date of birth.

   For modern use, an alternate pseudonymisation strategy is recommended as
   this does not use a cryptographically secure salt but one derived from the
   project itself.

   This is compatible with the legacy project-specific pseudonymous strategy
   from RSNews/rsdb from 2008."
  [project-name identifier ^LocalDate date-birth]
  (make-hash-pseudonym (DigestUtils/md5Hex ^String project-name) identifier (.format date-birth (DateTimeFormatter/ISO_LOCAL_DATE))))

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
                    (sql/format {:select    (conj fetch-pseudonym-patient-properties :t_episode/stored_pseudonym)
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

(defn ^:deprecated find-legacy-pseudonymous-patient
  "Attempts to identify a patient using the legacy rsdb pseudonym registration.
  Returns a patient record with keys:
  * global-pseudonym
  * project-pseudonym
  And with these additional keys, if a patient was found matching the search:
  * t_patient/id
  * t_patient/first_names
  * t_patient/last_name
  * t_patient/date_birth
  * t_patient/sex
  * t_patient/nhs_number"
  [conn & {:keys [salt project-name nhs-number date-birth]}]
  (let [project-pseudonym (calculate-project-pseudonym project-name nhs-number date-birth)
        global-pseudonym (calculate-global-pseudonym salt nhs-number date-birth)]
    (let [patient (or (fetch-by-project-pseudonym conn project-name project-pseudonym)
                      (fetch-by-global-pseudonym conn global-pseudonym)
                      (fetch-by-nhs-number conn nhs-number))]
      (merge (db/parse-entity patient)
             {:project-pseudonym project-pseudonym
              :global-pseudonym  global-pseudonym}))))

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

(defn create-episode!
  "Create a new episode, returning the data."
  [conn episode]
  (db/execute-one! conn
                   (sql/format {:insert-into [:t_episode]
                                :values      [episode]})
                   {:return-keys true}))

(defn create-patient!
  "Creates a patient.

  Frustratingly, the original designer of rsdb decided to have a default family
  identifier that was a textual representation of the primary key, by default.
  That's easy using WebObjects, because you can access the primary key in
  transaction. Here we fake it by getting the next value in the sequence and
  using that. Also, all patients need to be linked to a family, which seemed
  sensible at the time, and belies its genetic research database origins."
  [conn patient]
  (let [{patient-id :nextval} (jdbc/execute-one! conn ["select nextval('t_patient_seq')"])
        {family-id :nextval} (jdbc/execute-one! conn ["select nextval('t_family_seq')"])
        patient' (assoc patient
                   :t_patient/id patient-id
                   :t_patient/patient-identifier patient-id
                   :t_patient/family_fk family-id)]
    (jdbc/execute-one! conn (sql/format {:insert-into [:t_family]
                                         :values      [{:t_family/family_identifier (str family-id)
                                                        :t_family/id                family-id}]}))
    (db/execute-one! conn
                     (sql/format {:insert-into [:t_patient]
                                  :values      [patient']})
                     {:return-keys true})))

(defn register-patient-project
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
  this is processed within a serializable transaction."
  [conn project-id user-id {patient-id :t_patient/id} & {:keys [adopt-pending? pseudonym] :or {adopt-pending? true}}]
  (jdbc/with-transaction [tx conn {:isolation :serializable}]
    (let [episodes (->> (episodes-for-patient-in-project tx patient-id project-id)
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
        (register-episode! tx user-id pending)
        ;; no current or pending referral, or we're not adopting existing referrals, so create a new episode:
        :else
        (create-episode! tx (cond-> {:t_episode/project_fk           project-id
                                     :t_episode/patient_fk           patient-id
                                     :t_episode/registration_user_fk user-id
                                     :t_episode/referral_user_fk     user-id
                                     :t_episode/date_referral        (LocalDate/now)
                                     :t_episode/date_registration    (LocalDate/now)}
                                    pseudonym (assoc :t_episode/stored_pseudonym pseudonym)))))))

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
  - project-name : name of the project
  - nhs-number   : NHS number of the patient; must be valid.
  - sex          : sex, :MALE, :FEMALE, :UNKNOWN, \"MALE\", \"FEMALE\" or \"UNKNOWN\"
  - date-birth   : java.time.LocalDate representing date of birth

  If a patient exists, the month and year of birth, as well as sex, must match.
  If the existing record has an NHS number, then that must match as well."
  [conn & {:keys [_salt user-id project-name nhs-number sex date-birth] :as registration}]
  (if-not (com.eldrix.concierge.nhs-number/valid? nhs-number)
    (throw (ex-info "Invalid NHS number" registration))
    (if-let [{project-id :t_project/id} (jdbc/execute-one! conn (sql/format {:select [:id] :from :t_project :where [:= :name project-name]}))]
      (let [existing (find-legacy-pseudonymous-patient conn registration)]
        (if (:t_patient/id existing)
          ;; existing patient - so check matching core demographics, and proceed
          (if (and (.isEqual (.withDayOfMonth date-birth 1) (.withDayOfMonth (:t_patient/date_birth existing) 1))
                   (= sex (:t_patient/sex existing))
                   (= nhs-number (or (:t_patient/nhs_number existing) nhs-number)))
            (do (register-patient-project conn project-id user-id existing :pseudonym (:project-pseudonym existing))
                existing)
            (throw (ex-info "mismatch in patient demographics" {:expected registration :existing existing})))
          ;; no existing patient, so register a new patient and proceed
          (let [patient (create-patient! conn {:t_patient/sex                     (name sex)
                                               :t_patient/date_birth              (.withDayOfMonth date-birth 1)
                                               :t_patient/first_names             "******"
                                               :t_patient/last_name               "******"
                                               :t_patient/title                   "**"
                                               :t_patient/stored_global_pseudonym (:global-pseudonym existing)
                                               :t_patient/status                  "PSEUDONYMOUS"})]
            (register-patient-project conn project-id user-id patient :pseudonym (:project-pseudonym existing))
            patient))))))

(defn make-slug
  [s]
  (str/join "-" (-> s
                    (Normalizer/normalize Normalizer$Form/NFD)
                    (str/replace #"[\P{ASCII}]+" "")
                    (str/replace #"'s\s+" " ")
                    str/trim
                    str/lower-case
                    (str/split #"[\p{Space}\p{P}]+"))))

(defn fetch-users
  "Fetch users for the project specified. An individual may be listed more than
  once if they have more than one 'role' within the project."
  [conn project-id]
  (db/execute!
    conn
    (sql/format
      {:select    [:t_user/id :role :date_from :date_to :title :first_names :last_name :email :username
                   :t_job_title/name :custom_job_title]
       :from      [:t_project_user]
       :left-join [:t_user [:= :user_fk :t_user/id]
                   :t_job_title [:= :job_title_fk :t_job_title/id]]
       :where     [:= :project_fk project-id]
       :order-by  [:last_name :first_names]})))


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

(defn all-children-ids [conn project-id]
  (map :id (db/execute! conn (all-children-sql project-id))))

(defn all-parents-ids [conn project-id]
  (map :id (db/execute! conn (all-parents-sql project-id))))

(defn all-children [conn project-id]
  (when-let [children-ids (seq (all-children-ids conn project-id))]
    (db/execute! conn (fetch-projects-sql children-ids))))

(defn all-parents [conn project-id]
  (when-let [parent-ids (seq (all-parents-ids conn project-id))]
    (db/execute! conn (fetch-projects-sql parent-ids))))

(defn fetch-project [conn project-id]
  (db/execute-one! conn (fetch-project-sql project-id)))

(defn project-with-name [conn nm]
  (db/execute-one! conn (sql/format {:select :* :from :t_project
                                     :where  [:= :t_project/name nm]})))

(comment
  (require '[next.jdbc.connection])
  (require '[next.jdbc.date-time])
  (next.jdbc.date-time/read-as-local)

  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 10}))

  (make-slug "MND Cwm Taf")

  (group-by :t_project/type (all-children conn 5))
  (all-parents conn 5)
  (time (all-children conn 5))
  (all-children conn 2)
  (into #{} (map :id (db/execute! conn (all-children-sql 5))))

  ;; check we have legacy implementation compatibility.
  (= "c2f5699061566a5e6ab05c169848435028568f7eb87e098c5a740c28720bb52a"
     (calculate-project-pseudonym "CAMBRIDGEMS" "1111111111" (LocalDate/of 1975 1 1)))

  (def global-salt "123")
  (find-legacy-pseudonymous-patient conn
                                    :salt global-salt
                                    :project-name "NINFLAMMCARDIFF"
                                    :nhs-number "4965575768"
                                    :date-birth (LocalDate/of 1975 1 1))

  (search-by-project-pseudonym conn 124 "e657")

  (fetch-by-nhs-number conn "4965575768")

  (register-legacy-pseudonymous-patient conn
                                        {:salt         global-salt
                                         :user-id      1
                                         :project-name "COVIDDREAMS"
                                         :nhs-number   "1111111111"
                                         :sex          "MALE"
                                         :date-birth   (LocalDate/of 1973 10 1)})

  (search-by-project-pseudonym conn 124 "e657")
  (map episode-status (com.eldrix.pc4.server.rsdb.patients/fetch-episodes conn 14032))

  (group-by :t_episode/status (map #(assoc % :t_episode/status (episode-status %)) (com.eldrix.pc4.server.rsdb.patients/fetch-episodes conn 43518)))
  (group-by :t_episode/status (map #(assoc % :t_episode/status (episode-status %)) (episodes-for-patient-in-project conn 43518 37)))
  (discharge-episode! conn 1 {:t_episode/id 46540})
  (register-patient-project conn 18 2 {:t_patient/id 14031})
  (register-episode! conn 1 {:t_episode/id 46538})
  (insert-episode! conn {:t_episode/project_fk       18
                         :t_episode/patient_fk       14031
                         :t_episode/referral_user_fk 1
                         :t_episode/date_referral    (LocalDate/now)})


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
  (jdbc/execute! conn (fetch-project-sql 2))

  )