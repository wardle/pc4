(ns com.eldrix.pc4.rsdb.forms
  "We have an issue here.

  The legacy WebObjects application uses horizontal inheritance. That means:

  a) we must look in multiple tables and,
  b) the primary key generation occurs in the parent table ('t_form') not in the
  individual form tables.
  c) it places a limit of numbers of forms, simply due to performance.

  I tried to fix later with 't_form_generic' but rather than using a jsonb, it
  uses a separate system of tables and properties in tables 't_form_item' and
  't_form_item_result' acting as a loose schema and key value store
  respectively.

  These are the main scenarios we need to consider:
  1. Get all forms of a specific type, or types for a patient.
  2. Get all forms for a specific encounter.
  3. Get most recent version of a type of form for a patient.

  In order to be performant, and knowing that a specific encounter is associated
  with only a small number of forms, we can do two things:
  1. Use a union query to build an index of all forms indexed by encounter.
  2. When a user wants to look at a specific encounter, or look at the results
  of a specific form across encounters, we use that index to fetch the data we
  need.

  It is most unlikely a single patient will have data in all form tables, as
  the use of forms is dependent on the clinical condition(s) at hand.

  To complicate matters, the legacy application uses 't_form_type' to list all
  form types. This table includes name, description and form_entity_name and
  is used to drive the configuration of forms to encounter templates.

  At some point, we'll just have relatively flat lists of namespaced properties.
  In order to make it easier to migrate, it would be ideal to represent most
  forms flattened within an encounter. For the few forms that permit multiple
  forms per encounter, they would be represented as a to-many property."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [com.eldrix.pc4.rsdb.db :as db]
            [clojure.spec.alpha :as s]
            [next.jdbc :as jdbc]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.rsdb.patients :as patients])
  (:import (java.time LocalDateTime)))

