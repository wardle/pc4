(ns pc4.rsdb.msss-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :as stest]
   [clojure.test :as test :refer [deftest is]]
   [pc4.rsdb.msss :as msss]
   [next.jdbc :as jdbc]))

(stest/instrument)

(def test-db-connection-spec
  "Database connection specification for tests."
  {:dbtype "postgresql" :dbname "rsdb"})

(def msss-lookup-spec (:ret (s/get-spec `msss/msss-lookup)))

(deftest test-db-msss
  (with-open [conn (jdbc/get-connection test-db-connection-spec)]
    (let [msss-lookup (msss/msss-lookup {:type :db :conn conn})]
      (is (s/valid? msss-lookup-spec msss-lookup))
      (is (msss/msss-for-duration-and-edss msss-lookup 5 5.0))
      (is (msss/edss-for-duration-and-msss msss-lookup 5 5.0)))))

(deftest test-roxburgh-msss
  (let [msss-lookup (msss/msss-lookup {:type :roxburgh})]
    (is (s/valid? msss-lookup-spec msss-lookup))
    (is (= 8.078 (msss/msss-for-duration-and-edss msss-lookup 5 5.0)))))

(comment
  (def conn (jdbc/get-connection test-db-connection-spec))
  (test/run-tests)
  (s/valid? (:ret (s/get-spec `msss/msss-lookup)) (msss/msss-lookup {:type :roxburgh})))
