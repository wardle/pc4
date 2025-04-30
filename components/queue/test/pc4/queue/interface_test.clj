(ns pc4.queue.interface-test
  (:require [clojure.core.async :as async]
            [clojure.test :as test :refer :all]
            [next.jdbc :as jdbc]
            [pc4.queue.interface :as queue]))

(def test-db-connection-spec
  "Database connection specification for tests."
  {:dbtype "postgresql" :dbname "rsdb"})

(deftest dummy-test
  (is (= 1 1)))

(def test-env {:a 1})
(def test-payload {:b 1})
(def ch (async/chan))

(defmethod queue/handle-job! ::test-job
  [job-type env payload]
  (async/>!! ch [job-type env payload]))

(deftest test-simple-queue
  (let [ds (jdbc/get-datasource test-db-connection-spec)
        worker (queue/create-worker {:ds ds :env test-env :queue :test-queue})]
    (with-open [conn (jdbc/get-connection ds)]
      (try (queue/start! worker)
           (queue/enqueue! conn :test-queue ::test-job test-payload)
           (let [[job-type env payload] (async/<!! ch)]     ;; wait for worker to finish
             (is (= ::test-job job-type))
             (is (= test-env env))
             (is (= test-payload payload)))
           (finally (queue/stop! worker))))))