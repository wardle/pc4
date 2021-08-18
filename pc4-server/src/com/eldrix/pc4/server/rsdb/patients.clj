(ns com.eldrix.pc4.server.rsdb.patients
  (:require [clojure.tools.logging.readable :as log]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.string :as str]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [com.eldrix.pc4.server.rsdb.projects :as projects])
  (:import (java.time LocalDate)))

(defn fetch-episodes
  "Return the episodes for the given patient."
  [conn patient-identifier]
  (jdbc/execute!
    conn
    (sql/format {:select [:t_episode/*]
                 :from   [:t_episode]
                 :join   [:t_patient [:= :patient_fk :t_patient/id]]
                 :where  [:= :patient_identifier patient-identifier]})))

(defn active-episodes
  ([conn patient-identifier]
   (active-episodes conn patient-identifier (LocalDate/now)))
  ([conn patient-identifier ^LocalDate on-date]
   (->> (fetch-episodes conn patient-identifier)
        (filter #(contains? #{:referred :registered} (projects/episode-status % on-date))))))

(defn active-project-identifiers
  "Returns a set of project identifiers representing the projects to which
  the patient belongs.
  Parameters:
  - conn                : database connection or connection pool
  - patient-identifier  : patient identifier
  - include-parents?    : (optional, default true) - include transitive parents."
  ([conn patient-identifier] (active-project-identifiers conn patient-identifier true))
  ([conn patient-identifier include-parents?]
   (let [active-projects (set (map :t_episode/project_fk (active-episodes conn patient-identifier)))]
     (if-not include-parents?
       active-projects
       (into active-projects (flatten (map #(projects/all-parents-ids conn %) active-projects)))))))

(defn patients-in-projects-sql
  [project-ids on-date]
  (sql/format {:select-distinct :patient_fk
               :from            :t_episode
               :where           [:and
                                 [:in :project_fk project-ids]
                                 [:or
                                  [:is :t_episode/date_discharge nil]
                                  [:> :date_discharge on-date]]
                                 [:or
                                  [:is :date_registration nil]
                                  [:< :date_registration on-date]
                                  [:= :date_registration on-date]]]}))

(defn patients-in-projects
  "Return a set of patients in the projects specified, on the date `on-date`.
  Parameters:
  - conn        : database connection, or pool
  - project-ids : collection of project identifiers
  - on-date     : (optional, default now), date on which to determine membership

  Returns a set of patient identifiers."
  ([conn project-ids] (patients-in-projects conn project-ids (LocalDate/now)))
  ([conn project-ids ^LocalDate on-date]
   (transduce
     (map :t_episode/patient_fk)
     conj
     #{}
     (jdbc/plan conn (patients-in-projects-sql project-ids on-date)))))

(comment
  (patients-in-projects-sql [1 3 32] (LocalDate/now))
  (patients-in-projects conn #{1 3 32})
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 10}))
  (fetch-episodes conn 15203)
  (active-episodes conn 15203)
  (active-project-identifiers conn 15203)
  (map :t_episode/project_fk (active-episodes conn 15203))
  (projects/all-parents-ids conn 12)
  (projects/all-parents-ids conn 37)
  (projects/all-parents-ids conn 76)
  (projects/all-parents-ids conn 59)

  )