(ns pc4.araf.interface
  "This ARAF component provides:
    a) a set of pedestal routes for a patient-facing web application.
    b) a clinician-facing service that makes it easy to interact with the
       (remote) patient-facing server."
  (:require
    [clojure.spec.alpha :as s]
    [hato.client :as hc]
    [integrant.core :as ig]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [next.jdbc.connection :as connection]
    [next.jdbc.specs]
    [pc4.araf.impl.client :as client]
    [pc4.araf.impl.db :as db]
    [pc4.araf.impl.qr :as qr]
    [pc4.araf.impl.server :as server]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn])
  (:import [com.zaxxer.hikari HikariDataSource]
           (java.time Duration Instant)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Patient-facing araf service
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::ds :next.jdbc.specs/proto-connectable)
(s/def ::secret string?)
(s/def ::patient-config (s/keys :req-un [::ds ::secret]))
(s/def ::patient-svc (s/keys :req-un [::ds ::secret]))

(defmethod ig/init-key ::patient
  [_ {:keys [ds secret] :as config}]
  (when-not (s/valid? ::patient-config config)
    (throw (ex-info "invalid araf patient configuration" (s/explain-data ::patient-config config))))
  (log/debug "starting araf patient service" (select-keys ds [:dbname :maximumPoolSize]))
  (let [ds (connection/->pool HikariDataSource ds)
        migration-config {:store         :database
                          :migration-dir "araf/migrations"
                          :db            {:datasource ds}}
        pending (migratus/pending-list migration-config)]
    (when (seq pending)
      (log/debug "performing pending migrations" pending)
      (migratus/migrate migration-config))
    #_(db/test-date-time-handling ds)
    {:ds ds :secret secret}))

(defmethod ig/halt-key! ::patient
  [_ {:keys [ds]}]
  (log/debug "stopping araf patient service")
  (when ds
    (.close ds)))


(s/fdef server-routes
  :args (s/cat :svc ::patient-svc))
(defn server-routes
  "Return the araf patient server routing table."
  [svc]
  (server/routes svc))

(defn expiry
  "Convenience function to return a [[java.time.Instant]] of now + duration"
  ^Instant [^Duration duration]
  (Instant/.plus (Instant/now) duration))

(s/fdef create-request
  :args (s/cat :svc ::patient-svc :nhs-number nnn/valid? :araf-type keyword? :expires inst?))
(defn create-request
  "Creates a new request with a generated token for the given NHS number, araf type, and expiry time.
  NOTE: this is designed for use within the araf-server patient-facing component and is therefore
  here mainly for REPL usage as there is otherwise no public API available."
  [{:keys [ds]} nhs-number araf-type expires]
  (db/create-request ds nhs-number araf-type expires))

(comment
  (def ds {:dbtype "postgresql" :dbname "araf_remote"})
  (def conn (jdbc/get-connection ds))
  (create-request {:ds ds} "1111111111" :valproate-f (expiry (Duration/ofDays 12)))
  (create-request {:ds ds} "2222222222" :valproate-f (expiry (Duration/ofMinutes 1)))
  (create-request {:ds ds} "1111111111" :valproate-fna (expiry (Duration/ofDays 1)))
  (db/too-many-failed-attempts? conn "1111111111")
  (db/fetch-request {:ds ds} "5VRDZKKA" "1111111111"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Clinician-facing ARAF API
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(s/def ::url string?)
(s/def ::http-client-options map?)                          ;; see hato client documentation
(s/def ::http-client some?)
(s/def ::clinician-config (s/keys :req-un [::url ::secret] :opt-un [::http-client-options]))
(s/def ::clinician-svc (s/keys :req-un [::url ::secret ::http-client]))

(defmethod ig/init-key ::clinician
  [_ {:keys [url http-client-options] :as config}]
  (when-not (s/valid? ::clinician-config config)
    (throw (ex-info "invalid araf clinician configuration" (s/explain-data ::clinician-config config))))
  (log/info "starting araf clinician service" {:url url})
  (-> config
      (assoc :http-client (hc/build-http-client http-client-options))))

(defmethod ig/halt-key! ::clinician
  [_ _service]
  (log/info "stopping araf clinician service"))


(s/def ::size pos-int?)

(s/fdef generate-qr-code
  :args (s/cat :svc ::clinician-svc :long-access-key string? :opts (s/? (s/keys :opt-un [::size]))))
(defn generate-qr-code
  "Generates a QR code for the given base URL and long access key.
   Returns a byte array of the PNG image."
  ([svc long-access-key]
   (generate-qr-code svc long-access-key {}))
  ([{:keys [url]} long-access-key options]                  ;; TODO: could generate URL dynamically from routing table?
   (qr/generate (str url "araf/form/" long-access-key) options)))

(s/fdef send-create-request
  :args (s/cat :svc ::clinician-svc :params ::client/create-request-params))
(defn send-create-request
  "Create an ARAF request via the clinician service (calls patient server API).
  
  Parameters:
  - svc    : clinician service
  - params : map with :nhs-number, :araf-type, :expires
  
  Returns the created request from the patient server."
  [svc params]
  (client/create-request svc params))

(s/fdef send-get-request
  :args (s/cat :svc ::clinician-svc :long-access-key ::client/long-access-key))
(defn send-get-request
  "Fetch an ARAF request via the clinician service (calls patient server API).
  
  Parameters:
  - svc              : clinician service
  - long-access-key  : the request's long access key
  
  Returns the request from the patient server."
  [svc long-access-key]
  (client/get-request svc long-access-key))