(def forms
  "Support for legacy forms. Here each form is represented by a database table and a form type. "
  [{:form-type-id 1 :table "t_form_ace_r" :title "ACE-R" :key nil :entity-name "FormAceR"}
   {:form-type-id 2 :table "t_form_edss" :title "EDSS (short form)" :key nil :entity-name "FormEdss"}
   {:form-type-id 3 :table "t_form_edss_full" :title "EDSS (Neurostatus)" :key nil :entity-name "FormEdssFull"}
   {:form-type-id 4 :table "t_form_hospital_anxiety_and_depression_brief" :title "HAD (short form)" :key nil :entity-name "FormHospitalAnxietyAndDepressionBrief"}
   {:form-type-id 5 :table "t_form_hospital_anxiety_and_depression" :title "HAD" :key nil :entity-name "FormHospitalAnxietyAndDepression"}
   {:form-type-id 6 :table "t_form_icars" :title "ICARS" :key nil :entity-name "FormIcars"}
   {:form-type-id 7 :table "t_form_mmse" :title "MMSE" :key nil :entity-name "FormMmse"}
   {:form-type-id 8 :table "t_form_motor_updrs" :title "Motor-UPDRS" :key nil :entity-name "FormMotorUpdrs"}
   {:form-type-id 9 :table "t_form_ms_relapse" :title "MS Relapse / disease course" :key nil :entity-name "FormMSRelapse"}
   {:form-type-id 10 :table "t_form_nine_hole_peg" :title "Nine-hole peg" :key nil :entity-name "FormNineHolePeg"}
   {:form-type-id 11 :table "t_form_sara" :title "SARA" :key nil :entity-name " FormSara "}
   {:form-type-id 12 :table "t_form_timed_walk" :title "Timed walk" :key nil :entity-name " FormTimedWalk "}
   {:form-type-id 13 :table "t_form_soap" :title "SOAP" :key nil :entity-name "FormSoap"}
   {:form-type-id 14 :table "t_form_eq5d" :title "EQ5D" :key nil :entity-name "FormEq5d"}
   {:form-type-id 15 :table "t_form_bedside_swallow" :title "Bedside swallow" :key nil :entity-name "FormBedsideSwallow"}
   {:form-type-id 16 :table "t_form_edss_fs" :title "EDSS (FS)" :key nil :entity-name "FormEdssFs"}
   {:form-type-id 23 :table "t_form_logmar_visual_acuity" :title "Visual acuity (LogMAR chart)" :key nil :entity-name "FormLogmarVisualAcuity"}
   {:form-type-id 24 :table "t_form_snellen_visual_acuity" :title "Visual acuity (Snellen chart)" :key nil :entity-name "FormSnellenVisualAcuity"}
   {:form-type-id 25 :table "t_form_use_of_services" :title "Use of services questionnaire" :key nil :entity-name "FormUseOfServices"}
   {:form-type-id 26 :table "t_form_city_colour" :title "City University Colour Vision Test" :key nil :entity-name "FormCityColour"}
   {:form-type-id 28 :table "t_form_msis29" :title "MSIS-29" :key nil :entity-name "FormMsis29"}
   {:form-type-id 29 :table "t_form_weight_height" :title "Weight and height" :key nil :entity-name "FormWeightHeight"}
   {:form-type-id 30 :table "t_form_ishihara" :title "Ishihara" :key nil :entity-name "FormIshihara"}
   {:form-type-id 32 :table "t_form_routine_observations" :title "Routine observations (P/BP)" :key nil :entity-name "FormRoutineObservations"}
   {:form-type-id 35 :table "t_form_alsfrs" :title "ALS Functional Rating Scale" :key nil :entity-name "FormALSFRS"}
   {:form-type-id 36 :table "t_form_lung_function" :title "Lung function" :key nil :entity-name "FormLungFunction"}
   {:form-type-id 37 :name "form_smoking_history" :table "t_smoking_history" :title "Smoking history" :key nil :entity-name "FormSmokingHistory"}
   {:form-type-id 38 :table "t_form_walking_distance" :title "Walking distance" :key nil :entity-name "FormWalkingDistance"}
   {:form-type-id 39 :table "t_form_neurology_curriculum" :title "Neurology curriculum" :key nil :entity-name "FormNeurologyCurriculum"}
   {:form-type-id 40 :table "t_form_follow_up" :title "Follow up" :key nil :entity-name "FormFollowUp"}
   {:form-type-id 41 :table "t_form_presenting_complaints" :title "Presenting complaints / problems" :key nil :entity-name "FormPresentingComplaints"}
   {:form-type-id 42 :table "t_form_gorelick_hydration" :title "Gorelick hydration (not implemented)" :key nil :entity-name "FormGorelickHydration"}
   {:form-type-id 43 :table "t_form_asthma_pram" :title "PRAM (Asthma)" :key nil :entity-name "FormAsthmaPram"}
   {:form-type-id 44 :table "t_form_paediatric_observations" :title "Paediatric observations" :key nil :entity-name "FormPaediatricObservations"}
   {:form-type-id 45 :table "t_form_bronchiolitis_tal_score" :title "Tal score (Bronchiolitis)" :key nil :entity-name "FormBronchiolitisTalScore"}
   {:form-type-id 46 :table "t_form_croup_westley" :title "Westley score (Croup)" :key nil :entity-name "FormCroupWestley"}
   {:form-type-id 47 :table "t_form_nice_feverish_child" :title "NICE feverish child assessment" :key nil :entity-name "FormNiceFeverishChild"}
   {:form-type-id 48 :table "t_form_prescription" :title "Prescription" :key nil :entity-name "FormPrescription"}
   {:form-type-id 49 :table "t_form_mnd_symptom" :title "MND symptoms" :key nil :entity-name "FormMndSymptom"}
   {:form-type-id 50 :table "t_form_adrt" :title "Advance Decisions to Refuse Treatment" :key nil :entity-name "FormAdrt"}
   {:form-type-id 52 :table "t_form_multiple_sclerosis_dmt" :title "MS DMT Assessment" :key nil :entity-name "FormMultipleSclerosisDmt"}
   {:form-type-id 53 :table "t_form_hoehn_yahr" :title "Hoehn and Yahr" :key nil :entity-name "FormHoehnYahr"}
   {:form-type-id 54 :table "t_form_parkinson_non_motor" :title "Non-motor symptoms in PD" :key nil :entity-name "FormParkinsonNonMotor"}
   {:form-type-id 55 :table "t_form_moca" :title "MOCA" :key nil :entity-name "FormMoca"}
   {:form-type-id 56 :table "t_form_procedure_generic" :title "Generic procedure" :key nil :entity-name "FormProcedureGeneric" :allow-multiple? true}
   {:form-type-id 57 :table "t_form_procedure_lumbar_puncture" :title "Lumbar Puncture" :key nil :entity-name "FormProcedureLumbarPuncture" :allow-multiple? true}
   {:form-type-id 58 :table "t_form_medication" :title "Medication review" :key nil :entity-name "FormMedication"}
   {:form-type-id 60 :table "t_form_procedure_botulinum_toxin" :title "Botulinum toxin" :key nil :entity-name "FormProcedureBotulinumToxin" :allow-multiple? true}
   {:form-type-id 61 :table "t_form_botulinum_toxin_outcome" :title "Botulinum toxin outcome" :key nil :entity-name "FormBotulinumToxinOutcome"}
   {:form-type-id 62 :table "t_form_seizure_frequency" :title "Seizure frequency" :key nil :entity-name "FormSeizureFrequency"}
   {:form-type-id 64 :table "t_form_ambulation" :title "Ambulation" :key nil :entity-name "FormAmbulation"}
   {:form-type-id 65 :table "t_form_berg_balance" :title "Berg balance score" :key nil :entity-name "FormBergBalance"}
   {:form-type-id 66 :table "t_form_epilepsy_surgery_mdt" :title "Epilepsy surgery pathway" :key nil :entity-name "FormEpilepsySurgeryMdt"}
   {:form-type-id 67 :table "t_form_future_planning" :title "Future planning" :key nil :entity-name "FormFuturePlanning"}
   {:form-type-id 68 :table "t_form_generic" :title "Symbol digit modality test" :key "FormSymbolDigit" :entity-name "FormGeneric"}
   {:form-type-id 69 :table "t_form_generic" :title "RODS-CIDP" :key "FormRodsCidp" :entity-name "FormGeneric"}
   {:form-type-id 70 :table "t_form_generic" :title "Valproate annual risk acknowledgement form" :key "FormValproate" :entity-name "FormGeneric"}
   {:form-type-id 71 :table "t_form_generic" :title "Haemophilia Joint Health Score" :key "FormHJHS" :entity-name "FormGeneric"}
   {:form-type-id 72 :table "t_form_rehabilitation_assessment" :title "Rehabilitation assessment " :key nil :entity-name "FormRehabilitationAssessment"}
   {:form-type-id 73 :table "t_form_mnd_symptom" :title "MND phenotype" :key nil :entity-name "FormMotorNeuroneDiseasePhenotype"}
   {:form-type-id nil                                       ;; the ECAS form has no implementation in the legacy application
    :table        "t_form_ecas" :title "ECAS" :key nil :entity-name nil}
   {:form-type-id nil
    :table        "t_form_fimfam" :title "FIMFAM" :key nil :entity-name nil}
   {:form-type-id nil                                       ;;; the consent form is a special form, that is not listed as an official form in t_form_type.
    :table        "t_form_consent" :title "Consent form" :key nil :entity-name "FormConsent"}])

