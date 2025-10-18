(ns pc4.rsdb.danger.delete
  "Development and testing utilities for rsdb.

  WARNING: These functions are destructive and should ONLY be used in development."
  (:require
    [clojure.tools.logging.readable :as log]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [pc4.rsdb.patients :as patients]))

(def form-tables-with-encounter-fk
  "All form tables that have a foreign key to t_encounter.
  These must be deleted before deleting encounters."
  [:t_form_ace_r
   :t_form_adrt
   :t_form_alsfrs
   :t_form_ambulation
   :t_form_asthma_pram
   :t_form_bedside_swallow
   :t_form_berg_balance
   :t_form_botulinum_toxin_outcome
   :t_form_bronchiolitis_tal_score
   :t_form_city_colour
   :t_form_consent
   :t_form_croup_westley
   :t_form_diagnoses
   :t_form_ecas
   :t_form_edss
   :t_form_edss_fs
   :t_form_edss_full
   :t_form_epilepsy_surgery_mdt
   :t_form_eq5d
   :t_form_fimfam
   :t_form_follow_up
   :t_form_future_planning
   :t_form_generic
   :t_form_gorelick_hydration
   :t_form_hoehn_yahr
   :t_form_hospital_anxiety_and_depression
   :t_form_hospital_anxiety_and_depression_brief
   :t_form_icars
   :t_form_ishihara
   :t_form_logmar_visual_acuity
   :t_form_lung_function
   :t_form_medication
   :t_form_mmse
   :t_form_mnd_symptom
   :t_form_moca
   :t_form_motor_neurone_disease_phenotype
   :t_form_motor_uhdrs
   :t_form_motor_updrs
   :t_form_ms_relapse
   :t_form_msis29
   :t_form_msis29n
   :t_form_multiple_sclerosis_dmt
   :t_form_multiple_sclerosis_questionnaire
   :t_form_neurology_curriculum
   :t_form_nice_feverish_child
   :t_form_nine_hole_peg
   :t_form_paediatric_observations
   :t_form_parkinson_non_motor
   :t_form_prescription
   :t_form_presenting_complaints
   :t_form_problems
   :t_form_procedure
   :t_form_procedure_botulinum_toxin
   :t_form_procedure_generic
   :t_form_procedure_lumbar_puncture
   :t_form_prom
   :t_form_rehabilitation_assessment
   :t_form_request
   :t_form_routine_observations
   :t_form_sara
   :t_form_seizure_frequency
   :t_form_snellen_visual_acuity
   :t_form_soap
   :t_form_structured_clinic
   :t_form_timed_walk
   :t_form_todo
   :t_form_use_of_services
   :t_form_walking_distance
   :t_form_weight_height])

