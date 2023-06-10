(ns com.eldrix.pc4.rsdb.projects-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest use-fixtures is]]
            [com.eldrix.pc4.rsdb.projects :as projects]
            [next.jdbc :as jdbc])
  (:import (java.time LocalDate)))

(stest/instrument)
(def ^:dynamic *conn* nil)
(def ^:dynamic *patient* nil)

(def test-db-connection-spec
  "Database connection specification for tests."
  {:dbtype "postgresql" :dbname "rsdb"})

(defn with-conn
  [f]
  (with-open [conn (jdbc/get-connection test-db-connection-spec)]
    (jdbc/with-transaction [txn conn {:rollback-only true :isolation :serializable}]
      (binding [*conn* txn]
        (f)))))

(use-fixtures :once with-conn)

(deftest fetch-project
  (let [project (projects/project-with-name *conn* "LEGACYMS")]
    (is (= "LEGACYMS" (:t_project/name project)))))

(deftest register-legacy-pseudonymous-patient
  (let [pt {:salt "1" :user-id 1 :project-id 1
            :nhs-number "9999999999" :sex :MALE :date-birth (LocalDate/of 1980 1 1)}
        pt1 (projects/register-legacy-pseudonymous-patient *conn* pt)
        pt2 (projects/register-legacy-pseudonymous-patient *conn* pt)]
    (= pt1 pt2)))
