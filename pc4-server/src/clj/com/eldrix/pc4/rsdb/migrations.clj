(ns com.eldrix.pc4.rsdb.migrations
  (:require [next.jdbc :as jdbc]
            [migratus.core :as migratus]))

(def default-config
  {:store         :database
   :migration-dir "migrations/"})

(defn pending-list [config]
  (migratus/pending-list config))

(defn migrate [config]
  (migratus/migrate config))

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
  (migratus/create conf "remove-sct-constraints"))