(defn delete-patient-sql
  "Generate SQL statements to delete a patient and all related data.

  This function generates a sequence of DELETE statements that remove all data
  associated with a patient, respecting foreign key dependencies. The statements
  must be executed in order within a transaction.

  Parameters:
  - patient-pk : the patient's primary key (id)

  Returns:
  A sequence of HoneySQL maps representing DELETE statements to be executed in order.

  WARNING: This is a destructive operation and should ONLY be used in development
  environments for testing purposes."
  [patient-pk]
  (let [encounter-subquery {:select :id :from :t_encounter :where [:= :patient_fk patient-pk]}
        form-delete-stmts (map (fn [table]
                                 {:delete-from table
                                  :where       [:in :encounter_fk encounter-subquery]})
                               form-tables-with-encounter-fk)]
    (concat
      ;; Level 5: Delete nested form dependencies (forms that reference other forms)
      [{:delete-from :t_form_item_result :where [:in :form_generic_fk {:select :id :from :t_form_generic :where [:in :encounter_fk encounter-subquery]}]}
       {:delete-from :t_form_diagnoses_diagnosis_noted :where [:in :form_diagnoses_fk {:select :id :from :t_form_diagnoses :where [:in :encounter_fk encounter-subquery]}]}
       {:delete-from :t_form_diagnoses_diagnosis_owned :where [:in :form_diagnoses_fk {:select :id :from :t_form_diagnoses :where [:in :encounter_fk encounter-subquery]}]}
       {:delete-from :t_form_follow_up_status :where [:in :form_follow_up_fk {:select :id :from :t_form_follow_up :where [:in :encounter_fk encounter-subquery]}]}
       {:delete-from :t_form_multiple_sclerosis_dmt_eligible :where [:in :formmultiplesclerosisdmtid {:select :id :from :t_form_multiple_sclerosis_dmt :where [:in :encounter_fk encounter-subquery]}]}
       {:delete-from :t_form_presenting_complaints_concepts :where [:in :form_presenting_complaints_fk {:select :id :from :t_form_presenting_complaints :where [:in :encounter_fk encounter-subquery]}]}
       {:delete-from :t_form_problems_concepts :where [:in :formproblemsid {:select :id :from :t_form_problems :where [:in :encounter_fk encounter-subquery]}]}
       {:delete-from :t_form_rehabilitation_assessment_diagnoses :where [:in :formrehabilitationassessmentid {:select :id :from :t_form_rehabilitation_assessment :where [:in :encounter_fk encounter-subquery]}]}]

      ;; Level 4: Delete all form types that reference encounters
      form-delete-stmts

      ;; Delete encounter users
      [{:delete-from :t_encounter_user :where [:in :encounterid encounter-subquery]}]

      ;; Level 3: Delete nested dependencies of medication
      [{:delete-from :t_medication_event :where [:in :medication_fk {:select :id :from :t_medication :where [:= :patient_fk patient-pk]}]}
       {:delete-from :t_medication_dose :where [:in :medication_fk {:select :id :from :t_medication :where [:= :patient_fk patient-pk]}]}
       {:delete-from :t_form_prescription :where [:in :medication_fk {:select :id :from :t_medication :where [:= :patient_fk patient-pk]}]}
       {:delete-from :t_form_multiple_sclerosis_dmt :where [:in :current_dmt_medication_fk {:select :id :from :t_medication :where [:= :patient_fk patient-pk]}]}]

      ;; Level 3: Delete nested dependencies of summaries
      [{:delete-from :t_ms_event :where [:in :summary_multiple_sclerosis_fk {:select :id :from :t_summary_multiple_sclerosis :where [:= :patient_fk patient-pk]}]}]

      ;; Level 3: Delete nested dependencies of episodes
      [{:delete-from :t_episode_diagnosis :where [:in :episode_fk {:select :id :from :t_episode :where [:= :patient_fk patient-pk]}]}]

      ;; Level 2: Delete appointments (can be linked to both encounters and episodes)
      [{:delete-from :t_appointment :where [:= :patient_fk patient-pk]}]

      ;; Level 2: Delete direct children of patient
      [{:delete-from :t_address :where [:= :patient_fk patient-pk]}
       {:delete-from :t_biobank_sample :where [:= :patient_fk patient-pk]}
       {:delete-from :t_death_certificate :where [:= :patient_fk patient-pk]}
       {:delete-from :t_diagnosis :where [:= :patient_fk patient-pk]}
       {:delete-from :t_document :where [:= :patient_fk patient-pk]}
       {:delete-from :t_employment :where [:= :patient_fk patient-pk]}
       {:delete-from :t_equipment_location :where [:= :patient_fk patient-pk]}
       {:delete-from :t_message :where [:= :patient_fk patient-pk]}
       {:delete-from :t_nform :where [:= :patient_fk patient-pk]}
       {:delete-from :t_patient_hospital :where [:= :patient_fk patient-pk]}
       {:delete-from :t_patient_professional :where [:= :patient_fk patient-pk]}
       {:delete-from :t_patient_telephone :where [:= :patient_fk patient-pk]}
       {:delete-from :t_todo_item :where [:= :patient_fk patient-pk]}]

      ;; Delete medication (after its dependencies)
      [{:delete-from :t_medication :where [:= :patient_fk patient-pk]}]

      ;; Delete encounters (after their dependencies)
      [{:delete-from :t_encounter :where [:= :patient_fk patient-pk]}]

      ;; Delete episodes (after their dependencies)
      [{:delete-from :t_episode :where [:= :patient_fk patient-pk]}]

      ;; Delete all result types
      [{:delete-from :t_result :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_anti_mog :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_aquaporin4 :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_blood_gas :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_csf_culture :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_csf_ocb :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_csf_protein_glucose :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_dat_scan :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_ecg :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_eeg :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_full_blood_count :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_ifn_neutralising_ab :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_jc_virus :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_liver_function :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_mri_brain :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_mri_spine :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_pkg :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_renal :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_thyroid_function :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_tpmt :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_urinalysis :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_visual_evoked_potentials :where [:= :patient_fk patient-pk]}
       {:delete-from :t_result_wada :where [:= :patient_fk patient-pk]}]

      ;; Delete all summary types
      [{:delete-from :t_summary :where [:= :patient_fk patient-pk]}
       {:delete-from :t_summary_ataxia :where [:= :patient_fk patient-pk]}
       {:delete-from :t_summary_epilepsy :where [:= :patient_fk patient-pk]}
       {:delete-from :t_summary_iih :where [:= :patient_fk patient-pk]}
       {:delete-from :t_summary_metabolic :where [:= :patient_fk patient-pk]}
       {:delete-from :t_summary_mnd :where [:= :patient_fk patient-pk]}
       {:delete-from :t_summary_multiple_sclerosis :where [:= :patient_fk patient-pk]}]

      ;; Level 1: Finally, delete the patient record itself
      ;; Note: Self-referential foreign keys (mother/father) should be cleared first or will fail
      [{:update :t_patient :set {:mother_patient_fk nil :father_patient_id nil} :where [:or [:= :mother_patient_fk patient-pk] [:= :father_patient_id patient-pk]]}
       {:delete-from :t_patient :where [:= :id patient-pk]}])))

