(ns pc4.araf.interface
  "This ARAF component provides a set of pedestal routes for a patient-facing
  web application."
  (:require
    [clojure.spec.alpha :as s]
    [integrant.core :as ig]
    [migratus.core :as migratus]
    [next.jdbc :as jdbc]
    [next.jdbc.connection :as connection]
    [next.jdbc.specs]
    [pc4.araf.impl.db :as db]
    [pc4.araf.impl.server :as server]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn])
  (:import [com.zaxxer.hikari HikariDataSource]
           (java.time Duration Instant)))

(s/def ::ds :next.jdbc.specs/proto-connectable)
(s/def ::secret string?)
(s/def ::config (s/keys :req-un [::ds ::secret]))
(s/def ::svc (s/keys :req-un [::ds ::secret]))

(defmethod ig/init-key ::svc
  [_ {:keys [ds secret] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid araf patient configuration" (s/explain-data ::config config))))
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

(defmethod ig/halt-key! ::svc
  [_ {:keys [ds]}]
  (log/debug "stopping araf patient service")
  (when ds
    (.close ds)))

(s/fdef server-routes
  :args (s/cat :svc ::svc))
(defn server-routes
  "Return the araf patient server routing table."
  [svc]
  (server/routes svc))

(defn expiry
  "Convenience function to return a [[java.time.Instant]] of now + duration"
  ^Instant [^Duration duration]
  (Instant/.plus (Instant/now) duration))

(s/fdef create-request
  :args (s/cat :svc ::svc :nhs-number nnn/valid? :araf-type keyword? :expires inst?))
(defn create-request
  "Creates a new request with a generated token for the given NHS number, araf type, and expiry time.
  NOTE: this is designed for use within the araf-server patient-facing component and is therefore
  here mainly for REPL usage as there is otherwise no public Clojure API available."
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

