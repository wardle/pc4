(ns pc4.araf.impl
  (:require
    [clojure.string :as str]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.date-time])
  (:import
    [java.security SecureRandom]
    [java.time Instant Duration]))

(def ^:private base32-chars "ABCDEFGHJKMNPQRSTVWXYZ23456789")

(defn generate-token
  "Generates a secure 8-character token using base32 encoding.
   Uses characters A-Z (excluding I,L,O,U) and digits 2-9 to avoid confusion."
  []
  (let [rng (SecureRandom.)
        n (count base32-chars)]
    (str/join (take 8 (repeatedly #(nth base32-chars (.nextInt rng n)))))))

(defn sql-count-failed-attempts
  [nhs-number from to]
  {:select :%count.*
   :from   :access
   :where  [:and
            [:= :nhs_number nhs-number]
            [:= :success false]
            [:> :date_time
             [:coalesce
              {:select :%max.date_time :from :access
               :where  [:and
                        [:= :nhs_number nhs-number]
                        [:= :success true]
                        [:>= :date_time from]]}
              from]]
            [:<= :date_time to]]})

(defn too-many-failed-attempts?
  "Checks if there have been too many failed attempts for an NHS number."
  ([conn nhs-number]
   (too-many-failed-attempts?
     conn nhs-number
     {:max-attempts 3
      :from         (Instant/.minus (Instant/now) (Duration/ofHours 4))
      :to           (Instant/now)}))
  ([conn nhs-number {:keys [max-attempts ^Instant from ^Instant to]}]
   (let [{:keys [count]}
         (jdbc/execute-one! conn (sql/format (sql-count-failed-attempts nhs-number from to)))]
     (>= count max-attempts))))

(defn record-access
  "Records an access attempt in the access table."
  [conn nhs-number access-key success]
  (jdbc/execute-one!
    conn
    (sql/format {:insert-into :access
                 :values      [{:access_key access-key
                                :nhs_number nhs-number
                                :success    success}]})))

(defn create-request
  "Creates a new request with a generated token for the given NHS number, araf type, and expiry time."
  [conn nhs-number araf-type expires]
  (let [token (generate-token)
        request (jdbc/execute-one!
                  conn
                  (sql/format {:insert-into :request
                               :values      [{:access_key token
                                              :nhs_number nhs-number
                                              :araf_type  (name araf-type)
                                              :expires    expires}]})
                  {:return-keys true})]
    (update request :request/araf_type keyword)))

(defn fetch-request
  "Fetches a request from the database using access key and NHS number.
   Only returns requests that have not expired."
  [conn nhs-number access-key]
  (let [now (Instant/now)]
    (when-let [request (jdbc/execute-one!
                         conn
                         (sql/format {:select :* :from :request
                                      :where  [:and
                                               [:= :access_key access-key]
                                               [:= :nhs_number nhs-number]
                                               [:> :expires now]]}))]
      (update request :request/araf_type keyword))))

