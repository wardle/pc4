(ns com.eldrix.pc4.rsdb.queue-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging.readable :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.connection]
            [clojure.spec.test.alpha :as stest]
            [com.eldrix.pc4.rsdb.queue :as queue])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit)))

(stest/instrument)

(def ^:dynamic *conn* nil)

(defn with-live-conn [f]
  (with-open [conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                                  :dbname          "rsdb"
                                                                  :maximumPoolSize 10})]
    (binding [*conn* conn]
      (f))))

(use-fixtures :each with-live-conn)

(deftest ^:live test-queue
  (let [n 500
        stats (queue/queue-stats *conn*)
        jobs (repeatedly n #(hash-map :uuid (random-uuid)))]
    (when-not (empty? stats)
      (throw (ex-info "error: job queue is not empty at start of test" stats)))
    (run! #(queue/enqueue-job *conn* :test/topic %) jobs)
    (is (= jobs (map second (queue/dequeue-jobs *conn* :test/topic n))))))

(deftest ^:live test-concurrent-queue
  (let [conn *conn*
        stats (queue/queue-stats *conn*)
        n 100
        jobs (repeatedly n #(hash-map :uuid (random-uuid)))
        processed (atom 0)
        worker (fn [topic duration-milliseconds]
                 #(try
                    (jdbc/with-transaction [txn conn]
                      (if-let [job (queue/dequeue-job txn topic)]
                        (do (log/debug "processing job " job)
                            (Thread/sleep duration-milliseconds)
                            (if (> (rand) 0.8) (throw (ex-info "failed to process" {:job job})))
                            (swap! processed inc))
                        (log/debug "no job in queue")))
                    (catch Exception e (log/debug (ex-message e) (ex-data e)))))]

    (when-not (empty? stats)
      (throw (ex-info "error: job queue is not empty at start of test" stats)))
    (run! #(queue/enqueue-job *conn* (rand-nth [:test/email :test/sms]) %) jobs)
    (let [executor (ScheduledThreadPoolExecutor. 5)]
      (.scheduleWithFixedDelay executor (worker :test/sms 20) 0 10 TimeUnit/MILLISECONDS)
      (.scheduleWithFixedDelay executor (worker :test/email 50) 0 10 TimeUnit/MILLISECONDS)
      (loop []
        (when (seq (queue/queue-stats *conn*))
          (Thread/sleep 200)
          (recur)))
      (is (= n @processed))
      (.shutdown executor)
      (when-not (.awaitTermination executor 1 TimeUnit/SECONDS)
        (println "ERROR: timeout while waiting for executor service to shutdown")))))