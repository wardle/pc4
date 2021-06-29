(ns com.eldrix.pc4.server.rsdb.users
  "Support for legacy RSDB application; user management."
  (:require [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [clojure.string :as str])
  (:import (er.extensions.crypting BCrypt)
           (java.security MessageDigest)
           (org.apache.commons.codec.binary Base64)))

(defn can-authenticate-with-password?
  "Support for legacy rsdb authentication."
  [pool {:t_user/keys [username credential authentication_method]} password]
  (when-not (str/blank? password)
    (case authentication_method
      "LOCAL"
      (let [md (MessageDigest/getInstance "SHA")
            hash (Base64/encodeBase64String (.digest md (.getBytes password)))]
        (log/warn "warning: using outdated password check for user " username)
        (= credential hash))
      "LOCAL17"
      (BCrypt/checkpw password credential)
      "NADEX"
      (nadex/can-authenticate? pool username password)
      ;; no matching method: log an error
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
  )