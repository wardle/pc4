(ns pc4.rsdb.jobs
  (:require [clojure.spec.alpha :as s]
            [pc4.emailer.interface :as email]
            [pc4.log.interface :as log]
            [pc4.queue.interface :as queue]))

(defmethod queue/handle-job! :user/email
  [{:keys [email]} _  payload]
  (if (s/valid? ::email/svc email)
    (do (log/debug "sending email " payload)
        (email/send-message email payload))
    (log/debug "no valid SMTP configuration to send email" payload)))