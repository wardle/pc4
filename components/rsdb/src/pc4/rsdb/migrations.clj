(ns pc4.rsdb.migrations
  (:require [next.jdbc :as jdbc]
            [migratus.core :as migratus]))

(def ^:private default-config
  "The :migration-dir key specifies the directory on the classpath in which to find SQL migration files."
  {:store         :database
   :migration-dir "rsdb/migrations/"})

(defn ^:private pending-list*
  [config]
  (migratus/pending-list config))

(defn pending-list
  "List pending migrations."
  [conn]
  (pending-list* (assoc-in default-config [:db :datasource] conn)))

(defn ^:private migrate*
  [config]
  (migratus/migrate config))

(defn migrate
  "Bring up any migrations that are not completed. Returns nil if successful, :ignore if the table is reserved, :failure otherwise."
  [conn]
  (migrate* (assoc-in default-config [:db :datasource] conn)))

(comment
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 10}))
  (jdbc/execute! conn ["SELECT * from t_patient LIMIT 1"])
  (def conf (assoc-in default-config [:db :datasource] conn))
  (migratus/pending-list conf)
  (migratus/migrate conf)
  (migratus/rollback conf)
  (migratus/create conf "add-form-msis29-is-deleted"))
