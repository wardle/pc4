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
  (let [conn *conn*                                         ;; take care to close over data source for any child threads
        stats (queue/queue-stats *conn*)
        n 100
        jobs (repeatedly n #(hash-map :uuid (random-uuid)))
        processed (atom 0)
        worker (fn [topic duration-milliseconds]
                 #(try
                    (jdbc/with-transaction [txn conn]
                      (if-let [job (queue/dequeue-job txn topic)]
                        (do (log/debug "processing job " job)
                            (Thread/sleep (rand-int duration-milliseconds))
                            (when (> (rand) 0.8) (throw (ex-info "pretending to fail processing" {:job job})))
                            (swap! processed inc)
                            (not (Thread/interrupted)))     ;; return true if we've done work and we're not interrupted...
                        (log/debug "no job in queue" topic)))
                    (catch Exception e (log/debug (ex-message e) (ex-data e)))))
        looper (fn [w] #(loop [] (if-not (w) (log/debug "looper suspending") (recur))))]
    (when-not (empty? stats)
      (throw (ex-info "error: job queue is not empty at start of test" stats)))
    (let [executor (ScheduledThreadPoolExecutor. 2)]
      (.scheduleWithFixedDelay executor (looper (worker :test/sms 300)) 0 200 TimeUnit/MILLISECONDS)
      (.scheduleWithFixedDelay executor (looper (worker :test/email 500)) 0 200 TimeUnit/MILLISECONDS)
      (run! #(do (queue/enqueue-job *conn* (rand-nth [:test/email :test/sms]) %)
                 (Thread/sleep (rand-int 100))) jobs)
      (loop []
        (when (seq (queue/queue-stats *conn*))
          (Thread/sleep 200)
          (recur)))
      (is (= n @processed))
      (.shutdown executor)
      (when-not (.awaitTermination executor 1 TimeUnit/SECONDS)
        (println "ERROR: timeout while waiting for executor service to shutdown")))))