(defn delete-patient!
  "Delete a patient and all associated data from the database.

  This function removes all data associated with a patient, including:
  - Encounters and all forms
  - Episodes and diagnoses
  - Medications and events
  - Results of all types
  - Summaries and related data
  - Addresses, telephones, and other demographic data
  - Messages, appointments, and todo items

  Parameters:
  - conn : database connection (will be wrapped in a transaction)
  - patient-pk : the patient's primary key (id)

  Returns:
  A map with:
  - :deleted-patient-pk - the patient primary key that was deleted
  - :statements-executed - the number of DELETE statements executed

  WARNING: This is a DESTRUCTIVE operation that CANNOT be undone. Use ONLY in
  development environments for testing purposes. Never use in production!

  Example:
  ```clojure
  (delete-patient! conn {:patient-identifier 12345})
  => {:deleted-patient-pk 12345 :statements-executed 89}
  ```"
  [conn {:keys [patient-pk patient-identifier] :as params}]
  (let [patient-pk (or patient-pk
                       (when patient-identifier (patients/patient-identifier->pk conn patient-identifier))
                       (throw (ex-info "must specify either patient-pk or patient-identifier" params)))]
    (log/warn "DELETING PATIENT" {:patient-pk patient-pk :WARNING "This cannot be undone!"})
    (jdbc/with-transaction [txn conn]
      (let [statements (delete-patient-sql patient-pk)
            results (mapv (fn [stmt]
                            (let [sql (sql/format stmt)]
                              (log/debug "Executing delete" {:sql sql})
                              (jdbc/execute-one! txn sql)))
                          statements)]
        (log/info "Patient deleted" {:patient-pk patient-pk :statements (count results)})
        {:deleted-patient-pk  patient-pk
         :statements-executed (count results)}))))

(comment
  (require '[pc4.config.interface :as config])
  (require '[integrant.core :as ig])
  (def system (ig/init (config/config :dev) [:pc4.rsdb.interface/conn]))
  (def conn (:pc4.rsdb.interface/conn system))

  ;; WARNING: This will delete the patient!
  (delete-patient! conn {:patient-identifier 138265})

  (ig/halt! system))
