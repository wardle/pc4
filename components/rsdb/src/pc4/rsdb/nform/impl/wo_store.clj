(ns pc4.rsdb.nform.impl.wo-store
  "Support for legacy forms from the Java WebObjects application. This is
  complicated by the use of horizontal inheritance in the legacy application
  which means primary key generation occurs in the abstract parent table, and
  that there is a database table per form. The database model has a set of core
  common columns:

    Column    |            Type             | Collation | Nullable |               Default
--------------+-----------------------------+-----------+----------+--------------------------------------
 date_created | timestamp without time zone |           | not null |
 encounter_fk | integer                     |           | not null |
 id           | integer                     |           | not null | nextval('t_form_edss_seq'::regclass)
 user_fk      | integer                     |           | not null |
 is_deleted   | character varying(5)        |           | not null | 'false'::character varying

 The WO storage engine uses additional columns for form specific data rather
 than a generic JSON container like NFStore.

 Note than 'is_deleted' is encoded using varchar rather than a boolean type.

 WO horizontal inheritance does not rely on the per-table sequences for id
 generation but instead uses a sequence from the abstract parent table."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs]
            [pc4.log.interface :as log]
            [pc4.rsdb.nform.impl.form :as form]
            [pc4.rsdb.nform.impl.registry :as registry]
            [pc4.rsdb.nform.impl.protocols :as p])
  (:import (java.time LocalDateTime)))

(def jdbc-opts {:builder-fn rs/as-unqualified-maps, :return-keys true})

(s/def ::conn :next.jdbc.specs/connectable)
(s/def ::pk pos-int?)
(s/def ::id (s/tuple ::registry/table ::pk))

(defn row->form
  "Turn a database row from a WebObjects 'form' table into a more modern 'form'
  representation."
  [{form-type :id, table :table, :as fd} {:keys [id date_created] :as row}]
  (when row
    (-> row
        (assoc :form_type form-type
               :id [table id]
               :created date_created)
        (dissoc :date_created)
        (update :is_deleted parse-boolean))))

(defn form->row
  "Turn a more modern 'form' representation into a WebObjects 'form' table row.
  Unwraps a form id that is a tuple of table name and primary key into a simple
  primary key, when it exists."
  [{:keys [id created] :as form}]
  (when form
    (let [[table pk] id]
      (cond-> (-> form
                  (dissoc :id :form_type :created :patient_fk :summary :date_time)
                  (assoc :date_created (or created (LocalDateTime/now)))
                  (update :is_deleted (fnil str false)))
        pk (assoc :id pk)))))

(defn fetch-form-sql
  "Returns a vector representing a SQL statement and its parameters to fetch a
  form from the table specified."
  [table pk select]
  (let [table-name (name table)]
    (sql/format
      (cond-> {:select    [(keyword table-name "*") :t_encounter/patient_fk]
               :from      table
               :left-join [:t_encounter [:= :t_encounter/id :encounter_fk]]
               :where     [:= (keyword table-name "id") pk]}
        (:date-time select)
        (h/select :t_encounter/date_time)))))

(defn xf-filter-form-definition
  [{:keys [id form-type form-types]}]
  (cond
    id
    (if (s/valid? ::id id)
      (let [[tbl _] id]
        (filter (fn [{:keys [table]}] (= table tbl))))
      (throw (ex-info (str "invalid wo form id: '" id "'") (s/explain-data ::id id))))
    form-type
    (filter (fn [{:keys [id]}] (= form-type id)))
    form-types
    (let [types (set form-types)]
      (filter (fn [{:keys [id]}] (types id))))
    :else
    (filter (constantly true))))

(def xf-wo-form-definitions
  (filter (fn [{:keys [store]}] (= store :wo))))

(defn tables-for-fetch
  "Given a set of fetch parameters, returns the tables to be queried. Returns
  all forms that use :wo as storage mechanism unless a form-type or form-types
  explicitly set."
  [params]
  (into []
        (comp xf-wo-form-definitions (xf-filter-form-definition params) (map :table))
        registry/all-form-definitions))

(defn make-where-clauses
  [table {:keys [id encounter-id encounter-ids is-deleted]}]
  (cond-> {}
    id
    (h/where := :id (second id))
    encounter-id
    (h/where := :encounter_fk encounter-id)
    encounter-ids
    (h/where :in :encounter_fk encounter-ids)
    (some? is-deleted)
    (h/where [(if is-deleted :or :and)
              [:= (keyword (name table) "is_deleted") (str is-deleted)]
              [:= :t_encounter/is_deleted (str is-deleted)]])))

(defn fetch-forms-sql
  "Generate a sequence of maps of :table and :sql-params to fetch against the specified criteria.
  NOTE: implementation of 'patient-pk' cannot occur here and instead users of
  this function must turn a patient-pk into a sequence of encounter-ids."
  [{:keys [id form-type form-types is-deleted encounter-id encounter-ids select] :as params}]
  (let [tables (tables-for-fetch params)]
    (map (fn [table]
           {:table      table
            :sql-params (sql/format
                          (cond-> (-> (make-where-clauses table params)
                                      (h/select (keyword (name table) "*") :t_encounter/patient_fk )
                                      (h/from table :t_encounter)
                                      (h/where := :encounter_fk :t_encounter/id))
                            (:date-time select)
                            (h/select :t_encounter/date_time)))})
         tables)))

