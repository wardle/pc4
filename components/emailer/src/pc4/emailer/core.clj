(ns pc4.emailer.core
  "Support for email. Currently, this only supporting sending email and so is a
    *very* thin wrapper around the Clojure 'postal' library, which itself wraps
    the Jakarta Mail library. It is trivially easy to use."
  (:require
   [clojure.spec.alpha :as s]
   [postal.core :as postal]))

(s/def ::host string?)
(s/def ::port (s/int-in 0 65535))
(s/def ::user string?)
(s/def ::pass string?)
(s/def ::smtp (s/keys :req-un [::host] :opt-un [::port ::user ::pass]))

(s/def ::from string?)
(s/def ::to (s/or :single string? :multiple (s/coll-of string?)))
(s/def ::cc (s/or :single string? :multiple (s/coll-of string?)))
(s/def ::bcc (s/or :single string? :multiple (s/coll-of string?)))
(s/def ::message (s/keys :req-un [::from ::to] :opt-un [::subject ::body ::cc ::bcc]))

(defn valid-config?
  "Is the configuration valid?"
  [config]
  (s/valid? ::smtp config))

(s/fdef send-message
  :args (s/cat :smtp ::smtp :message ::message))
(defn send-message
  "Send a 'message' using configuration in 'smtp'. "
  [config message]
  (postal/send-message config message))

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


