(ns pc4.rsdb.patients
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.tools.logging.readable :as log]
    [com.eldrix.nhsnumber :as nnn]
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [integrant.core :as ig]
    [medley.core :as m]
    [next.jdbc :as jdbc]
    [next.jdbc.plan :as plan]
    [next.jdbc.sql]
    [pc4.nhs-number.interface :as nhs-number]
    [pc4.ods.interface :as clods]
    [pc4.ods.interface :as ods]
    [pc4.rsdb.db :as db]
    [pc4.rsdb.projects :as projects]
    [pc4.rsdb.users :as users])
  (:import (java.time LocalDate LocalDateTime)
           (java.time.format DateTimeFormatter)))

(s/def ::clods some?)

(comment
  (require '[integrant.repl.state])
  (def ods-svc (:pc4.ods.interface/svc integrant.repl.state/system))
  (def f (ods/make-related?-fn ods-svc "RWM"))
  (f "7A4"))

(defn xf-patient-hospital-by-org-code
  "Returns a transducer that can filter a sequence of t_patient_hospital to 
  return only those linked to the organisation specified."
  [ods-svc org-code]
  (let [related? (ods/related-org-codes ods-svc org-code)]    ;; a set of related organisational ids
    (filter (fn [{:t_patient_hospital/keys [hospital_identifier hospital_fk]}]
              (related? (or hospital_identifier hospital_fk))))))

(s/fdef set-cav-authoritative-demographics!
  :args (s/cat :clods ::clods, :txn ::db/txn
               :pt (s/keys :req [:t_patient/id])
               :ph (s/keys :req [:t_patient_hospital/id
                                 :t_patient_hospital/patient_fk
                                 (or :t_patient_hospital/hospital_fk :t_patient_hospital/hospital_identifier)
                                 :t_patient_hospital/patient_identifier])))
(defn set-cav-authoritative-demographics!
  "Set the authoritative source of demographics for a patient.
  This will be deprecated in the future and can only be used to use CAVUHB as
  the authoritative source for demographics data for the patient specified.
  Parameters:
  - ods  - 'clods' organisational data services instance
  - txn  - database connection [in a transaction]
  - pt   - patient, with key :t_patient/id
  - ph   - patient-hospital, with keys referencing hospital and patient identifier"
  [ods txn {patient-pk :t_patient/id :as pt}
   {ph-id :t_patient_hospital/id crn :t_patient_hospital/patient_identifier :t_patient_hospital/keys [patient_fk hospital_fk hospital_identifier] :as ph}]
  (when-not (= patient-pk patient_fk)
    (throw (ex-info "Mismatch between patient ids:" {:patient pt :patient-hospital ph})))
  (when (str/blank? crn)
    (throw (ex-info "Missing hospital number " ph)))
  (let [{:keys [root extension]} (if hospital_fk {:root nil :extension hospital_fk}
                                                 (ods/parse-org-id hospital_identifier))
        cavuhb? (ods/related-org-codes ods "7A4")]
    (if-not (cavuhb? extension)
      (throw (ex-info "Invalid organisation. Must be CAVUHB." {:patient ph :org extension}))
      ;; first, set patient record so it uses an authority for demographics
      (do
        (jdbc/execute-one! txn (sql/format {:update :t_patient
                                            :set    {:authoritative_demographics "CAVUHB"}
                                            :where  [:= :id patient-pk]}))
        ;; next, set the patient_hospital record to be authoritative
        (jdbc/execute-one! txn (sql/format {:update :t_patient_hospital
                                            :set    {:authoritative true}
                                            :where  [:and [:= :id ph-id] [:= :patient_fk patient-pk]]}))
        ;; and finally, ensure all other hospital numbers are not authoritative
        (jdbc/execute-one! txn (sql/format {:update :t_patient_hospital
                                            :set    {:authoritative false}
                                            :where  [:and [:<> :id ph-id] [:= :patient_fk patient-pk]]}))))))

(defn fetch-patient
  [conn {patient-pk :t_patient/id patient-identifier :t_patient/patient_identifier}]
  (db/execute-one!
    conn
    (sql/format {:select :*
                 :from   :t_patient
                 :where  (if patient-pk [:= :id patient-pk]
                                        [:= :patient_identifier patient-identifier])})))

(defn save-patient!
  [conn {patient-pk :t_patient/id :as patient}]
  (next.jdbc.sql/update!
    conn :t_patient
    (select-keys patient [:t_patient/country_of_birth_concept_fk
                          :t_patient/date_birth
                          :t_patient/date_death
                          :t_patient/ethnic_origin_concept_fk
                          :t_patient/first_names
                          :t_patient/last_name
                          :t_patient/marital_status_fk
                          :t_patient/racial_group_concept_fk
                          :t_patient/title
                          :t_patient/maiden_name
                          :t_patient/email
                          :t_patient/highest_educational_level_concept_fk])
    {:t_patient/id patient-pk}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Patient search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private q-token
  [s]
  (when-not (str/blank? s)
    (if-let [id (parse-long s)]
      [:= :patient_identifier id]
      (let [s' (str s "%")]
        [:or
         [:ilike :first_names (str "%" s "%")]
         [:ilike :last_name s']
         [:in :t_patient/id {:select :patient_fk :from :t_patient_hospital
                             :where  [:ilike :patient_identifier s']}]]))))

(defn ^:private q-tokens
  [s]
  (when-not (str/blank? s)
    (if-let [nhs-number (nhs-number/normalise s :strict)]   ;; handle special case of all input being an NHS number
      [:= :nhs_number nhs-number]
      (let [tokens (str/split s #"\s")                      ;; split by whitespace into tokens
            clauses (remove nil? (mapv q-token tokens))]
        (when (seq clauses)
          (into [:and] clauses))))))

(defn only-matching-search
  "Add a 'where' clause to limit a patient query to patients matching the search
  term specified. This matches for names, NHS number, CRNs and patient
  identifier."
  [query s]
  (if-let [clauses (q-tokens s)]
    (h/where query clauses)
    query))

(defn only-patient-identifier
  "Add 'where' clause to limit to given patient."
  [query patient-identifier]
  (h/where query [:= :t_patient/patient_identifier patient-identifier]))

(defn only-in-project-ids
  "Add 'join' and 'where' clauses to limit a patient query to only include
  patients who are currently registered to the given project-ids. Used in
  'search for my patients' if you know the user's own project-ids."
  ([query project-ids]
   (only-in-project-ids query project-ids nil))
  ([query project-ids ^LocalDate on-date]
   (let [on-date# (or on-date (LocalDate/now))]
     (-> query
         (h/left-join :t_episode [:= :t_episode/patient_fk :t_patient/id])
         (h/where [:in :project_fk project-ids])
         (h/where [:or
                   [:is :date_discharge nil]
                   [:> :date_discharge on-date#]])
         (h/where [:or
                   [:is :date_registration nil]
                   [:< :date_registration on-date#]
                   [:= :date_registration on-date#]])))))

(defn with-current-address
  "Updates a SQL patient query to include a patient's current address. Adds a
  'left join' to determine the 'current address' and optionally adds address
  columns into a 'select'. You will not want to add those columns into your
  select statement if you are using 'count' or 'select distinct' for example.
  ```
  (-> {:select :first_names,:last_name :from :t_patient :where [:= :patient_identifier 13358]}
      (with-current-address)
      (sql/format)
  =>
  [\"SELECT first_names, last_name, address1, address2, address3, address4, postcode_raw, date_from, date_to FROM t_patient LEFT JOIN (SELECT patient_fk, address1, address2, address3, address4, postcode_raw, date_from, date_to, row_number() over (partition by patient_fk order by date_from desc nulls last, date_to desc nulls last, id asc) AS address_row_number FROM t_address WHERE ((date_from IS NULL) OR (date_from <= ?)) AND ((date_to IS NULL) OR (date_to > ?))) AS current_address ON (address_row_number = ?) AND (t_patient.id = current_address.patient_fk) WHERE patient_identifier = ?\"
   #object[java.time.LocalDate 0x60b18ff8 \"2025-01-03\"]
   #object[java.time.LocalDate 0x60b18ff8 \"2025-01-03\"]
   1
   13358]
  ```"
  ([query]
   (with-current-address query {}))
  ([query {:keys [on-date update-select?]}]
   (let [on-date# (or on-date (LocalDate/now))]
     (-> query
         (h/left-join
           [{:select [:t_address/patient_fk :t_address/address1 :t_address/address2 :t_address/address3 :t_address/address4 :t_address/postcode_raw :t_address/date_from :t_address/date_to
                      [[:raw "row_number() over (partition by patient_fk order by date_from desc nulls last, date_to desc nulls last, id asc) AS address_row_number"]]]
             :from   :t_address
             :where  [:and
                      [:or [:= :t_address/date_from nil] [:<= :t_address/date_from on-date#]]
                      [:or [:= :t_address/date_to nil] [:> :t_address/date_to on-date#]]]} :current_address]
           [:and
            [:= :address_row_number 1]
            [:= :t_patient/id :current_address/patient_fk]])
         (cond->
           update-select?                                   ;; only alter select if explicitly requested
           (h/select :current_address/address1 :current_address/address2 :current_address/address3 :current_address/address4 :current_address/postcode_raw, :current_address/date_from :current_address/date_to))))))

(defn with-hospital-crns
  ([query {:keys [hospital-identifiers ods hospital-identifier update-select?]}]
   (let [hospital-identifiers
         (or hospital-identifiers                           ;; TODO: look at 'parent' org and then partner sites for that org?
             (when (and ods hospital-identifier) (clods/equivalent-org-codes ods hospital-identifier)))]
     (cond-> query                                          ;;TODO: or perhaps t_patient_hospital should have org not hospital???
       ;; if we have hospital identifiers -> use them 
       (seq hospital-identifiers)
       (h/left-join
         [(cond-> {:select   [:patient_fk
                              [[:raw " string_agg (patient_identifier, ' ') AS crn"]]]
                   :from     :t_patient_hospital

                   :group-by :patient_fk}
            hospital-identifiers
            (h/where :in :t_patient_hospital/hospital_fk hospital-identifiers))
          :ph]
         [:= :t_patient/id :ph/patient_fk])
       ;; handle case where there are no hospital identifiers -> so return an empty CRN
       (empty? hospital-identifiers)
       (h/left-join
         [{:select [:patient_fk [[:raw "'' as crn"]]] :from :t_patient_hospital :where :false} :ph]
         [:= :t_patient/id :ph/patient_fk])
       ;; update the select statement if required
       update-select?
       (->
         (h/select :crn))))))

(comment
  (def conn (jdbc/get-connection "jdbc:postgresql:rsdb"))
  (jdbc/execute! conn (-> {:select [:first_names, :last_name] :from :t_patient :where [:= :patient_identifier 13358]}
                          (with-current-address {:update-select? true})
                          (sql/format)))
  (sql/format (with-current-address {:select :* :from :t_patient})
              {:inline true})
  (require '[pc4.config.interface :as config])
  (def system (ig/init (config/config :dev) [:pc4.ods.interface/svc]))
  (ig/halt! system)
  (sort (ods/equivalent-and-child-org-ids ods nil "RWM"))
  (def ods (:pc4.ods.interface/svc system))
  (def s (sql/format (with-hospital-crns {:select [:patient_identifier :crn] :from :t_patient
                                          :where  [:= :patient_identifier 14032]}
                                         {:update-select?      true
                                          :ods                 ods
                                          :hospital-identifier "7A4"})
                     {:inline true}))
  (jdbc/execute! conn s))

(defn with-order-by
  "Add an order-by clause. 'order-by' must be one of 
  [:name :asc] [:name :desc] [:date-birth :asc] [:date-birth :desc]"
  [query order-by]
  (cond-> {:select :* :from [[query :p]]}
    (= [:name :asc] order-by)
    (h/order-by [:last_name :asc] [:first_names :asc])

    (= [:name :desc] order-by)
    (h/order-by [:last_name :desc] [:first_names :desc])

    (= [:date-birth :asc] order-by)
    (h/order-by [:date_birth :desc])

    (= [:date-birth :desc] order-by)
    (h/order-by [:date_birth :asc])))

(defn with-search-query
  "Convenience function to returns a query for Google-like search for a patient.
  Starts by looking to see if the text is an NHS number, but if not, tokenises
  the input search text and tries to interpret tokens thusly:
  - as a pc4 patient identifier (numeric digits)
  - as a CRN
  - as a combination of names.
  Search can be limited to patients of the specific project-ids if required."
  [query {:keys [s project-ids status address? ods hospital-identifier order-by limit offset]}]
  (cond-> query

    (not (str/blank? s))                                    ;; add free text search if requested
    (only-matching-search s)

    (nil? status)
    (h/where [:= :t_patient/status "FULL"])

    (seq status)                                            ;; limit by patient status by default
    (h/where [:in :t_patient/status status])

    address?                                                ;; include current address if required
    (with-current-address)

    (seq project-ids)                                       ;; limit to patients in specific projects if required
    (only-in-project-ids project-ids)

    hospital-identifier                                     ;; include hospital CRNs in results
    (with-hospital-crns {:ods ods :hospital-identifier hospital-identifier :update-select? true})

    order-by                                                ;; order-by has to come after all other clauses except pagination
    (with-order-by order-by)

    limit
    (h/limit limit)

    offset
    (h/offset offset)))

(defn search
  [conn {:keys [query s project-ids status address? order-by limit offset] :as opts}]
  (let [stmt (-> (or query {:select [:patient_identifier :t_patient/id] :from :t_patient})
                 (with-search-query opts))]
    (log/debug "patient search" {:opts opts :stmt stmt :sql (sql/format stmt)})
    (log/debug "sql:" (sql/format stmt {:inline true}))
    (m/dedupe-by :t_patient/patient_identifier (jdbc/execute! conn (sql/format stmt)))))

(comment

  (q-tokens "")
  (def ods (com.eldrix.clods.core/open-index "data/ods-2022-01-24.db" "data/nhspd-2022-11-10.db"))
  (jdbc/execute! conn (sql/format (-> {:select :* :from :t_patient}
                                      (with-search-query {:s "A123456" :ods ods :hospital-identifier "7A4"}))))
  (time (map
          (juxt :t_patient/last_name :t_patient/first_names :crn)
          (jdbc/execute! conn
                         (sql/format (with-search-query
                                       {:select :* :from :t_patient}
                                       {:s                   "davies" :address? true :order-by [:name :asc]
                                        :ods                 ods
                                        :hospital-identifier "RWMBV"
                                        :limit               50 :offset 0})
                                     {:pretty true :inline false}))))

  (only-matching-search {} "donald duck")
  (sql/format (with-search-query nil {:s "donald duck" :project-ids [5] :address? true}) {:pretty true :inline true})
  (sql/format (with-search-query nil {:s "a123456"}) {:inline true})

  (time (jdbc/execute!
          conn
          (-> {:select [:patient_identifier, :first_names :last_name :address1 :address2 :address3 :postcode_raw] :from :t_patient}
              (with-search-query {:s "mouse" :address? true :status #{"FULL" "FAKE" "PSEUDONYMOUS"} :order-by [:name :asc]})
              (sql/format)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn patient-pk->hospitals
  [conn patient-pk]
  (db/execute! conn (sql/format {:select [:*]
                                 :from   [:t_patient_hospital]
                                 :where  [:= :patient_fk patient-pk]})))

(defn patient-pk->hospitals-for-org
  [conn patient-pk ods-svc org-code]
  (let [xf (xf-patient-hospital-by-org-code ods-svc org-code)]
    (into [] xf (patient-pk->hospitals conn patient-pk))))

(defn patient-pk->crn-for-org
  "Returns a CRN for the organisation specified. Preferentially uses any
  identifiers marked as authoritative, but otherwise will return the most
  recently recorded CRN.
  Parameters:
  - conn       : database connection
  - patient-pk : patient primary key
  - ods-svc    : clods ODS service handle
  - org-code   : string representing UK organisation code, e.g. \"7A4\"."
  [conn patient-pk ods-svc org-code]
  (let [patient-hospitals (patient-pk->hospitals-for-org conn patient-pk ods-svc org-code)
        authoritative (filter :t_patient_hospital/authoritative patient-hospitals)]
    (:t_patient_hospital/patient_identifier
      (or (first authoritative) (last (sort-by :t_patient_hospital/id patient-hospitals))))))

(defn best-patient-crn-fn
  "Returns a function that can 'choose' the best CRN from a sequence of
  t_patient_hospital. This is useful in contexts in which we 'know' the current
  project (either user's default, or currently set). Within an encounter, we
  know the encounter organisation, so prefer [[patient-pk->crn-for-org]]."
  [conn ods-svc project-id]
  (let [org-ids (when project-id (projects/project->default-hospital-org-ids conn project-id))
        org-ids# (into #{} (mapcat #(ods/equivalent-org-codes ods-svc %) org-ids))]
    (fn [patient-hospitals]
      (let [related (filter #(org-ids# (:t_patient_hospital/hospital_fk %)) patient-hospitals)
            related' (filter :t_patient_hospital/authoritative related)]
        (cond
          ;; best match is first authoritative record linked to this project
          (seq related')
          (first related')
          ;; next best is first non-authoritative record linked to this project
          (seq related)
          (first related)
          ;; otherwise, choose either first authoritative record, or first record
          :else
          (let [authoritative (filter :t_patient_hospital/authoritative patient-hospitals)]
            (if (seq authoritative)
              (first authoritative)
              (first patient-hospitals))))))))

(comment
  (def conn (:pc4.rsdb.interface/conn integrant.repl.state/system))
  (patient-pk->crn-for-org conn 14031 ods-svc "rwm")
  (->> (patient-pk->hospitals-for-org
         conn
         14031
         ods-svc "7A4BV")
       (sort-by :t_patient_hospital/authoritative)))

(s/fdef fetch-patient-addresses
  :args (s/cat :conn ::db/conn :patient (s/keys :req [:t_patient/id])))
(defn fetch-patient-addresses
  "Returns patient addresses ordered using date_from descending."
  [conn {patient-pk :t_patient/id patient-identifier :t_patient/patient_identifier}]
  (db/execute! conn
               (sql/format {:select   [:t_address/id :address1 :address2 :address3 :address4 [:postcode_raw :postcode]
                                       :date_from :date_to :housing_concept_fk :ignore_invalid_address]
                            :from     :t_address
                            :join     [:t_patient [:= :t_patient/id :t_address/patient_fk]]
                            :where    (if patient-pk [:= :patient_fk patient-pk]
                                                     [:= :patient_identifier patient-identifier])
                            :order-by [[:date_to :desc] [:date_from :desc]]})))

(s/fdef address-for-date
  :args (s/cat :addresses (s/coll-of (s/keys :req [:t_address/date_from :t_address/date_to]))
               :on-date (s/? (s/nilable #(instance? LocalDate %)))))
(defn address-for-date
  "Given a collection of addresses sorted by date_from in descending order,
  determine the address on a given date, the current date if none given."
  ([sorted-addresses]
   (address-for-date sorted-addresses nil))
  ([sorted-addresses ^LocalDate date]
   (let [date' (or date (LocalDate/now))]
     (->> sorted-addresses
          (filter #(db/date-in-range? (:t_address/date_from %) (:t_address/date_to %) date'))
          first))))

(defn patient->episodes
  ([conn patient-pk]
   (patient->episodes conn patient-pk nil))
  ([conn patient-pk project-id-or-ids]
   (jdbc/execute!
     conn
     (sql/format {:select   [:*]
                  :from     [:t_episode]
                  :where    (if project-id-or-ids
                              [:and
                               [:= :patient_fk patient-pk]
                               (if (coll? project-id-or-ids)
                                 [:in :project_fk project-id-or-ids]
                                 [:= :project_fk project-id-or-ids])]
                              [:= :patient_fk patient-pk])
                  :order-by [[:t_episode/date_registration :asc]
                             [:t_episode/date_referral :asc]
                             [:t_episode/date_discharge :asc]]}))))

(defn episode-by-id
  [conn episode-id]
  (jdbc/execute-one! conn (sql/format {:select :* :from :t_episode :where [:= :id episode-id]})))

(defn fetch-episodes
  "Return the episodes for the given patient."
  [conn patient-identifier]
  (jdbc/execute!
    conn
    (sql/format {:select [:t_episode/*]
                 :from   [:t_episode]
                 :join   [:t_patient [:= :patient_fk :t_patient/id]]
                 :where  [:= :patient_identifier patient-identifier]})))

(defn active-episodes
  ([conn patient-identifier]
   (active-episodes conn patient-identifier (LocalDate/now)))
  ([conn patient-identifier ^LocalDate on-date]
   (->> (fetch-episodes conn patient-identifier)
        (filter #(contains? #{:referred :registered} (projects/episode-status % on-date))))))

(defn episode-id->encounters [conn episode-id]
  (db/execute! conn (sql/format {:select [:id :date_time]
                                 :from   [:t_encounter]
                                 :where  [:= :episode_fk episode-id]})))

(s/fdef fetch-death-certificate
  :args (s/cat :conn ::db/conn :patient (s/keys :req [:t_patient/id])))
(defn fetch-death-certificate
  "Return a death certificate for the patient specified.
  Parameters:
  conn - a database connection
  patient - a map containing :t_patient/id"
  [conn {patient-pk :t_patient/id}]
  (jdbc/execute-one! conn (sql/format {:select [:*]
                                       :from   [:t_death_certificate]
                                       :where  [:= :patient_fk patient-pk]})))

(defn active-project-identifiers
  "Returns a set of project identifiers representing the projects to which
  the patient belongs.
  Parameters:
  - conn                : database connection or connection pool
  - patient-identifier  : patient identifier
  - include-parents?    : (optional, default true) - include transitive parents."
  ([conn patient-identifier] (active-project-identifiers conn patient-identifier true))
  ([conn patient-identifier include-parents?]
   (let [active-projects (set (map :t_episode/project_fk (active-episodes conn patient-identifier)))]
     (if-not include-parents?
       active-projects
       (into active-projects (mapcat #(projects/all-parents-ids conn %) active-projects))))))

(s/def ::on-date #(instance? LocalDate %))
(s/def ::patient-status #{:FULL :PSEUDONYMOUS :STUB :FAKE :DELETED :MERGED})
(s/def ::discharged? boolean?)
(s/fdef patient-ids-in-projects
  :args (s/cat :conn ::db/conn
               :project-ids (s/coll-of pos-int?)
               :opts (s/keys* :opt-un [::on-date ::patient-status ::discharged?])))
(defn patient-ids-in-projects
  "Returns a set of patient identifiers in the projects specified.
  Parameters:
  - conn        : database connectable
  - project-ids : project identifiers
  - on-date     : optional, determine membership on this date, default today
  - patient-status : 'status' of patient.
  - "
  [conn project-ids & {:keys [^LocalDate on-date patient-status discharged?]
                       :or   {on-date        (LocalDate/now)
                              patient-status #{:FULL :PSEUDONYMOUS}
                              discharged?    false}}]
  (into #{} (map :t_patient/patient_identifier)
        (jdbc/plan conn (sql/format {:select-distinct :patient_identifier
                                     :from            :t_patient
                                     :left-join       [:t_episode [:= :patient_fk :t_patient/id]]
                                     :where           [:and
                                                       [:in :project_fk project-ids]
                                                       [:in :t_patient/status (map name patient-status)]
                                                       (when-not discharged?
                                                         [:or
                                                          [:is :t_episode/date_discharge nil]
                                                          [:> :date_discharge on-date]])
                                                       [:or
                                                        [:is :date_registration nil]
                                                        [:< :date_registration on-date]
                                                        [:= :date_registration on-date]]]}))))

(s/fdef pks->identifiers
  :args (s/cat :conn ::db/conn :pks (s/coll-of pos-int?)))
(defn pks->identifiers
  "Turn patient primary keys into identifiers."
  [conn pks]
  (into #{} (map :t_patient/patient_identifier)
        (jdbc/plan conn (sql/format {:select :patient_identifier :from :t_patient :where [:in :id pks]}))))

(defn pk->identifier
  "Turn a single patient primary key into a patient identifier."
  [conn pk]
  (:t_patient/patient_identifier
    (plan/select-one! conn [:t_patient/patient_identifier]
                      (sql/format {:select :patient_identifier :from :t_patient :where [:= :id pk]}))))

(defn patient-identifier->pk
  "Turn a single patient identifier into the primary key."
  [conn patient-identifier]
  (:t_patient/id
    (plan/select-one! conn [:t_patient/id] (sql/format {:select :id :from :t_patient :where [:= :patient_identifier patient-identifier]}))))

(defn encounter-by-id
  [conn encounter-id]
  (db/execute-one!
    conn
    (sql/format {:select [:*] :from :t_encounter :where [:= :id encounter-id]})))

(defn ^:deprecated encounter->users
  "DEPRECATED. Use [[encounter->users#]] instead.
  Returns a sequence of maps :t_encounter_user/userid for each user in the
  encounter. "
  [conn encounter-id]
  (jdbc/execute! conn (sql/format {:select [:userid]
                                   :from   [:t_encounter_user]
                                   :where  [:= :encounterid encounter-id]})))

(defn encounter->users#
  [conn encounter-id]
  (jdbc/execute! conn (sql/format (assoc users/fetch-user-query
                                    :where [:in :t_user/id {:select-distinct :userid
                                                            :from            :t_encounter_user
                                                            :where           [:= :encounterid encounter-id]}]))))

(defn patient->encounters
  [conn patient-pk]
  (db/execute! conn (sql/format {:select   [:*]
                                 :from     [:t_encounter]
                                 :where    [:= :patient_fk patient-pk]
                                 :order-by [[:date_time :desc]]})))

(defn patient->active-encounter-ids
  [conn {:t_patient/keys [id patient_identifier]}]
  (into #{}
        (jdbc/plan conn (sql/format {:select :id :from :t_encounter
                                     :where
                                     [:and
                                      [:!= :is_deleted "true"]
                                      [:= :patient_fk
                                       (or id {:select :t_patient/id :from [:t_patient]
                                               :where  [:= :t_patient/patient_identifier patient_identifier]})]]}))))

(defn encounter-templates-by-ids [conn encounter-template-ids]
  (jdbc/execute! conn (sql/format {:select [:*] :from [:t_encounter_template]
                                   :where  [:in :id (set encounter-template-ids)]})))

(defn encounter-type-by-id [conn encounter-type-id]
  (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_type] :where [:= :id encounter-type-id]})))

(defn paged-encounters-sql
  "Generate SQL for paging through encounters in descending date time order
  for a given patient.
  Parameters:
  - patient-pk : patient's primary key
  - first-id   : (optional) first encounter id to return
  - page-size  : (optional, default 20), number of results per page

  One additional row will be returned than requested. This should be used to
  generate the `first-id` for the next page request."
  [patient-pk {:keys [first-id page-size] :or {page-size 20}}]
  (cond-> {:select   :id :from :t_encounter
           :join     [:t_patient [:= :t_patient.id :patient_fk]]
           :order-by [[:date_time :desc] [:id :desc]]}
    (pos-int? page-size)
    (assoc :limit (inc page-size))
    first-id
    (assoc :where [:>= :id first-id])))

(defn encounter-ids->form-edss
  [conn encounter-ids]
  (db/execute! conn
               (sql/format {:select [:id :encounter_fk :edss_score :user_fk]
                            :from   :t_form_edss
                            :where  [:and [:<> :is_deleted "true"]
                                     [:in :t_form_edss/encounter_fk encounter-ids]]})))
(defn encounter-ids->form-edss-fs
  [conn encounter-ids]
  (db/execute! conn
               (sql/format {:select [:id :encounter_fk :edss_score :user_fk]
                            :from   :t_form_edss_fs
                            :where  [:and [:<> :is_deleted "true"]
                                     [:in :encounter_fk encounter-ids]]})))

(defn encounter-ids->form-ms-relapse
  [conn encounter-ids]
  (db/execute! conn (sql/format {:select    [:t_form_ms_relapse/id
                                             :t_form_ms_relapse/encounter_fk
                                             :t_form_ms_relapse/in_relapse
                                             :t_form_ms_relapse/ms_disease_course_fk
                                             :t_ms_disease_course/name
                                             :t_form_ms_relapse/activity :t_form_ms_relapse/progression]
                                 :from      [:t_form_ms_relapse]
                                 :left-join [:t_ms_disease_course [:= :t_form_ms_relapse/ms_disease_course_fk :t_ms_disease_course/id]]
                                 :where     [:and [:in :t_form_ms_relapse/encounter_fk encounter-ids]
                                             [:<> :t_form_ms_relapse/is_deleted "true"]]})))

(defn encounter-ids->form-weight-height
  [conn encounter-ids]
  (db/execute! conn (sql/format {:select [:t_form_weight_height/id
                                          :t_form_weight_height/encounter_fk
                                          :t_form_weight_height/weight_kilogram
                                          :t_form_weight_height/height_metres]
                                 :from   [:t_form_weight_height]
                                 :where  [:and
                                          [:in :t_form_weight_height/encounter_fk encounter-ids]
                                          [:<> :t_form_weight_height/is_deleted "true"]]})))

(defn diagnosis-active?
  ([diagnosis] (diagnosis-active? diagnosis (LocalDate/now)))
  ([{:t_diagnosis/keys [^LocalDate date_diagnosis ^LocalDate date_to]} ^LocalDate on-date]
   (and (or (nil? date_diagnosis) (.isBefore date_diagnosis on-date) (.isEqual date_diagnosis on-date))
        (or (nil? date_to) (.isAfter date_to on-date)))))

(s/fdef create-diagnosis
  :args (s/cat :conn ::db/conn
               :diagnosis (s/keys :req [:t_patient/patient_identifier
                                        :t_diagnosis/concept_fk :t_diagnosis/status]
                                  :opt [:t_diagnosis/date_onset :t_diagnosis/date_onset_accuracy
                                        :t_diagnosis/date_diagnosis :t_diagnosis/date_diagnosis_accuracy
                                        :t_diagnosis/date_to :t_diagnosis/date_to_accuracy])))
(s/fdef create-diagnosis-sql
  :args (s/cat :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier)])
               :diagnosis (s/keys :req [:t_diagnosis/concept_fk :t_diagnosis/status])))
(defn create-diagnosis-sql
  [{patient-pk :t_patient/id, patient-identifier :t_patient/patient_identifier}
   {:t_diagnosis/keys [concept_fk date_onset date_onset_accuracy date_diagnosis date_diagnosis_accuracy date_to date_to_accuracy status]}]
  (sql/format
    {:insert-into [:t_diagnosis]
     :values      [{:date_onset              date_onset
                    :date_onset_accuracy     date_onset_accuracy
                    :date_diagnosis          date_diagnosis
                    :date_diagnosis_accuracy date_diagnosis_accuracy
                    :date_to                 date_to
                    :date_to_accuracy        date_to_accuracy
                    :status                  (name status)
                    :concept_fk              concept_fk
                    :patient_fk              (or patient-pk {:select :t_patient/id
                                                             :from   [:t_patient]
                                                             :where  [:= :t_patient/patient_identifier patient-identifier]})}]}))

(comment
  (create-diagnosis-sql
    {:t_patient/id 14031}
    {:t_diagnosis/concept_fk 24700007
     :t_diagnosis/status     :ACTIVE
     :t_patient/id           14031})
  (create-diagnosis-sql
    {:t_patient/patient_identifier 14031}
    {:t_diagnosis/concept_fk 24700007
     :t_diagnosis/status     :ACTIVE}))

(defn create-diagnosis! [conn patient diagnosis]
  (db/execute-one! conn
                   (create-diagnosis-sql patient diagnosis)
                   {:return-keys true}))

(defn ^:deprecated create-diagnosis
  "DEPRECATED: Use `create-diagnosis!`"
  [conn diagnosis]
  (db/execute-one! conn
                   (create-diagnosis-sql diagnosis diagnosis)
                   {:return-keys true}))

(defn update-diagnosis
  [conn {:t_diagnosis/keys [id concept_fk date_onset date_onset_accuracy date_diagnosis date_diagnosis_accuracy date_to date_to_accuracy status]}]
  (db/execute-one! conn
                   (sql/format {:update [:t_diagnosis]
                                :set    {:date_onset              date_onset
                                         :date_onset_accuracy     date_onset_accuracy
                                         :date_diagnosis          date_diagnosis
                                         :date_diagnosis_accuracy date_diagnosis_accuracy
                                         :date_to                 date_to
                                         :date_to_accuracy        date_to_accuracy
                                         :status                  (name status)}
                                :where  [:and
                                         [:= :t_diagnosis/concept_fk concept_fk]
                                         [:= :t_diagnosis/id id]]})
                   {:return-keys true}))

(defn diagnoses
  [conn patient-pk]
  (db/execute!
    conn
    (sql/format {:select [:*]
                 :from   [:t_diagnosis]
                 :where  [:and
                          [:= :patient_fk patient-pk] [:!= :status "INACTIVE_IN_ERROR"]]})))

(defn diagnosis-by-id
  [conn diagnosis-id]
  (db/execute-one! conn
                   (sql/format {:select :* :from :t_diagnosis
                                :where  [:= :id diagnosis-id]})))

(s/fdef fetch-medications
  :args (s/cat :conn ::db/conn :patient (s/keys :req [(or :t_patient/patient_identifier :t_patient/id)])))
(defn fetch-medications
  "Return all medication for a patient. Data are returned as a sequence of maps
  each representing a medication record."
  [conn {patient-identifier :t_patient/patient_identifier, patient-pk :t_patient/id :as req}]
  (db/execute! conn (sql/format
                      (cond
                        patient-pk
                        {:select [:t_medication/*]
                         :from   [:t_medication]
                         :where  [:= :patient_fk patient-pk]}
                        patient-identifier
                        {:select [:t_medication/*]
                         :from   [:t_medication]
                         :join   [:t_patient [:= :patient_fk :t_patient/id]]
                         :where  [:= :patient-identifier patient-identifier]}
                        :else
                        (throw (ex-info "Either t_patient/id or t_patient/patient_identifier must be provided" req))))))

(defn fetch-medication-events
  "Returns medication events for the medication records specified.
  Results are guaranteed to match the ordering of the input identifiers."
  [conn medication-ids]
  (let [results (db/execute! conn (sql/format {:select [:*]
                                               :from   :t_medication_event
                                               :where  [:in :medication_fk medication-ids]}))
        index (group-by :t_medication_event/medication_fk results)]
    (reduce (fn [acc id]
              (conj acc (get index id []))) [] medication-ids)))

(s/fdef fetch-medications-and-events
  :args (s/cat :conn ::db/repeatable-read-txn :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier)])))
(defn fetch-medications-and-events
  "Returns a sequence of medications, as well as any medication events nested
  under key :t_medication/events. "
  [txn patient]
  (let [meds (fetch-medications txn patient)
        evts (when (seq meds) (fetch-medication-events txn (map :t_medication/id meds)))]
    (map #(assoc %1 :t_medication/events %2) meds evts)))

(def default-medication-event
  {:t_medication_event/type                       :ADVERSE_EVENT
   :t_medication_event/severity                   nil
   :t_medication_event/sample_obtained_antibodies false
   :t_medication_event/event_concept_fk           nil
   :t_medication_event/action_taken               nil
   :t_medication_event/description_of_reaction    nil
   :t_medication_event/infusion_start_date_time   nil
   :t_medication_event/drug_batch_identifier      nil
   :t_medication_event/premedication              nil
   :t_medication_event/reaction_date_time         nil})

(defn unparse-medication-event
  [medication-id evt]
  (-> (merge default-medication-event evt)
      (select-keys (keys default-medication-event))         ;;only include specified keys
      (assoc :t_medication_event/medication_fk medication-id)
      (update :t_medication_event/type {:INFUSION_REACTION "INFUSION_REACTION" ;; TODO: fix consistency of type in legacy rsdb
                                        :ADVERSE_EVENT     "AdverseEvent"})
      (update :t_medication_event/severity #(when % (name %)))))

(s/fdef upsert-medication!
  :args (s/cat :conn ::db/txn
               :medication (s/keys
                             :req [:t_medication/patient_fk :t_medication/medication_concept_fk]
                             :opt [:t_medication/id :t_medication/events])))
(defn upsert-medication!
  "Insert or update a medication record. "
  [txn {:t_medication/keys [reason_for_stopping id events] :as med}]
  (let [med' (-> (select-keys med [:t_medication/id :t_medication/medication_concept_fk
                                   :t_medication/date_from :t_medication/date_to
                                   :t_medication/reason_for_stopping :t_medication/patient_fk
                                   :t_medication/date_from_accuracy :t_medication/date_to_accuracy
                                   :t_medication/temporary_stop :t_medication/more_information :t_medication/as_required])
                 (update :t_medication/reason_for_stopping #(when % (name %)))
                 (update :t_medication/date_from_accuracy #(when % (name %)))
                 (update :t_medication/date_to_accuracy #(when % (name %))))
        med'' (db/parse-entity
                (if id
                  ;; delete all related events iff we are updating a record
                  (do (next.jdbc.sql/delete! txn :t_medication_event {:medication_fk id})
                      (next.jdbc.sql/update! txn :t_medication med' {:id id} {:return-keys true}))
                  ;; just create a new record
                  (next.jdbc.sql/insert! txn :t_medication med')))
        id' (:t_medication/id med'')
        events' (mapv #(unparse-medication-event id' %) events)]
    (log/debug "upserted medication" med'')
    (log/debug "upserting medication events" events')
    (assoc med'' :t_medication/events
                 (if (seq events')
                   (mapv db/parse-entity (next.jdbc.sql/insert-multi! txn :t_medication_event events' {:return-keys true}))
                   []))))

(defn medication-by-id
  [conn medication-id]
  (db/execute-one! conn (sql/format {:select [:t_medication/*] :from :t_medication :where [:= :id medication-id]})))

(s/fdef delete-medication!                                  ;; TODO: should be in a transaction so if one step fails, all fail
  :args (s/cat :conn ::db/conn :medication (s/keys :req [:t_medication/id])))
(defn delete-medication!
  [conn {:t_medication/keys [id]}]
  (next.jdbc.sql/delete! conn :t_medication_event {:medication_fk id})
  (next.jdbc.sql/delete! conn :t_medication {:id id}))

(defn calculate-medication-daily-dose
  "Calculate total 'equivalent' daily dose in units of grams per day. This is
  an inexact process as units such as mL are not currently converted via dm+d
  reference knowledge to mg equivalent before conversion. This is instead a
  simple trick for charting purposes."
  ;; TODO: contemplate a more 'exact' approach using dm+d reference data
  ;; TODO: support grouping by active drug ingredient
  [{:t_medication/keys [dose units frequency] :as medication}]
  (when (s/valid? (s/keys :req [:t_medication/dose :t_medication/units :t_medication/frequency]) medication)
    (let [unit-factor (get db/medication-unit-conversion-factors units)
          freq-factor (get db/medication-frequency-conversion-factors frequency)]
      (when (and dose unit-factor freq-factor)
        (* dose unit-factor freq-factor)))))

(defn fetch-summary-multiple-sclerosis
  [conn patient-identifier]
  (let [sms (db/execute! conn (sql/format {:select    [:t_summary_multiple_sclerosis/id
                                                       :t_ms_diagnosis/id :t_ms_diagnosis/name
                                                       :t_summary_multiple_sclerosis/patient_fk
                                                       :t_patient/patient_identifier
                                                       :t_summary_multiple_sclerosis/ms_diagnosis_fk]
                                           :from      [:t_summary_multiple_sclerosis]
                                           :join      [:t_patient [:= :patient_fk :t_patient/id]]
                                           :left-join [:t_ms_diagnosis [:= :ms_diagnosis_fk :t_ms_diagnosis/id]]
                                           :where     [:and
                                                       [:= :t_patient/patient_identifier patient-identifier]
                                                       [:= :t_summary_multiple_sclerosis/is_deleted "false"]]
                                           :order-by  [[:t_summary_multiple_sclerosis/date_created :desc]]}))]
    (when (> (count sms) 1)
      (log/error "Found more than one t_summary_multiple_sclerosis for patient" {:patient-identifier patient-identifier :results sms}))
    (first sms)))

(defn all-multiple-sclerosis-diagnoses [conn]
  (db/execute! conn
               (sql/format {:select [:id :name]
                            :from   [:t_ms_diagnosis]})))

(defn all-ms-event-types [conn]
  (db/execute! conn
               (sql/format {:select [:id :abbreviation :name]
                            :from   [:t_ms_event_type]})))
(defn all-ms-disease-courses [conn]
  (db/execute! conn
               (sql/format {:select [:id :name]
                            :from   [:t_ms_disease_course]})))
(def ms-event-types
  "Defines the types of MS event; these are recorded in the database in legacy
  rsdb but this defines this static data with event types that are permitted to
  follow when ordered by date."
  [{:id           1
    :abbreviation "RO"
    :onset        true
    :relapse      true
    :description  "Relapse at disease onset but no fixed disability"
    :followed-by  #{"RU", "RR", "RW", "SW", "POP"}}
   {:id           2
    :abbreviation "RR"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse but no fixed disability"
    :followed-by  #{"POP", "RU", "RR", "RW", "SW"}}
   {:id           3
    :abbreviation "RU"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse but outcome unknown"
    :followed-by  #{"POP", "RU", "RR", "SW"}}
   {:id           4
    :abbreviation "SO"
    :onset        true
    :relapse      true
    :description  "Relapse at disease onset with fixed disability"
    :followed-by  #{"SW", "SU", "SN", "POP"}}
   {:id           5
    :abbreviation "SN"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse but return to previous level of fixed disability"
    :followed-by  #{"POP", "SW", "SU", "SN"}}
   {:id           6
    :abbreviation "SW"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse in patient with increased level of fixed disability"
    :followed-by  #{"POP", "SW", "SU", "SN"}}
   {:id           7
    :abbreviation "SU"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse in patient with stable fixed disability but outcome unknown"
    :followed-by  #{"POP", "SW", "SU", "SN"}}
   {:id           8
    :abbreviation "POR"
    :onset        true
    :relapse      false
    :progressive  true
    :description  "Progressive from disease onset"
    :followed-by  #{"PR"}}
   {:id           9
    :abbreviation "POP"
    :onset        false
    :relapse      false
    :progressive  true
    :description  "Onset of secondary progressive disease"
    :followed-by  #{"PR"}}
   {:id           10
    :abbreviation "PR"
    :onset        false
    :relapse      true
    :progressive  true
    :description  "Relapse superimposed on progressive disease"
    :followed-by  #{"PR"}}
   {:id           11
    :abbreviation "UK"
    :onset        true
    :relapse      true
    :description  "Unknown"
    :followed-by  nil}
   {:id           12
    :abbreviation "RW"
    :onset        false
    :relapse      true
    :description  "Subsequent relapse with fixed disability"
    :followed-by  #{"POP", "SN", "SU", "SW"}}])

(def ms-event-type-by-abbreviation
  (reduce (fn [acc {abbrev :abbreviation, :as event-type}] (assoc acc abbrev event-type)) {} ms-event-types))

(def ms-event-type-at-onset
  (map :abbreviation (filter :onset ms-event-types)))

(def ms-event-followed-by
  (reduce (fn [acc {:keys [abbreviation followed-by]}] (assoc acc abbreviation followed-by)) {} ms-event-types))

(s/fdef ms-event-ordering-errors
  :args (s/cat :events (s/coll-of (s/keys :req [:t_ms_event_type/abbreviation]))))
(defn ms-event-ordering-errors
  "Given an already sorted sequence of events, returns a sequence of errors.
  Each result will include:
  - :error     - error code; one of :first-event or :ordering
  - :events    - the events providing context
  - :expected  - a set of abbreviations representing expected type(s)
  - :actual    - what was actually found"
  [events]
  (let [first-event (first events)
        events' (remove #(= "UK" (:t_ms_event_type/abbreviation %)) events)] ;; remove any 'unknown' events, as they can appear anywhere
    (when (seq events')
      (let [errors (if-not (:onset (ms-event-type-by-abbreviation (:t_ms_event_type/abbreviation first-event)))
                     [{:error    :first-event, :events [first-event],
                       :expected ms-event-type-at-onset :actual (:t_ms_event_type/abbreviation first-event)}]
                     [])]
        (into errors
              (comp (map (fn [[{a' :t_ms_event_type/abbreviation, :as a}
                               {b' :t_ms_event_type/abbreviation, :as b}]]
                           (let [followed-by (get-in ms-event-type-by-abbreviation [a' :followed-by])]
                             (when-not (followed-by b')
                               {:error :ordering, :events [a b], :expected followed-by, :actual b'}))))
                    (remove nil?))
              (partition 2 1 events'))))))

(defn ms-event-ordering-error->en-GB
  [{:keys [error events expected actual]}]
  (when error
    (let [abbrev (get-in events [0 :t_ms_event_type/abbreviation])
          date (some-> (get-in events [0 :t_ms_event/date]) (.format (DateTimeFormatter/ofPattern "d-MMM-YYYY")))
          expected' (map #(str "'" % "'") expected)]
      (case error
        :first-event
        (str "The first event must be one of: " (str/join ", " expected'))
        :ordering
        (str "Event '" abbrev "' (" date ") must be followed by"
             (if (= 1 (count expected'))
               (str ": " (first expected'))
               (str " one of: " (str/join ", " expected)))
             ", not '" actual "'")))))

(comment

  (let [ex1 ["RO" "UK" "RU"]]))

(defn ms-event-is-relapse?
  "Is the given MS event a type of relapse?"
  [{abbrev :t_ms_event_type/abbreviation}]
  (get-in ms-event-type-by-abbreviation [abbrev :relapse]))

(defn ms-event-is-progressive?
  [{abbrev :t_ms_event_type/abbreviation}]
  (get-in ms-event-type-by-abbreviation [abbrev :progressive]))

(defn fetch-ms-events
  "Return the MS events for the given summary multiple sclerosis.
  Each event includes an additional :t_ms_event/is_relapse boolean property
  based on whether the event is a type to be counted as a 'relapse' rather than
  another kind of event, such as onset of progressive disease."
  [conn sms-id]
  (->> (db/execute! conn (sql/format {:select    [:t_ms_event/* :t_ms_event_type/*]
                                      :from      [:t_ms_event]
                                      :left-join [:t_ms_event_type [:= :t_ms_event_type/id :ms_event_type_fk]]
                                      :where     [:= :t_ms_event/summary_multiple_sclerosis_fk sms-id]}))
       (map #(assoc % :t_ms_event/is_relapse (ms-event-is-relapse? %)
                      :t_ms_event/is_progressive (ms-event-is-progressive? %)
                      :t_ms_event/type (select-keys % [:t_ms_event_type/id :t_ms_event_type/name
                                                       :t_ms_event_type/abbreviation])))))


(defn fetch-ms-event
  [conn ms-event-id]
  (if-let [event (db/execute-one! conn (sql/format {:select    [:t_ms_event/* :t_ms_event_type/*]
                                                    :from      [:t_ms_event]
                                                    :left-join [:t_ms_event_type [:= :t_ms_event_type/id :ms_event_type_fk]]
                                                    :where     [:= :t_ms_event/id ms-event-id]}))]
    (assoc event :t_ms_event/is_relapse (ms-event-is-relapse? event)
                 :t_ms_event/is_progressive (ms-event-is-progressive? event)
                 :t_ms_event/type (select-keys event [:t_ms_event_type/id :t_ms_event_type/name
                                                  :t_ms_event_type/abbreviation]))))

(defn patient-identifier-for-ms-event
  "Returns the patient identifier for a given MS event"
  [conn {ms-event-id :t_ms_event/id :as _event}]
  (:t_patient/patient_identifier (db/execute-one! conn (sql/format {:select [:patient_identifier]
                                                                    :from   [:t_patient]
                                                                    :join   [:t_summary_multiple_sclerosis [:= :t_summary_multiple_sclerosis/patient_fk :t_patient/id]
                                                                             :t_ms_event [:= :t_ms_event/summary_multiple_sclerosis_fk :t_summary_multiple_sclerosis/id]]
                                                                    :where  [:= :t_ms_event/id ms-event-id]}))))

(s/fdef delete-ms-event!
  :args (s/cat :conn ::db/conn :event (s/keys :req [:t_ms_event/id])))

(defn delete-ms-event! [conn {ms-event-id :t_ms_event/id}]
  (db/execute-one! conn (sql/format {:delete-from [:t_ms_event]
                                     :where       [:= :t_ms_event/id ms-event-id]})))

(s/fdef save-ms-diagnosis*!
  :args (s/cat :txn ::db/repeatable-read-txn :ms-diagnosis (s/keys :req [:t_ms_diagnosis/id :t_patient/patient_identifier :t_user/id])))
(defn save-ms-diagnosis*!
  [txn {ms-diagnosis-id    :t_ms_diagnosis/id
        patient-identifier :t_patient/patient_identifier
        user-id            :t_user/id
        :as                params}]
  (if-let [sms (fetch-summary-multiple-sclerosis txn patient-identifier)]
    (next.jdbc.sql/update! txn :t_summary_multiple_sclerosis
                           {:ms_diagnosis_fk ms-diagnosis-id
                            :user_fk         user-id}
                           {:id (:t_summary_multiple_sclerosis/id sms)}
                           {:return-keys true})

    (jdbc/execute-one! txn (sql/format
                             {:insert-into [:t_summary_multiple_sclerosis]
                              ;; note as this table uses legacy WO horizontal inheritance, we use t_summary_seq to generate identifiers manually.
                              :values      [{:t_summary_multiple_sclerosis/id                  {:select [[[:nextval "t_summary_seq"]]]}
                                             :t_summary_multiple_sclerosis/written_information ""
                                             :t_summary_multiple_sclerosis/under_active_review "true"
                                             :t_summary_multiple_sclerosis/date_created        (LocalDateTime/now)
                                             :t_summary_multiple_sclerosis/ms_diagnosis_fk     ms-diagnosis-id
                                             :t_summary_multiple_sclerosis/user_fk             user-id
                                             :t_summary_multiple_sclerosis/patient_fk          {:select :t_patient/id
                                                                                                :from   [:t_patient]
                                                                                                :where  [:= :t_patient/patient_identifier patient-identifier]}}]})
                       {:return-keys true})))

(defn save-ms-diagnosis!
  "Save an MS diagnosis and return an updated Summary Multiple Sclerosis with
  required denormalised data as per [[fetch-summary-multiple-sclerosis]]."
  [conn {patient-identifier :t_patient/patient_identifier :as params}]
  (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
    (try
      (save-ms-diagnosis*! txn params)
      (catch Exception e (log/error "failed to save ms diagnosis" (ex-data e))))
    (assoc (fetch-summary-multiple-sclerosis txn patient-identifier)
      :t_patient/patient_identifier patient-identifier)))

(def default-ms-event
  {:t_ms_event/site_arm_motor    false
   :t_ms_event/site_ataxia       false
   :t_ms_event/site_bulbar       false
   :t_ms_event/site_cognitive    false
   :t_ms_event/site_diplopia     false
   :t_ms_event/site_face_motor   false
   :t_ms_event/site_face_sensory false
   :t_ms_event/site_leg_motor    false
   :t_ms_event/site_limb_sensory false
   :t_ms_event/site_optic_nerve  false
   :t_ms_event/site_other        false
   :t_ms_event/site_psychiatric  false
   :t_ms_event/site_sexual       false
   :t_ms_event/site_sphincter    false
   :t_ms_event/site_unknown      false
   :t_ms_event/site_vestibular   false})

(defn save-ms-event! [conn event]
  (if (:t_ms_event/id event)
    (next.jdbc.sql/update! conn
                           :t_ms_event
                           (merge default-ms-event event)
                           {:t_ms_event/id (:t_ms_event/id event)}
                           {:return-keys true})
    (next.jdbc.sql/insert! conn :t_ms_event (merge default-ms-event event))))

(s/fdef save-pseudonymous-patient-lsoa!
  :args (s/cat :txn ::db/repeatable-read-txn
               :patient (s/keys :req [:t_patient/patient_identifier :uk.gov.ons.nhspd/LSOA11])))
(defn save-pseudonymous-patient-lsoa!
  "Special function to store LSOA11 code in place of postal code when working
  with pseudonymous patients. We know LSOA11 represents 1000-1500 members of the
  population. We don't keep an address history, so simply write an address with
  no dates which is the 'current'."
  [txn {patient-identifier :t_patient/patient_identifier, lsoa11 :uk.gov.ons.nhspd/LSOA11, :as params}]
  (when-let [patient (db/execute-one! txn (sql/format {:select [:id :patient_identifier :status] :from :t_patient :where [:= :patient_identifier patient-identifier]}))]
    (if-not (= :PSEUDONYMOUS (:t_patient/status patient))
      (throw (ex-info "Invalid operation: cannot save LSOA for non-pseudonymous patient" params))
      (let [addresses (fetch-patient-addresses txn patient)
            current-address (address-for-date addresses)]
        ;; we currently do not support an address history for pseudonymous patients, so either edit or create
        ;; current address
        (if current-address
          (next.jdbc.sql/update! txn :t_address
                                 {:t_address/date_to                nil
                                  :t_address/date_from              nil
                                  :t_address/address1               lsoa11
                                  :t_address/postcode_raw           nil
                                  :t_address/postcode_fk            nil
                                  :t_address/ignore_invalid_address "true"
                                  :t_address/address2               nil
                                  :t_address/address3               nil
                                  :t_address/address4               nil}
                                 {:t_address/id (:t_address/id current-address)}
                                 {:return-keys true})
          (next.jdbc.sql/insert! txn :t_address {:t_address/address1               lsoa11
                                                 :t_address/ignore_invalid_address "true"
                                                 :t_address/patient_fk             (:t_patient/id patient)}
                                 {:return-keys true}))))))

(s/def ::save-encounter (s/keys :req [:t_encounter/date_time
                                      :t_encounter/patient_fk
                                      :t_encounter/encounter_template_fk]
                                :opt [:t_encounter/id
                                      :t_encounter/episode_fk]))
(defn save-encounter-sql
  [{:t_encounter/keys [id encounter_template_fk patient_fk episode_fk date_time notes]
    :t_patient/keys   [patient_identifier] :as encounter}]
  (when-not (s/valid? ::save-encounter encounter)
    (throw (ex-info "Invalid save encounter" (s/explain-data ::save-encounter encounter))))
  (if id
    {:update [:t_encounter]
     :where  [:= :id id]
     :set    {:encounter_template_fk encounter_template_fk
              :date_time             date_time
              :notes                 notes
              :episode_fk            episode_fk}}
    {:insert-into [:t_encounter]
     :values      [{:date_time             date_time
                    :notes                 notes
                    :encounter_template_fk encounter_template_fk
                    :patient_fk            (or patient_fk {:select :t_patient/id
                                                           :from   [:t_patient]
                                                           :where  [:= :t_patient/patient_identifier patient_identifier]})
                    :episode_fk            episode_fk}]}))

(s/fdef save-encounter!
  :args (s/cat :conn ::db/conn :encounter ::save-encounter))
(defn save-encounter!
  "Save an encounter. If there is no :t_encounter/id then a new encounter will
  be created.
  TODO: set encounter lock time on creation or edit...."
  [conn {encounter-id          :t_encounter/id
         encounter-template-id :t_encounter/encounter_template_fk
         episode-id            :t_encounter/episode_fk
         patient-identifier    :t_patient/patient_identifier
         date-time             :t_encounter/date_time
         :as                   encounter}]
  (when-not (s/valid? ::save-encounter encounter)
    (throw (ex-info "Invalid save encounter" (s/explain-data ::save-encounter encounter))))
  (log/debug "saving encounter" {:encounter_id encounter-id :encounter encounter})
  (db/execute-one! conn (sql/format (save-encounter-sql encounter)) {:return-keys true}))

(defn delete-encounter!
  [conn encounter-id]
  (db/execute-one! conn (sql/format {:update [:t_encounter]
                                     :where  [:= :id encounter-id]
                                     :set    {:t_encounter/is_deleted "true"}})
                   {:return-keys true}))

(defn unlock-encounter!
  [conn encounter-id]
  (db/execute-one! conn (sql/format {:update [:t_encounter]
                                     :where  [:= :id encounter-id]
                                     :set    {:t_encounter/lock_date_time (.plusHours (java.time.LocalDateTime/now) 12)}})
                   {:return-keys true}))

(defn lock-encounter!
  [conn encounter-id]
  (db/execute-one! conn (sql/format {:update [:t_encounter]
                                     :where  [:= :id encounter-id]
                                     :set    {:t_encounter/lock_date_time (java.time.LocalDateTime/now)}})
                   {:return-keys true}))

(s/fdef set-date-death
  :args (s/cat :conn ::db/conn :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier) :t_patient/date_death])))

(defn set-date-death
  [conn {patient-pk :t_patient/id, patient-identifier :t_patient/patient_identifier, date_death :t_patient/date_death :as params}]
  (db/execute-one!
    conn
    (sql/format {:update [:t_patient]
                 :where  (cond
                           patient-pk [:= :id patient-pk]
                           patient-identifier [:= :patient_identifier patient-identifier]
                           :else (throw (ex-info "missing patient pk or identifier" params)))
                 :set    {:date_death date_death}})
    {:return-keys true}))

(s/fdef notify-death!
  :args (s/cat :conn ::db/serializable-txn
               :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier)
                                      :t_patient/date_death]
                                :opt [:t_death_certificate/part1a
                                      :t_death_certificate/part1b
                                      :t_death_certificate/part1c
                                      :t_death_certificate/part2])))
(defn notify-death!
  [txn {patient-pk                :t_patient/id
        patient-identifier        :t_patient/patient_identifier
        date_death                :t_patient/date_death :as patient
        :t_death_certificate/keys [part1a part1b part1c part2]}]
  (let [patient-pk (or patient-pk (patient-identifier->pk txn patient-identifier))
        patient' (assoc patient :t_patient/id patient-pk)
        _ (log/debug "Fetching certificate " patient')
        existing-certificate (fetch-death-certificate txn patient')]
    (cond
      ;; if there's a date of death, and an existing certificate, update both
      (and date_death existing-certificate)
      (do (set-date-death txn patient')
          (assoc patient' :t_patient/death_certificate
                          (jdbc/execute-one! txn (sql/format {:update [:t_death_certificate]
                                                              :where  [:= :id (:t_death_certificate/id existing-certificate)]
                                                              :set    {:t_death_certificate/part1a part1a
                                                                       :t_death_certificate/part1b part1b
                                                                       :t_death_certificate/part1c part1c
                                                                       :t_death_certificate/part2  part2}})
                                             {:return-keys true})))
      ;; patient has died, but no existing certificate
      date_death
      (do (set-date-death txn patient')
          (assoc patient' :t_patient/death_certificate
                          (jdbc/execute-one! txn (sql/format {:insert-into :t_death_certificate
                                                              :values      [{:t_death_certificate/patient_fk patient-pk
                                                                             :t_death_certificate/part1a     part1a
                                                                             :t_death_certificate/part1b     part1b
                                                                             :t_death_certificate/part1c     part1c
                                                                             :t_death_certificate/part2      part2}]})
                                             {:return-keys true})))
      ;; patient has not died, clear date of death and delete death certificate
      :else
      (do (set-date-death txn patient')
          (jdbc/execute-one! txn (sql/format {:delete-from [:t_death_certificate]
                                              :where       [:= :t_death_certificate/patient_fk patient-pk]}))
          (dissoc patient' :t_patient/death_certificate)))))

(comment
  (patient-identifier-for-ms-event conn 7563)
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool com.zaxxer.hikari.HikariDataSource {:dbtype          "postgresql"
                                                                             :dbname          "rsdb"
                                                                             :maximumPoolSize 2}))

  (save-ms-event! conn {:t_ms_event/ms_event_type_fk              1
                        :t_ms_event/date                          (LocalDate/now)
                        :t_ms_event/summary_multiple_sclerosis_fk 4711})

  (next.jdbc/execute-one! conn (sql/format {:select [[[:nextval "t_summary_seq"]]]}))
  (sql/format {:select [[[:nextval "t_summary_seq"]]]})
  (fetch-summary-multiple-sclerosis conn 1)
  (count (fetch-ms-events conn 4708))
  (save-ms-diagnosis! conn {:t_ms_diagnosis/id 12 :t_patient/patient_identifier 3 :t_user/id 1})
  (fetch-episodes conn 15203)

  (fetch-medications-and-events conn {:t_patient/patient_identifier 14032})

  (fetch-patient-addresses conn 124018)
  (active-episodes conn 15203)
  (active-project-identifiers conn 15203)
  (map :t_episode/project_fk (active-episodes conn 15203))
  (projects/all-parents-ids conn 12)
  (projects/all-parents-ids conn 37)
  (projects/all-parents-ids conn 76)
  (projects/all-parents-ids conn 59)
  (save-encounter! conn {:t_encounter/date_time             (LocalDateTime/now)
                         :t_encounter/episode_fk            48224
                         :t_encounter/encounter_template_fk 469})
  (notify-death! conn {:t_patient/patient_identifier 124010
                       :t_patient/date_death         (LocalDate/now)}))
