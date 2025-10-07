(ns pc4.rsdb.projects-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest use-fixtures is]]
            [next.jdbc :as jdbc]
            [pc4.rsdb.helper :as helper]
            [pc4.rsdb.projects :as projects])
  (:import (java.time LocalDate)))

(stest/instrument)
(def ^:dynamic *conn* nil)
(def ^:dynamic *patient* nil)

(defn with-conn
  [f]
  (with-open [conn (jdbc/get-connection (helper/get-dev-datasource))]
    (jdbc/with-transaction [txn conn {:rollback-only true :isolation :serializable}]
      (binding [*conn* txn]
        (f)))))

(use-fixtures :each with-conn)

(deftest ^:live fetch-project
  (let [project (projects/project-with-name *conn* "LEGACYMS")]
    (is (= "LEGACYMS" (:t_project/name project)))))

(deftest ^:live register-legacy-pseudonymous-patient
  (let [pt {:salt "1" :user-id 1 :project-id 1
            :nhs-number "9999999999" :sex :MALE :date-birth (LocalDate/of 1980 1 1)}
        pt1 (projects/register-legacy-pseudonymous-patient *conn* pt)
        pt2 (projects/register-legacy-pseudonymous-patient *conn* pt)]
    (= pt1 pt2)))
