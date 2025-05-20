(ns pc4.rsdb.messages
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as jdbc.sql]
            [pc4.log.interface :as log]
            [pc4.queue.interface :as queue]
            [pc4.rsdb.db :as db]
            [pc4.rsdb.html :as html]
            [pc4.rsdb.patients :as patients]
            [pc4.rsdb.users :as users]
            [selmer.parser :as selmer])
  (:import (java.time LocalDateTime)))


(defn is-nhs-wales-email? [email]
  (str/ends-with? (str/lower-case email) "@wales.nhs.uk"))

(defn sanitise-emails?
  "Do we need to sanitise emails because the user's email is not 'secure'?
  At the moment, this simply checks the email address of the user."
  [{email :t_user/email}]
  (not (is-nhs-wales-email? email)))

(s/def ::id int?)
(s/def ::email string?)
(s/def ::mobile string?)
(s/def ::name string?)
(s/def ::subject string?)
(s/def ::body string?)
(s/def ::user (s/keys :req-un [::id ::name] :opt-un [::email ::mobile]))
(s/def ::from ::user)
(s/def ::to ::user)
(s/def ::patient (s/keys :req-un [::id ::name]
                         :opt-un [::dob ::nnn ::crn ::address]))
(s/def ::message
  (s/keys :req-un [::from ::to ::subject ::body]
          :opt-un [::patient]))


(defn ^:private create-message
  [{:keys [from to subject patient body]}]
  {:t_message/date_time    (LocalDateTime/now)
   :t_message/from_user_fk (:id from)
   :t_message/is_unread    "true"
   :t_message/is_completed "false"
   :t_message/message      body
   :t_message/to_user_fk   (:id to)
   :t_message/patient_fk   (:id patient)
   :t_message/subject      subject})

(defn ^:private create-email-job
  "Create an email job payload for a given message."
  [{:keys [from to subject body] :as data}]
  (when-not (s/valid? ::message data)
    (throw (ex-info "invalid parameters" (s/explain-data ::message data))))
  (when (:email to)
    (cond->
      {:to      (:email to)
       :subject "You have a new secure message in PatientCare"
       :body    [:alternative
                 {:type    "text/plain"
                  :content (selmer/render-file "rsdb/templates/secure-message-v1.txt"
                                               (assoc data :body (html/html->text body)))}
                 {:type    "text/html"
                  :content (selmer/render-file "rsdb/templates/secure-message-v1.html" data)}]}
      (:email from)
      (assoc :from (:email from)))))

(defn ^:private create-sms-job
  [{:keys [from to]}]
  {:from    (:name from)
   :to      (:mobile to)
   :message (str "You have a new secure message in PatientCare from " (:name from))})

(defn ^:private prep-send*
  "Generate actions for a given message."
  [{:keys [from to patient subject body] :as message} {:keys [email? sanitise? sms?]}]
  (when-not (s/valid? ::message message)
    (throw (ex-info "invalid parameters" (s/explain-data ::message message))))
  (cond->
    {:sql (next.jdbc.sql.builder/for-insert :t_message (create-message message) {})}
    email?
    (assoc :email (create-email-job (if sanitise?
                                      (-> message
                                          (assoc :body "As you do not have an NHS email registered for use with PatientCare, you must login from an NHS computer to read this message.")
                                          (dissoc :patient))
                                      message)))
    sms?
    (assoc :sms (create-sms-job message))))

(defn ^:private prep-send
  "Generate actions to send a message from one user to another."
  [conn from-user-id to-user-id patient-identifier subject body]
  (let [from-user (users/user->display-names (users/fetch-user-by-id conn from-user-id))
        to-user (users/user->display-names (users/fetch-user-by-id conn to-user-id))
        send-email? (:t_user/send_email_for_messages to-user)
        send-sms? (:t_user/send_sms_for_messages to-user)   ;; this is always false at the moment
        sanitise? (sanitise-emails? to-user)
        patient (when patient-identifier (patients/fetch-patient conn {:t_patient/patient_identifier patient-identifier}))
        address (when patient (patients/address-for-date (patients/fetch-patient-addresses conn patient)))
        message {:to      {:id    to-user-id
                           :name  (:t_user/full_name to-user)
                           :email (:t_user/email to-user)}
                 :from    {:id   from-user-id
                           :name (:t_user/full_name from-user)}
                 :subject subject
                 :patient (when patient-identifier
                            {:id      patient-identifier
                             :name    (str/join " " (remove str/blank? [(:t_patient/title patient)
                                                                        (:t_patient/first_names patient)
                                                                        (:t_patient/last_name patient)]))
                             :dob     (:t_patient/date_birth patient)
                             :nnn     (:t_patient/nhs_number patient)
                             :address (str/join ", " (remove str/blank? [(:t_address/address1 address)
                                                                         (:t_address/postcode_raw address)]))})
                 :body    body}]
    (prep-send* message {:email? send-email? :sanitise? sanitise? :sms? send-sms?})))

(s/fdef send-message
  :args (s/cat :conn ::conn :to-user-id int? :from-user-id int?
               :patient-identifier int? :subject string? :message string?))
(defn send-message
  "Send a message from one user to another.
  Queues an email if a target user has email notifications turned on."
  [conn from-user-id to-user-id patient-identifier subject body]
  (let [{:keys [sql email sms]} (prep-send conn from-user-id to-user-id patient-identifier subject body)]
    {:message (db/parse-entity (jdbc/execute-one! conn sql {:return-keys true}))
     :email   (when email (queue/enqueue! conn :default :user/email email))
     :sms     (when sms (queue/enqueue! conn :default :user/sms sms))}))

(comment
  (def conn (jdbc/get-connection "jdbc:postgresql:rsdb"))
  (prep-send* {:from    {:id    1
                         :name  "System administrator"
                         :email "mark@wardle.org"}
               :to      {:id    2
                         :name  "John Smith"
                         :email "john.smith@gmail.com"}
               :subject "Hi there"
               :body    "This is a message"})
  (send-message conn 2 1 14032 "Hi" "Body"))


(comment
  (require '[selmer.parser])
  (selmer.parser/render-file "rsdb/templates/secure-message-v1.txt"
                             {:from    "Mark Wardle"
                              :to      "Mark Wardle"
                              :patient {:id      12
                                        :name    "John Smith"
                                        :crn     "A123456"
                                        :nnn     "111 111 1111"
                                        :address "1 Station Rd"}
                              :body    "Hi. Can you call this patient back please?"
                              :url     "https://127.0.0.1"}))