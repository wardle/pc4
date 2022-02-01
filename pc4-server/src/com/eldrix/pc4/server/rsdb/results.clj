(ns com.eldrix.pc4.server.rsdb.results
  "Support for legacy rsdb integration for results."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [com.eldrix.pc4.server.rsdb.forms]
            [honey.sql :as sql])
  (:import (java.time LocalDate LocalDateTime)))

(s/def :t_result_mri_brain/id int?)
(s/def :t_result_mri_brain/date #(instance? LocalDate %))
(s/def :t_result_mri_brain/patient_fk int?)
(s/def :t_result_mri_brain/report (s/nilable string?))
(s/def :t_result_mri_brain/user_fk int?)
(s/def :t_result_mri_brain/with_gadolinium boolean?)
(s/def ::t_result_mri_brain (s/keys :req [:t_result_mri_brain/date
                                          :t_result_mri_brain/patient_fk
                                          :t_result_mri_brain/report
                                          :t_result_mri_brain/user_fk
                                          :t_result_mri_brain/with_gadolinium]
                                    :opt [:t_result_mri_brain/id]))

(s/def :t_result_mri_spine/id int?)
(s/def :t_result_mri_spine/date #(instance? LocalDate %))
(s/def :t_result_mri_spine/patient_fk int?)
(s/def :t_result_mri_spine/report (s/nilable string?))
(s/def :t_result_mri_spine/type #{"CERVICAL_AND_THORACIC" "CERVICAL" "LUMBOSACRAL" "WHOLE_SPINE" "THORACIC"})
(s/def :t_result_mri_spine/user_fk int?)
(s/def ::t_result_mri_spine (s/keys :req [:t_result_mri_spine/date
                                          :t_result_mri_spine/patient_fk
                                          :t_result_mri_spine/report
                                          :t_result_mri_spine/type
                                          :t_result_mri_spine/user_fk]
                                    :opt [:t_result_mri_spine/id]))

(s/def :t_result_jc_virus/id int?)
(s/def :t_result_jc_virus/date #(instance? LocalDate %))
(s/def :t_result_jc_virus/patient_fk int?)
(s/def :t_result_jc_virus/user_fk int?)
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

(s/def :t_result_renal/id int?)
(s/def :t_result_renal/date #(instance? LocalDate %))
(s/def :t_result_renal/patient_fk int?)
(s/def :t_result_renal/user_fk int?)
(s/def ::t_result_renal (s/keys :req [:t_result_renal/date
                                      :t_result_renal/patient_fk
                                      :t_result_renal/user_fk]
                                :opt [:t_result_renal/id]))
(s/def :t_result_full_blood_count/id int?)
(s/def :t_result_full_blood_count/date #(instance? LocalDate %))
(s/def :t_result_full_blood_count/patient_fk int?)
(s/def :t_result_full_blood_count/user_fk int?)
(s/def ::t_result_full_blood_count (s/keys :req [:t_result_full_blood_count/date
                                                 :t_result_full_blood_count/patient_fk
                                                 :t_result_full_blood_count/user_fk]
                                           :opt [:t_result_full_blood_count/id]))

(s/def :t_result_ecg/id int?)
(s/def :t_result_ecg/date #(instance? LocalDate %))
(s/def :t_result_ecg/patient_fk int?)
(s/def :t_result_ecg/user_fk int?)
(s/def ::t_result_ecg (s/keys :req [:t_result_ecg/date
                                    :t_result_ecg/patient_fk
                                    :t_result_ecg/user_fk]
                              :opt [:t_result_ecg/id]))

(s/def :t_result_urinalysis/id int?)
(s/def :t_result_urinalysis/date #(instance? LocalDate %))
(s/def :t_result_urinalysis/patient_fk int?)
(s/def :t_result_urinalysis/user_fk int?)
(s/def ::t_result_urinalysis (s/keys :req [:t_result_urinalysis/date
                                           :t_result_urinalysis/patient_fk
                                           :t_result_urinalysis/user_fk]
                                     :opt [:t_result_urinalysis/id]))

(s/def :t_result_liver_function/id int?)
(s/def :t_result_liver_function/date #(instance? LocalDate %))
(s/def :t_result_liver_function/patient_fk int?)
(s/def :t_result_liver_function/user_fk int?)
(s/def ::t_result_liver_function (s/keys :req [:t_result_liver_function/date
                                               :t_result_liver_function/patient_fk
                                               :t_result_liver_function/user_fk]
                                         :opt [:t_result_liver_function/id]))



(def result->types
  "Frustratingly, the legacy system manages types using both entities and a
  runtime 'result_type' identifier. This list of 'magic' identifiers is here
  for currently supported types."
  {:t_result_mri_brain        {:t_result_type/id 9
                               ::spec            ::t_result_mri_brain
                               ::summary         :t_result_mri_brain/report}
   :t_result_mri_spine        {:t_result_type/id 10
                               ::spec            ::t_result_mri_spine
                               ::summary         :t_result_mri_spine/report}
   :t_result_jc_virus         {:t_result_type/id 14
                               ::spec            ::t_result_jc_virus
                               ::summary         :t_result_jc_virus/jc_virus}
   :t_result_csf_ocb          {:t_result_type/id 8
                               ::spec            ::t_result_csf_ocb
                               ::summary         :t_result_csf_ocb/result}
   :t_result_renal            {:t_result_type/id 23
                               ::spec            ::t_result_renal
                               ::summary         :t_result_renal/notes}
   :t_result_full_blood_count {:t_result_type/id 24
                               ::spec            ::t_result_full_blood_count
                               ::summary         :t_result_full_blood_count/notes}
   :t_result_ecg              {:t_result_type/id 25
                               ::spec            ::t_result_ecg
                               ::summary         :t_result_ecg/notes}
   :t_result_urinalysis       {:t_result_type/id 26
                               ::spec            ::t_result_urinalysis
                               ::summary         :t_result_urinalysis/notes}
   :t_result_liver_function   {:t_result_type/id 27
                               ::spec            ::t_result_liver_function
                               ::summary         :t_result_liver_function/notes}})

(def supported-types (keys result->types))
(def lookup-by-id (zipmap (map :t_result_type/id (vals result->types)) (keys result->types)))

(defn result-type-by-id [data]
  (get lookup-by-id (:t_result_type/id data)))

(defn result-type-by-keys
  "Returns the type of the result by looking at the keys."
  [result]
  (let [all-keys (map namespace (keys result))
        k1 (first all-keys)
        all-same? (every? #(= k1 %) all-keys)]
    (when all-same?
      (keyword k1))))

(defn result-type [result]
  (or (result-type-by-id result) (result-type-by-keys result)))

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

(defn make-summary [rtype result]
  (when-let [summary-key (::summary (get result->types rtype))]
    (summary-key result)))

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

(defn -update-result! [conn table data]
  (log/info "updating result" {:table table :data data})
  (db/execute-one! conn (sql/format {:update [table]
                                     :where  [:= :id (get data "id")]
                                     :set    (dissoc data :id)}) {:return-keys true}))

(defn save-result! [conn data]
  (let [rtype (result-type data)]
    (when-not rtype
      (throw (ex-info "Failed to save result; unable to determine result type" data)))
    (let [id-key (keyword (name rtype) "id")
          id (get data id-key)
          user-key (keyword (name rtype) "user_fk")
          patient-key (keyword (name rtype) "patient_fk")
          data' (-> data
                    (assoc user-key (:user_fk data)
                           patient-key (:patient_fk data))
                    (dissoc :user_fk :patient_fk))]
      (when-not (valid? data')
        (throw (ex-info "Failed to save result; invalid data" (explain-data data'))))
      (let [data'' (com.eldrix.pc4.server.rsdb.forms/select-keys-by-namespace data' rtype)]
        (if id
          (when-not (-update-result! conn rtype data'')
            (throw (ex-info "failed to update result" data'')))
          (-insert-result! conn rtype data''))))))

(defn delete-result! [conn result]
  (if-let [rtype (result-type result)]
    (db/execute-one! conn (sql/format {:update [rtype]
                                       :where  [:= :id (get result (keyword (name rtype) "id"))]
                                       :set    {:is_deleted "true"}}))
    (throw (ex-info "Failed to delete result: unsupported type" result))))

(defn ^:private -results-from-table
  [conn patient-identifier table]
  (let [id-key (keyword (name table) "id")
        date-key (keyword (name table) "date")]
    (->> (db/execute! conn (sql/format {:select    [:*]
                                        :from      [table]
                                        :left-join [:t_result_type [:= :t_result_type/id :result_type_fk]]
                                        :where     [:and
                                                    [:= :patient_fk {:select [:t_patient/id] :from [:t_patient] :where [:= :patient_identifier patient-identifier]}]
                                                    [:<> :is_deleted "true"]]}))
         (map #(assoc %
                 :t_result/id (get % id-key)
                 :t_result/date (get % date-key)
                 :t_result/summary (make-summary table %))))))

(defn results-for-patient
  "Returns all of the results for a patient by patient-identifier."
  [conn patient-identifier]
  (apply concat (map #(-results-from-table conn patient-identifier %) supported-types)))

(comment
  (require '[next.jdbc.connection])
  (import 'com.zaxxer.hikari.HikariDataSource)
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 1}))
  (def example-result {:t_result_mri_brain/date            (LocalDate/now)
                       :t_result_mri_brain/patient_fk      124010
                       :t_result_mri_brain/user_fk         1
                       :t_result_mri_brain/with_gadolinium false
                       :t_result_mri_brain/report          "Innumerable lesions"})
  (save-result! conn example-result)
  (save-result! conn (assoc example-result :t_result_mri_brain/id 108823 :t_result_mri_brain/report "Sausages, lots of sausages"))
  (delete-result! conn (assoc example-result :t_result_mri_brain/id 108822))
  (results-for-patient conn 13929)


  (def example2 {:t_result_mri_brain/encounter_fk                                nil,
                 :t_result_type/name                                             "MRI brain",
                 :t_result_type/description                                      nil,
                 :t_result_mri_brain/with_gadolinium                             false,
                 :t_result_type/id                                               9,
                 :t_result_mri_brain/date                                        (LocalDate/of 2021 11 28)
                 :t_result_mri_brain/annotation_mri_brain_multiple_sclerosis_fk  nil,
                 :t_result/id                                                    108824,
                 :t_result_mri_brain/data_source_type                            "MANUAL",
                 :t_result_mri_brain/hospital_fk                                 nil, :t_patient/patient_identifier 124010,
                 :t_result_mri_brain/id                                          108824,
                 :t_result_mri_brain/is_deleted                                  "false",
                 :t_result_mri_brain/user_fk                                     1,
                 :t_result_mri_brain/date_created                                (LocalDateTime/now)
                 :t_result_mri_brain/result_type_fk                              9, :t_result_type/further_reading nil,
                 :user_fk                                                        1,
                 :t_result_mri_brain/patient_fk                                  124010,
                 :t_result/summary                                               "Innumerable lesions",
                 :t_result_type/result_entity_name                               "ResultMriBrain",
                 :t_result/date                                                  (LocalDate/of 2021 11 28)
                 :patient_fk                                                     124010,
                 :t_result_mri_brain/data_source_url                             nil,
                 :t_result_mri_brain/annotation_mribrain_multiple_sclerosis_2_fk nil,
                 :t_result_mri_brain/report                                      "Innumerable lesions typical for MS",
                 :t_result_mri_brain/annotation_mri_brain_ataxia_fk              nil})
  (save-result! conn example2)
  (result-type-by-id example2)
  (:t_result_type/id
    example2)
  (valid? example2)
  (explain-data example2)
  lookup-by-id
  )