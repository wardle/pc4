(ns pc4.rsdb.encounters-test
  (:require
    [clojure.test :refer :all]
    [clojure.spec.alpha :as s]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [pc4.rsdb.encounters :as encounters]
    [pc4.rsdb.helper :as helper]))

(deftest ^:live sql-execution-test
  (testing "All generated parameter combinations produce executable SQL"
    (let [ds (helper/get-dev-datasource)
          exercises (s/exercise-fn `encounters/q-encounters 100)]
      (doseq [[[params] query] exercises]
        (try
          (jdbc/execute! ds (sql/format query))
          (is true)
          (catch Exception e
            (is false (str "SQL execution failed for params: " params 
                           "\nError: " (.getMessage e)))))))))