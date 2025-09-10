(ns pc4.araf.impl.db
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [next.jdbc.specs]
    [next.jdbc.sql :as jdbc-sql]
    [next.jdbc.date-time]
    [pc4.araf.impl.token :as token]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn])
  (:import
    [java.time Instant Duration]))

(next.jdbc.date-time/read-as-instant)

(def error-too-many-attempts ::too-many-attempts)
(def error-no-matching-request ::no-matching-request)

(s/def ::conn :next.jdbc.specs/connectable)
(s/def ::nhs-number nnn/valid?)
(s/def ::long-access-key string?)
(s/def ::access-key string?)

(defn get-failed-attempts
  [conn nhs-number from to]
  (jdbc/execute!
    conn
    (sql/format
      {:select   :date_time :from :access
       :where    [:and
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
                  [:<= :date_time to]]
       :order-by [[:date_time :desc]]})
    {:builder-fn rs/as-unqualified-maps}))

(defn lockout-duration []
  (Duration/ofHours 4))

(s/def ::max-attempts int?)
(s/def ::from inst?)
(s/def ::to inst?)
(s/fdef lockout
  :args (s/cat :conn ::conn :nhs-number ::nhs-number :opts (s/? (s/keys :opt-un [::max-attempts ::from ::to]))))
(defn lockout
  "Checks if there have been too many failed attempts for a given NHS number.
  Parameters:
  - conn
  - nhs-number
  - options    - optional, including :max-attempts, :from and :to
  Returns a map containing:
  - :exp - expiry of the lockout (java.time.Instant)
  - :s   - human-readable formatted string for hrs/minutes until can try again."
  ([conn nhs-number]
   (lockout conn nhs-number
            {:max-attempts 3
             :from         (Instant/.minus (Instant/now) (lockout-duration))
             :to           (Instant/now)}))
  ([conn nhs-number {:keys [max-attempts ^Instant from ^Instant to]}]
   (let [failed-attempts (get-failed-attempts conn nhs-number from to)]
     (when (>= (count failed-attempts) max-attempts)
       (let [lockout-until (some-> failed-attempts first :date_time (Instant/.plus (lockout-duration)))
             dur (Duration/between (Instant/now) lockout-until)
             hrs (Duration/.toHoursPart dur)
             mins (Duration/.toMinutesPart dur)]
         {:exp lockout-until
          :s   (str (when (pos-int? hrs) (str hrs "h")) " " mins "m")})))))

(s/fdef record-access!
  :args (s/cat :conn :next.jdbc.specs/connectable
               :request (s/keys :req-un [::nhs-number (or ::long-access-key ::access-key)])
               :success boolean?))
(defn record-access!
  "Records an access attempt in the access table."
  [conn {:keys [nhs-number access-key long-access-key]} success]
  (next.jdbc.sql/insert!
    conn :access
    (cond-> {:nhs_number nhs-number
             :success    success}
      access-key (assoc :access_key access-key)
      long-access-key (assoc :long_access_key long-access-key))
    {:builder-fn rs/as-unqualified-maps}))