(def form-by-name
  (reduce (fn [acc {nm :name, table :table :as form}]
            (assoc acc (or nm (if (str/starts-with? table "t_form")
                                (subs table 2)
                                (throw (ex-info "Cannot determine default form name" form)))) form)) {} forms))

#_(ns-unmap *ns* 'normalize)                                  ;; => unmap the var from the namespace
(defmulti normalize #(some-> % first first namespace))
(defmethod normalize :default [form] form)
(defmethod normalize nil [form] nil)
(defmethod normalize "t_form_ms_relapse" [form]
  (-> (merge {:t_form_ms_relapse/in_relapse false} form)
      (dissoc :t_form_ms_relapse/ms_disease_course)
      (assoc :t_form_ms_relapse/ms_disease_course_fk (get-in form [:t_form_ms_relapse/ms_disease_course :t_ms_disease_course/id]))))

(defn all-active-encounter-ids
  "Return a set of encounter ids for active encounters of the given patient."
  [conn patient-identifier]
  (into #{} (map :t_encounter/id)
        (jdbc/plan conn (sql/format
                          {:select [:t_encounter/id]
                           :from   :t_encounter
                           :where  [:and
                                    [:= :patient_fk {:select [:t_patient/id] :from [:t_patient] :where [:= :patient_identifier patient-identifier]}]
                                    [:<> :t_encounter/is_deleted "true"]]}))))

(def edss-score->score
  {"SCORE0_0"          "0.0"
   "SCORE1_0"          "1.0"
   "SCORE1_5"          "1.5"
   "SCORE2_0"          "2.0"
   "SCORE2_5"          "2.5"
   "SCORE3_0"          "3.0"
   "SCORE3_5"          "3.5"
   "SCORE4_0"          "4.0"
   "SCORE4_5"          "4.5"
   "SCORE5_0"          "5.0"
   "SCORE5_5"          "5.5"
   "SCORE6_0"          "6.0"
   "SCORE6_5"          "6.5"
   "SCORE7_0"          "7.0"
   "SCORE7_5"          "7.5"
   "SCORE8_0"          "8.0"
   "SCORE8_5"          "8.5"
   "SCORE9_0"          "9.0"
   "SCORE9_5"          "9.5"
   "SCORE10_0"         "10.0"
   "SCORE_LESS_THAN_4" "<4"})

(defn encounter->form_smoking_history
  "Return a form smoking history for the encounter."
  [conn encounter-id]
  (db/execute-one! conn (sql/format {:select [:t_smoking_history/id :t_smoking_history/status
                                              :t_smoking_history/current_cigarettes_per_day]
                                     :from   [:t_smoking_history]
                                     :where  [:and
                                              [:= :t_smoking_history/encounter_fk encounter-id]
                                              [:<> :t_smoking_history/is_deleted "true"]]})))

(defn select-keys-by-namespace
  "Like select-keys, but selects based on namespace.
  Keys will be selected if there are not namespaced, or of the namespace
  specified."
  [m nspace]
  (let [nspace' (name nspace)]
    (reduce-kv (fn [a k v]
                 (if (or (nil? (namespace k)) (= nspace' (namespace k)))
                   (assoc a (name k) v) a)) {} m)))

(defn some-keys-in-namespace? [nspace m]
  (some #(= (name nspace) (namespace %)) (keys m)))

(defn all-keys-in-namespace? [pred m]
  (every? pred (map namespace (keys m))))

(defn delete-all-forms-sql [encounter-id table-name]
  {:update (keyword table-name)
   :where  [:and [:= :encounter_fk encounter-id] [:= :is_deleted "false"]]
   :set    {:is_deleted "true"}})

(defn insert-form-sql
  [encounter-id table-name form-data]
  {:insert-into (keyword table-name)                        ;; note as this table uses legacy WO horizontal inheritance, we use t_form_seq to generate identifiers manually.
   :values      [(merge {:id           {:select [[[:nextval "t_form_seq"]]]}
                         :is_deleted   "false"
                         :date_created (LocalDateTime/now)
                         :encounter_fk encounter-id}
                        form-data)]})

(defn save-form-sql
  "Return a vector of SQL to save the form data `data` to the given table.
  There are four alternatives:
  1. If there is no form-data, all forms of the type specified that are linked
     to the encounter will be marked as deleted.
  2. If the form-data contains a form id (e.g. `:t_form_edss/id`), then existing
     data with the specified identifier will be updated with the form data.
  3. If there is no form id, the data will be inserted as a new record.
  3. If the form data is a collection of form data, each will be processed, but
     after all forms of the type specified linked to that encounter are deleted."
  ([encounter table-name form-data]
   (save-form-sql encounter table-name form-data true))
  ([{encounter-id :t_encounter/id :as encounter} table-name form-data delete-before-insert]
   (when table-name
     (let [user-key (keyword table-name "user_fk")
           form-data (cond-> (normalize form-data)
                       (nil? (get form-data user-key))
                       (assoc user-key (:t_user/id encounter)))
           id-key (keyword table-name "id")
           id (get form-data id-key)
           encounter-fk (get form-data (keyword table-name "encounter_fk"))]
       (cond
         ;; if there is no form data, delete all forms of this type
         (nil? form-data)
         [(delete-all-forms-sql encounter-id table-name)]

         ;; if the encounter identifiers don't match, throw
         (and encounter-fk (not= encounter-id encounter-fk))
         (throw (ex-info "form data has different encounter fk to encounter" {:encounter encounter :form-data form-data}))

         ;; if the form data isn't correct, throw
         (and (map? form-data) (not (all-keys-in-namespace? #{table-name} form-data)))
         (throw (ex-info "form data keys must all use the same namespace" form-data))

         ;; if we're updating existing data, update
         (and (map? form-data) id)
         [{:update (keyword table-name) :set form-data :where [:and [:= :id id] [:= :encounter_fk encounter-id]]}]

         ;; we've got a new form => delete any existing and insert a new
         (and (map? form-data) delete-before-insert)
         [(delete-all-forms-sql encounter-id table-name)
          (insert-form-sql encounter-id table-name form-data)]

         ;; just insert without deleting
         (map? form-data)
         [(insert-form-sql encounter-id table-name form-data)]

         ;; we have multiple forms of this type => save each one in turn
         (seq form-data)
         (into [(delete-all-forms-sql encounter-id table-name)]
               (mapcat #(save-form-sql encounter table-name (dissoc % id-key) false) form-data))

         ;; we have an empty sequence of forms => delete all active forms
         :else
         [(delete-all-forms-sql encounter-id table-name)])))))

(defn save-encounter-forms-sql
  "Returns a sequence of SQL statements (as Clojure data structures) to either
   delete, update or insert the form data. It is expected that 'forms' are
   nested within an encounter. They should be nested using their form name
   (e.g. `:t_encounter/form_edss`)."
  [encounter]
  (->> (keys encounter)
       (reduce
         (fn [acc k]
           (if-let [{:keys [table]} (form-by-name (name k))]
             (into acc (save-form-sql encounter table (get encounter k)))
             acc)) [])
       (remove nil?)))

(defn ^:deprecated insert-form!
  "Inserts a form.
  e.g

  (insert form! conn :t_form_edss {:encounter_fk 1
                                   :t_form_edss/user_fk 1
                                   :t_form_edss/edss_score \"SCORE1_0\"}).

  This manages the form id safely, because the legacy WebObjects application
  uses horizontal inheritance so that the identifiers are generated from a
  sequence from 't_form'."
  [conn table data]
  (log/info "writing form" {:table table :data (select-keys-by-namespace data table)})
  (db/execute-one! conn (sql/format {:insert-into [table]
                                     ;; note as this table uses legacy WO horizontal inheritance, we use t_form_seq to generate identifiers manually.
                                     :values      [(merge {:id           {:select [[[:nextval "t_form_seq"]]]}
                                                           :is_deleted   "false"
                                                           :date_created (LocalDateTime/now)}
                                                          (select-keys-by-namespace data table))]})
                   {:return-keys true}))

(defn ^:deprecated count-forms [conn encounter-id form-name]
  (:count (jdbc/execute-one! conn (sql/format {:select [:%count.id]
                                               :from   [form-name]
                                               :where  [:and [:= :encounter_fk encounter-id]
                                                        [:<> :is_deleted "true"]]}))))

(defn ^:deprecated delete-all-forms!
  "Delete all forms of the specific type 'table' from the encounter."
  [conn table {encounter-id :t_encounter/id}]
  (jdbc/execute-one! conn (sql/format {:update table
                                       :where  [:and [:= :is_deleted "false"]
                                                [:= :encounter_fk encounter-id]]
                                       :set    {:is_deleted "true"}})))

(defn ^:deprecated delete-form!
  [conn table data]
  (when-let [id (get data (keyword (name table) "id"))]
    (log/info "Deleting form" {:table table :id id})
    (jdbc/execute-one! conn (sql/format {:update table
                                         :where  [:= :id id]
                                         :set    {:is_deleted "true"}}))))

(s/def ::save-form (s/keys :req [:t_encounter/id
                                 :t_user/id]))

(defn ^:deprecated save-form!
  "Saves an arbitrary form. Designed for forms that permit only a single
  instance per encounter.
  Parameters:
  - tx    : a database transaction
  - table : form table, e.g. :t_form_edss
  - data  : form data, e.g. {:t_form_edss/id 1 :t_form_edss/edss_score \"SCORE0_0\"}
  - pred  : a predicate, optional, if the form has content

  If the form does not have content as defined by pred, the existing form will
  will be removed from the encounter. The default predicate simply looks for
  keys for the table, except 'id'."
  ([tx table data] (save-form! tx table data nil))
  ([tx table data pred]
   (when-not (s/valid? ::save-form data)
     (throw (ex-info "Invalid parameters" (s/explain-data ::save-form data))))
   (let [form-id-key (keyword (name table) "id")
         pred' (if pred pred (partial some-keys-in-namespace? table))
         encounter-id (:t_encounter/id data)
         user-id (:t_user/id data)
         data' (dissoc data form-id-key)                    ;; data without the identifier
         has-data? (pred' data')]
     (when (get data form-id-key)                           ;; when we have an existing form - delete it
       (delete-form! tx table data))
     (if-not has-data?
       (log/debug "skipping writing form; no data" {:table table :data data})
       (if (= 0 (count-forms tx encounter-id table))        ;; check we have no existing form...
         (insert-form! tx table (assoc data' :user_fk user-id :encounter_fk encounter-id))
         (throw (ex-info "A form of this type already exists in the encounter" {:table table :data data})))))))

(s/def ::save-encounter-with-forms (s/keys :req [:t_encounter/date_time
                                                 :t_episode/id
                                                 :t_patient/patient_identifier
                                                 :t_encounter_template/id
                                                 :t_user/id]
                                           :opt [:t_encounter/id]))

(defn save-encounter-and-forms-sql
  "Generates a sequence of SQL to write the encounter and any nested forms
  to the database. The SQL to write the encounter is always returned first."
  [encounter]
  (into [(patients/save-encounter-sql encounter)]
        (save-encounter-forms-sql encounter)))

(s/fdef save-encounter-with-forms!
  :args (s/cat :txn ::db/txn :data ::save-encounter-with-forms))
(defn ^:deprecated save-encounter-with-forms!
  [txn data]
  (log/info "saving encounter with forms" {:data data})
  (when-not (s/valid? ::save-encounter-with-forms data)
    (throw (ex-info "Invalid parameters" (s/explain-data ::save-encounter-with-forms data))))
  (let [encounter (patients/save-encounter! txn (merge (when (:t_encounter/id data) {:t_encounter/id (:t_encounter/id data)})
                                                       {:t_encounter/date_time             (:t_encounter/date_time data)
                                                        :t_encounter/episode_fk            (:t_episode/id data)
                                                        :t_patient/patient_identifier      (:t_patient/patient_identifier data)
                                                        :t_encounter/encounter_template_fk (:t_encounter_template/id data)}))
        _ (log/info "saved encounter, result:" {:encounter encounter})
        data' (assoc data :t_encounter/id (:t_encounter/id encounter))]
    (save-form! txn :t_form_edss data' :t_form_edss/edss_score)
    (save-form! txn :t_form_ms_relapse data')
    (save-form! txn :t_smoking_history data')
    (save-form! txn :t_form_weight_height data')
    encounter))

(s/def ::save-encounter-and-forms
  (s/keys :req [:t_encounter/date_time
                :t_encounter/encounter_template_fk]
          :opt [:t_encounter/id :t_encounter/notes]))
(s/fdef save-encounter-and-forms!
  :args (s/cat :txn ::db/txn :encounter ::save-encounter-and-forms))
(defn save-encounter-and-forms!
  [txn encounter]
  (log/info "saving encounter and forms" encounter)
  (let [[encounter-sql & forms-sql-stmts] (save-encounter-and-forms-sql encounter)
        encounter' (db/execute! txn (sql/format encounter-sql) {:return-keys true})]
    (doseq [stmt forms-sql-stmts]
      (db/execute! txn (sql/format stmt)))
    encounter'))                                            ;; take care to return updated encounter entity.

(comment
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  ()
  group-by
  (def encounters (mapv (fn [id] {:t_encounter/id id}) (all-active-encounter-ids conn 124018)))
  (com.eldrix.pc4.rsdb.patients/active-episodes conn 124010)
  (all-active-encounter-ids conn 124010)
  (com.eldrix.pc4.rsdb.patients/save-encounter! conn {:t_encounter/encounter_template_fk 1
                                                      :t_encounter/episode_fk            48221
                                                      :t_encounter/patient_fk            124010
                                                      :t_encounter/date_time             (LocalDateTime/now)})
  (save-form! conn :t_form_edss {:t_form_edss/edss_score "SCORE1_0"
                                 :t_form_edss/id         244555
                                 :t_user/id              1
                                 :t_encounter/id         529783} :t_form_edss/edss_score)
  (save-encounter-with-forms! conn {:t_encounter/id                               529792
                                    :t_encounter/date_time                        (LocalDateTime/now)
                                    :t_episode/id                                 48221
                                    :t_patient/patient_identifier                 124010
                                    :t_form_edss/edss_score                       "SCORE0_0"
                                    :t_form_edss/id                               244573
                                    :t_encounter_template/id                      1
                                    :t_form_ms_relapse/in_relapse                 true
                                    :t_form_ms_relapse/ms_disease_course_fk       5
                                    :t_smoking_history/status                     "EX-SMOKER"
                                    :t_smoking_history/current_cigarettes_per_day 0
                                    :t_user/id                                    1}))

