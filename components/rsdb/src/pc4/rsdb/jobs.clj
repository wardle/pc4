(ns pc4.rsdb.jobs
  (:require [clojure.spec.alpha :as s]
            [pc4.emailer.interface :as email]
            [pc4.log.interface :as log]
            [pc4.queue.interface :as queue]))

(defn enqueue!
  ([svc job-type payload]
   (queue/enqueue! svc :default job-type payload))
  ([svc queue job-type payload]
   (queue/enqueue! svc queue job-type payload)))


(defmethod queue/handle-job! :user/email
  [{:keys [email config]} _  payload]
  (if (s/valid? ::email/svc email)
    (let [payload (merge (get-in config [:email :default-payload]) payload)]
      (do (log/info "sending email " (select-keys payload [:from :to :subject]))
          (log/trace "email:" (:body payload))
          (email/send-message email payload)))
    (log/debug "no valid SMTP configuration to send email" payload)))

(defmethod queue/handle-job! :user/sms
  [svc _ {:keys [from mobile message] :as payload}]
  (log/info "Send SMS - not implemented:" payload))