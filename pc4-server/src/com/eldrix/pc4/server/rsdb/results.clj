(ns com.eldrix.pc4.server.rsdb.results
  "Support for legacy rsdb integration for results."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [honey.sql :as sql])
  (:import (java.time LocalDate LocalDateTime)))

(s/def :t_result_mri_brain/id int?)
(s/def :t_result_mri_brain/date #(instance? LocalDate %))
(s/def :t_result_mri_brain/patient_fk int?)
(s/def :t_result_mri_brain/report string?)
(s/def :t_result_mri_brain/user_fk int?)
(s/def :t_result_mri_brain/with_gadolinium boolean?)
(s/def ::t_result_mri_brain (s/keys :req [:t_result_mri_brain/date
                                          :t_result_mri_brain/patient_fk
                                          :t_result_mri_brain/user_fk
                                          :t_result_mri_brain/report
                                          :t_result_mri_brain/with_gadolinium]
                                    :opt [:t_result_mri_brain/id]))
(s/def :t_result_jc_virus/id int?)
(s/def :t_result_jc_virus/date #(instance? LocalDate %))
(s/def :t_result_jc_virus/patient_fk int?)
(s/def :t_result_jc_virtus/user_fk int?)
(s/def :t_result_jc_virus/jc_virus #{"POSITIVE" "NEGATIVE"})
(s/def ::t_result_jc_virus (s/keys :req [:t_result_jc_virus/date
                                         :t_result_jc_virus/patient_fk
                                         :t_result_jc_virus/user_fk
                                         :t_result_jc_virus/jc_virus]
                                   :opt [:t_result_jc_virus/id]))

(s/def :t_result_csf_ocb/id int?)
(s/def :t_result_csf_ocb/date #(instance? LocalDate %))
(s/def :t_result_csf_ocb/patient_fk int?)
(s/def :t_result_csf_ocb/user_fk int?)
(s/def :t_result_csf_ocb/result #{"POSITIVE" "PAIRED" "NEGATIVE" "EQUIVOCAL"})
(s/def ::t_result_csf_ocb (s/keys :req [:t_result_csf_ocb/date
                                        :t_result_csf_ocb/patient_fk
                                        :t_result_csf_ocb/user_fk
                                        :t_result_csf_ocb/result]
                                  :opt [:t_result_csf_ocb/id]))

(def result->types
  "Frustratingly, the legacy system manages types using both entities and a
  runtime 'result_type' identifier. This list of 'magic' identifiers is here
  for currently supported types."
  {:t_result_mri_brain {:t_result_type/id 9
                        ::spec            ::t_result_mri_brain}
   :t_result_jc_virus  {:t_result_type/id 14
                        ::spec            ::t_result_jc_virus}
   :t_result_csf_ocb   {:t_result_type/id 8
                        ::spec            ::t_result_csf_ocb}})

(def supported-types (keys result->types))


(defn result-type
  "Returns the type of the result."
  [result]
  (let [all-keys (map namespace (keys result))
        k1 (first all-keys)
        all-same? (every? #(= k1 %) all-keys)]
    (when all-same?
      (keyword k1))))

(defn valid?
  [result]
  (let [rtype (result-type result)
        spec (when rtype (get-in result->types [rtype ::spec]))]
    (when spec (s/valid? spec result))))

(defn explain-data [result]
  (let [rtype (result-type result)
        spec (when rtype (get-in result->types [rtype ::spec]))]
    (if spec
      (s/explain-data spec result)
      {:message "Invalid result data: unsupported type"
       :result  result})))

(defn ^:private -insert-result!
  "Inserts a result

  This manages the form id safely, because the legacy WebObjects application
  uses horizontal inheritance so that the identifiers are generated from a
  sequence from 't_result'."
  [conn table data]
  (let [result-type-id (:t_result_type/id (result->types table))]
    (when-not result-type-id (throw (ex-info "Unsupported result type" {:table           table
                                                                        :supported-types result->types})))
    (db/execute-one! conn (sql/format {:insert-into [table]
                                       ;; note as this table uses legacy WO horizontal inheritance, we use t_result_seq to generate identifiers manually.
                                       :values      [(merge {:id               {:select [[[:nextval "t_result_seq"]]]}
                                                             :data_source_type "MANUAL"
                                                             :date_created     (LocalDateTime/now)
                                                             :is_deleted       "false"
                                                             :result_type_fk   result-type-id}
                                                            data)]})
                     {:return-keys true})))

(defn -update-result! [conn table id-key data]
  (db/execute-one! conn (sql/format {:update [table]
                                     :where  [:= :id (id-key data)]
                                     :set    (dissoc data id-key)}) {:return-keys true}))

(defn save-result! [conn data]
  (let [rtype (result-type data)
        valid? (valid? data)]
    (when-not valid?
      (throw (ex-info "Failed to save result; invalid data" (explain-data data))))
    (let [id-key (keyword (name rtype) "id")
          id (get data id-key)]
      (if id
        (-update-result! conn rtype id-key data)
        (-insert-result! conn rtype data)))))

(defn delete-result [conn result]
  (if-let [rtype (result-type result)]
    (db/execute-one! conn (sql/format {:update [rtype]
                                       :where  [:= :id (get result (keyword (name rtype) "id"))]
                                       :set    {:is_deleted "true"}}))
    (throw (ex-info "Failed to delete result: unsupported type" result))))

(comment
  (require '[next.jdbc.connection])
  (import 'com.zaxxer.hikari.HikariDataSource)
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 1}))
  (def example-result {:t_result_mri_brain/date            (LocalDate/now)
                       :t_result_mri_brain/patient_fk      1
                       :t_result_mri_brain/user_fk         1
                       :t_result_mri_brain/with_gadolinium false
                       :t_result_mri_brain/report          "Innumerable lesions"})
  (save-result! conn example-result)
  (save-result! conn (assoc example-result :t_result_mri_brain/id 108823 :t_result_mri_brain/report "Sausages, lots of sausages"))
  (delete-result conn (assoc example-result :t_result_mri_brain/id 108822))
  )