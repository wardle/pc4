(ns pc4.rsdb.nform.impl.nf-store
  "NewForm persistence store backed by a single database table and a 'jsonb'
  data column for form-specific data. All forms have a 'form_type'
  which is a keyword with the structure <name>/<version>. Versioning is
  mandatory and this permits functionality such as running aggregate queries
  across current and older versions, or automated migration of forms from an
  older storage mechanism.
  Each form has a set of 'core' properties:
  - id                  : a UUID
  - form_type           : keyword of the form <name>/<version> e.g. :edss/v1
  - created             : timestamp when form created
  - is_deleted          : whether form deleted
  - encounter_fk        : encounter id of parent encounter
  - patient_fk          : patient id
  - user_fk             : user id of responsible user

  The form specific data is stored in a 'data' field, but properties aree
  flattened automatically to avoid excessive nesting."
  (:require
    [charred.api :as json]
    [clojure.spec.alpha :as s]
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]
    [pc4.rsdb.nform.impl.protocols :as p])
  (:import (java.time LocalDateTime)))

(defn read-json [x]
  (json/read-json x {:key-fn keyword}))
(def write-json
  json/write-json-str)

(defn encode-form-type
  "Encode form type into string needed for database row"
  [k]
  (str (namespace k) "/" (name k)))

(def jdbc-opts {:builder-fn rs/as-unqualified-maps, :return-keys true})

(defn upsert-sql
  "SQL to 'upsert' the given form."
  [{:keys [id form_type created is_deleted patient_fk encounter_fk user_fk] :as form}]
  ["INSERT INTO t_nform (id, form_type, created, is_deleted, patient_fk, encounter_fk, user_fk, data)
    VALUES (?, ?, ?, ?, ?, ?, ?, ? ::jsonb)
    ON CONFLICT (id)
    DO UPDATE set user_fk = EXCLUDED.user_fk,
               is_deleted = EXCLUDED.is_deleted,
               data       = EXCLUDED.data
    RETURNING id, form_type, created, is_deleted, patient_fk, encounter_fk, user_fk, data::text"
   (or id (random-uuid))
   (encode-form-type form_type)
   (or created (LocalDateTime/now))
   is_deleted
   patient_fk
   encounter_fk
   user_fk
   (write-json (dissoc form :id :form_type :patient_fk :date_time :encounter_fk :created :is_deleted :user_fk))])

(defn fetch-sql*
  [{:keys [id form-type form-types patient-pk is-deleted encounter-id encounter-ids select]}]
  (cond-> (-> (h/select :t_nform/id :t_nform/created :t_nform/encounter_fk :t_nform/patient_fk
                        :t_nform/is_deleted :t_nform/user_fk :t_nform/form_type [[:raw "data::text"]])
              (h/from :t_nform))
    id
    (h/where := :t_nform/id id)
    form-type
    (h/where := :form_type (encode-form-type form-type))
    form-types
    (h/where :in :form_type (map encode-form-type form-types))
    patient-pk
    (h/where := :t_nform/patient_fk patient-pk)
    (:date-time select)
    (h/select :t_encounter/date_time)
    (some? is-deleted)
    (h/where [(if is-deleted :or :and)
              [:= :t_nform/is_deleted is-deleted]
              [:= :t_encounter/is_deleted (str is-deleted)]])
    (or (some? is-deleted) (:date-time select))
    (h/left-join :t_encounter [:= :encounter_fk :t_encounter/id])
    encounter-id
    (h/where := :encounter_fk encounter-id)
    encounter-ids
    (h/where :in :encounter_fk encounter-ids)))

(s/fdef fetch-sql
  :args (s/cat :params ::p/fetch-params))
(defn fetch-sql
  "Return SQL to fetch form(s) meeting the criteria specified."
  [params]
  (sql/format (fetch-sql* params)))

(defn parse-row
  "Parse form row data from the database, turning the JSON into Clojure data.
  This basically unnests the form-specific data and merges with the generic
  form data."
  [{:keys [data] :as form}]
  (cond-> (-> form
              (update :form_type keyword)
              (dissoc :data))
    data
    (merge (read-json data))))

(deftype NFFormStore [ds]
  p/FormStore
  (upsert [_ form]
    (parse-row (jdbc/execute-one! ds (upsert-sql form) jdbc-opts)))
  (form [_ id]
    (parse-row (jdbc/execute-one! ds (fetch-sql {:id id}) jdbc-opts)))
  (forms [_ params]
    (map parse-row (jdbc/execute! ds (fetch-sql params) jdbc-opts))))

(comment
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (def st (->NFFormStore conn))
  (p/form st #uuid "281b4ee6-9cfe-4e9e-84e3-52ba172ae46f")
  (p/forms st {:encounter-id 17420 :is-deleted false :select #{:date-time}}))