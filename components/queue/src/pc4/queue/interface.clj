(ns pc4.queue.interface
  "Creates an asynchronous job queue based on the 'proletarian' library."
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [pc4.log.interface :as log]
            [proletarian.job :as job]
            [proletarian.protocols :as p]
            [proletarian.worker :as worker])
  (:import (java.sql Connection)))

(s/def ::ds any?)
(s/def ::queue keyword?)
(s/def ::env map?)
(s/def ::nthreads pos-int?)
(s/def ::polling-interval-ms pos-int?)

(s/def ::worker
  (s/keys :req-un [::queue] :opt-un [::env ::nthreads ::polling-interval-ms]))

(s/def ::workers (s/coll-of ::worker :min-count 1))

(s/def ::config
  (s/keys :req-un [::ds ::workers]
          :opt-un [::env]))

(s/def ::queue-workers #(instance? p/QueueWorker %))

(s/def ::svc
  (s/keys :req-un [::ds ::queue-workers ::serializer]))

(s/def ::job-id uuid?)

(defmulti handle-job!
  "Multi-method for background asynchronous job processing.
  Parameters:
  - env      : the standard configured worker environment
  - job-type : type of job
  - payload  : payload of the job"
  (fn [_env job-type _payload] job-type))

(defmethod handle-job! :default
  [_env job-type payload]
  (log/error "missing handle-job! multimethod for job type" job-type)
  (throw (ex-info (str "Missing handle-job! multimethod for job type" job-type)
                  {:job-type job-type, :payload payload})))

(defn supported-job-types
  "Returns a set of supported job types by key."
  []
  (-> handle-job! methods keys set (disj :default)))

(defn log-level
  [x]
  (case x
    ::worker/queue-worker-shutdown-error :error
    ::worker/handle-job-exception-with-interrupt :error
    ::worker/handle-job-exception :error
    ::worker/job-worker-error :error
    ::worker/polling-for-jobs :trace
    :proletarian.retry/not-retrying :error
    :info))

(defn logger
  [x data]
  (log/logp (log-level x) x data))

(defn create-serializer
  []
  (proletarian.transit/create-serializer))

(defn create-worker
  [{:keys [env ds queue nthreads polling-interval-ms serializer] :as config}]
  (when-not (s/valid? ::worker config)
    (throw (ex-info "invalid queue worker configuration" (s/explain-data ::worker-config config))))
  (worker/create-queue-worker
    ds
    (fn [job-type payload] (handle-job! env job-type payload)) ;; close over environment for all job workers
    (cond-> {:proletarian/log logger}
      queue (assoc :proletarian/queue queue)
      serializer (assoc :proletarian/serializer serializer)
      nthreads (assoc :proletarian/worker-threads nthreads)
      polling-interval-ms (assoc :proletarian/polling-interval-ms polling-interval-ms))))

(defn start! [queue-worker]
  (worker/start! queue-worker))

(defn stop! [queue-worker]
  (worker/stop! queue-worker))

(defmethod ig/init-key ::svc
  [_ {:keys [ds env workers] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid queue service configuration" (s/explain-data ::config config))))
  (log/info "starting queue worker service " {:n-workers (count workers)})
  (let [supported (or (seq (supported-job-types)) (throw (ex-info "no supported job types configured!" {})))
        serializer (create-serializer)
        queue-workers (map (fn [worker-config]
                             (log/info "creating queue worker " worker-config)
                             (create-worker (merge {:ds ds :env env :serializer serializer}
                                                   worker-config)))
                           workers)]
    (log/debug "starting queue workers supporting job types:" supported)
    (doseq [worker queue-workers]
      (worker/start! worker))
    (assoc config :queue-workers queue-workers :serializer serializer)))

(defmethod ig/halt-key! ::svc
  [_ {:keys [queue-workers]}]
  (log/info "stopping " (count queue-workers) "queue workers")
  (doseq [worker queue-workers]
    (worker/stop! worker)))

(s/fdef enqueue!
  :args (s/cat :svc-or-conn (s/or :svc ::svc :conn :next.jdbc.specs/connection)
               :queue ::queue :job-type ::job-type ::payload any?))
(defn enqueue!
  "Enqueue a job in the named job queue. Returns the job id of the enqueued job.
  Parameters:
  - svc-or-conn   : queue service, or a database connection or transaction
  - queue         : keyword with name of queue
  - job-type      : keyword representing job type
  - payload       : job payload

  It would usually be expected to call this in context of an existing database
  transaction/connection, but for convenience, the queue service can also be
  passed in.

  Options:
  - :process-at   : [[java.time.Instant]] for when job should be processed
  - :process-in   : [[java.time.Duration]] for when job should be processed"
  ([svc-or-conn queue job-type payload]
   (enqueue! svc-or-conn queue job-type payload {}))
  ([svc-or-conn queue job-type payload opts]
   (if (instance? Connection svc-or-conn)
     (job/enqueue! svc-or-conn job-type payload (assoc opts :proletarian/queue queue))
     (enqueue! (jdbc/get-connection (:ds svc-or-conn)) queue job-type payload opts))))

(s/fdef status
  :args (s/cat :svc ::svc :job-id ::job-id))
(defn status
  "Return the status of the job specified."
  [{:keys [ds serializer]} job-id]
  (some->
    (jdbc/execute-one!
      ds
      ["select job_id,ltrim(queue, ':') as queue,'queued' as status,ltrim(job_type, ':') as job_type,payload,attempts,enqueued_at,process_at, null as finished_at from proletarian.job
        where job_id=?
        union all
        select job_id,ltrim(queue, ':') as queue,ltrim(status,':') as status,ltrim(job_type, ':') as job_type,payload,attempts,enqueued_at, process_at, finished_at from proletarian.archived_job
        where job_id=?" job-id job-id]
      {:builder-fn rs/as-unqualified-maps})
    (update :status keyword)
    (update :queue keyword)
    (update :job_type keyword)
    (update :payload #(.decode serializer %))))

(comment
  (require '[pc4.config.interface :as config])
  (require '[integrant.core :as ig])
  (ig/load-namespaces (config/config :dev))
  (def system (ig/init (config/config :dev) [::svc]))
  system
  (keys system)
  (def svc (:pc4.queue.interface/svc system))
  (enqueue! svc :default :user/email {:to "mark@wardle.org" :from "mark@wardle.org"} {:process-in (java.time.Duration/ofMinutes 1)})
  (enqueue! svc :default :user/email {:to "mark@wardle.org" :from "mark@wardle.org" :subject "Hello World!"})
  (ig/halt! system)
  (status svc (parse-uuid "b5a7d132-1a05-460b-ab9f-3ab4d0abaa84"))
  (status svc (parse-uuid "317b903e-fd3d-4687-812c-ac32cf39132a"))
  (status svc (parse-uuid "17e59dcf-ea1d-4f09-b142-4f1ffb78b645"))
  (status svc (parse-uuid "72fa10e7-fcf1-49eb-8318-23c3a7b18d35")))
