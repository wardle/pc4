(ns com.eldrix.pc4.server.rsdb.users
  "Support for legacy RSDB application; user management."
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [clojure.string :as str]
            [com.eldrix.pc4.server.rsdb.parse :as parse])
  (:import (er.extensions.crypting BCrypt)
           (java.security MessageDigest)
           (org.apache.commons.codec.binary Base64)))

(defn- can-authenticate-with-password?
  "Support for legacy rsdb authentication.
  For development, we temporarily use the fallback local authentication
  available if we do not have an active LDAP connection pool."
  [pool {:t_user/keys [username credential authentication_method]} password]
  (when-not (or (str/blank? password) (str/blank? credential))
    (cond
      (= authentication_method "LOCAL")                     ;; TODO: force password change for these users
      (let [md (MessageDigest/getInstance "SHA")
            hash (Base64/encodeBase64String (.digest md (.getBytes password)))]
        (log/warn "warning: using outdated password check for user " username)
        (= credential hash))

      (= authentication_method "LOCAL17")                   ;; TODO: upgrade to more modern hash here and in rsdb codebase
      (BCrypt/checkpw password credential)

      (and pool (= authentication_method "NADEX"))
      (nadex/can-authenticate? pool username password)

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


(defn- save-password!
  [conn username new-password & {:keys [update-auth-method?]}]
  (let [hash (BCrypt/hashpw new-password (BCrypt/gensalt))]
    (jdbc/execute-one!
      conn
      (sql/format {:update :t_user
                   :where  [:= :username username]
                   :set    (cond-> {:credential hash}
                                   update-auth-method?
                                   (assoc :authentication_method "LOCAL17"))}))))

(defn save-password
  "Save a password for the given user.
  This does not check existing password."
  [conn {:t_user/keys [username authentication_method]} new-password]
  (case authentication_method
    "LOCAL"
    (save-password! conn username new-password :update-auth-method? true)
    "LOCAL17"
    (save-password! conn username new-password)
    "NADEX"
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

(defn projects [conn username]
  (jdbc/execute!
    conn
    (sql/format {:select [:*]
                 :from   [:t_project]
                 :where  [:in :t_project/id {:select [:t_project_user/project_fk]
                                             :from   [:t_project_user :t_user]
                                             :where  [:and
                                                      [:= :t_project_user/user_fk :t_user/id]
                                                      [:= :t_user/username username]]}]})))

(def fetch-user-query
  {:select    [:username :title :first_names :last_name :postnomial :custom_initials
               :email :custom_job_title :t_job_title/name
               :can_be_responsible_clinician :is_clinical
               :send_email_for_messages
               :authentication_method :professional_registration
               :t_professional_registration_authority/name
               :t_professional_registration_authority/abbreviation]
   :from      [:t_user]
   :left-join [:t_job_title [:= :t_user/job_title_fk :t_job_title/id]
               :t_professional_registration_authority [:= :t_user/professional_registration_authority_fk :t_professional_registration_authority/id]]})

(defn fetch-user [conn username]
  (jdbc/execute-one! conn (sql/format (assoc fetch-user-query
                                        :where [:= :username (str/lower-case username)]))))

(defn fetch-user-by-id [conn user-id]
  (jdbc/execute-one! conn (sql/format (assoc fetch-user-query
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

(comment
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 10}))
  (count-incomplete-messages conn "ma090906")
  (count-unread-messages conn "ma090906")
  (sort (map :t_project/title (filter com.eldrix.pc4.server.rsdb.projects/active? (projects conn "ma090906"))))

  (group-by :t_project/type (projects conn "ma090906"))

  (fetch-user-photo conn "rh084967")
  (parse/parse-entity (fetch-user conn "ma090906"))
  (can-authenticate-with-password? nil (fetch-user conn "system") "password")
  )