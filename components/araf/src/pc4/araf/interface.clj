(ns pc4.araf.interface
  (:require
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [next.jdbc.connection :as connection]
    [next.jdbc.specs]
    [pc4.araf.forms :as forms]
    [pc4.araf.impl :as impl]
    [pc4.araf.qr :as qr]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn])
  (:import [com.zaxxer.hikari HikariDataSource]
           (java.time Duration Instant)))

(def error-too-many-attempts ::error-too-many-attempts)
(def error-no-matching-request ::error-no-matching-request)

(s/def ::ds :next.jdbc.specs/proto-connectable)
(s/def ::patient-config (s/keys :req-un [::ds]))
(s/def ::patient-svc (s/keys :req-un [::ds]))

(defmethod ig/init-key ::patient
  [_ {:keys [ds] :as config}]
  (when-not (s/valid? ::patient-config config)
    (throw (ex-info "invalid araf patient configuration" (s/explain-data ::patient-config config))))
  (log/info "starting araf patient service" (select-keys ds [:dbname :maximumPoolSize]))
  (let [ds (connection/->pool HikariDataSource ds)
        migration-config {:store :database
                          :migration-dir "araf/migrations"
                          :db {:datasource ds}}
        pending (migratus/pending-list migration-config)]
    (when (seq pending)
      (log/info "performing pending migrations" pending)
      (migratus/migrate migration-config))
    {:ds ds}))

(defmethod ig/halt-key! ::patient
  [_ {:keys [ds]}]
  (log/info "stopping araf patient service")
  (when ds
    (.close ds)))

(s/def ::rsdb any?)
(s/def ::url string?)
(s/def ::clinician-config (s/keys :req-un [::rsdb ::url]))

(defmethod ig/init-key ::clinician
  [_ {:keys [rsdb url] :as config}]
  (when-not (s/valid? ::clinician-config config)
    (throw (ex-info "invalid araf clinician configuration" (s/explain-data ::clinician-config config))))
  (log/info "starting araf clinician service" {:url url})
  config)

(defmethod ig/halt-key! ::clinician
  [_ _service]
  (log/info "stopping araf clinician service"))

(defn generate-token
  "Generates a secure 8-character access token."
  []
  (impl/generate-token))

(defn expiry
  "Convenience function to return a [[java.time.Instant]] of now + duration"
  ^Instant [^Duration duration]
  (Instant/.plus (Instant/now) duration))


(s/fdef create-request
  :args (s/cat :svc ::patient-svc :nhs-number nnn/valid? :araf-type keyword? :expires inst?))
(defn create-request
  "Creates a new request with a generated token for the given NHS number, araf type, and expiry time."
  [{:keys [ds]} nhs-number araf-type expires]
  (impl/create-request ds nhs-number araf-type expires))

(s/def ::size pos-int?)

(s/fdef generate-qr-code
  :args (s/cat :base-url string? :nhs-number nnn/valid? :access-key string? :opts (s/? (s/keys :opt-un [::size]))))
(defn generate-qr-code
  "Generates a QR code for the given base URL, access key, and NHS number.
   Returns a byte array of the PNG image."
  ([base-url nhs-number access-key]
   (qr/generate base-url nhs-number access-key))
  ([base-url nhs-number access-key options]
   (qr/generate base-url nhs-number access-key options)))

(s/fdef fetch-request*
  :args (s/cat :txn :next.jdbc.specs/transactable :nhs-number nnn/valid? :access-key string?))
(defn fetch-request*
  "Checks if there have been too many failed attempts, then fetches request if allowed.
   Records the access attempt in the access log."
  [txn nhs-number access-key]
  (when (and nhs-number access-key)
    (if (impl/too-many-failed-attempts? txn nhs-number)
      (do
        (impl/record-access txn nhs-number access-key false)
        {:error   error-too-many-attempts
         :message "Too many failed access attempts. Please try again later."})
      (let [request (impl/fetch-request txn nhs-number access-key)]
        (impl/record-access txn nhs-number access-key (some? request))
        (or request
            {:error   error-no-matching-request
             :message "Invalid or expired access key or invalid NHS number"}))) ))

(s/fdef fetch-request
  :args (s/cat :svc ::patient-svc :access-key string? :nhs-number nnn/valid?))
(defn fetch-request
  "As [[fetch-request*]] but runs in a transaction."
  [{:keys [ds]} nhs-number access-key]
  (jdbc/with-transaction [txn ds]
    (fetch-request* txn nhs-number access-key)))

(defn form-config
  [araf-type]
  (forms/form-config araf-type))

(comment
  (def ds {:dbtype "postgresql" :dbname "araf_remote"})
  (def conn (jdbc/get-connection ds))
  (create-request {:ds ds} "1111111111" :valproate-female (expiry (Duration/ofDays 1)))
  (create-request {:ds ds} "2222222222" :valproate-female (expiry (Duration/ofMinutes 1)))
  (create-request {:ds ds} "1111111111" :valproate-female-na (expiry (Duration/ofDays 1)))
  (impl/too-many-failed-attempts? conn "1111111111")
  (fetch-request {:ds ds} "5VRDZKKA" "1111111111"))