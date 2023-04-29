(ns com.eldrix.pc4.rsdb.job
  "Queue for asynchronous jobs built using PostgreSQL"
  (:require [clojure.edn :as edn]
            [clojure.tools.logging.readable :as log]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn get-pending-jobs-sql
  ([n] (get-pending-jobs-sql nil n))
  ([topic n]
   (sql/format {:delete-from :t_job_queue
                :using       [[(cond-> {:select   :*
                                        :from     :t_job_queue
                                        :limit    n
                                        :order-by [[:queue_time :asc]]
                                        :for      [:update :skip-locked]}
                                       topic (assoc :where [:= :topic topic]))
                               :q]]
                :where       [:= :q/id :t_job_queue/id]
                :returning   :t_job_queue/*})))

(defn get-pending-jobs
  "Return pending jobs, or nil. Perform in a transaction so that jobs
  remains queued until transaction complete."
  ([txn n] (get-pending-jobs txn nil n))
  ([txn topic n]
   (->> (jdbc/execute! txn (get-pending-jobs-sql topic n))
        (map :t_job_queue/payload)
        (map edn/read-string))))

(defn queue-job [conn topic job]
  (jdbc/execute! conn
                 (sql/format {:insert-into :t_job_queue
                              :columns     [:topic :payload]
                              :values      [[topic (pr-str job)]]})))

(defn queue-stats
  [conn]
  (reduce (fn [acc {topic :topic, n :count}] (assoc acc topic n))
          {}
          (jdbc/plan conn (sql/format {:select   [:topic :%count.id]
                                       :from     :t_job_queue
                                       :group-by :topic}))))

(comment
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (jdbc/execute! conn (get-pending-jobs-sql))
  (jdbc/with-transaction [txn conn]
    (when-let [jobs (seq (get-pending-jobs txn "message" 5))]
      (log/info "Processing jobs" jobs)
      (throw (ex-info "I failed to perform jobs" {:jobs jobs}))))
  (dotimes [n 5] (queue-job conn "message" {:a n :download false}))
  (get-pending-jobs conn "message" 5)
  (queue-stats conn)
  (jdbc/execute! conn ["vacuum full t_job_queue"]))
