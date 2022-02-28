(ns com.eldrix.pc4.server.rsdb.results
  "Support for legacy rsdb integration for results."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [com.eldrix.pc4.server.rsdb.forms]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql])
  (:import (java.time LocalDate LocalDateTime)))

(def re-count-lesions
  "Regular expression to match lesion count syntax such as 2 ~2 >2 2+/-1 and 1-3"
  #"(?x) # allow white-space and comments
  (?<exactcount>^(\d+)$) |                                 # exact count            ;; e.g. 2
  (^~(?<approxcount>\d+)$) |                               # approximate count      ;; e.g. ~2
  (^>(?<morethan>\d+)$) |                                  # more than              ;; e.g. >20
  (?<approxrange>^(?<count>\d+)\+\/\-(?<plusminus>\d+)$) | # approximate-range      ;; e.g. 10+/-2
  (?<range>^(?<from>\d+)\-(?<to>\d+)$)                     # range                  ;; e.g. 3-5
  ")

(defn parse-count-lesions
  "Parse lesion count string into a map containing structured data.
  The supported formats are shown in [[re-count-lesions]]. Returns nil if format invalid."
  [s]
  (let [m (re-matcher re-count-lesions (or s ""))]
    (when (.matches m)
      (reduce-kv (fn [m k v] (if v (assoc m k v) m)) {}
                 {:exact-count       (parse-long (or (.group m "exactcount") ""))
                  :approximate-count (parse-long (or (.group m "approxcount") ""))
                  :more-than         (parse-long (or (.group m "morethan") ""))
                  :approximate-range (when (.group m "approxrange") {:count      (parse-long (.group m "count"))
                                                                     :plus-minus (parse-long (.group m "plusminus"))})
                  :range             (when (.group m "range") {:from (parse-long (.group m "from"))
                                                               :to   (parse-long (.group m "to"))})}))))

(def re-change-lesions
  "Regular expression to match 'change in lesion count' syntax such as +2 or -2.
  A plus or minus sign is mandatory in order to be absolutely clear this is reflecting change."
  #"(?<change>^(\+|-)(\d+)$)")

(defn parse-change-lesions
  [s]
  (let [m (re-matcher re-change-lesions (or s ""))]
    (when (.matches m)
      {:change (parse-long (or (.group m "change") ""))})))


(s/fdef -insert-result!
  :args (s/cat :conn ::conn :table keyword? :entity-name string? :result-data map?))
(defn ^:private -insert-result!
  "Inserts a result

  This manages the form id safely, because the legacy WebObjects application
  uses horizontal inheritance so that the identifiers are generated from a
  sequence from 't_result'."
  [conn table entity-name data]
  (log/info "inserting result:" {:entity-name entity-name :data data})
  (db/execute-one! conn (sql/format {:insert-into [table]
                                     ;; note as this table uses legacy WO horizontal inheritance, we use t_result_seq to generate identifiers manually.
                                     :values      [(merge {:id               {:select [[[:nextval "t_result_seq"]]]}
                                                           :data_source_type "MANUAL"
                                                           :date_created     (LocalDateTime/now)
                                                           :is_deleted       "false"
                                                           :result_type_fk   {:select :id :from :t_result_type :where [:= :result_entity_name entity-name]}}
                                                          data)]})
                   {:return-keys true}))

(defn -insert-annotation!
  [conn table data]
  (db/execute-one! conn (sql/format {:insert-into [table]
                                     ;; note as this table uses legacy WO horizontal inheritance, we use t_annotation_seq to generate identifiers manually.
                                     :values      [(assoc data :id {:select [[[:nextval "t_annotation_seq"]]]})]})
                   {:return-keys true}))


(s/fdef -update-result!
  :args (s/cat :conn ::conn :table keyword? :result-id int? :data map?))
(defn -update-result! [conn table result-id data]
  (log/info "updating result" {:table table :data data})
  (db/execute-one! conn (sql/format {:update [table]
                                     :where  [:= :id result-id]
                                     :set    data}) {:return-keys true}))


(s/fdef save-result*
  :args (s/cat :conn ::conn
               :result-type (s/keys :req [::entity-name ::table ::spec])
               :result-data (s/keys :opt-un [::user_fk ::patient_fk])))
(defn- save-result*
  "Save a result.
  - conn               : database connection or pool
  - result-type        : a map containing the following keys:
      - ::entity-name      : the legacy entity name e.g. 'ResultMriBrain'
      - ::table            : namespace for result e.g. :t_result_mri_brain
      - ::spec             : specification for data
  - data               : data for the result, matching the specification for the result, but can include
      - :user_fk           : user_fk to be used if not already namespaced under result
      - :patient_fk        : patient_fk to be used if not already namespaced under result

  If non-namespaced values are supplied for :user_fk and :patient_fk, they will be used to create
  namespaced keys if they don't exist."
  [conn {entity-name ::entity-name table ::table spec ::spec} {:keys [user_fk patient_fk] :as data}]
  (let [id-key (keyword (name table) "id")
        id (get data id-key)
        user-key (keyword (name table) "user_fk")
        patient-key (keyword (name table) "patient_fk")
        data' (cond-> (dissoc data :user_fk :patient_fk)    ;; create user_fk and patient_fk namespaced keys if they don't exist
                      (nil? (get data user-key))
                      (assoc user-key user_fk)
                      (nil? (get data patient-key))
                      (assoc patient-key patient_fk))]
    (when-not (s/valid? spec data')
      (throw (ex-info "Failed to save result; invalid data" (s/explain spec data'))))
    (if id (-update-result! conn table id (dissoc data' id-key))
           (-insert-result! conn table entity-name data'))))

(def annotation-multiple-sclerosis-summary #{"TYPICAL" "ATYPICAL" "NON_SPECIFIC" "ABNORMAL_UNRELATED" "NORMAL"})

(s/def ::conn identity)
(s/def :t_result_mri_brain/multiple_sclerosis_summary annotation-multiple-sclerosis-summary)
(s/def :t_result_mri_brain/total_gad_enhancing_lesions (s/nilable parse-count-lesions))
(s/def :t_result_mri_brain/total_t2_hyperintense (s/nilable parse-count-lesions))
(s/def :t_result_mri_brain/change_t2_hyperintense (s/nilable parse-change-lesions))
(s/def :t_result_mri_brain/enlarging_t2_lesions (s/nilable boolean?))
(s/def :t_result_mri_brain/compare_to_result_mri_brain_fk (s/nilable int?))
(s/def :t_result_mri_brain/id int?)
(s/def :t_result_mri_brain/date #(instance? LocalDate %))
(s/def :t_result_mri_brain/patient_fk int?)
(s/def :t_result_mri_brain/report (s/nilable string?))
(s/def :t_result_mri_brain/user_fk int?)
(s/def :t_result_mri_brain/with_gadolinium boolean?)
(s/def ::t_annotation_mri_brain_multiple_sclerosis (s/keys :req [:t_result_mri_brain/multiple_sclerosis_summary]
                                                           :opt [:t_result_mri_brain/total_gad_enhancing_lesions
                                                                 :t_result_mri_brain/total_t2_hyperintense
                                                                 :t_result_mri_brain/change_t2_hyperintense
                                                                 :t_result_mri_brain/compare_to_result_mri_brain_fk]))

(s/def ::t_result_mri_brain (s/keys :req [:t_result_mri_brain/date
                                          :t_result_mri_brain/patient_fk
                                          :t_result_mri_brain/report
                                          :t_result_mri_brain/user_fk
                                          :t_result_mri_brain/with_gadolinium]
                                    :opt [:t_result_mri_brain/id
                                          :t_result_mri_brain/compare_to_result_mri_brain_fk
                                          :t_result_mri_brain/total_gad_enhancing_lesions
                                          :t_result_mri_brain/total_t2_hyperintense
                                          :t_result_mri_brain/change_t2_hyperintense
                                          :t_result_mri_brain/multiple_sclerosis_summary]))

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

(s/fdef default-fetch-fn
  :args (s/cat :conn ::conn :table keyword? :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier)])))
(defn- default-fetch-fn [conn table {patient-pk :t_patient/id patient-identifier :t_patient/patient_identifier}]
  (db/execute! conn (sql/format {:select    [:*]
                                 :from      [table]
                                 :left-join [:t_result_type [:= :t_result_type/id :result_type_fk]]
                                 :where     [:and
                                             [:= :patient_fk (or patient-pk {:select [:t_patient/id] :from [:t_patient] :where [:= :patient_identifier patient-identifier]})]
                                             [:<> :is_deleted "true"]]})))

(s/fdef normalize-result
  :args (s/cat :result map? :result-type (s/keys :req [::entity-name ::table] :opt [::summary-fn])))
(defn normalize-result
  [result {::keys [entity-name table summary-fn]}]
  (let [table' (name table)
        id-key (keyword table' "id")
        date-key (keyword table' "date")]
    (assoc result
      :t_result_type/result_entity_name entity-name
      :t_result/id (get result id-key)
      :t_result/date (get result date-key)
      :t_result/summary (when summary-fn (summary-fn result)))))

(s/fdef -results-from-table
  :args (s/cat :conn identity
               :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier)])
               :result-type (s/keys :req [::entity-name ::table] :opt [::fetch-fn ::summary-fn])))
(defn ^:private -results-from-table
  [conn patient {::keys [fetch-fn table] :as result-type}]
  (->> (if fetch-fn (fetch-fn conn table patient)
                    (default-fetch-fn conn table patient))
       (map #(normalize-result % result-type))))

(defn normalize-mri-brain
  "Normalises an MRI brain scan record, flattening and renaming any annotations."
  [result]
  (let [has-ms-annotation? (:t_annotation_mri_brain_multiple_sclerosis_new/id result)]
    (reduce-kv (fn [m k v]
                 (cond
                   (= "t_result_mri_brain" (namespace k))
                   (assoc m k v)
                   (= "t_result_type" (namespace k))
                   (assoc m k v)
                   (and (= :t_annotation_mri_brain_multiple_sclerosis_new/id k) v)
                   (assoc m :t_result_mri_brain/annotation_mri_brain_multiple_sclerosis_new_id v)
                   (and has-ms-annotation? (= "t_annotation_mri_brain_multiple_sclerosis_new" (namespace k)))
                   (assoc m (keyword "t_result_mri_brain" (name k)) v)
                   :else m)) {} result)))

(s/fdef fetch-mri-brain-results
  :args (s/cat :conn ::conn :patient (s/keys :req [(or :t_patient/id :t_patient/patient_identifier)])))
(defn- fetch-mri-brain-results
  "Fetch MRI brain scan results for the given patient.
  This carefully fetches annotations for each scan and then determines the 'best' annotation as there may be more than
  one annotation recorded in the legacy system."
  [conn _ {patient-pk :t_patient/id patient-identifier :t_patient/patient_identifier}]
  (->> (db/execute! conn (sql/format {:select    [:t_result_mri_brain/id
                                                  :t_result_mri_brain/date
                                                  :t_result_mri_brain/hospital_fk
                                                  :t_result_mri_brain/is_deleted
                                                  :t_result_mri_brain/with_gadolinium
                                                  :t_result_mri_brain/encounter_fk
                                                  :t_result_mri_brain/data_source_type
                                                  :t_result_mri_brain/user_fk
                                                  :t_result_mri_brain/result_type_fk
                                                  :t_result_mri_brain/data_source_url
                                                  :t_result_mri_brain/patient_fk
                                                  :t_result_mri_brain/report
                                                  :t_result_type/*
                                                  :t_annotation_mri_brain_multiple_sclerosis_new/id
                                                  :t_annotation_mri_brain_multiple_sclerosis_new/date_created
                                                  :t_annotation_mri_brain_multiple_sclerosis_new/change_t2_hyperintense
                                                  :t_annotation_mri_brain_multiple_sclerosis_new/total_t2_hyperintense
                                                  :t_annotation_mri_brain_multiple_sclerosis_new/total_gad_enhancing_lesions
                                                  :t_annotation_mri_brain_multiple_sclerosis_new/compare_to_result_mri_brain_fk
                                                  [:t_annotation_mri_brain_multiple_sclerosis_new/summary :multiple_sclerosis_summary]]
                                      :from      [:t_result_mri_brain]
                                      :left-join [:t_result_type [:= :t_result_type/id :result_type_fk]
                                                  :t_annotation_mri_brain_multiple_sclerosis_new [:= :result_fk :t_result_mri_brain/id]]
                                      :where     [:and
                                                  [:= :patient_fk (or patient-pk {:select [:t_patient/id] :from [:t_patient] :where [:= :patient_identifier patient-identifier]})]
                                                  [:<> :is_deleted "true"]]
                                      :order-by  [[:t_annotation_mri_brain_multiple_sclerosis_new/date_created :desc]]}))
       (group-by :t_result_mri_brain/id)
       vals
       (map first)
       (map normalize-mri-brain)))

(s/fdef -save-mri-brain-ms-annotation!
  :args (s/cat :conn ::conn
               :result (s/keys :req [:t_result_mri_brain/id :t_result_mri_brain/user_fk])
               :annotation ::t_annotation_mri_brain_multiple_sclerosis))
(defn -save-mri-brain-ms-annotation!
  "Save an MRI brain multiple sclerosis annotation.
  - tx - a connection that *must* be within a transaction as multiple operations should either succeed or fail as one. "
  [tx {result-id :t_result_mri_brain/id user-id :t_result_mri_brain/user_fk} annotation]
  (when (and (:t_result_mri_brain/total_t2_hyperintense annotation)
             (:t_result_mri_brain/change_t2_hyperintense annotation))
    (throw (ex-info "Invalid annotation data: cannot specify both absolute and relative T2 counts" annotation)))
  (let [annotation' (-> annotation
                        (dissoc :t_result_mri_brain/multiple_sclerosis_summary)
                        (assoc :user_fk user-id :result_fk result-id
                               :date_created (LocalDateTime/now)
                               :gad_enhancing_lesions "NONE" ;; these are the legacy annotations - should delete
                               :juxta_cortical_lesions "NONE" ;; but they currently have a non-NULL constraint
                               :periventricularlesions "NONE" ;; so this is a temporary workaround
                               :infra_tentorial_lesions "NONE" ;; until full migration
                               :t2_hyperintense_lesions "NONE"
                               :summary (:t_result_mri_brain/multiple_sclerosis_summary annotation)))]
    (log/info "Saving annotation for result " annotation')
    (jdbc/execute-one! tx (sql/format {:delete-from [:t_annotation_mri_brain_multiple_sclerosis_new]
                                       :where       [:= :result_fk result-id]}))
    (-insert-annotation! tx :t_annotation_mri_brain_multiple_sclerosis_new annotation')))

(s/fdef save-mri-brain!
  :args (s/cat :conn ::conn
               :result-type (s/keys :req [::entity-name ::table])
               :data (s/keys :req [(or ::patient_fk :t_result_mri_brain/patient_fk)
                                   (or ::user_fk :t_result_mri_brain/user_fks)])))
(defn- save-mri-brain!
  "Customised save for MRI brain scan to include flattened annotation data."
  [conn result-type data]
  (log/info "saving MRI brain:" data)
  (let [data' (select-keys data [:t_result_mri_brain/id :t_result_mri_brain/report
                                 :t_result_mri_brain/date :t_result_mri_brain/with_gadolinium
                                 :patient_fk :t_result_mri_brain/patient_fk
                                 :user_fk :t_result_mri_brain/user_fk])
        ms-annot (select-keys data [:t_result_mri_brain/change_t2_hyperintense
                                    :t_result_mri_brain/total_t2_hyperintense
                                    :t_result_mri_brain/total_gad_enhancing_lesions
                                    :t_result_mri_brain/compare_to_result_mri_brain_fk
                                    :t_result_mri_brain/multiple_sclerosis_summary])]
    (when-not (or (empty? ms-annot) (s/valid? ::t_annotation_mri_brain_multiple_sclerosis ms-annot))
      (throw (ex-info "Invalid MRI brain annotation data" (s/explain-data ::t_annotation_mri_brain_multiple_sclerosis ms-annot))))
    (next.jdbc/with-transaction [tx conn {:isolation :serializable}]
      (let [result (save-result* tx result-type data')]     ;; this will validate the MRI data
        (when-not result
          (throw (ex-info "failed to save MRI brain result" data')))
        (if (empty? ms-annot)
          (normalize-result result result-type)
          (let [annot-result (-save-mri-brain-ms-annotation! tx result ms-annot)]
            (-> result
                (merge annot-result)
                (normalize-mri-brain)
                (normalize-result result-type))))))))





(def result-types
  "Frustratingly, the legacy system manages types using both entities and a
  runtime 'result_type' identifier. This list of 'magic' identifiers is here
  for currently supported types."
  [{::entity-name "ResultMriBrain"
    ::table       :t_result_mri_brain
    ::spec        ::t_result_mri_brain
    ::summary-fn  :t_result_mri_brain/report
    ::fetch-fn    fetch-mri-brain-results
    ::save-fn     save-mri-brain!}
   {::entity-name "ResultMriSpine"
    ::table       :t_result_mri_spine
    ::spec        ::t_result_mri_spine
    ::summary-fn  :t_result_mri_spine/report}
   {::entity-name "ResultJCVirus"
    ::table       :t_result_jc_virus
    ::spec        ::t_result_jc_virus
    ::summary-fn  :t_result_jc_virus/jc_virus}
   {::entity-name "ResultCsfOcb"
    ::table       :t_result_csf_ocb
    ::spec        ::t_result_csf_ocb
    ::summary-fn  :t_result_csf_ocb/result}
   {::entity-name "ResultRenalProfile"
    ::table       :t_result_renal
    ::spec        ::t_result_renal
    ::summary-fn  :t_result_renal/notes}
   {::entity-name "ResultFullBloodCount"
    ::table       :t_result_full_blood_count
    ::spec        ::t_result_full_blood_count
    ::summary-fn  :t_result_full_blood_count/notes}
   {::entity-name "ResultECG"
    ::table       :t_result_ecg
    ::spec        ::t_result_ecg
    ::summary-fn  :t_result_ecg/notes}
   {::entity-name "ResultUrinalysis"
    ::table       :t_result_urinalysis
    ::spec        ::t_result_urinalysis
    ::summary-fn  :t_result_urinalysis/notes}
   {::entity-name "ResultLiverFunction"
    ::table       :t_result_liver_function
    ::spec        ::t_result_liver_function
    ::summary-fn  :t_result_liver_function/notes}])

(def result-type-by-entity-name
  "Map of entity-name to result-type."
  (zipmap (map ::entity-name result-types) result-types))

(def result-type-by-table
  (zipmap (map ::table result-types) result-types))

(s/fdef save-result!
  :args (s/cat :conn ::conn
               :result (s/keys :req [:t_result_type/result_entity_name])))
(defn save-result!
  "Save an investigation result. Parameters:
  - conn   : database connection, or pool
  - result : data of the result
  The data must include :t_result_type/result_entity_name with the type of result"
  [conn {entity-name :t_result_type/result_entity_name :as result}]
  (let [result-type (or (result-type-by-entity-name entity-name)
                        (throw (ex-info "Failed to save result: unknown result type" result)))
        save-fn (::save-fn result-type)
        table (name (::table result-type))
        result' (reduce-kv (fn [m k v] (let [nspace (namespace k)]
                                         (if (or (nil? nspace) (= nspace table))
                                           (assoc m k v) m))) {} result)]
    (if save-fn
      (save-fn conn result-type result')
      (-> (save-result* conn result-type result')
          (normalize-result result-type)))))

(s/fdef delete-result!
  :args (s/cat :conn ::conn
               :result (s/keys :req [:t_result_type/result_entity_name])))
(defn delete-result! [conn {entity-name :t_result_type/result_entity_name :as result}]
  (if-let [result-type (result-type-by-entity-name entity-name)]
    (db/execute-one! conn (sql/format {:update (::table result-type)
                                       :where  [:= :id (get result (keyword (name (::table result-type)) "id"))]
                                       :set    {:is_deleted "true"}}))
    (throw (ex-info "Failed to delete result: unsupported type" result))))

(defn delete-all-results!!
  "DANGER: Deletes all results for a given patient. Designed for use in automated tests only."
  ([conn patient-identifier]
   (doseq [result-type result-types]
     (delete-all-results!! conn patient-identifier (::table result-type))))
  ([conn patient-identifier table]
   (db/execute-one! conn (sql/format {:delete-from table
                                      :where       [:= :patient_fk {:select :id :from :t_patient :where [:= :patient_identifier patient-identifier]}]}))))



(defn results-for-patient
  "Returns all of the results for a patient by patient-identifier.
  Normalizes results, to include generic result properties to simplify generic processing of all results, and may
  include annotations if supported by that result type."
  [conn patient-identifier]
  (let [patient {:t_patient/patient_identifier patient-identifier}]
    (apply concat (map #(-results-from-table conn patient %) result-types))))

(comment
  (require '[next.jdbc.connection])
  (import 'com.zaxxer.hikari.HikariDataSource)
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 1}))

  (-results-from-table conn {:t_patient/id 100197} (get result-type-by-table :t_result_mri_brain))
  (-results-from-table conn {:t_patient/id 100197} (get result-type-by-table :t_result_renal))
  (default-fetch-fn conn :t_result_renal {:t_patient/id 100197})

  (results-for-patient conn 129409)

  (->> (results-for-patient conn 129409)
       (filter #(= (:t_result/date %) (LocalDate/now))))
  (save-result! conn {:t_result_type/result_entity_name "ResultFullBloodCount"
                      :t_result_full_blood_count/date   (LocalDate/now)
                      :patient_fk                       129409
                      :user_fk                          1}))



