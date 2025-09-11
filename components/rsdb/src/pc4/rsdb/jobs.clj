(ns pc4.rsdb.jobs
  (:require [clojure.spec.alpha :as s]
            [pc4.emailer.interface :as email]
            [pc4.log.interface :as log]
            [pc4.queue.interface :as queue]
            [pc4.notify.interface :as notify]))

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


(s/def ::phone-number string?)
(s/def ::patient string?)
(s/def ::drug string?)
(s/def ::url string?)
(s/def ::content
  (s/keys :req-un [::patient ::drug ::url]))
(s/def ::araf-request-by-sms
  (s/keys :req-un [::phone-number ::content]))

(defmethod queue/handle-job! :araf/request-by-sms
  [{:keys [notify]} job-type {:keys [phone-number content] :as payload}]
  (when-not (s/valid? ::araf-request-by-sms payload)
    (throw (ex-info "invalid job payload" {:job-type job-type :payload payload})))
  (log/info "sending sms" job-type payload)
  (let [response (notify/send-sms! notify phone-number "64bc8d82-2161-4585-ac08-21feee5a7922" content)]
    (log/info "sent sms " {:id (get-in response [:body :id])
                           :template-id (get-in response [:body :template :id])
                           :message (get-in response [:body :content :body])})))
