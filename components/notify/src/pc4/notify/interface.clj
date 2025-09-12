(ns pc4.notify.interface
  "gov.uk Notify integration"
  (:require
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [pc4.notify.impl :as impl]))

(s/def ::api-key string?)
(s/def ::config
  (s/keys :req-un [::api-key]))

(defmethod ig/init-key ::svc
  [_ config]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid gov.uk notify configuration" (s/explain-data ::config config))))
  config)

(defn send-sms!
  "Send an SMS to the phone number specified using the template and
  personalisation in 'params'. Returns the HTTP API response, but body is parsed
  from JSON into Clojure data structures automatically. Throws an exception if
  there is an error."
  [{:keys [api-key]} phone template-id params]
  (impl/send-sms api-key phone template-id params))

(comment
  (require '[pc4.config.interface :as config])
  (config/config :dev)
  (def api-key (get-in (config/config :dev) [::svc :api-key]))
  (def api-key (get-in (config/config :aws) [::svc :api-key]))
  api-key
  (def template-id "765ef309-74d0-43d8-80ea-1dbd9e92a3e8")
  (impl/send-sms api-key "07786000000" template-id {:sender "System Administrator"})
  (def template-id "64bc8d82-2161-4585-ac08-21feee5a7922")
  (impl/send-sms api-key "07786000000" template-id {:patient "Mark" :drug "Valproate" :url "https://slashdot.org"}))


