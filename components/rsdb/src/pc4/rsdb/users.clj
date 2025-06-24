(ns pc4.rsdb.users
  "Support for legacy RSDB application; user management.

  Legacy user management is based upon a unique username, rather than a tuple
  of namespace and username. This is unfortunate, but understandable in
  retrospect.

  The mitigation is that each rsdb user has an authentication method. This
  means that we know :NADEX users *must* be part of the cymru.nhs.uk domain.
  We know :LOCAL/:LOCAL17 users' usernames *cannot clash* with cymru usernames
  as a result of the unique structure of NADEX usernames while local users will
  always be given usernames that do not fit that pattern.

  As such, we can quite easily treat rsdb users, currently, as a single
  namespace. That means we can safely allow rsdb to resolve existing users
  against the cymru.nhs.uk namespace - until there is an explicit namespace
  listed for each user. "
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [buddy.core.codecs]
            [buddy.core.nonce]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.plan :as jdbc.plan]
            [next.jdbc.sql :as jdbc.sql]
            [pc4.fhir.interface :as fhir]
            [pc4.queue.interface :as queue]
            [pc4.rsdb.auth :as auth]
            [pc4.rsdb.db :as db]
            [pc4.rsdb.projects :as projects]
            [pc4.wales-nadex.interface :as nadex])
  (:import (er.extensions.crypting BCrypt)
           (java.security MessageDigest)
           (org.apache.commons.codec.binary Base64)
           (java.time LocalDate LocalDateTime)
           (pc4.rsdb.auth AuthorizationManager)))

(s/def ::role
  (s/keys :req [:t_project_user/id
                :t_project_user/date_from :t_project_user/date_to
                :t_project_user/active?
                :t_project_user/permissions
                :t_project/active?
                :t_role/is_system
                :t_role/name]))

(defn authenticate
  "Authenticate a user using the password specified. Available authentication
  methods:
  - LOCAL: an outdated SHA encoded password dating from rsdb's first version
  - LOCAL17: a slightly more modern password encoding from 2017
  - NADEX : use of NHS Wales' active directory"
  [wales-nadex {:t_user/keys [username credential authentication_method] :as user} password]
  (cond
    (or (str/blank? username) (str/blank? password))
    false

    (= authentication_method :LOCAL)                        ;; TODO: force password change for these users
    (let [md (MessageDigest/getInstance "SHA")
          hash (Base64/encodeBase64String (.digest md (.getBytes password)))]
      (log/warn "warning: using outdated password check for user " username)
      (= credential hash))

    (and credential (= authentication_method :LOCAL17))     ;; TODO: upgrade to more modern hash here and in rsdb codebase
    (BCrypt/checkpw password credential)

    (and wales-nadex (= authentication_method :NADEX))
    (nadex/can-authenticate? wales-nadex username password)

    (and credential (= authentication_method :NADEX))       ;; TODO: remove this fallback
    (do (log/warn "requested NADEX authentication but no connection, fallback to LOCAL17")
        (BCrypt/checkpw password credential))

    (not credential)
    (throw (ex-info "missing credential for user" user))

    :else                                                   ;; no matching method: log an error
    (log/error "unsupported authentication method:" authentication_method)))

(defn- ^:deprecated can-authenticate-with-password?
  "Support for legacy rsdb authentication.
  For development, we temporarily use the fallback local authentication
  available if we do not have an active LDAP connection pool."
  [nadex-pool {:t_user/keys [username credential authentication_method]} password]
  (when-not (or (str/blank? password) (str/blank? credential))
    (cond
      (= authentication_method "LOCAL")                     ;; TODO: force password change for these users
      (let [md (MessageDigest/getInstance "SHA")
            hash (Base64/encodeBase64String (.digest md (.getBytes password)))]
        (log/warn "warning: using outdated password check for user " username)
        (= credential hash))

      (= authentication_method "LOCAL17")                   ;; TODO: upgrade to more modern hash here and in rsdb codebase
      (BCrypt/checkpw password credential)

      (and nadex-pool (= authentication_method "NADEX"))
      (nadex/can-authenticate? nadex-pool username password)

      (= authentication_method "NADEX")                     ;; TODO: remove this fallback
      (do (log/warn "requested NADEX authentication but no connection, fallback to LOCAL17")
          (BCrypt/checkpw password credential))

      :else                                                 ;; no matching method: log an error
      (do
        (log/error "unsupported authentication method:" authentication_method)
        false))))

