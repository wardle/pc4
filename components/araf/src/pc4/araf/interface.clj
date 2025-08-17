(ns pc4.araf.interface
  (:require
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [next.jdbc.connection :as connection]
    [pc4.araf.impl :as impl]
    [pc4.araf.qr :as qr]
    [pc4.log.interface :as log])
  (:import [com.zaxxer.hikari HikariDataSource]))

(def error-too-many-attempts ::error-too-many-attempts)
(def error-no-matching-request ::error-no-matching-request)

(s/def ::ds any?)
(s/def ::patient-config (s/keys :req-un [::ds]))

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

(defn create-request
  "Creates a new request with a generated token for the given NHS number and araf type."
  [{:keys [ds]} nhs-number araf-type]
  (impl/create-request ds nhs-number araf-type))

(defn generate-qr-code
  "Generates a QR code for the given base URL, access key, and NHS number.
   Returns a byte array of the PNG image."
  ([base-url access-key nhs-number]
   (qr/generate base-url access-key nhs-number))
  ([base-url access-key nhs-number options]
   (qr/generate base-url access-key nhs-number options)))

(defn fetch-request
  "Checks if there have been too many failed attempts, then fetches request if allowed.
   Records the access attempt in the access log."
  [{:keys [ds]} access-key nhs-number]
  (when (and access-key nhs-number)
    (jdbc/with-transaction [tx ds]
      (if (impl/too-many-failed-attempts? tx nhs-number)
        (do
          (impl/record-access tx access-key nhs-number false)
          {:error   error-too-many-attempts
           :message "Too many failed access attempts. Please try again later."})
        (let [request (impl/fetch-request tx access-key nhs-number)]
          (impl/record-access tx access-key nhs-number (some? request))
          (or request
              {:error   error-no-matching-request
               :message "No request found for the provided access key and NHS number."}))))))


(comment
  (def ds {:dbtype "postgresql" :dbname "araf_remote"})
  (def conn (jdbc/get-connection ds))
  (create-request {:ds ds} "1111111111" :valproate-female)
  (impl/too-many-failed-attempts? conn "1111111111")
  (fetch-request {:ds ds} "5VRDZKKA" "1111111111"))