(s/fdef create-request*
  :args (s/cat :nhs-number nnn/valid? :araf-type keyword? :expires #(instance? Instant %)))
(defn create-request*
  [nhs-number araf-type ^Instant expires]
  {:long_access_key (token/gen-long-access-key)
   :access_key      (token/gen-access-key)
   :nhs_number      nhs-number
   :araf_type       (name araf-type)
   :expires         expires})

(s/fdef create-request
  :args (s/cat :conn :next.jdbc.specs/connectable :nhs-number nnn/valid?
               :araf-type keyword? :expires #(instance? Instant %)))
(defn create-request
  "Creates a new request with generated access keys and the given NHS number, araf type, and expiry time."
  [conn nhs-number araf-type expires]
  (-> (next.jdbc.sql/insert! conn :request (create-request* nhs-number araf-type expires) {:builder-fn rs/as-unqualified-maps})
      (assoc :completed false :status :pending)
      (update :araf_type keyword)))

(defn normalize-access-key
  [s]
  (when-not (str/blank? s)
    (-> s (str/replace #"\s" "") (str/upper-case))))

(defn format-access-key
  ([s]
   (format-access-key s " "))
  ([s sep]
   (->> (partition-all 4 s)
        (map str/join)
        (str/join sep))))

(comment
  (normalize-access-key "123 \t bfg 433B"))


(defn -select-request-sql
  "Generates SQL for fetching a request with completion status."
  ([long-access-key now]
   {:select [:* [[:exists {:select [1] :from [:response]
                           :where  [:= :response.request_id :request.id]}] :completed]]
    :from   [:request]
    :where  [:and
             [:= :long_access_key long-access-key]
             [:> :expires now]]})
  ([nhs-number access-key now]
   {:select [:* [[:exists {:select [1] :from [:response]
                           :where  [:= :response.request_id :request.id]}] :completed]]
    :from   [:request]
    :where  [:and
             [:= :access_key access-key]
             [:= :nhs_number nhs-number]
             [:> :expires now]]}))

(defn status
  "Determine the status of an ARAF request.
  Returns one of: :pending, :acknowledged, :expired"
  [{:keys [completed expires] :as _request}]
  (cond
    completed :acknowledged
    (and expires (Instant/.isAfter (Instant/now) expires)) :expired
    :else :pending))

(defn -select-request
  "Fetches a request from the database using access key and NHS number.
   Only returns requests that have not expired. Includes derived completed status."
  ([conn long-access-key]
   (let [now (Instant/now)]
     (some-> (jdbc/execute-one!
               conn
               (sql/format (-select-request-sql long-access-key now))
               {:builder-fn rs/as-unqualified-maps})
             (update :araf_type keyword))))
  ([conn nhs-number access-key]
   (let [now (Instant/now)]
     (some-> (jdbc/execute-one!
               conn
               (sql/format (-select-request-sql nhs-number (normalize-access-key access-key) now))
               {:builder-fn rs/as-unqualified-maps})
             (update :araf_type keyword)))))

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
   (if-let [{:keys [nhs_number] :as request} (-select-request txn long-access-key)]
     (do (record-access! txn {:nhs-number nhs_number :long-access-key long-access-key} true)
         (assoc request :status (status request)))
     {:error   error-no-matching-request
      :message "Invalid access key"}))
  ([txn nhs-number access-key]
   (when (and nhs-number access-key)
     (if-let [{:keys [exp s]} (lockout txn nhs-number)]
       {:error         error-too-many-attempts
        :message       (str "Too many failed access attempts. Try again in " s)
        :lockout-until exp}
       (let [request (-select-request txn nhs-number access-key)]
         (record-access! txn {:nhs-number nhs-number :access-key access-key} (some? request))
         (or (when request (assoc request :status (status request)))
             {:error   error-no-matching-request
              :message "Invalid or expired access key or invalid NHS number"}))))))

(s/fdef fetch-request
  :args (s/or :by-long-access-key
              (s/cat :conn ::conn :long-access-key string?)
              :by-nnn-and-access-key
              (s/cat :conn ::conn :nhs-number nnn/valid? :access-key string?)))
(defn fetch-request
  "As [[fetch-request*]] but runs in a transaction."
  ([conn long-access-key]
   (jdbc/with-transaction [txn conn]
     (fetch-request* txn long-access-key)))
  ([conn nhs-number access-key]
   (jdbc/with-transaction [txn conn]
     (fetch-request* txn nhs-number access-key))))








(defn test-date-time-handling
  "Check that date time handling for JDBC is working as expected and returning
  [[java.time.Instant]] and not [[javax.sql.Timestamp]]."
  [ds]
  (jdbc/with-transaction [txn ds {:rollback-only true}]
    (let [exp (Instant/.plus (Instant/now) (Duration/ofDays 14))
          {:keys [date_time expires] :as req} (create-request txn "1111111111" :valproate-f exp)]
      (when-not (instance? Instant date_time)
        (throw (ex-info "expected type of java.time.Instant" {:request req :class (class date_time)})))
      (when-not (= exp expires)
        (throw (ex-info "roundtrip for expiry invalid " {:request req :exp exp}))))))

;; Response specs
(s/def ::request_id pos-int?)
(s/def ::data (s/nilable map?))
(s/def ::signature (s/nilable bytes?))
(s/def ::mime_type (s/nilable (s/and string? #(re-matches #"^image/.+" %))))
(s/def ::name (s/nilable string?))

(s/def ::response
  (s/keys :req-un [::request_id]
          :opt-un [::data ::signature ::mime_type ::name]))

(s/fdef create-response
  :args (s/cat :conn :next.jdbc.specs/connectable :data ::response))
(defn create-response
  "Inserts a new response record into the response table.
   Validates input data against spec before insertion."
  [conn response-data]
  (jdbc-sql/insert!
    conn
    #_(jdbc/with-logging conn #(log/info %1 %2))
    :response response-data {:builder-fn rs/as-unqualified-maps}))

(s/fdef submit-form!
  :args (s/cat :conn ::conn :long-access-key string?))
(defn submit-form!
  "Completes a form by inserting a response for the given request.
   Handles the transaction internally."
  [conn long-access-key data]
  (jdbc/with-transaction [txn conn]
    (let [{:keys [id]} (fetch-request* txn long-access-key)]
      (when-not id
        (throw (ex-info "No valid request found" {:long-access-key long-access-key})))
      (create-response txn (assoc data :request_id id)))))

(s/fdef get-responses-from
  :args (s/cat :conn :next.jdbc.specs/connectable :id pos-int?))
(defn get-responses-from
  "Fetches all responses from the database with an ID greater than the one specified."
  [conn id]
  (jdbc/execute! conn
                 ["SELECT * FROM response WHERE id > ? ORDER BY id ASC" id]
                 {:builder-fn rs/as-unqualified-maps}))

