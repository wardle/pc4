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

(defn too-many-failed-attempts?
  "Checks if there have been too many failed attempts for an NHS number.
   Multi-arity: 
   - [conn nhs-number] uses defaults: 3 attempts, last 4 hours
   - [conn nhs-number config] for testing with custom parameters"
  ([conn nhs-number]
   (let [now (Instant/now)
         from (Instant/.minus now (Duration/ofHours 4))]
     (too-many-failed-attempts? conn nhs-number {:max-attempts 3 :from from :to now})))
  ([conn nhs-number {:keys [max-attempts from to]}]
   (let [result (jdbc/execute-one!
                  conn
                  (sql/format {:select :%count.* :from :access
                               :where  [:and
                                        [:= :nhs_number nhs-number]
                                        [:= :success false]
                                        [:>= :date_time from]
                                        [:<= :date_time to]]}))]
     (>= (:count result) max-attempts))))

(defn record-access
  "Records an access attempt in the access table."
  [conn token nhs-number success]
  (jdbc/execute-one!
    conn
    (sql/format {:insert-into :access
                 :values      [{:token      token
                                :nhs_number nhs-number
                                :success    success}]})))

(defn create-request
  "Creates a new request with a generated token for the given NHS number, araf type, and expiry time."
  [conn nhs-number araf-type expires]
  (let [token (generate-token)
        request (jdbc/execute-one!
                  conn
                  (sql/format {:insert-into :request
                               :values      [{:token      token
                                              :nhs_number nhs-number
                                              :araf_type  (name araf-type)
                                              :expires    expires}]})
                  {:return-keys true})]
    (update request :request/araf_type keyword)))

(defn fetch-request
  "Fetches a request from the database using access key and NHS number.
   Only returns requests that have not expired."
  [conn access-key nhs-number]
  (let [now (Instant/now)]
    (when-let [request (jdbc/execute-one!
                         conn
                         (sql/format {:select :* :from :request
                                      :where  [:and
                                               [:= :token access-key]
                                               [:= :nhs_number nhs-number]
                                               [:> :expires now]]}))]
      (update request :request/araf_type keyword))))

