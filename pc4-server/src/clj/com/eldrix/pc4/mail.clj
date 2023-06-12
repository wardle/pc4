(ns com.eldrix.pc4.mail
  "Support for email. Currently, this only supporting sending email and so is a
  *very* thin wrapper around the Clojure 'postal' library, which itself wraps
  the Jakarta Mail library. It is trivially easy to use."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.system :as pc4]
            [integrant.core :as ig]
            [postal.core :as postal]))

(s/def ::host string?)
(s/def ::port #(s/int-in-range? 0 65535 %))
(s/def ::user string?)
(s/def ::pass string?)
(s/def ::smtp (s/keys :req-un [::host] :opt-un [::port ::user ::pass]))

(s/def ::from string?)
(s/def ::to (s/or :single string? :multiple (s/coll-of string?)))
(s/def ::cc (s/or :single string? :multiple (s/coll-of string?)))
(s/def ::bcc (s/or :single string? :multiple (s/coll-of string?)))
(s/def ::message (s/keys :req-un [::from ::to] :opt-un [::subject ::body ::cc ::bcc]))

(s/fdef send-message
  :args (s/cat :smtp ::smtp :message ::message))
(defn send-message
  "Send a 'message' using configuration in 'smtp'. "
  [config message]
  (postal/send-message config message))

(defmethod ig/init-key ::smtp [_ config]
  (if config
    (do (log/info "Registering SMTP service via " (select-keys config [:host :port]))
        config)
    (log/info "No SMTP service configured")))

(comment
  (require '[com.eldrix.pc4.system :as pc4])
  (def config (::smtp (pc4/config :dev)))
  (postal/send-message {:host "cavmail.cymru.nhs.uk"}
                       {:from       "mark.wardle@wales.nhs.uk"
                        :to         "mark@wardle.org"
                        :subject    "Hello there"
                        :body       "Hello there Mark"
                        :user-agent "PatientCare v4"})
  (postal/send-message config
                       {:from    "mark@eldrix.co.uk"
                        :to      "mark@eldrix.co.uk"
                        :subject "Test message"
                        :body    [{:type    "text/html"
                                   :content "<html><head></head><body><h1>HELLO THERE</h1>This is test 4 of Amazon SES</body></html>"}]}))


