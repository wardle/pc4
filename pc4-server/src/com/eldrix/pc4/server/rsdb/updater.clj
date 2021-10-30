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
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [next.jdbc.sql :as sql]
            [next.jdbc :as jdbc])
  (:import (com.zaxxer.hikari HikariDataSource)))

(defn stream-extended-concepts
  "Return a channel on which all concepts will be returned."
  [hermes & {:keys [buf-n] :or {buf-n 50}}]
  (let [xf-extended-concept (map #(hermes/get-extended-concept hermes (:id %)))
        ch (a/chan buf-n xf-extended-concept)]
    (com.eldrix.hermes.impl.store/stream-all-concepts (.-store hermes) ch)
    ch))

(def upsert-concepts-sql
  (clojure.string/join " " ["insert into t_concept"
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

(def upsert-descriptions-sql
  (clojure.string/join " " ["insert into t_description"
                            "(concept_id, description_id, description_status_code, description_type_code, initial_capital_status, language_code, term)"
                            "values (?,?,?,?,?,?,?)"
                            "on conflict (description_id) do update"
                            "set concept_id = EXCLUDED.concept_id,"
                            "    description_status_code = EXCLUDED.description_status_code,"
                            "    description_type_code = EXCLUDED.description_type_code,"
                            "    initial_capital_status = EXCLUDED.initial_capital_status,"
                            "    language_code = EXCLUDED.language_code,"
                            "    term = EXCLUDED.term"]))

(defn ec->rf1-descriptions
  [ec]
  (let [preferred (->> (:descriptions ec)
                       (remove snomed/is-fully-specified-name?)
                       (filter #(seq (:preferredIn %)))
                       first)]
    (map (fn [d] [(get-in ec [:concept :id])                ;; concept_id
                  (:id d)                                   ;; description_id
                  (if (:active d) 0 1)                      ;; description_status_code 0 = "current  1 = "non-current"
                  (cond                                     ;;description_type_code 1 = preferred, 2 = synonym, 3 = fsn
                    (and (seq (:preferredIn d)) (snomed/is-fully-specified-name? d)) 3
                    (= preferred d) 1 :else 2)
                  (case (:caseSignificanceId d)             ;; initial_capital_status
                    900000000000020002 "1"                  ;; initial character is case-sensitive - we can make initial character lowercase
                    900000000000448009 "0"                  ;; entire term case insensitive - just make it all lower-case
                    900000000000017005 "1")                 ;; entire term is case sensitive - can't do anything
                  (:languageCode d)                         ;; language_code
                  (:term d)])                               ;; term
         (:descriptions ec))))

(def insert-relationships-sql
  (clojure.string/join " " ["insert into t_relationship"
                            "(source_concept_id, relationship_type_concept_id, target_concept_id, characteristic_type, refinability, relationship_group)"
                            "values (?,?,?,?,?,?)"]))
(defn ec->rf1-relationships
  "Generate RF1 relationships from an extended concept.
  We cheat here by skipping most of the data, as it is not used in the legacy
  application. All we need is the source, the type and the destination.
  For referential integrity, all concepts will need to exist if these data
  are imported, and all existing relationships should be simply deleted before
  creating these new relationships.
  Warning: the relationship_id is generated by PostgreSQL as we do not use it."
  [ec]
  (->> (reduce-kv (fn [acc k v] (into acc (map #(vector k %) v))) [] (:directParentRelationships ec))
       (map (fn [[type-id target-id]]
              [(get-in ec [:concept :id])                   ;; source_concept_id
               type-id                                      ;; relationship_type_concept_id
               target-id                                    ;; target_concept_id
               0                                            ;; characteristic_type
               0                                            ;;refinability
               0                                            ;;relationship_group
               ]))))

(def insert-cached-parents-sql
  (clojure.string/join " " ["insert into t_cached_parent_concepts"
                            "(child_concept_id, parent_concept_id)"
                            "values (?,?)"]))

(defn ec->cached-parents
  "Build a sequence of legacy cached parents.
  Parameters:
  - ec : extended concept
  Returns
  - A sequence of vectors containing child_concept_id and parent_concept_id"
  [ec]
  (let [concept-id (get-in ec [:concept :id])
        isa-parents (get-in ec [:parentRelationships 116680003])]
    (map #(vector concept-id %) isa-parents)))

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

(defn update-descriptions
  "Update the legacy rsdb descriptions from the data available in hermes.
  * Streams all concepts from hermes
  * Maps each to a sequence of RF1 representations for each concept's descriptions
  * Upserts each batch
  Returns a count of descriptions processed."
  [conn hermes & {:keys [batch-size] :or {batch-size 5000}}]
  (let [ch (stream-extended-concepts hermes)
        ch2 (a/chan 5 (partition-all batch-size))]
    (a/pipeline 4 ch2 (map ec->rf1-descriptions) ch)        ;; note - returns multiple descriptions per concept - so we need to concatenate a batch
    (a/<!! (a/reduce (fn [acc batch] (+ acc (apply + (execute-batch conn upsert-descriptions-sql (apply concat batch))))) 0 ch2))))

(defn update-relationships
  "Update the legacy rsdb relationships from the data available in hermes.
  * Delete all existing relationships
  * Streams all concepts from hermes
  * Maps each to a sequence of RF1 representations for each concept's relationships
  * Inserts each batch
  Returns a count of relationships processed."
  [conn hermes & {:keys [batch-size] :or {batch-size 5000}}]
  (jdbc/execute-one! conn ["truncate t_relationship"])
  (let [ch (stream-extended-concepts hermes)
        ch2 (a/chan 5 (partition-all batch-size))]
    (a/pipeline 4 ch2 (map ec->rf1-relationships) ch)       ;; note - returns multiple per concept - so we need to concatenate a batch
    (a/<!! (a/reduce (fn [acc batch] (+ acc (apply + (execute-batch conn insert-relationships-sql (apply concat batch))))) 0 ch2))))

(defn build-cached-parents
  "Builds a cache parent index.
  * Delete existing cache
  * Streams all concepts from hermes
  * Maps each to a sequence of vectors representing source and target concepts
  * Inserts each batch.
  Returns a count of rows processed."
  [conn hermes & {:keys [batch-size] :or {batch-size 5000}}]
  (jdbc/execute-one! conn ["truncate t_cached_parent_concepts"])
  (let [ch (stream-extended-concepts hermes)
        ch2 (a/chan 5 (partition-all batch-size))]
    (a/pipeline 4 ch2 (map ec->cached-parents) ch)          ;; note - returns multiple per concept - so we need to concatenate a batch
    (a/<!! (a/reduce (fn [acc batch] (+ acc (apply + (execute-batch conn insert-cached-parents-sql (apply concat batch))))) 0 ch2))))

(defn update-snomed'
  "Updates a legacy rsdb RF1 SNOMED database 'conn' from the data in the modern
  SNOMED RF2 service 'hermes'."
  [conn hermes]
  (println "Updating concepts")
  (update-concepts conn hermes)
  (println "Updating descriptions")
  (update-descriptions conn hermes)
  (println "Updating relationships")
  (update-relationships conn hermes)
  (println "Building legacy parent cache")
  (build-cached-parents conn hermes)
  (println "Now use legacy index creator to build a lucene6 index:\nSee https://github.com/wardle/rsterminology/releases/tag/v1.1\nRun using:\njava -jar target/rsterminology-server-1.1-SNAPSHOT.jar --config run.yml --build-index wibble"))


(defn update-snomed
  "Update the database db-name (default 'rsdb') with data from hermes.
  e.g
  clj -X com.eldrix.pc4.server.rsdb.updater/update-snomed :hermes '\"/Users/mark/Dev/hermes/snomed.db\"'

  will update the database 'rsdb' from data in the hermes file specified."
  [{:keys [db-name hermes] :or {db-name "rsdb"}}]
  (let [hermes-svc (hermes/open hermes)
        conn (next.jdbc.connection/->pool HikariDataSource {:dbtype                            "postgresql"
                                                                              :dbname          db-name
                                                                              :maximumPoolSize 10})]
    (update-snomed' conn hermes-svc)))

(comment

  (def hermes (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (hermes/get-extended-concept hermes 24700007)
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype                            "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 10}))
  (sql/get-by-id conn :t_concept 104001 :concept_id {})
  (sql/update! conn :t_concept (ec->rf1-concept ec) {:concept_id (get-in ec [:concept :id])})



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
