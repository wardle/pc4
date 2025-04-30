(ns pc4.emailer.interface
  (:require
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [pc4.emailer.core :as core]))

(s/def ::svc ::core/config)
(s/def ::message ::core/message)

(defmethod ig/init-key ::svc [_ config]
  (when-not (core/valid-config? config)
    (throw (ex-info "Invalid SMTP configuration" config)))
  config)

(s/fdef send-message
  :args (s/cat :svc ::svc :message ::message))
(defn send-message
  [svc message]
  (core/send-message svc message))
