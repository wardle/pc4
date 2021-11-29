(ns com.eldrix.pc4.server.rsdb.users
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
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.string :as str]
            [com.eldrix.pc4.server.rsdb.auth :as auth]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [com.eldrix.pc4.server.rsdb.projects :as projects])
  (:import (er.extensions.crypting BCrypt)
           (java.security MessageDigest)
           (org.apache.commons.codec.binary Base64)
           (java.time LocalDate)
           (com.eldrix.pc4.server.rsdb.auth AuthorizationManager)))

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
  Parameters:
   - conn     : database connection
   - nadex    : LDAP connection pool
   - username : username
   - password : password."
  [conn nadex username password]
  (let [user (jdbc/execute-one!
               conn (sql/format {:select [:username :credential :authentication_method]
                                 :from   [:t_user]
                                 :where  [:= :username (.toLowerCase username)]}))]
    (can-authenticate-with-password? nadex user password)))

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
                   :set    (cond-> {:credential hash
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
  (jdbc/execute-one!
    conn
    (sql/format {:select [[:%count.t_message/id :unread_messages]]
                 :from   [:t_message :t_user]
                 :where  [:and
                          [:= :t_user/username username]
                          [:= :t_message/to_user_fk :t_user/id]
                          [:= :is_unread "false"]]})))

(defn count-incomplete-messages
  [conn username]
  (jdbc/execute-one!
    conn
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

(defn all-projects-and-children-identifiers
  "Returns identifiers for all projects to which user is registered, together
  with any sub-projects. In general, "
  [conn username]
  (let [project-ids (set (map :t_project/id (projects conn username)))]
    (into project-ids (map #(projects/all-children-ids conn %) project-ids))))

(defn role-active?
  "Determine the status of the role as of now, or on the specified date."
  ([role] (role-active? role (LocalDate/now)))
  ([{:t_project_user/keys [^LocalDate date_from ^LocalDate date_to]} ^LocalDate on-date]
   (and (or (nil? date_from)
            (.equals on-date date_from)
            (.isAfter on-date date_from))
        (or (nil? date_to)
            (.isBefore on-date date_to)))))

(defn roles-for-user
  "Return the roles for the given user, each flattened and pre-fetched to
  include keys from the 't_project_user' and related 't_project' tables.

  In essence, a user will have roles as defined by `t_project_user` rows.

  For convenience, the following properties are included:
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
  [conn username]
  (->> (db/execute!
         conn
         (sql/format {:select [:t_user/id :t_user/username :t_role/name :t_role/is_system
                               :t_project/* :t_project_user/*]
                      :from   :t_project_user
                      :join   [:t_user [:= :user_fk :t_user/id]
                               :t_project [:= :project_fk :t_project/id]
                               :t_role [:= :role_fk :t_role/id]]
                      :where  [:= :t_user/username username]}))
       (map #(assoc % :t_project_user/active? (role-active? %)
                      :t_project/active? (projects/active? %)
                      :t_project_user/permissions (get auth/permission-sets (:t_project_user/role %))))))

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

(defn- ^AuthorizationManager make-authorization-manager'
  "Create an authorization manager for the user specified, providing subsequent
  decisions on authorization for a given action via an open-ended permission
  system. The manager is an immutable service; it closes over the permissions
  at the time of creation. The manager is principally designed for use in a
  single request-response cycle."
  [roles]
  (if (:t_role/is_system (first roles))
    (reify auth/AuthorizationManager                        ;; system user: can do everything...
      (authorized? [_ patient-project-ids permission] true)
      (authorized-any? [_ permission] true))
    (reify auth/AuthorizationManager                        ;; non-system users defined by project roles
      (authorized? [_ project-ids permission]
        (some #(contains? (permissions-for-project roles %) permission) project-ids))
      (authorized-any? [_ permission]
        (some #(contains? (:t_project_user/permissions %) permission) roles)))))

(defn ^AuthorizationManager make-authorization-manager
  [conn username]
  (make-authorization-manager' (roles-for-user conn username)))

(def fetch-user-query
  {:select    [:t_user/id :username :title :first_names :last_name :postnomial :custom_initials
               :email :custom_job_title :t_job_title/name
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
                       :order-by [[:date_time :desc]]
                       :limit     5})))

(comment
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 10}))
  (count-incomplete-messages conn "ma090906")
  (count-unread-messages conn "ma090906")
  (fetch-latest-news conn "ma090906")
  (projects conn "ma090906")
  (sort (map :t_project/title (filter com.eldrix.pc4.server.rsdb.projects/active? (projects conn "ma090906"))))

  (group-by :t_project/type (projects conn "ma090906"))

  (fetch-user-photo conn "rh084967")
  (fetch-user conn "ma090906")
  (can-authenticate-with-password? nil (fetch-user conn "system") "password")
  (group-by :t_project_user/role (roles-for-user conn "ma090906"))
  (map :t_project/id (roles-for-user conn "ma090906"))
  (filter #(= 15 (:t_project/id %)) (roles conn "ma090906"))
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
  (is-rsdb-user? conn "cymru.nhs.uk" "ma090906")
  )