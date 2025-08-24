(ns pc4.araf.interface
  (:require
    [clojure.spec.alpha :as s]
    [hato.client :as hc]
    [integrant.core :as ig]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [next.jdbc.connection :as connection]
    [next.jdbc.specs]
    [pc4.araf.impl.forms :as forms]
    [pc4.araf.impl.db :as db]
    [pc4.araf.impl.qr :as qr]
    [pc4.araf.impl.token :as token]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn])
  (:import [com.zaxxer.hikari HikariDataSource]
           (java.time Duration Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(def error-too-many-attempts ::error-too-many-attempts)
(def error-no-matching-request ::error-no-matching-request)

(s/def ::ds :next.jdbc.specs/proto-connectable)
(s/def ::secret string?)
(s/def ::patient-config (s/keys :req-un [::ds ::secret]))
(s/def ::patient-svc (s/keys :req-un [::ds ::secret]))

(defmethod ig/init-key ::patient
  [_ {:keys [ds secret] :as config}]
  (when-not (s/valid? ::patient-config config)
    (throw (ex-info "invalid araf patient configuration" (s/explain-data ::patient-config config))))
  (log/info "starting araf patient service" (select-keys ds [:dbname :maximumPoolSize]))
  (let [ds (connection/->pool HikariDataSource ds)
        migration-config {:store         :database
                          :migration-dir "araf/migrations"
                          :db            {:datasource ds}}
        pending (migratus/pending-list migration-config)]
    (when (seq pending)
      (log/info "performing pending migrations" pending)
      (migratus/migrate migration-config))
    (db/test-date-time-handling ds)
    {:ds ds :secret secret}))

(defmethod ig/halt-key! ::patient
  [_ {:keys [ds]}]
  (log/info "stopping araf patient service")
  (when ds
    (.close ds)))

(s/def ::rsdb any?)
(s/def ::url string?)
(s/def ::http-client-options map?)                          ;; see hato client documentation
(s/def ::clinician-config (s/keys :req-un [::rsdb ::url ::secret ::http-client-options]))
(s/def ::clinician-svc (s/keys :req-un [::rsdb ::url ::secret ::http-client]))
(defmethod ig/init-key ::clinician
  [_ {:keys [rsdb url http-client-options] :as config}]
  (when-not (s/valid? ::clinician-config config)
    (throw (ex-info "invalid araf clinician configuration" (s/explain-data ::clinician-config config))))
  (log/info "starting araf clinician service" {:url url})
  (-> config
      (assoc :http-client (hc/build-http-client http-client-options))))

(defmethod ig/halt-key! ::clinician
  [_ _service]
  (log/info "stopping araf clinician service"))

(defn expiry
  "Convenience function to return a [[java.time.Instant]] of now + duration"
  ^Instant [^Duration duration]
  (Instant/.plus (Instant/now) duration))

(s/fdef create-request
  :args (s/cat :svc ::patient-svc :nhs-number nnn/valid? :araf-type keyword? :expires inst?))
(defn create-request
  "Creates a new request with a generated token for the given NHS number, araf type, and expiry time."
  [{:keys [ds]} nhs-number araf-type expires]
  (db/create-request ds nhs-number araf-type expires))

(s/def ::size pos-int?)

(s/fdef generate-qr-code
  :args (s/cat :base-url string? :long-access-key string? :opts (s/? (s/keys :opt-un [::size]))))
(defn generate-qr-code
  "Generates a QR code for the given base URL and long access key.
   Returns a byte array of the PNG image."
  ([base-url long-access-key]
   (qr/generate base-url long-access-key))
  ([base-url long-access-key options]
   (qr/generate base-url long-access-key options)))

(s/fdef fetch-request*
  :args
  (s/or :by-long-access-key
        (s/cat :txn :next.jdbc.specs/transactable :long-access-key string?)
        :by-nnn-and-access-key
        (s/cat :txn :next.jdbc.specs/transactable :nhs-number nnn/valid? :access-key string?)))
(defn fetch-request*
  "Checks if there have been too many failed attempts, then fetches request if allowed.
   Records the access attempt in the access log."
  ([txn long-access-key]
   (if-let [{:keys [nhs_number] :as request} (db/fetch-request txn long-access-key)]
     (do (db/record-access! txn {:nhs-number nhs_number :long-access-key long-access-key} true)
         request)
     {:error   error-no-matching-request
      :message "Invalid access key"}))
  ([txn nhs-number access-key]
   (when (and nhs-number access-key)
     (if-let [{:keys [exp s]} (db/lockout txn nhs-number)]
       {:error         error-too-many-attempts
        :message       (str "Too many failed access attempts. Try again in " s)
        :lockout-until exp}
       (let [request (db/fetch-request txn nhs-number access-key)]
         (db/record-access! txn {:nhs-number nhs-number :access-key access-key} (some? request))
         (or request
             {:error   error-no-matching-request
              :message "Invalid or expired access key or invalid NHS number"}))))))

(s/fdef fetch-request
  :args (s/or :by-long-access-key
              (s/cat :svc ::patient-svc :long-access-key string?)
              :by-nnn-and-access-key
              (s/cat :svc ::patient-svc :nhs-number nnn/valid? :access-key string?)))
(defn fetch-request
  "As [[fetch-request*]] but runs in a transaction."
  ([{:keys [ds]} long-access-key]
   (jdbc/with-transaction [txn ds]
     (fetch-request* txn long-access-key)))
  ([{:keys [ds]} nhs-number access-key]
   (jdbc/with-transaction [txn ds]
     (fetch-request* txn nhs-number access-key))))

(defn form-config
  "Return the form definition for the Annual Risk Assessment Form (ARAF) type
  specified."
  [araf-type]
  (forms/form-config araf-type))

(defn format-access-key
  "Format an access key for human presentation purposes into groups of 4 digits.
  ```
  (format-access-key \"123456781234\")
  =>
  \"1234 5678 1234\"

  Takes an optional separator."
  ([s]
   (format-access-key s))
  ([s sep]
   (db/format-access-key s sep)))

(defn generate-jwt
  "Generate an authentication token. Optionally takes configuration which can
  include:
  - :now            - [[java.time.Instant]] for 'now' - useful in testing
  - :expiry-seconds - lifespan of the JWT in seconds"
  ([{:keys [secret]}]
   (token/gen-jwt secret))
  ([{:keys [secret]} options]
   (token/gen-jwt secret options)))

(defn valid-jwt?
  "Authenticate a JWT using the service's secret."
  ([{:keys [secret]} token]
   (token/valid-jwt? secret token))
  ([{:keys [secret]} token ^Instant timestamp]
   (token/valid-jwt? secret token timestamp)))

(s/fdef submit-form
  :args (s/cat :svc ::svc :long-access-key string?))
(defn submit-form!
  "Completes a form by inserting a response for the given request.
   Handles the transaction internally."
  [{:keys [ds]} long-access-key data]
  (jdbc/with-transaction [txn ds]
    (let [{:keys [id]} (fetch-request* txn long-access-key)]
      (when-not id
        (throw (ex-info "No valid request found" {:long-access-key long-access-key})))
      (db/create-response txn (assoc data :request_id id)))))

(s/fdef get-responses-from
  :args (s/cat :svc ::patient-svc :id pos-int?))
(defn get-responses-from
  "Fetches all responses from the database with an ID greater than the one specified."
  [{:keys [ds]} id]
  (db/get-responses-from ds id))

(comment
  (def ds {:dbtype "postgresql" :dbname "araf_remote"})
  (def conn (jdbc/get-connection ds))
  (create-request {:ds ds} "1111111111" :valproate-f (expiry (Duration/ofDays 1)))
  (create-request {:ds ds} "2222222222" :valproate-f (expiry (Duration/ofMinutes 1)))
  (create-request {:ds ds} "1111111111" :valproate-fna (expiry (Duration/ofDays 1)))
  (db/too-many-failed-attempts? conn "1111111111")
  (fetch-request {:ds ds} "5VRDZKKA" "1111111111"))