(defn is-rsdb-user?
  [conn nspace username]
  (when (and conn (= "cymru.nhs.uk" nspace))
    (jdbc/execute-one!
      conn (sql/format {:select :id :from :t_user
                        :where  [:= :username (.toLowerCase username)]}))))

(defn- save-password!
  [conn username new-password & {:keys [update-auth-method?]}]
  (let [hash (BCrypt/hashpw new-password (BCrypt/gensalt))]
    (jdbc/execute-one!
      conn
      (sql/format {:update :t_user
                   :where  [:= :username username]
                   :set    (cond-> {:credential           hash
                                    :must_change_password false}
                             update-auth-method?
                             (assoc :authentication_method :LOCAL17))}))))

(defn save-password
  "Save a password for the given user.
  This does not check existing password."
  [conn {:t_user/keys [username authentication_method]} new-password]
  (case authentication_method
    :LOCAL
    (save-password! conn username new-password :update-auth-method? true)
    :LOCAL17
    (save-password! conn username new-password)
    :NADEX
    (save-password! conn username new-password)))

(defn count-unread-messages
  [conn username]
  (jdbc.plan/select-one!
    conn :unread_messages
    (sql/format {:select [[:%count.t_message/id :unread_messages]]
                 :from   [:t_message :t_user]
                 :where  [:and
                          [:= :t_user/username username]
                          [:= :t_message/to_user_fk :t_user/id]
                          [:= :is_unread "true"]]})))

(defn count-incomplete-messages
  [conn username]
  (jdbc.plan/select-one!
    conn :incomplete_messages
    (sql/format {:select [[:%count.t_message/id :incomplete_messages]]
                 :from   [:t_message :t_user]
                 :where  [:and
                          [:= :t_user/username username]
                          [:= :t_message/to_user_fk :t_user/id]
                          [:= :is_completed "false"]]})))

(defn projects
  [conn username]
  (db/execute!
    conn
    (sql/format {:select [:*]
                 :from   [:t_project]
                 :where  [:in :t_project/id {:select [:t_project_user/project_fk]
                                             :from   [:t_project_user :t_user]
                                             :where  [:and
                                                      [:= :t_project_user/user_fk :t_user/id]
                                                      [:= :t_user/username username]]}]})))

(defn sql-active-project-ids
  "Generate SQL to return a user's active project identifiers.
  If `active-projects?` is `true`, then both registration and project must be
  active on the date specified."
  [user-id on-date active-projects?]
  (if active-projects?
    (sql/format {:select-distinct [:t_project/id] :from [:t_project :t_project_user]
                 :where           [:and
                                   [:= :t_project_user/user_fk user-id]
                                   [:= :t_project_user/project_fk :t_project/id]
                                   [:or [:is :t_project/date_from nil] [:<= :t_project/date_from on-date]]
                                   [:or [:is :t_project/date_to nil] [:> :t_project/date_to on-date]]
                                   [:or [:is :t_project_user/date_from nil] [:<= :t_project_user/date_from on-date]]
                                   [:or [:is :t_project_user/date_to nil] [:> :t_project_user/date_to on-date]]]})
    (sql/format {:select-distinct [[:project_fk :id]] :from [:t_project_user]
                 :where           [:and
                                   [:= :t_project_user/user_fk user-id]
                                   [:or [:is :date_to nil] [:> :date_to on-date]]
                                   [:or [:is :date_from nil] [:<= :date_from on-date]]]})))

