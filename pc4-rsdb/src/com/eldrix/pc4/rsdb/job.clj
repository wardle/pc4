(ns com.eldrix.pc4.rsdb.job
  "Queue for asynchronous jobs built using PostgreSQL.
  The current design deletes completed jobs, and keeps a long-running transaction for the length of the job. This
  could cause difficulties in certain circumstances such as large numbers of jobs but the current plan is this will
  support low frequency asynchronous tasks. The other option is to keep jobs, update their status as they are
  dequeued and then on a per-job basis given a timeout such that a transaction doesn't have to be held open."
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn pr-topic [kw]
  (subs (str kw) 1))

(defn ^:private pending-jobs-sql
  ([n] (pending-jobs-sql nil n))
  ([topic n]
   (sql/format {:delete-from :t_job_queue
                :using       [[(cond-> {:select   :*
                                        :from     :t_job_queue
                                        :limit    n
                                        :order-by [[:created :asc]]
                                        :for      [:update :skip-locked]}
                                       topic (assoc :where [:= :topic (pr-topic topic)]))
                               :q]]
                :where       [:= :q/id :t_job_queue/id]
                :returning   :t_job_queue/*})))



(s/fdef dequeue-jobs
  :args (s/cat :txn any? :topic (s/? (s/nilable keyword?)) :n pos-int?))
(defn dequeue-jobs
  "Return pending jobs, or nil. Perform in a transaction so that jobs
  remains queued until transaction complete."
  ([txn n] (dequeue-jobs txn nil n))
  ([txn topic n]
   (reduce (fn [acc {payload :payload}] (conj acc (edn/read-string payload)))
           []
           (jdbc/plan txn (pending-jobs-sql topic n)))))

(s/fdef dequeue-job
  :args (s/cat :txn any? :topic (s/? (s/nilable keyword?))))
(defn dequeue-job
  ([txn] (dequeue-job txn nil))
  ([txn topic]
   (edn/read-string (:t_job_queue/payload (jdbc/execute-one! txn (pending-jobs-sql topic 1))))))

(s/fdef enqueue-job
  :args (s/cat :conn any? :topic keyword? :job any?))
(defn enqueue-job [conn topic job]
  (jdbc/execute! conn
                 (sql/format {:insert-into :t_job_queue
                              :columns     [:topic :payload]
                              :values      [[(pr-topic topic) (pr-str job)]]})))


(defn queue-stats
  "Return a map of queue statistics keyed by topic. Currently, the only
  statistic returned is :pending representing a count of pending work in that
  topic. "
  [conn]
  (reduce (fn [acc {topic :topic, n :count}] (assoc acc (keyword topic) {:pending n}))
          {}
          (jdbc/plan conn (sql/format {:select   [:topic :%count.id]
                                       :from     :t_job_queue
                                       :group-by :topic}))))

(comment
  (require '[clojure.spec.test.alpha])
  (clojure.spec.test.alpha/instrument)
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (jdbc/execute! conn (pending-jobs-sql 5))
  (jdbc/with-transaction [txn conn]
    (when-let [job (dequeue-job txn :basic/report)]
      (log/info "Processing job" job)
      (throw (ex-info "I failed to perform job" {:job job}))))
  (dotimes [n 5] (enqueue-job conn :user/message {:from_user_id 1 :t_user_id 5 :message "Hello"}))
  (dequeue-job conn :user/message)
  (dequeue-jobs conn 5)
  (queue-stats conn)
  (jdbc/execute! conn ["vacuum full verbose t_job_queue"]))