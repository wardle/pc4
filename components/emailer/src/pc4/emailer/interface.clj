(ns pc4.emailer.interface
  (:require
   [integrant.core :as ig]
   [pc4.emailer.core :as core]))

(defmethod ig/init-key ::svc [_ config]
  (when-not (core/valid-config? config)
    (throw (ex-info "Invalid SMTP configuration" config))))

(defn send-message
  [svc message]
  (core/send-message svc message))
