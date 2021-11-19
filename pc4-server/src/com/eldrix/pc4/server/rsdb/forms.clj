(ns com.eldrix.pc4.server.rsdb.forms
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
  (:require [honey.sql :as sql]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [clojure.spec.alpha :as s]
            [next.jdbc :as jdbc])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time LocalDateTime)))

(def forms
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
   {:form-type-id 37 :table "t_smoking_history" :title "Smoking history" :key nil :entity-name "FormSmokingHistory"}
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

(defn all-active-encounter-ids [conn patient-identifier]
  (transduce
    (map :t_encounter/id)
    conj
    #{}
    (next.jdbc/plan conn
                    (sql/format
                      {:select [:t_encounter/id]
                       :from   :t_encounter
                       :where  [:and
                                [:= :patient_fk {:select [:t_patient/id] :from [:t_patient] :where [:= :patient_identifier patient-identifier]}]
                                [:<> :t_encounter/is_deleted "true"]]}))))

(defn all-form_edss
  "Given a sequence of encounter-ids, returns a sub-sequence of data for each
  encounter containing Kurtzke Expanded Disability Status Scale (EDSS)."
  [conn encounter-ids]
  (when (seq encounter-ids)
    (concat
      (->> (next.jdbc/execute! conn (sql/format {:select [:t_encounter/id :t_encounter/date_time :t_form_edss/id :t_form_edss/edss_score :t_form_edss/user_fk]
                                                 :from   [:t_form_edss]
                                                 :join   [:t_encounter [:= :t_form_edss/encounter_fk :t_encounter/id]]
                                                 :where  [:and [:in :t_form_edss/encounter_fk encounter-ids]
                                                          [:<> :t_form_edss/is_deleted "true"]]}))
           (map #(assoc % :t_form_edss/edss_type :edss)))
      (->> (next.jdbc/execute! conn (sql/format {:select [:t_encounter/id :t_encounter/date_time :t_form_edss_fs/id :t_form_edss_fs/edss_score :t_form_edss_fs/user_fk]
                                                 :from   [:t_form_edss_fs]
                                                 :join   [:t_encounter [:= :t_form_edss_fs/encounter_fk :t_encounter/id]]
                                                 :where  [:and [:in :t_form_edss_fs/encounter_fk encounter-ids]
                                                          [:<> :t_form_edss_fs/is_deleted "true"]]}))
           (map #(assoc % :t_form_edss/edss_score (:t:form_edss_fs/edss_score)
                          :t_form_edss/edss_type :edss-fs))))))

(defn all-form_weight_height [conn encounter-ids]
  (when (seq encounter-ids)
    (next.jdbc/execute! conn (sql/format {:select [:t_encounter/id :t_encounter/date_time
                                                   :t_form_weight_height/id :t_form_weight_height/weight_kilogram :t_form_weight_height/height_metres]
                                          :from   [:t_form_weight_height]
                                          :join   [:t_encounter [:= :t_form_weight_height/encounter_fk :t_encounter/id]]
                                          :where  [:and [:in :t_form_weight_height/encounter_fk encounter-ids]
                                                   [:<> :t_form_weight_height/is_deleted "true"]]}))))

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

(s/def :t_form_edss/edss_score (set (keys edss-score->score)))
(s/def :t_form_ms_relapse/in_relapse boolean?)

(defn all-ms-disability-forms
  "Returns all of the forms for the patient specified. "
  [conn patient-identifier]
  (->> (db/execute! conn (sql/format {:select    [:t_encounter/id :t_encounter/date_time
                                                  :t_form_edss/edss_score :t_form_edss_fs/edss_score
                                                  :t_form_ms_relapse/in_relapse :t_form_ms_relapse/activity
                                                  :t_ms_disease_course/id :t_ms_disease_course/name]
                                      :from      :t_encounter
                                      :left-join [:t_form_edss [:= :t_form_edss/encounter_fk :t_encounter/id]
                                                  :t_form_edss_fs [:= :t_form_edss_fs/encounter_fk :t_encounter/id]
                                                  :t_form_ms_relapse [:= :t_form_ms_relapse/encounter_fk :t_encounter/id]
                                                  :t_ms_disease_course [:= :t_form_ms_relapse/ms_disease_course_fk :t_ms_disease_course/id]]
                                      :where     [:and
                                                  [:= :patient_fk {:select [:t_patient/id] :from [:t_patient] :where [:= :patient_identifier patient-identifier]}]
                                                  [:<> :t_encounter/is_deleted "true"]
                                                  [:<> :t_form_edss/is_deleted "true"]
                                                  [:<> :t_form_ms_relapse/is_deleted "true"]]}))
       (map (fn [encounter] (let [edss (:t_form_edss/edss_score encounter)
                                  edss-fs (:t_form_edss_fs/edss_score encounter)
                                  edss-type (cond edss-fs :edss-fs edss :edss :else nil)
                                  score (or edss edss-fs)]
                              (cond-> encounter
                                      score (assoc :t_form_edss/edss (edss-score->score score))
                                      edss-type (assoc :t_form_edss/edss_type edss-type)))))))

(defn insert-form!
  "Inserts a form.
  e.g

  (insert form! conn :t_form_edss {:encounter_fk 1
                                   :t_form_edss/user_fk 1
                                   :t_form_edss/edss_score \"SCORE1_0\"}).

  This manages the form id safely, because the legacy WebObjects application
  uses horizontal inheritance so that the identifiers are generated from a
  sequence from 't_form'."
  [conn form-name values]
  (db/execute-one! conn (sql/format {:insert-into [form-name]
                                     ;; note as this table uses legacy WO horizontal inheritance, we use t_form_seq to generate identifiers manually.
                                     :values      [(merge {:id           {:select [[[:nextval "t_form_seq"]]]}
                                                           :is_deleted   "false"
                                                           :date_created (LocalDateTime/now)}
                                                          values)]})
                   {:return-keys true}))

(defn count-forms [conn encounter-id form-name]
  (:count (next.jdbc/execute-one! conn (sql/format {:select [:%count.id]
                                                    :from   [form-name]
                                                    :where  [:and [:= :encounter_fk encounter-id]
                                                             [:<> :is_deleted "true"]]}))))

(s/def ::save-form-edss
  (s/keys :req [:t_encounter/id
                :t_user/id
                :t_form_edss/edss_score]
          :opt [:t_form_edss/id]))

(defn save-form-edss!
  [conn {form-edss-id :t_form_edss/id
         encounter-id :t_encounter/id
         user-id      :t_user/id
         edss-score   :t_form_edss/edss_score
         :as data}]
  (s/assert ::save-form-edss data)
  (jdbc/with-transaction
    [tx conn {:isolation :serializable}]
    (when form-edss-id                                      ;; if we have an existing form, delete it, and then create a new form
      (db/execute-one! tx (sql/format {:update [:t_form_edss]
                                       :where  [:= :id form-edss-id]
                                       :set    {:t_form_edss/is_deleted "true"}})))
    (when-not (= 0 (count-forms tx encounter-id :t_form_edss))  ;; enforce user-space count of forms per encounter
      (throw (ex-info "A form of this type already exists in the encounter" data)))
    (insert-form! tx :t_form_edss {:t_form_edss/user_fk      user-id
                                   :t_form_edss/encounter_fk encounter-id
                                   :t_form_edss/edss_score   edss-score})))

(defn save-form-ms-relapse! [conn data]
  (throw (ex-info "not implemented " data))
  )

(comment
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 1}))
  (com.eldrix.pc4.server.rsdb.patients/pk)
  (sql/format {:union-all [{:select [:id :encounter_fk :is_deleted]
                            :from   [:t_form_edss]}
                           {:select [:id :encounter_fk :is_deleted]
                            :from   [:t_form_edss_fs]}]})

  (time (all-form_weight_height conn (all-active-encounter-ids conn 9500)))
  (all-form_edss conn (all-active-encounter-ids conn 124018))
  (time (all-ms-disability-forms conn 124018))
  (next.jdbc/execute-one! conn (insert-form! conn :t_form_edss
                                             {:t_form_edss/encounter_fk 1
                                              :t_form_edss/user_fk      1
                                              :t_form_edss/edss_score   "SCORE1_0"}))
  )