(comment
  (fetch-forms-sql {:id [:t_form_edss 123]})
  (fetch-forms-sql {:encounter-ids [1 2 3]})
  (fetch-forms-sql {:form-types [:edss/v1 :relapse/v1] :encounter-ids [1 2 3]})
  (fetch-forms-sql {:patient-pk 14032 :is-deleted false}))

(defn encounter-ids-for-patient-pk-sql
  [patient-pk is-deleted]
  (sql/format
    (cond-> (-> (h/select :t_encounter/id)
                (h/from :t_encounter)
                (h/where := :patient_fk patient-pk))
      (some? is-deleted)
      (h/where := :is_deleted (str is-deleted)))))

(defn with-encounters-ids-for-patient-pk
  "Given 'fetch-params', "
  [conn {:keys [patient-pk encounter-ids is_deleted] :as params}]
  (if patient-pk
    (let [sql-params (encounter-ids-for-patient-pk-sql patient-pk is_deleted)
          encounter-ids' (into #{} (map :id) (jdbc/plan conn sql-params))]
      (-> params
          (dissoc :patient-pk)
          (assoc :encounter-ids (into encounter-ids' encounter-ids))))
    params))

(comment
  (encounter-ids-for-patient-pk-sql 14302 false))

(defn fetch-form
  [conn table pk]
  (let [form-definition (registry/form-definition-by-table table)]
    (row->form form-definition (jdbc/execute-one! conn (fetch-form-sql table pk {}) jdbc-opts))))

(defn insert-sql
  ([{:keys [form_type] :as form}]
   (insert-sql (registry/form-definition-by-form-type form_type) form))
  ([{:keys [table]} form]
   (let [form' (form->row form)]
     (sql/format
       {:insert-into table
        :values      [(assoc form' :id {:select [[[:nextval "t_form_seq"]]]})]}))))

(comment
  (insert-sql (registry/form-definition-by-table :t_form_edss) {:form_type :edss/v1 :edss "1.0"})
  (insert-sql {:form_type :edss/v1 :edss "1.0"})
  (insert-sql {:form_type :relapse/v1, :in_relapse true}))

(defn insert! [conn {:keys [form_type patient_fk] :as form}]
  (if-let [{:keys [table] :as fd} (registry/form-definition-by-form-type form_type)]
    (-> (row->form fd (jdbc/execute-one! conn (insert-sql fd form) jdbc-opts))
        (assoc :patient_fk patient_fk))
    (throw (ex-info (str "unknown form type:'" form_type "'") form))))

(defn update-sql
  ([{:keys [form_type] :as form}]
   (update-sql (registry/form-definition-by-form-type form_type) form))
  ([{:keys [table]} form]
   (let [form' (form->row form)
         key-map (dissoc form' :id :encounter_fk :patient_fk :date_created)
         where (select-keys form' [:id :encounter_fk])]
     (next.jdbc.sql.builder/for-update table key-map where jdbc-opts))))

(defn update! [conn {:keys [form_type patient_fk] :as form}]
  (if-let [{:keys [table] :as fd} (registry/form-definition-by-form-type form_type)]
    (do
      (-> (row->form fd (jdbc/execute-one! conn (update-sql fd form) jdbc-opts))
          (assoc :patient_fk patient_fk)))
    (throw (ex-info (str "unknown form type:'" form_type "'") form))))

(deftype WOFormStore
  [conn]
  p/FormStore
  (upsert [_ {:keys [id] :as form}]
    (if id (update! conn form) (insert! conn form)))
  (form [_ id]
    (when (s/valid? ::id id)
      (let [[table pk] id]
        (fetch-form conn table pk))))
  (forms [_ params]
    (let [params' (with-encounters-ids-for-patient-pk conn params)
          queries (fetch-forms-sql params')]
      (mapcat (fn [{:keys [table sql-params]}]
                (println "query " sql-params)
                (map #(row->form (registry/form-definition-by-table table) %)
                     (jdbc/execute! conn sql-params jdbc-opts))) queries))))

(comment
  (require '[next.jdbc.date-time])
  (next.jdbc.date-time/read-as-local)
  (def ds {:dbtype "postgresql" :dbname "rsdb"})
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (update-sql {:form_type :edss/v1 :edss "1.0" :id [:t_form_edss 32] :patient_fk 14032 :encounter_fk 1 :user_fk 1 :created (LocalDateTime/now) :is_deleted false})
  (update-sql {:form_type :relapse/v1 :in_relapse false :ms_disease_course :relapsing-remitting :id [:t_form_ms_relapse 23]})
  (require '[clojure.spec.gen.alpha :as gen])
  (insert-sql (gen/generate (form/gen-form {:mode :insert :using {:form_type :alsfrs-r/v1}})))
  (def saved (insert! ds (form/dehydrate (gen/generate (form/gen-form {:mode :insert :using {:form_type :alsfrs-r/v1}})))))
  saved
  (def st (->WOFormStore ds))
  (p/forms st {:encounter-id 26162}))