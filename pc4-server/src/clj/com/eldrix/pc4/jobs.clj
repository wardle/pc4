(ns com.eldrix.pc4.jobs
  "Service providing an asynchronous job queue.
  The defined jobs and their payloads are:

  |- Topic --------|- Description and parameters in the payload ---------------|
  | :user/email    | Send email to a user                                      |
  |                | :name - name of recipient                                 |
  |                | :sender - name of sender                                  |
  |                | :to - email address                                       |
  |                | :template, one of                                         |
  |                |    :password/reset (:url - URL for reset password)        |
  |                |    :extract/started                                       |
  |                |    :extract/finished (:url - URL for download link        |
  |                |    :message/received (:url - URL to read message)         |
  |                |  :url - a URL (dependent on template)                     |
  |----------------|-----------------------------------------------------------|
  | :user/sms      | Send an SMS to a user                                     |
  |                | :to - phone number                                        |
  |                | :template, one of                                         |
  |                |    :extract/started                                       |
  |                |    :extract/finished                                      |
  |----------------|-----------------------------------------------------------|
  | :extract/start | Generate a data extract                                   |
  |                | :user-id - user id                                        |
  |                | :extract - details of requested extract (see below)       |
  |                |                                                           |
  |----------------|-----------------------------------------------------------|"
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.concierge.notify :as notify]
            [com.eldrix.pc4.rsdb.queue :as queue]
            [integrant.core :as ig]
            [next.jdbc :as jdbc])
  (:import (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit)))

;;
;; configuration specification
;;
(s/def :uk.gov.notify/api-key string?)
(s/def ::pool-size pos-int?)
(s/def ::delay-ms pos-int?)
(s/def ::termination-timeout-secs pos-int?)
(s/def :com.eldrix.rsdb/conn any?)
(s/def ::config (s/keys :req [:com.eldrix.rsdb/conn]
                        :opt-un [::pool-size :uk.gov.notify/api-key
                                 ::termination-timeout-secs]))

;; job specification
(s/def ::topic #{:user/email :user/sms :extract/start})
(s/def ::template #{:password/reset :extract/started :extract/finished :message/received})
(s/def ::to string?)
(s/def ::name string?)
(s/def ::sender string?)
(s/def ::url string?)
(defmulti job-template-spec (fn [{:keys [topic template]}] [topic template]))
(defmethod job-template-spec [:user/email :extract/started] [_] (s/keys :req-un [::topic ::template ::to ::name]))
(defmethod job-template-spec [:user/email :extract/finished] [_] (s/keys :req-un [::topic ::template ::to ::name ::url]))
(defmethod job-template-spec [:user/email :message/received] [_] (s/keys :req-un [::topic ::template ::to ::name ::sender ::url]))
(s/def ::job (s/multi-spec job-template-spec :topic))


(def uk-gov-notify-email-template
  {:extract/started  "94f360c3-8c6b-43fc-abf9-9b57dc857b91"
   :extract/finished "dbeb7930-42d8-4eb5-af09-46d2b19433cb"
   :message/received "5926fb5c-04c9-4d0f-8018-ce0e895e3641"})

(defmulti execute-job (fn [_config {topic :topic}] topic))

(defmethod execute-job :user/email [{notify-api-key :uk.gov.notify/api-key} {:keys [to template] :as job}]
  (if-not notify-api-key
    (log/error "unable to send mail: missing uk.gov.notify/api-key in configuration" job)
    (try (notify/send-email notify-api-key to (uk-gov-notify-email-template template) job)
         (catch Exception e                                 ;; we don't retry if failed... just log
           (log/error "unable to send mail" e)))))

(defn process-job
  [{conn :com.eldrix.rsdb/conn, :as config}]
  (jdbc/with-transaction [txn conn]
    (try
      (let [[topic payload] (queue/dequeue-job txn)]
        (when topic                                           ;; return nil if no job, always return true if job was dequeued
          (let [job (assoc payload :topic topic)
                job' (s/conform ::job job)]
            (do (if (= job' :clojure.spec.alpha/invalid)
                  (log/error "skipping invalid job in queue" (s/explain ::job job))
                  (execute-job config job'))
                true))))
      (catch Exception e
        (log/error "Error not caught during processing job" e)))))

(defn process-jobs [{conn :com.eldrix.rsdb/conn, :as config}]
  (log/trace "checking job queue" (queue/queue-stats conn))
  (loop []
    (when (process-job config)
      (recur))))

(defmethod ig/init-key :com.eldrix.pc4/jobs
  [_ {pool-size :pool-size
      delay-ms  :delay-ms
      conn      :com.eldrix.rsdb/conn,
      :as       config :or {pool-size 2 delay-ms 500}}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid configuration" (s/explain-data ::config config))))
  (log/info "starting background job service" (select-keys config [:pool-size :delay-ms :termination-timeout-secs]))
  (let [executor (ScheduledThreadPoolExecutor. pool-size)
        task (.scheduleWithFixedDelay executor #(process-jobs config) 0 delay-ms TimeUnit/MILLISECONDS)]
    (assoc config :executor executor
                  :task task)))

(defmethod ig/halt-key! :com.eldrix.pc4/jobs
  [_ {:keys [executor termination-timeout-secs]
      :or   {termination-timeout-secs 5}}]
  (.shutdown executor)
  (when-not (.awaitTermination executor termination-timeout-secs TimeUnit/SECONDS)
    (log/error "timeout while waiting for executor service to shutdown")))

(defn enqueue
  "Enqueue a job. The implementation is subject to change, but the job will
  usually be executed in a background thread rather than performed
  synchronously. Usually jobs are performed in the order in which they are
  queued. Future versions of this function may support additional configuration
  options in order to change this default behaviour."
  [{conn :com.eldrix.rsdb/conn, executor :executor} topic job]
  (when-not (s/valid? ::job (assoc job :topic topic))
    (throw (ex-info "invalid job" (s/explain-data ::job (assoc job :topic topic)))))
  (queue/enqueue-job conn topic job))
