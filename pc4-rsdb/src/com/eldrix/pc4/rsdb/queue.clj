(ns com.eldrix.pc4.rsdb.queue
  "Queue for asynchronous jobs built using PostgreSQL.
  The current design deletes completed jobs, and keeps a long-running transaction for the length of the job. This
  could cause difficulties in certain circumstances such as large numbers of jobs but the current plan is this will
  support low frequency asynchronous tasks. The other option is to keep jobs, update their status as they are
  dequeued and then on a per-job basis given a timeout such that a transaction doesn't have to be held open."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn pr-topic [kw]
  (subs (str kw) 1))

(defn ^:private dequeue-jobs-sql
  ([n] (dequeue-jobs-sql nil n))
  ([topic n]
   (sql/format {:delete-from :t_job_queue
                :using       [[(cond-> {:select   :*
                                        :from     :t_job_queue
                                        :limit    n
                                        :order-by [[:created :asc] [:id :asc]]
                                        :for      [:update :skip-locked]}
                                       topic (assoc :where [:= :topic (pr-topic topic)]))
                               :q]]
                :where       [:= :q/id :t_job_queue/id]
                :returning   :t_job_queue/*})))

(defn ^:private dequeue-job-by-id-sql
  [job-id]
  (sql/format {:delete-from :t_job_queue
               :using       [[(cond-> {:select :*
                                       :from   :t_job_queue
                                       :where  [:= :id job-id]
                                       :for    [:update :skip-locked]})
                              :q]]
               :where       [:= :q/id :t_job_queue/id]
               :returning   :t_job_queue/*}))

(s/fdef dequeue-jobs
  :args (s/cat :txn any? :topic (s/? (s/nilable keyword?)) :n pos-int?)
  :ret (s/coll-of (s/cat :topic keyword? :payload any?)))
(defn dequeue-jobs
  "Return pending jobs, or nil. Perform in a transaction if it is required  that
  jobs remain queued until successfully completed. Otherwise, the jobs will be
  dequeued immediately. Each job is a vector of 'topic' and 'payload'."
  ([txn n] (dequeue-jobs txn nil n))
  ([txn topic n]
   (reduce (fn [acc {topic :topic, payload :payload}] (conj acc (vector (keyword topic) (edn/read-string payload))))
           []
           (jdbc/plan txn (dequeue-jobs-sql topic n)))))

(s/fdef dequeue-job
  :args (s/cat :txn any? :topic (s/? (s/nilable keyword?)))
  :ret (s/cat :topic keyword? :payload any?))
(defn dequeue-job
  "Returns a single job from the queue as a vector of 'topic' and 'payload'.
  This should be performed in a transaction if it is required that the job
  remains queued until successful completion of the job. Otherwise, the job will
  be dequeued immediately."
  ([txn] (dequeue-job txn nil))
  ([txn topic]
   (when-let [{:t_job_queue/keys [topic payload]} (jdbc/execute-one! txn (dequeue-jobs-sql topic 1))]
     (vector (keyword topic) (edn/read-string payload)))))

(defn dequeue-job-by-id
  "Return a single job by id from the queue as a vector of topic and payload.
  This must be performed in a transaction if it is required that the job remain
  queued until the processing of the job completes successfully. Otherwise, the
  job will be dequeued immediately."
  [txn {:t_job_queue/keys [id]}]
  (when-let [{:t_job_queue/keys [topic payload]} (jdbc/execute-one! txn (dequeue-job-by-id-sql id))]
    (vector (keyword topic) (edn/read-string payload))))

(s/fdef enqueue-job
  :args (s/cat :conn any? :topic keyword? :job any?))
(defn enqueue-job
  "Enqueue a job under the topic specified.
  - topic : a keyword representing the topic
  - job   : a map of data serialisable using edn.
  Returns the generated job."
  [conn topic job]
  (jdbc/execute! conn
                 (sql/format {:insert-into :t_job_queue
                              :columns     [:topic :payload]
                              :values      [[(pr-topic topic) (pr-str job)]]})
                 {:return-keys true}))


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
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (jdbc/execute! conn (dequeue-jobs-sql 5))
  (jdbc/with-transaction [txn conn]
    (when-let [job (dequeue-job txn :basic/report)]
      (log/info "Processing job" job)
      (throw (ex-info "I failed to perform job" {:job job}))))
  (dotimes [n 5] (enqueue-job conn :user/message {:from_user_id 1 :t_user_id 5 :message "Hello"}))
  (dotimes [n 5] (enqueue-job conn :user/report {:user_id 1 :report_id 5}))
  (dotimes [n 5] (enqueue-job conn :user/message {:message-id (random-uuid) :from_user_id 1 :t_user_id 5 :message "Goodbye"}))
  (dotimes [n 5] (enqueue-job conn :user/message {:uuid (random-uuid) :from_user_id 1 :t_user_id 5 :message "Hello"}))
  (dequeue-job conn :user/message)
  (dequeue-jobs conn 5)
  (dequeue-job conn)
  (queue-stats conn)
  (jdbc/execute! conn ["vacuum full verbose t_job_queue"]))