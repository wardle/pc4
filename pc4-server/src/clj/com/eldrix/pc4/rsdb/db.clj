(ns com.eldrix.pc4.rsdb.db
  (:require [next.jdbc.date-time]
            [next.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [com.eldrix.nhsnumber]
            [next.jdbc.sql.builder :as sql.builder])
  (:import (java.sql Connection)
           (java.time LocalDate LocalDateTime)))

(next.jdbc.date-time/read-as-local)

(def parse-local-date #(when % (LocalDate/from %)))
(def parse-local-datetime #(when % (LocalDateTime/from %)))

(def property-parsers
  {:t_address/date_from                           parse-local-date
   :t_address/date_to                             parse-local-date
   :t_address/ignore_invalid_address              parse-boolean
   :t_diagnosis/date_diagnosis                    parse-local-date
   :t_diagnosis/date_onset                        parse-local-date
   :t_diagnosis/date_to                           parse-local-date
   :t_encounter/is_deleted                        parse-boolean
   :t_encounter_template/can_change_consultant    parse-boolean
   :t_encounter_template/is_deleted               parse-boolean
   :t_encounter_template/mandatory                parse-boolean
   :t_encounter_template/can_change_hospital      parse-boolean
   :t_encounter_template/allow_multiple           parse-boolean
   :t_job_title/can_be_responsible_clinician      parse-boolean
   :t_job_title/is_clinical                       parse-boolean
   :t_medication/date_from                        parse-local-date
   :t_medication/date_to                          parse-local-date
   :t_medication/date_from_accuracy               keyword
   :t_medication/date_to_accuracy                 keyword
   :t_medication/as_required                      parse-boolean
   :t_medication/frequency                        keyword
   :t_medication/reason_for_stopping              keyword
   :t_medication/temporary_stop                   parse-boolean
   :t_medication/route                            keyword
   :t_medication_event/type                       {"INFUSION_REACTION" :INFUSION_REACTION ;; TODO: fix consistency of type in legacy rsdb
                                                   "AdverseEvent"      :ADVERSE_EVENT}
   :t_medication_event/sample_obtained_antibodies parse-boolean
   :t_medication_event/severity                   keyword
   :t_ms_event/date                               parse-local-date
   :t_form_ms_relapse/in_relapse                  parse-boolean
   :t_patient/date_birth                          parse-local-date
   :t_patient/date_death                          parse-local-date
   :t_patient/sex                                 keyword
   :t_patient/status                              keyword
   :t_patient/authoritative_demographics          keyword
   :t_patient/authoritative_last_updated          parse-local-datetime
   :t_patient_hospital/authoritative              parse-boolean
   :t_project/advertise_to_all                    parse-boolean
   :t_project/can_own_equipment                   parse-boolean
   :t_project/date_from                           parse-local-date
   :t_project/date_to                             parse-local-date
   :t_project/admission                           parse-boolean
   :t_project/is_private                          parse-boolean
   :t_project/type                                keyword
   :t_project/pseudonymous                        parse-boolean
   :t_project/virtual                             parse-boolean
   :t_project_user/role                           keyword
   :t_result_mri_brain/with_gadolinium            parse-boolean
   :t_result_mri_brain/date                       parse-local-date
   :t_result_full_blood_count/date                parse-local-date
   :t_result_renal/date                           parse-local-date
   :t_result_csf_ocb/date                         parse-local-date
   :t_result_jc_virus/date                        parse-local-date
   :t_result_mri_spine/date                       parse-local-date
   :t_result_liver_function/date                  parse-local-date
   :t_result_urinalysis/date                      parse-local-date
   :t_result_ecg/date                             parse-local-date
   :t_result_thyroid_function/date                parse-local-date
   :t_role/is_system                              parse-boolean
   :t_user/authentication_method                  keyword
   :t_user/must_change_password                   parse-boolean
   :t_user/send_email_for_messages                parse-boolean
   :t_ms_event/site_arm_motor                     parse-boolean
   :t_ms_event/site_ataxia                        parse-boolean
   :t_ms_event/site_bulbar                        parse-boolean
   :t_ms_event/site_cognitive                     parse-boolean
   :t_ms_event/site_diplopia                      parse-boolean
   :t_ms_event/site_face_motor                    parse-boolean
   :t_ms_event/site_face_sensory                  parse-boolean
   :t_ms_event/site_leg_motor                     parse-boolean
   :t_ms_event/site_limb_sensory                  parse-boolean
   :t_ms_event/site_optic_nerve                   parse-boolean
   :t_ms_event/site_other                         parse-boolean
   :t_ms_event/site_psychiatric                   parse-boolean
   :t_ms_event/site_sexual                        parse-boolean
   :t_ms_event/site_sphincter                     parse-boolean
   :t_ms_event/site_unknown                       parse-boolean
   :t_ms_event/site_vestibular                    parse-boolean
   :t_ms_events/is_relapse                        parse-boolean})


(defn parse-entity
  "Simple mapping from rsdb source data.
  Principles here are:
   * convert columns that represent rsdb java enums into keywords
   * convert LocalDateTime to LocalDate when appropriate.
   * deliberately does not do mapping from snake case to kebab case as that
     would be redundant; the snake case reflects origin domain - ie rsdb."
  [m & {:keys [remove-nils?] :or {remove-nils? false}}]
  (when m
    (reduce-kv
      (fn [m k v]
        (when (or (not remove-nils?) v)
          (assoc m k (let [f (get property-parsers k)]
                       (if (and f v) (f v) v))))) {} m)))

(defn execute!
  ([connectable sql-params]
   (map parse-entity (jdbc/execute! connectable sql-params)))
  ([connectable sql-params opts]
   (map parse-entity (jdbc/execute! connectable sql-params opts))))

(defn execute-one!
  ([connectable sql-params]
   (parse-entity (jdbc/execute-one! connectable sql-params)))
  ([connectable sql-params opts]
   (parse-entity (jdbc/execute-one! connectable sql-params opts))))

(defn date-in-range?
  "Is the date in the range specified, or is the range 'current'?
  Handles open ranges if `from` or `to` nil."
  ([^LocalDate from ^LocalDate to]
   (date-in-range? from to nil))
  ([^LocalDate from ^LocalDate to ^LocalDate date]
   (let [date' (or date (LocalDate/now))]
     (and (or (nil? from) (not (.isBefore date' from)))
          (or (nil? to) (.isBefore date' to))))))

(defn txn? [conn]
  (and (instance? Connection conn)
       (not (.getAutoCommit ^Connection conn))))

(defn repeatable-read-txn? [conn]
  (and (instance? Connection conn)
       (not (.getAutoCommit ^Connection conn))
       (<= Connection/TRANSACTION_REPEATABLE_READ (.getTransactionIsolation ^Connection conn))))

(defn serializable-txn? [conn]
  (and (instance? Connection conn)
       (not (.getAutoCommit ^Connection conn))
       (= Connection/TRANSACTION_SERIALIZABLE (.getTransactionIsolation ^Connection conn))))

(s/def ::conn any?)
(s/def ::txn txn?)
(s/def ::repeatable-read-txn repeatable-read-txn?)
(s/def ::serializable-txn serializable-txn?)

(s/def :t_death_certificate/part1a (s/nilable string?))
(s/def :t_death_certificate/part1b (s/nilable string?))
(s/def :t_death_certificate/part1c (s/nilable string?))
(s/def :t_death_certificate/part2 (s/nilable string?))
(s/def :t_diagnosis/id int?)
(s/def :t_diagnosis/date_diagnosis (s/nilable #(instance? LocalDate %)))
(s/def :t_diagnosis/date_onset (s/nilable #(instance? LocalDate %)))
(s/def :t_diagnosis/date_to (s/nilable #(instance? LocalDate %)))
(s/def :t_diagnosis/status #{"INACTIVE_REVISED" "ACTIVE" "INACTIVE_RESOLVED" "INACTIVE_IN_ERROR"})
(s/def :t_diagnosis/concept_fk int?)
(s/def :t_encounter/episode_fk int?)
(s/def :t_encounter/date_time #(instance? LocalDateTime %))
(s/def :t_encounter/id int?)
(s/def :t_encounter/patient_fk int?)
(s/def :t_encounter/encounter_template_fk int?)

(s/def :t_episode/id int?)
(s/def :t_episode/date_discharge (s/nilable #(instance? LocalDate %)))
(s/def :t_episode/date_referral #(instance? LocalDate %))
(s/def :t_episode/discharge_user_fk (s/nilable int?))
(s/def :t_episode/notes (s/nilable string?))
(s/def :t_episode/patient_fk int?)
(s/def :t_episode/project_fk int?)
(s/def :t_episode/referral_user_fk int?)
(s/def :t_episode/registration_user_fk (s/nilable int?))
(s/def :t_episode/stored_pseudonym (s/nilable string?))
(s/def :t_episode/external_identifier (s/nilable string?))
(s/def :t_medication_event/id int?)
(s/def :t_medication_event/type #{:INFUSION_REACTION :ADVERSE_EVENT})
(s/def :t_medication_event/action_taken (s/nilable string?))
(s/def :t_medication_event/description_of_reaction (s/nilable string?))
(s/def :t_medication_event/sample_obtained_antibodies (s/nilable boolean?))
(s/def :t_medication_event/severity (s/nilable #{:MILD_NO_INTERRUPTION
                                                 :PROLONGED_REACTION
                                                 :MODERATE_TEMPORARY_INTERRUPTION
                                                 :LIFE_THREATENING}))
(s/def :t_medication_event/reaction_date_time (s/nilable #(instance? LocalDateTime %)))
(s/def :t_medication_event/infusion_start_date_time (s/nilable #(instance? LocalDateTime %)))
(s/def :t_medication_event/event_concept_fk (s/nilable int?))
(s/def :t_medication/id int?)
(s/def :t_medication/date_from (s/nilable #(instance? LocalDate %)))
(s/def :t_medication/date_to (s/nilable #(instance? LocalDate %)))
(def medication-reasons-for-stopping
  #{:CHANGE_OF_DOSE :ADVERSE_EVENT :NOT_APPLICABLE :PREGNANCY :LACK_OF_EFFICACY :PLANNING_PREGNANCY :RECORDED_IN_ERROR
    :ALLERGIC_REACTION :ANTI_JCV_POSITIVE__PML_RISK :LACK_OF_TOLERANCE
    :NON_ADHERENCE :OTHER
    :PATIENT_CHOICE_CONVENIENCE :PERSISTENCE_OF_RELAPSES
    :PERSISTING_MRI_ACTIVITY :DISEASE_PROGRESSION :SCHEDULED_STOP})
(s/def :t_medication/reason_for_stopping medication-reasons-for-stopping)
(s/def :t_medication/events (s/nilable (s/coll-of (s/keys :req [:t_medication_event/type]))))

(s/def :t_ms_event/id int?)
(s/def :t_ms_event/date #(instance? LocalDate %))
(s/def :t_ms_event/impact string?)
(s/def :t_ms_event/notes (s/nilable string?))
(s/def :t_ms_event/summary_multiple_sclerosis_fk int?)
(s/def :t_patient/id int?)
(s/def :t_patient/patient_identifier int?)
(s/def :t_patient/nhs_number (s/and string? com.eldrix.nhsnumber/valid?))
(s/def :t_patient/date_death (s/nilable #(instance? LocalDate %)))
(s/def :t_patient_hospital/patient_fk int?)
(s/def :t_patient_hospital/hospital_fk string?)
(s/def :t_patient_hospital/patient_identifier string?)
(s/def :t_patient_telephone/telephone string?)
(s/def :t_user/id int?)
(s/def :t_smoking_history/id int?)
(s/def :t_smoking_history/current_cigarettes_per_day int?)
(s/def :t_smoking_history/status #{"NEVER_SMOKED" "CURRENT_SMOKER" "EX_SMOKER"})

(s/def :t_address/address1 (s/nilable string?))
(s/def :t_address/address2 (s/nilable string?))
(s/def :t_address/address3 (s/nilable string?))
(s/def :t_address/address4 (s/nilable string?))
(s/def :t_address/postcode_raw (s/nilable string?))
(s/def :t_address/date_from (s/nilable #(instance? LocalDate %)))
(s/def :t_address/date_to (s/nilable #(instance? LocalDate %)))