(defn active-project-ids
  "Return a collection of project identifiers representing projects to which the
  user is actively registered. To be active, the user has to have an active
  registration, on the date specified, or today if omitted. Additionally,
  results can be limited to only projects that are themselves active."
  ([conn user-id] (active-project-ids conn user-id {}))
  ([conn user-id {:keys [^LocalDate on-date only-active-projects?] :or {only-active-projects? false}}]
   (jdbc.plan/select! conn :id (sql-active-project-ids user-id (or on-date (LocalDate/now)) only-active-projects?))))

(defn common-concepts
  "Return a set of the common concepts for the user."
  [conn user-id]
  (let [project-ids (active-project-ids conn user-id {:only-active-projects? true})]
    (projects/common-concepts conn project-ids)))

(defn all-projects-and-children-identifiers
  "Returns identifiers for all projects to which user is registered, together
  with any sub-projects. In general, "
  [conn username]
  (let [project-ids (set (map :t_project/id (projects conn username)))]
    (into project-ids (map #(projects/all-children-ids conn %) project-ids))))

(defn roles-for-user-sql
  ([username]
   (roles-for-user-sql username {}))
  ([username {:keys [project-id]}]
   {:select [:t_user/id :t_user/username :t_role/name :t_role/is_system
             :t_project/id :t_project/title :t_project_user/*]
    :from   :t_project_user
    :join   [:t_user [:= :user_fk :t_user/id]
             :t_project [:= :project_fk :t_project/id]
             :t_role [:= :role_fk :t_role/id]]
    :where  (if project-id [:and [:= :t_user/username username] [:= :t_project/id project-id]]
                           [:= :t_user/username username])}))

(s/fdef roles-for-user
  :args (s/cat :conn ::db/conn :username string? :opts (s/? (s/keys :opt [:t_project/id])))
  :ret (s/coll-of ::role))
(defn roles-for-user
  "Return the 'roles' for the given user, each flattened and pre-fetched to
  include keys from the 't_project_user' and related 't_project' tables.

  In essence, a user will have roles as defined by `t_project_user` rows.
  - :t_project_user/id
  - :t_project_user/user_fk
  - :t_project_user/project_fk
  - :t_project_user/role - one of PID_DATA POWER_USER NORMAL_USER BIOBANK_ADMINISTRATOR LIMITED_USER
  - :t_project_user/date_from
  - :t_project_user/date_to

  For convenience, the following properties are also included:
  - :t_project_user/active?     - whether the role is active
  - :t_project/active?          - whether the project is active
  - :t_project_user/permissions - set of permissions for project
  - :t_role/is_system           - is the user a rsdb 'system' user?
  - :t_role/name                - name of the legacy global 'role'.

  The legacy permissions system migrated to a per-project permission early on
  but the 't_role' is an artefact of an earlier time. The global 'role' in
  't_role' is now only useful in determining whether the user is a system user
  and thus having authentication for everything.

  Permissions are *not* inherited under the legacy model; users must be
  explicitly added to sub-projects. Patients *are* inherited."
  ([conn username]
   (roles-for-user conn username {}))
  ([conn username opts]
   (->> (db/execute!
          conn
          (sql/format (roles-for-user-sql username opts)))
        (map #(assoc % :t_project_user/active? (projects/role-active? %)
                       :t_project/active? (projects/active? %)
                       :t_project_user/permissions (get auth/permission-sets (:t_project_user/role %)))))))

(s/fdef permissions-for-project
  :args (s/cat :roles (s/coll-of ::role) :project-id int?))
(defn permissions-for-project
  "Given a sequence of roles for a user, derive a set of permissions for the
  project specified.
  Parameters:
  - roles       : the result of calling function 'roles-for-user' for the user
  - project-id  : project identifier."
  [roles project-id]
  (->> roles
       (filter #(= (:t_project/id %) project-id))
       (filter :t_project_user/active?)
       (map :t_project_user/permissions)
       (apply clojure.set/union)))

(defn projects-with-permission
  "Given a sequence of roles for a user, derive a list of projects to which
  the user has the specified permission.
  Parameters:
  - roles      : the result of calling function 'roles-for-user' for the user
  - permission : the permission to test."
  [roles permission]
  (->> roles
       (filter #(contains? (:t_project_user/permissions %) permission))))

(defn active-roles-by-project-id
  "Returns a set of roles by project identifier."
  [conn username]
  (->> (roles-for-user conn username)
       (filter :t_project_user/active?)
       (group-by :t_project/id)
       (reduce-kv (fn [acc project-id roles]
                    (assoc acc project-id (into #{} (map :t_project_user/role) roles))) {})))

(s/fdef authorization-manager
  :args (s/cat :roles (s/coll-of ::role)))
(defn authorization-manager
  "Create an authorization manager for the user specified, providing subsequent
  decisions on authorization for a given action via an open-ended permission
  system. The manager is an immutable service; it closes over the permissions
  at the time of creation. The manager is principally designed for use in a
  single request-response cycle."
  ^AuthorizationManager [roles]
  (if (:t_role/is_system (first roles))
    (reify auth/AuthorizationManager                        ;; system user: can do everything...
      (authorized? [_ patient-project-ids permission] true)
      (authorized-any? [_ permission] true))
    (reify auth/AuthorizationManager                        ;; non-system users defined by project roles
      (authorized? [_ project-ids permission]
        (some #(contains? (permissions-for-project roles %) permission) project-ids))
      (authorized-any? [_ permission]
        (some #(contains? (:t_project_user/permissions %) permission) roles)))))

(s/def ::user-for-authorization-manager (s/keys :req [:t_user/active_roles :t_role/is_system]))

(defn authorization-manager2
  "Create an authorization manager for the user specified, providing subsequent
  decisions on authorization for a given action via an open-ended permission
  system. The manager is an immutable service; it closes over the permissions
  at the time of creation. The manager is principally designed for use in a
  single request-response cycle."
  ^AuthorizationManager [user]
  (when-not (s/valid? ::user-for-authorization-manager user)
    (log/error "invalid authenticated error" (s/explain-data ::user-for-authorization-manager user))
    (throw (ex-info "Invalid authenticated user" (s/explain-data ::user-for-authorization-manager user))))
  (if (:t_role/is_system user)
    (reify auth/AuthorizationManager                        ;; system user: can do everything...
      (authorized? [_ patient-project-ids permission] true)
      (authorized-any? [_ permission] true))
    (let [permissions-by-project (update-vals (:t_user/active_roles user) auth/expand-permission-sets)] ;; a map of project-id to a set of permissions
      (reify auth/AuthorizationManager                      ;; non-system users defined by project roles
        (authorized? [_ project-ids permission]
          (log/trace "checking auth:" {:project-ids project-ids :permission permission :permissions permissions-by-project})
          (some #(contains? (permissions-by-project %) permission) project-ids))
        (authorized-any? [_ permission]
          (some #(permissions-by-project %) permission) permissions-by-project)))))

(defn ^:deprecated make-authorization-manager
  "DEPRECATED: Use [[authorization-manager]] instead.
   Create an authorization manager for the user with `username`. It is usually
   more appropriate to use [[authorization-manager]] directly with a list of
   roles."
  ^AuthorizationManager [conn username]
  (authorization-manager (roles-for-user conn username)))

(def fetch-user-query
  {:select    [:t_user/id :username :title :first_names :last_name :postnomial :custom_initials
               :email :custom_job_title :t_job_title/name :photo_fk
               :can_be_responsible_clinician :is_clinical
               :send_email_for_messages
               :must_change_password
               :authentication_method :professional_registration
               :t_role/is_system
               :t_professional_registration_authority/name
               :t_professional_registration_authority/abbreviation]
   :from      [:t_user]
   :left-join [:t_role [:= :t_user/role_fk :t_role/id]
               :t_job_title [:= :t_user/job_title_fk :t_job_title/id]
               :t_professional_registration_authority [:= :t_user/professional_registration_authority_fk :t_professional_registration_authority/id]]})

(defn fetch-user
  ([conn username]
   (fetch-user conn username {}))
  ([conn username {:keys [with-credentials] :or {with-credentials false}}]
   (db/execute-one! conn
                    (sql/format (cond-> (assoc fetch-user-query
                                          :where [:= :username (str/lower-case username)])
                                  with-credentials
                                  (update :select conj :credential))))))

(defn fetch-user-by-id
  ([conn user-id]
   (fetch-user-by-id conn user-id {}))
  ([conn user-id {:keys [with-credentials] :or {with-credentials false}}]
   (db/execute-one! conn (sql/format (cond-> (assoc fetch-user-query
                                               :where [:= :t_user/id user-id])
                                       with-credentials
                                       (update :select conj :credential))))))

(defn job-title [{custom-job-title :t_user/custom_job_title, job-title :t_job_title/name}]
  (if (str/blank? custom-job-title) job-title custom-job-title))

(defn administrator-users-sql
  "Returns SQL to get basic information about administrative users for the projects specified."
  [project-ids]
  {:select [:id :username :first_names :last_name :title]
   :from   :t_user
   :where  (cond-> [:or [:= :username "system"]]
             (seq project-ids)
             (conj [:in :id {:select-distinct :administrator_user_fk
                             :from            :t_project
                             :where           [:in :id project-ids]}]))})

(defn administrator-users
  "Returns administrator users for the projects specified."
  [conn project-ids]
  (log/debug "sql" (administrator-users-sql project-ids))
  (db/execute! conn (sql/format (administrator-users-sql project-ids))))

(defn random-password
  "Returns a vector of new random password and its credential.
  Currently only the legacy bcrypt hash is supported, but this provides
  a point of extension."
  [{nbytes :nbytes hash-type :type :or {nbytes 8 hash-type :bcrypt} :as params}]
  (let [new-password (str (buddy.core.codecs/bytes->hex (buddy.core.nonce/random-bytes nbytes)))
        credential (case hash-type
                     :bcrypt (BCrypt/hashpw new-password (BCrypt/gensalt))
                     (throw (ex-info (str "Unsupported password hash type:" hash-type) params)))]
    [new-password credential]))

(comment
  (def conn ()))

(s/def ::create-user
  (s/keys :req [:t_user/username
                :t_user/title
                :t_user/first_names
                :t_user/last_name
                :t_user/job_title_fk]
          :opt [:t_user/email
                :t_user/custom_job_title]))

(defn create-user-sql
  "Returns a map containing: 
  - :sql      : the SQL to create the given user
  - :password : the new password."
  [user]
  (when-not (s/valid? ::create-user user)
    (throw (ex-info "Invalid parameters" (s/explain-data ::create-user user))))
  (let [[new-password credential] (random-password {})]
    {:sql      {:insert-into :t_user
                :values      [(merge {:t_user/credential            credential
                                      :t_user/must_change_password  true
                                      :t_user/role_fk               4 ;; normal user
                                      :t_user/job_title_fk          14 ;; "other"
                                      :t_user/authentication_method "LOCAL17"}
                                     user)]}
     :password new-password}))

(def known-job-titles
  "This is a hardcoded list of records from t_job_title from the legacy
  rsdb database. This simply maps an id and name to a SNOMED concept
  identifier. TODO: this should simply be in the database but this 
  approach to job titles will eventually be deprecated in favour of 
  using SNOMED CT directly as part of a FHIR PractitionerRole equivalent."
  [{:id 2, :name "Consultant", :concept-id 768839008}
   {:id 3, :name "Specialist registrar", :concept-id 302211009}
   {:id 4, :name "Speciality registrar", :concept-id 224531000}
   {:id 5, :name "Clinical research fellow", :concept-id 309397006}
   {:id 6, :name "Senior house officer", :concept-id 224532007}
   {:id 7, :name "Pre-registration house officer", :concept-id 158972004}
   {:id 9, :name "Specialist nurse", :concept-id 310179005}
   {:id 10, :name "Physiotherapist", :concept-id 36682004}
   {:id 11, :name "Occupational therapist", :concept-id 80546007}
   {:id 12, :name "Psychologist", :concept-id 59944000}
   {:id 13, :name "Continence nurse", :concept-id 310180008}
   {:id 15, :name "Medical student", :concept-id 398130009}
   {:id 16, :name "Physiotherapy student", :concept-id 65853000}
   {:id 17, :name "OT student", :concept-id 65853000}
   {:id 18, :name "Psychology student", :concept-id 65853000}
   {:id 19, :name "Nursing student", :concept-id 65853000}
   {:id 20, :name "Speech and language therapist", :concept-id 159026005}
   {:id 22, :name "Dietician", :concept-id 159033005}
   {:id 23, :name "Associate specialist", :concept-id 309396002}])

(defn sct->job-title
  "Returns a function that can convert a concept id into a legacy rsdb
  job title"
  [hermes]
  (let [match-fns
        (reduce (fn [{:keys [concept-id] :as jt}])
                known-job-titles)]
    (fn [concept-id])))

(defn create-managed-user-sql
  [{identifiers :org.hl7.fhir.Practitioner/identifier
    names       :org.hl7.fhir.Practitioner/name
    telecom     :org.hl7.fhir.Practitioner/telecom}]
  (let [id (fhir/best-identifier "https://fhir.nhs.wales/Id/nadex-identifier" identifiers)
        nm (fhir/best-human-name names)
        email (:org.hl7.fhir.ContactPoint/value (fhir/best-contact-point "email" telecom))]
    (when (and id nm)
      (create-user-sql {:t_user/username              (:org.hl7.fhir.Identifier/value id)
                        :t_user/title                 (str/join " " (:org.hl7.fhir.HumanName/prefix nm))
                        :t_user/first_names           (str/join " " (:org.hl7.fhir.HumanName/given nm))
                        :t_user/last_name             (:org.hl7.fhir.HumanName/family nm)
                        :t_user/email                 email
                        :t_user/job_title_fk          14    ;; "other" ;; TODO: map from FHIR (SNOMED) job titles
                        :t_user/role_fk               4
                        :t_user/must_change_password  false
                        :t_user/authentication_method "NADEX"}))))

(comment
  (require '[clojure.spec.gen.alpha :as gen])
  (->> (:org.hl7.fhir.Practitioner/identifier (nadex/user->fhir-r4 (gen/generate (nadex/gen-user))))
       (fhir/best-match [(fhir/match-identifier :system "https://fhir.nhs.wales/Id/nadex-identifier")]))
  (gen/generate (s/gen :org.hl7.fhir/Practitioner)))

(comment
  (create-user-sql {:t_user/username     "ma090906"
                    :t_user/title        "Mr"
                    :t_user/last_name    "Wardle"
                    :t_user/first_names  "Mark"
                    :t_user/job_title_fk 8}))

(defn create-user [conn user]
  (let [{:keys [sql password]} (create-user-sql user)]
    (-> (next.jdbc/execute-one! conn sql {:return-keys true})
        (assoc :t_user/new_password password))))

(defn reset-password!
  "Reset password for a user. Returns the new randomly-generated password."
  [conn {user-id :t_user/id}]
  (let [[new-password credential] (random-password {:nbytes 32})]
    (jdbc.sql/update! conn :t_user {:credential            credential
                                    :authentication_method "LOCAL17"
                                    :must_change_password  true}
                      {:id user-id})
    new-password))

(defn register-user-to-project
  "Register a user to a project.
  Parameters:
  conn        - database connection, pool or transaction
  :username   - username of user
  :project-id - project identifier"
  [conn {:keys [username
                project-id
                date-from] :or {date-from (LocalDate/now)}}]
  (next.jdbc/execute-one! conn (sql/format {:insert-into [:t_project_user]
                                            :values      [{:project_fk project-id
                                                           :user_fk    {:select [:id] :from [:t_user] :where [:= :username username]}
                                                           :date_from  date-from}]})
                          {:return-keys true}))

(defn set-must-change-password! [conn username]
  (next.jdbc/execute-one! conn (sql/format {:update [:t_user]
                                            :where  [:= :username username]
                                            :set    {:must_change_password true}})))

(defn ^:deprecated fetch-user-photo
  [conn username]
  (jdbc/execute-one!
    conn
    (sql/format
      {:select [:username :data :originalfilename :mimetype :size :creationdate]
       :from   [:erattachmentdata :erattachment :t_user]
       :where  [:and
                [:= :erattachment/attachmentdataid :erattachmentdata/id]
                [:= :erattachment/id :t_user/photo_fk]
                [:= :t_user/username username]]})))

(defn user-id->photo
  [conn user-id]
  (jdbc/execute-one!
    conn
    (sql/format
      {:select [:username :data :originalfilename :mimetype :size :creationdate]
       :from   [:erattachmentdata :erattachment :t_user]
       :where  [:and
                [:= :erattachment/attachmentdataid :erattachmentdata/id]
                [:= :erattachment/id :t_user/photo_fk]
                [:= :t_user/id user-id]]})))


(defn has-photo? [conn username]
  (jdbc.plan/select-one! conn :photo_fk (sql/format {:select :photo_fk :from :t_user
                                                     :where  [:= :t_user/username username]})))

(defn fetch-latest-news
  "Returns the latest news items for this user. Note: each news item can be
  linked to a specific project, but we've never used that functionality. At
  the moment, all recent news is returned."
  [conn username]
  (db/execute! conn (sql/format
                      {:select    [:t_news/id :date_time :t_news/title :body
                                   :username :t_user/id :t_user/title :first_names :last_name :postnomial :custom_initials
                                   :email :custom_job_title :t_job_title/name]
                       :from      [:t_news]
                       :left-join [:t_user [:= :author_fk :t_user/id]
                                   :t_job_title [:= :job_title_fk :t_job_title/id]]
                       :order-by  [[:date_time :desc]]
                       :limit     5})))

(defn record-login!
  "Record the date of login for audit purposes. At the moment, this simply
  records directly into the 'date_last_login' column of 't_user', but this
  would be better into a user log file as per how the audit trail functionality
  used to work in the legacy application."
  ([conn username] (record-login! conn username (LocalDateTime/now)))
  ([conn username ^LocalDateTime date]
   (jdbc.sql/update! conn :t_user {:date_last_login date} {:username username})))

(defn search-users
  "Search for users by name or username using tokenized search.
   Each token in the search term must match at least one field (username, first_names, or last_name).
   This allows searches like 'John Smith' to match users with first name 'John' and last name 'Smith'.
   
   Parameters:
   - conn: database connection
   - s: search term to tokenize and match against names
   - opts: options map with optional keys:
     - :limit : maximum number of results (default 50)"
  [conn s {:keys [limit] :or {limit 50}}]
  (when-not (str/blank? s)
    (let [tokens (->> (str/split (str/trim s) #"\s+")
                      (remove str/blank?)
                      (map #(str "%" (str/lower-case %) "%")))
          clauses (for [token tokens]
                    [:or
                     [:ilike [:lower :username] token]
                     [:ilike [:lower :first_names] token]
                     [:ilike [:lower :last_name] token]])]
      (when (seq clauses)
        (db/execute! conn
                     (sql/format 
                       (merge fetch-user-query
                              {:where (into [:and] clauses)
                               :order-by [:last_name :first_names]
                               :limit limit})))))))

(defn user->display-names
  "Add display names (`:t_user/full_name`, `:t_user/initials`) to the user when
  possible."
  [{:t_user/keys [custom_initials title last_name first_names] :as user}]
  (when user
    (cond-> user
      (or last_name first_names)
      (assoc :t_user/full_name (str/join " " (remove str/blank? [title first_names last_name])))
      (not (str/blank? custom_initials))
      (assoc :t_user/initials custom_initials)
      (and (str/blank? custom_initials) (not (or (str/blank? first_names) (str/blank? last_name))))
      (assoc :t_user/initials (str (apply str (map first (str/split first_names #"\s"))) (first last_name))))))

(defn perform-login!                                        ;; TODO: use single SQL to fetch user data and active roles
  "Returns a user with the given username iff the password is correct, including
  a sequence of active roles under key :t_user/active_roles."
  ([conn wales-nadex username password]
   (perform-login! conn wales-nadex username password {}))
  ([conn wales-nadex username password {:keys [impersonate]}]
   (when-let [user (fetch-user conn username {:with-credentials true})]
     (when (or impersonate (authenticate wales-nadex user password))
       (when-not impersonate (record-login! conn username))
       (-> (select-keys user [:t_user/id :t_user/username :t_role/is_system :t_job_title/is_clinical
                              :t_user/title :t_user/first_names :t_user/last_name
                              :t_user/custom_initials])
           (assoc :t_user/active_roles (active-roles-by-project-id conn username))
           (user->display-names))))))








(comment
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 10}))
  (require '[com.eldrix.hermes.core :as hermes])
  (def hermes (com.eldrix.hermes.core/open "/Users/mark/Dev/hermes/snomed.db"))
  (let [known-job-titles
        (jdbc/execute! conn (sql/format {:select :* :from :t_job_title}))]
    (mapv (fn [{:t_job_title/keys [id name]}]
            (let [concept (first (hermes/ranked-search hermes {:s name :max-hits 1 :constraint "<14679004" :boost-length? true}))]
              {:id id :name name :concept-id (:conceptId concept) :concept concept})) known-job-titles))
  (count-incomplete-messages conn "ma090906")
  (count-unread-messages conn "ma090906")
  (queue/dequeue-job conn :user/email)
  (send-message conn 1 {:t_user/id 2 :t_user/send_email_for_messages true :t_user/email "mark@wardle.org"} nil "Subject" "Body")

  (fetch-latest-news conn "ma090906")
  (projects conn "ma090906")
  (sort (map :t_project/title (filter com.eldrix.pc4.rsdb.projects/active? (projects conn "ma090906"))))

  (group-by :t_project/type (projects conn "ma090906"))

  (fetch-user-photo conn "rh084967")
  (fetch-user conn "ma090906")
  (authenticate {} (fetch-user conn "system" {:with-credentials true}) "password")
  (group-by :t_project_user/role (roles-for-user conn "ma090906"))
  (map :t_project/id (roles-for-user conn "ma090906"))
  (filter #(= 15 (:t_project/id %)) (roles-for-user conn "ma090906"))
  (permissions-for-project (roles-for-user conn "ma090906") 15)
  (:t_role/is_system (first (roles-for-user conn "ma090906")))
  (roles-for-user conn "ma090906")
  (def manager (make-authorization-manager conn "ma090906"))
  (auth/authorized-any? manager :PATIENT_EDIT)
  (auth/authorized? manager #{1 32 14} :NEWS_CREATE)
  (map :t_project/title (map #(projects/fetch-project conn %) [1 32 14]))
  (auth/authorized? manager #{1} :PATIENT_VIEW)
  (map #(select-keys % [:t_project_user/role :t_project/id :t_project/title])
       (roles-for-user conn "ma090906"))
  (def sys-manager (make-authorization-manager conn "system"))
  (auth/authorized? sys-manager #{1} :PATIENT_VIEW)
  (is-rsdb-user? conn "cymru.nhs.uk" "ma090906"))
