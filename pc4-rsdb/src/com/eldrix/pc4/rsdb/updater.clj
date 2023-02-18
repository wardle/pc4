(ns com.eldrix.pc4.rsdb.updater
  "A collection of tools to support updates to the legacy rsdb backend.

  rsdb v3 and earlier were monolithic applications with a single PostgreSQL
  backend.

  We may migrate away from PostgreSQL altogether, or at least, migrate to a
  different database schema, built upon immutable data. With unlimited time
  and resources, I'd build from the ground-up, and simply use legacy rsdb to
  pre-populate in read-only mode, with all users moving to v4 at the same time.

  However, we are going to have to run in parallel for some time with some users
  using v4, and others using v3, as functionality is ported across.

  That means we need to be able to upgrade legacy rsdb so we can continue to
  use as a backend for both v3 and v4 concurrently.

  This does not prevent future migration to a new data backend, perhaps
  datomic. But that cannot be at the same time as migrating to the v4
  front-end(s).

  The following reference data are stored:
  * SNOMED CT RF1 - in tables
        - t_concept  - simply for referential integrity

  This namespace provides functions that can take modern reference data sources
  and update the v3 backend so it may continue to run safely."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [com.eldrix.hermes.core :as hermes]
            [next.jdbc.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.connection])
  (:import (com.zaxxer.hikari HikariDataSource)))

(defn stream-concept-identifiers
  "Return a channel on which all concept identifiers will be returned."
  [hermes & {:keys [batch-size] :or {batch-size 5000}}]
  (let [ch (a/chan 5 (comp (map :id) (partition-all batch-size)))]
    (a/thread (hermes/stream-all-concepts (.-store hermes) ch))
    ch))

(def upsert-concepts-sql
  "insert into t_concept (concept_id) values (?) on conflict (concept_id) do nothing")

(defn execute-batch [conn sql batch]
  [conn sql batch]
  (with-open [conn' (jdbc/get-connection conn)
              stmt (next.jdbc/prepare conn' [sql])]
    (next.jdbc/execute-batch! stmt batch)))

(defn update-concepts
  "Update the legacy rsdb concepts from the data available in hermes.
  * Streams all concepts from hermes
  * Maps each to an RF1 representation
  * Turns into batches of values
  * Upserts each batch.
  Returns a count of concepts processed."
  [conn hermes]
  (let [ch (stream-concept-identifiers hermes)]
    (a/<!! (a/reduce (fn [acc batch]
                       (+ acc (apply + (execute-batch conn upsert-concepts-sql (map vector batch))))) 0 ch))))

(defn update-snomed'
  "Updates a legacy rsdb RF1 SNOMED database 'conn' from the data in the modern
  SNOMED RF2 service 'hermes'."
  [conn hermes]
  (println "Updating concepts")
  (update-concepts conn hermes))

(defn need-update?
  "Does the database need updating from the hermes service specified?"
  [conn hermes]
  (not= (:count (next.jdbc/execute-one! conn ["select count(*) from t_concept"]))
        (get-in (hermes/status* hermes {:counts? true}) [:components :concepts])))

(defn update-snomed
  "Update the database db-name (default 'rsdb') with data from hermes.
  e.g
  clj -X com.eldrix.pc4.rsdb.updater/update-snomed :hermes '\"/Users/mark/Dev/hermes/snomed.db\"'

  will update the database 'rsdb' from data in the hermes file specified."
  [{:keys [db-name hermes] :or {db-name "rsdb"}}]
  (with-open [hermes-svc (hermes/open hermes)
              conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                                  :dbname          db-name
                                                                  :maximumPoolSize 10})]
    (update-snomed' conn hermes-svc)))

(comment

  (def hermes (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (hermes/get-extended-concept hermes 24700007)
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 10}))
  (sql/get-by-id conn :t_concept 104001 :concept_id {})
  (sql/get-by-id conn :t_concept 38097211000001103 :concept_id {})
  (:count (next.jdbc/execute-one! conn ["select count(*) from t_concept"]))
  (get-in (hermes/status* hermes {:counts? true}) [:components :concepts])
  (update-concepts conn hermes))


