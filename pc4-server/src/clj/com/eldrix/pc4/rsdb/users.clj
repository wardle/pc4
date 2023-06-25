(ns com.eldrix.pc4.rsdb.users
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
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [buddy.core.codecs]
            [buddy.core.nonce]
            [next.jdbc :as jdbc]
            [next.jdbc.plan :as jdbc.plan]
            [next.jdbc.sql :as jdbc.sql]
            [honey.sql :as sql]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [com.eldrix.pc4.rsdb.auth :as auth]
            [com.eldrix.pc4.rsdb.db :as db]
            [com.eldrix.pc4.rsdb.projects :as projects]
            [com.eldrix.pc4.rsdb.queue :as queue])
  (:import (er.extensions.crypting BCrypt)
           (java.security MessageDigest)
           (org.apache.commons.codec.binary Base64)
           (java.time LocalDate LocalDateTime)
           (com.eldrix.pc4.rsdb.auth AuthorizationManager)))


(s/def ::role
  (s/keys :req [:t_project_user/id
                :t_project_user/date_from :t_project_user/date_to
                :t_project_user/active?
                :t_project_user/permissions
                :t_project/active?
                :t_role/is_system
                :t_role/name]))


(defn- can-authenticate-with-password?
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

(defn check-password
  "Check a user's credentials.
  Returns a map containing :t_user/id and :t_user/username if the password is
  correct.
  Parameters:
   - conn     : database connection
   - nadex    : LDAP connection pool
   - username : username
   - password : password."
  [conn nadex username password]
  (let [user (jdbc/execute-one!
               conn (sql/format {:select [:id :username :credential :authentication_method]
                                 :from   [:t_user]
                                 :where  [:= :username (.toLowerCase username)]}))]
    (when (can-authenticate-with-password? nadex user password)
      (select-keys user [:t_user/id :t_user/username]))))

(defn is-rsdb-user?
  [conn namespace username]
  (when (and conn (= "cymru.nhs.uk" namespace))
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
    (com.eldrix.pc4.rsdb.projects/common-concepts conn project-ids)))

(defn all-projects-and-children-identifiers
  "Returns identifiers for all projects to which user is registered, together
  with any sub-projects. In general, "
  [conn username]
  (let [project-ids (set (map :t_project/id (projects conn username)))]
    (into project-ids (map #(projects/all-children-ids conn %) project-ids))))

(s/fdef roles-for-user
  :args (s/cat :conn ::conn :username string?)
  :ret (s/coll-of ::role))
(defn roles-for-user
  "Return the roles for the given user, each flattened and pre-fetched to
  include keys from the 't_project_user' and related 't_project' tables.

  In essence, a user will have roles as defined by `t_project_user` rows.

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
  ([conn username] (roles-for-user conn username {}))
  ([conn username {:keys [project-id]}]
   (->> (db/execute!
          conn
          (sql/format {:select [:t_user/id :t_user/username :t_role/name :t_role/is_system
                                :t_project/id :t_project/title :t_project_user/*]
                       :from   :t_project_user
                       :join   [:t_user [:= :user_fk :t_user/id]
                                :t_project [:= :project_fk :t_project/id]
                                :t_role [:= :role_fk :t_role/id]]
                       :where  (if project-id [:and [:= :t_user/username username] [:= :t_project/id project-id]]
                                              [:= :t_user/username username])}))
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

(defn ^:deprecated make-authorization-manager
  "Create an authorization manager for the user with `username`. It is usually
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
               :t_professional_registration_authority/name
               :t_professional_registration_authority/abbreviation]
   :from      [:t_user]
   :left-join [:t_job_title [:= :t_user/job_title_fk :t_job_title/id]
               :t_professional_registration_authority [:= :t_user/professional_registration_authority_fk :t_professional_registration_authority/id]]})

(defn fetch-user [conn username]
  (db/execute-one! conn (sql/format (assoc fetch-user-query
                                      :where [:= :username (str/lower-case username)]))))

(defn fetch-user-by-id [conn user-id]
  (db/execute-one! conn (sql/format (assoc fetch-user-query
                                      :where [:= :t_user/id user-id]))))

(defn job-title [{custom-job-title :t_user/custom_job_title, job-title :t_job_title/name}]
  (if (str/blank? custom-job-title) job-title custom-job-title))


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

(s/def ::create-user (s/keys :req [:t_user/username
                                   :t_user/title
                                   :t_user/first_names
                                   :t_user/last_name
                                   :t_user/job_title_fk]
                             :opt [:t_user/email
                                   :t_user/custom_job_title]))
(defn create-user [conn {:t_user/keys [username custom_job_title email
                                       job_title_fk
                                       title first_names
                                       last_name] :as params}]
  (when-not (s/valid? ::create-user params)
    (throw (ex-info "Invalid parameters" (s/explain-data ::create-user params))))
  (let [[new-password credential] (random-password {})]
    (-> (next.jdbc/execute-one! conn (sql/format {:insert-into [:t_user]
                                                  :values      [(merge {:credential            credential
                                                                        :must_change_password  true
                                                                        :role_fk               4
                                                                        :authentication_method "LOCAL17"}
                                                                       params)]})
                                {:return-keys true})
        (assoc :t_user/new_password new-password))))

(defn reset-password!
  "Reset password for a user. Returns the new randomly-generated password."
  [conn {user-id :t_user/id}]
  (let [[new-password credential] (random-password {:nbytes 32})]
    (jdbc.sql/update! conn :t_user {:credential credential
                                    :authentication_method "LOCAL17"
                                    :must_change_password true}
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


(defn fetch-user-photo [conn username]
  (jdbc/execute-one!
    conn
    (sql/format
      {:select [:username :data :originalfilename :mimetype :size]
       :from   [:erattachmentdata :erattachment :t_user]
       :where  [:and
                [:= :erattachment/attachmentdataid :erattachmentdata/id]
                [:= :erattachment/id :t_user/photo_fk]
                [:= :t_user/username username]]})))

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

(defn record-login
  "Record the date of login for audit purposes. At the moment, this simply
  records directly into the 'date_last_login' column of 't_user', but this
  would be better into a user log file as per how the audit trail functionality
  used to work in the legacy application."
  ([conn username] (record-login conn username (LocalDateTime/now)))
  ([conn username ^LocalDateTime date]
   (jdbc.sql/update! conn :t_user {:date_last_login date} {:username username})))


(defn is-nhs-wales-email? [email]
  (str/ends-with? (str/lower-case email) "wales.nhs.uk"))

(defn email-patient-identifiable-information?
  "Can this user receive patient identifiable information by email?
  At the moment, this simply checks the email address of the user."
  [{email :t_user/email}]
  (is-nhs-wales-email? email))

(defn sanitise-message [{:t_message/keys [subject body]}]
  "You have a new secure message on PatientCare")

(s/fdef send-message
  :args (s/cat :conn ::conn :from-user-id int?
               :to-user (s/keys :req [:t_user/id :t_user/email :t_user/send_email_for_messages])
               :patient (s/nilable (s/keys :req [:t_patient/id]))
               :subject string? :message string?))
(defn send-message
  "Send a message from one user to another. If the user has chosen in their
  preferences to 'send_email_for_messages', then a job in the queue will be
  created under topic :user/email. Returns a map containing the following keys:
  - message : the created message, including id
  - email   : if an email was queued, the payload of that job."
  [conn from-user-id {to-user-id :t_user/id, send-email :t_user/send_email_for_messages, email :t_user/email} {patient-pk :t_patient/id} subject body]
  (jdbc/with-transaction [txn conn]
    (log/debug "message" {:from from-user-id :to to-user-id :email email :send-email? send-email})
    (let [message (jdbc.sql/insert! txn :t_message {:t_message/date_time    (java.time.LocalDateTime/now)
                                                    :t_message/from_user_fk from-user-id
                                                    :t_message/is_unread    "true"
                                                    :t_message/is_completed "false"
                                                    :t_message/message      body
                                                    :t_message/to_user_fk   to-user-id
                                                    :t_message/patient_fk   patient-pk
                                                    :t_message/subject      subject})]
      (when send-email
        (log/debug "queuing email for message" {:message-id (:t_message/id message) :to to-user-id :to-email email :from from-user-id}))
      (cond-> {:message message}
              send-email
              (assoc :email (queue/enqueue-job txn :user/email {:message-id (:t_message/id message)
                                                                :to         email :subject subject :body body}))))))



(comment
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 10}))
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
  (check-password conn nil "system" "password")
  (can-authenticate-with-password? nil (fetch-user conn "system") "password")
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
