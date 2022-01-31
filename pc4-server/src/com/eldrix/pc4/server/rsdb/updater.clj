(ns com.eldrix.pc4.server.rsdb.updater
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
        - t_concept
        - t_description
        - t_relationship
        - t_cached_parent_concepts
  * UK organisation data
        - t_health_authority
        - t_trust
        - t_hospital
        - t_surgery
        - t_general_practitioner
        - t_postcode

  This namespace provides functions that can take modern reference data sources
  and update the v3 backend so it may continue to run safely."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.store]
            [com.eldrix.hermes.snomed :as snomed]
            [next.jdbc.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.connection])
  (:import (com.zaxxer.hikari HikariDataSource)))

(defn stream-extended-concepts
  "Return a channel on which all concepts will be returned."
  [hermes & {:keys [buf-n] :or {buf-n 50}}]
  (let [xf-extended-concept (map #(hermes/get-extended-concept hermes (:id %)))
        ch (a/chan buf-n xf-extended-concept)]
    (com.eldrix.hermes.impl.store/stream-all-concepts (.-store hermes) ch)
    ch))

(def upsert-concepts-sql
  (str/join " " ["insert into t_concept"
                 "(concept_id, concept_status_code, ctv_id, fully_specified_name, is_primitive, snomed_id)"
                 "values (?,?,?,?,?,?)"
                 "on conflict (concept_id) do update"
                 "set concept_status_code = EXCLUDED.concept_status_code,"
                 "ctv_id = EXCLUDED.ctv_id, fully_specified_name = EXCLUDED.fully_specified_name, is_primitive = EXCLUDED.is_primitive,"
                 "snomed_id = EXCLUDED.snomed_id"]))
(defn ec->rf1-concept [ec]
  [(get-in ec [:concept :id])                               ;; concept_id
   (if (get-in ec [:concept :active]) 0 1)                  ;; concept_status_code ( 0 = "current" 1 = "retired")
   ""                                                       ;; ctv_id
   (:term (first (filter snomed/is-fully-specified-name? (:descriptions ec)))) ;; fsn
   (if (snomed/is-primitive? (:concept ec)) 1 0)            ;; is_primitive
   ""                                                       ;; snomed_id
   ])


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
  [conn hermes & {:keys [batch-size] :or {batch-size 5000}}]
  (let [ch (stream-extended-concepts hermes)
        ch2 (a/chan 5 (partition-all batch-size))]
    (a/pipeline 4 ch2 (map ec->rf1-concept) ch)
    (a/<!! (a/reduce (fn [acc batch]
                       (+ acc (apply + (execute-batch conn upsert-concepts-sql batch)))) 0 ch2))))

(defn update-snomed'
  "Updates a legacy rsdb RF1 SNOMED database 'conn' from the data in the modern
  SNOMED RF2 service 'hermes'."
  [conn hermes]
  (println "Updating concepts")
  (update-concepts conn hermes))

(defn update-snomed
  "Update the database db-name (default 'rsdb') with data from hermes.
  e.g
  clj -X com.eldrix.pc4.server.rsdb.updater/update-snomed :hermes '\"/Users/mark/Dev/hermes/snomed.db\"'

  will update the database 'rsdb' from data in the hermes file specified."
  [{:keys [db-name hermes] :or {db-name "rsdb"}}]
  (let [hermes-svc (hermes/open hermes)
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
  (sql/update! conn :t_concept (ec->rf1-concept ec) {:concept_id (get-in ec [:concept :id])})

  (sql/get-by-id conn :t_concept 38097211000001103 :concept_id {})
  (update-concepts conn hermes)

  (def ch (stream-extended-concepts hermes))
  (a/<!! ch)
  (def ch2 (a/chan 5 (partition-all 500)))
  (a/pipeline 4 ch2 (map (comp rf1-concept->vals ec->rf1-concept)) ch)
  (a/<!! ch2)
  (def ch3 (a/reduce (fn [acc batch] (+ acc (apply + (upsert-concepts conn batch))))
                     0 ch2))
  (a/poll! ch3)
  (upsert-concepts conn [(ec->rf1-concept ec)])
  (map rf1-concept->vals [(ec->rf1-concept ec)])
  (update-descriptions conn hermes)
  (def ch2 (a/chan))
  (a/pipeline 4 ch2 (map ec->rf1-descriptions) ch)
  (a/<!! ch2)
  (apply concat (a/<!! ch2))
  (jdbc/execute-one! conn ["truncate t_relationship"])
  (update-relationships conn hermes)
  (a/pipeline 4 ch2 (map ec->cached-parent-concepts) ch)
  (a/<!! ch2)
  (build-cached-parents conn hermes)
  )
