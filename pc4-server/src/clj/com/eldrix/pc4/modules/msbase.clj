(ns com.eldrix.pc4.modules.msbase
  "Provides functionality to resolve the Unified MSBase JSON model from PatientCare."
  (:require [clojure.string :as str]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.pc4.system :as pc4]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.operation :as pco])
  (:import (java.time.format DateTimeFormatter)
           (java.time.temporal TemporalAccessor)))

(defn format-iso-date [^TemporalAccessor d]
  (when d (.format (DateTimeFormatter/ISO_LOCAL_DATE) d)))

(pco/defresolver patient-identification
  "Resolves MSBase 'identification' data. See https://msbasecloud.prosynergie.ch/docs/demographics"
  [{:t_patient/keys [patient_identifier sex] :as patient}]
  {::pco/input [:t_patient/patient_identifier
                :t_patient/sex
                (pco/? {:t_patient/ethnic_origin [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]})]}
  {:org.msbase.identification/localId   (str "com.eldrix.pc4/" patient_identifier)
   :org.msbase.identification/isActive  true
   :org.msbase.identification/gender    (case sex :MALE "M" :FEMALE "F" "")
   :org.msbase.identification/ethnicity (get-in patient [:t_patient/ethnic_origin :info.snomed.Concept/preferredDescription :info.snomed.Description/term])})

(pco/defresolver demographics
  "Return MSBase 'demographics' data. See https://msbasecloud.prosynergie.ch/docs/demographics"
  [{:t_patient/keys [last_name first_names date_birth nhs_number]}]
  {:org.msbase.demographics/lastName  last_name
   :org.msbase.demographics/firstName first_names           ;; TODO: split and only include first?
   :org.msbase.demographics/birthDate (format-iso-date date_birth)
   :org.msbase.demographics/nhsNumber nhs_number})

(pco/defresolver medical-history
  "See https://msbasecloud.prosynergie.ch/docs/medical-history
  We derive the 'date of entry in the clinic' as the first recorded encounter.
  There are several possible alternatives which could be used instead."
  [{:t_patient/keys [encounters]}]
  {:org.msbase.medicalHistory/entryDate (some->> encounters first :t_encounter/date_time (.format (DateTimeFormatter/ISO_LOCAL_DATE)))})

(pco/defresolver ms-event-symptoms
  [{:t_ms_event/keys [site_arm_motor site_ataxia site_bulbar site_cognitive
                      site_diplopia site_face_motor site_face_sensory
                      site_leg_motor site_limb_sensory site_optic_nerve
                      site_other site_psychiatric site_sexual site_sphincter site_unknown
                      site_vestibular]}]
  {::pco/output [:org.msbase/symptoms]}
  {:org.msbase/symptoms (cond-> []
                                (or site_cognitive site_psychiatric)
                                (conj "supr")
                                site_optic_nerve
                                (conj "opti")
                                (or site_ataxia site_bulbar site_diplopia site_face_motor site_face_sensory site_vestibular)
                                (conj "brai")
                                (or site_arm_motor site_leg_motor site_limb_sensory site_sexual site_sphincter)
                                (conj "spin"))})

(def ms-diagnosis-categories
  {1  {:poser "a1" :description "Poser: Clinically definite multiple sclerosis"}
   2  {:poser "b1" :description "Poser: Laboratory supported definite multiple sclerosis"}
   3  {:poser "c1" :description "Poser: Clinically probable multiple sclerosis"}
   4  {:poser "d1" :description "Poser: Laboratory supported probable multiple sclerosis"}
   5  {:description "Schumacher: Possible multiple sclerosis"}
   6  {:description "Schumacher: Probable multiple sclerosis"}
   7  {:mcDonald "ms" :description "Schumacher: Clinically definite multiple sclerosis"}
   8  {:mcDonald "ms" :description "McDonald: Multiple sclerosis (>=2 attacks/>=2 objective)"}
   9  {:mcDonald "ms" :description "McDonald: Multiple sclerosis (>=2 attacks/>=1 objective with dissem. in space)"}
   10 {:mcDonald "ms" :description "McDonald: Multiple sclerosis (1 attack/>=2 objective with dissem. in time)"}
   11 {:mcDonald "ms" :description "McDonald: Multiple sclerosis (1 attack/1 objective with dissem. in time and space)"}
   12 {:mcDonald "ms" :description "McDonald: Multiple sclerosis (insidious with positive CSF and dissem. in time and space)"}
   13 {:mcDonald "ms" :description "McDonald: Multiple sclerosis (not specified)"}
   14 {:mcDonald "possibleMs" :description "McDonald: Possible multiple sclerosis"}
   15 {:mcDonald "notMs" :description "McDonald: Not multiple sclerosis"}
   16 {:description "Multiple sclerosis - post-mortem confirmed"}
   17 {:description "Multiple sclerosis - death certificate proven"}
   18 {:description "Multiple sclerosis - unclassified"}
   19 {:description "Multiple sclerosis - reported"}
   20 {:mcDonald "notMS" :description "Neuromyelitis optica"}
   21 {:description "Clinically isolated syndrome "}
   22 {:description "Radiologically isolated syndrome "}})

(pco/defresolver ms-diagnosis
  "Resolve MSBase neuro-inflammatory core diagnosis dataset.
  For onset, we use the recorded MS events, but if there are none, fallback to using
  the manually recorded onset from t_diagnosis.
  See https://msbasecloud.prosynergie.ch/docs/diagnosis-ms"
  [{hermes :com.eldrix.hermes.graph/svc}
   {diagnoses :t_patient/diagnoses,
    sms       :t_patient/summary_multiple_sclerosis}]
  {::pco/input  [{:t_patient/diagnoses [:t_diagnosis/concept_fk
                                        :t_diagnosis/date_diagnosis
                                        :t_diagnosis/date_onset
                                        :t_diagnosis/status]}
                 {:t_patient/summary_multiple_sclerosis
                  [:t_ms_diagnosis/id
                   {:t_summary_multiple_sclerosis/events [:t_ms_event/date
                                                          :t_ms_event_type/abbreviation
                                                          :org.msbase/symptoms]}]}]
   ::pco/output [:org.msbase.msDiagnosis/onsetDate
                 :org.msbase.msDiagnosis/diagDate
                 :org.msbase.msDiagnosis/firstSympt
                 :org.msbase.msDiagnosis/progOnset
                 :org.msbase.msDiagnosis/progDate
                 :org.msbase.msDiagnosis/diagConfBy
                 :org.msbase.msDiagnosis/clMcDonald
                 :org.msbase.msDiagnosis/clPoser]}
  (let [ms-concept-ids (hermes/intersect-ecl hermes (map :t_diagnosis/concept_fk diagnoses) "<<24700007")
        diagnoses' (->> diagnoses
                        (remove #(= "INACTIVE_IN_ERROR" (:t_diagnosis/status %)))
                        (filter #(ms-concept-ids (:t_diagnosis/concept_fk %))))
        events (sort-by :t_ms_event/date (:t_summary_multiple_sclerosis/events sms))]
    {;;derive onset date from first event, or if missing, date of onset in diagnoses list
     :org.msbase.msDiagnosis/onsetDate
     (format-iso-date
       (or (:t_ms_event/date (first events)) (first (sort (map :t_diagnosis/date_onset diagnoses')))))
     ;; derive the diagnosis from the first recorded date of diagnosis of MS or subtype
     :org.msbase.msDiagnosis/diagDate
     (format-iso-date (first (sort (map :t_diagnosis/date_diagnosis diagnoses'))))
     ;; derive the first symptom from the first recorded disease event
     :org.msbase.msDiagnosis/firstSympt
     (:org.msbase/symptoms (first events))
     ;; derive whether progressive from disease onset by simply looking for if first event "POR"
     :org.msbase.msDiagnosis/progOnset
     (boolean (= "POR" (:t_ms_event_type/abbreviation (first events))))
     ;; derive date of secondary progressive phase by returning date of first "POP" event
     :org.msbase.msDiagnosis/progDate
     (format-iso-date (:t_ms_event/date (first (filter #(#{"POR" "POP"} (:t_ms_event_type/abbreviation %)) events))))
     ;; diagnosis confirmed by ["csf" "fin" "mri" "evp"] TODO: consider whether we want to support this?
     :org.msbase.msDiagnosis/diagConfBy []
     :org.msbase.msDiagnosis/clMcDonald (get-in ms-diagnosis-categories [(:t_ms_diagnosis/id sms) :mcDonald])
     :org.msbase.msDiagnosis/clPoser    (get-in ms-diagnosis-categories [(:t_ms_diagnosis/id sms) :poser])}))


(pco/defresolver visits
  [{encounters :t_patient/encounters}]
  {::pco/input [{:t_patient/encounters [:t_encounter/id :t_encounter/active :t_encounter/date_time]}]
   ::pco/output [{:org.msbase/visits [:t_encounter/id :t_encounter/active :t_encounter/date_time]}]}
  (morse/inspect {:resolver/visits {:encounters encounters}})
  {:org.msbase/visits (filterv :t_encounter/active encounters)})

(pco/defresolver visit
  [{:t_encounter/keys [id date_time]}]
  {::pco/input  [:t_encounter/id :t_encounter/active :t_encounter/date_time]
   ::pco/output [:org.msbase.visit/localId
                 :org.msbase.visit/currDisease
                 :org.msbase.visit/visitDate
                 :org.msbase.visit/status]}
  {:org.msbase.visit/localId     id
   :org.msbase.visit/visitDate   (format-iso-date date_time)
   :org.msbase.visit/currDisease nil
   :org.msbase.visit/status      nil})

(pco/defresolver visit->edss
  [{:t_encounter/keys [form_edss]}]
  {::pco/input [{:t_encounter/form_edss [(pco/? :t_form_edss/score) (pco/? :t_form_edss_fs/score)]}]
   ::pco/output [:org.msbase.visit/edss]}
  {:org.msbase.visit/edss
   (let [edss (:t_form_edss/score form_edss)
         edss-fs (:t_form_edss_fs/score form_edss)]
     (or (when-not (str/blank? edss) (parse-double edss))
         (when-not (str/blank? edss-fs) (parse-double edss-fs))))})

(pco/defresolver relapse
  [{:t_ms_event/keys [id date notes
                      site_arm_motor site_ataxia site_bulbar site_cognitive
                      site_diplopia site_face_motor site_face_sensory
                      site_leg_motor site_limb_sensory site_optic_nerve
                      site_other site_psychiatric site_sexual site_sphincter site_unknown
                      site_vestibular]}]
  {::pco/input  [:t_ms_event/id :t_ms_event/date :t_ms_event_type/id :t_ms_event_type/abbreviation :t_ms_event/notes
                 :t_ms_event/site_arm_motor :t_ms_event/site_ataxia :t_ms_event/site_bulbar :t_ms_event/site_cognitive
                 :t_ms_event/site_diplopia :t_ms_event/site_face_motor :t_ms_event/site_face_sensory
                 :t_ms_event/site_leg_motor :t_ms_event/site_limb_sensory :t_ms_event/site_optic_nerve
                 :t_ms_event/site_other :t_ms_event/site_psychiatric :t_ms_event/site_sexual :t_ms_event/site_sphincter :t_ms_event/site_unknown
                 :t_ms_event/site_vestibular]
   ::pco/output [:org.msbase.relapse/localId
                 :org.msbase.relapse/currDisease
                 :org.msbase.relapse/onsetDate
                 :org.msbase.relapse/fsAffected]}
  {:org.msbase.relapse/localId     (str id)
   :org.msbase.relapse/currDisease nil
   :org.msbase.relapse/onsetDate   (format-iso-date date)
   :org.msbase.relapse/duration    nil
   :org.msbase.relapse/impactADL   "unk"
   :org.msbase.relapse/severity    "unk"
   :org.msbase.relapse/fsAffected  (cond-> []
                                           (or site_cognitive site_psychiatric)
                                           (conj "neu")
                                           site_optic_nerve
                                           (conj "vis")
                                           site_ataxia
                                           (conj "cer")
                                           (or site_bulbar site_diplopia site_face_motor site_face_sensory site_vestibular)
                                           (conj "bra")
                                           (or site_arm_motor site_leg_motor site_limb_sensory)
                                           (conj "pyr")
                                           (or site_sexual site_sphincter)
                                           (conj "bow"))
   :org.msbase.relapse/fsOther     notes})


(def stop-causes
  {:CHANGE_OF_DOSE              "scheduled"
   :ADVERSE_EVENT               "adverse"
   :NOT_APPLICABLE              nil
   :PREGNANCY                   "pregconf"
   :LACK_OF_EFFICACY            "efficacy"
   :PLANNING_PREGNANCY          "pregplan"
   :RECORDED_IN_ERROR           :error
   :ALLERGIC_REACTION           "allergic"
   :ANTI_JCV_POSITIVE__PML_RISK "adverse"
   :LACK_OF_TOLERANCE           "tolerance"
   :NON_ADHERENCE               "adherence"
   :OTHER                       nil
   :PATIENT_CHOICE_CONVENIENCE  "convenience"
   :PERSISTENCE_OF_RELAPSES     "relapses"
   :PERSISTING_MRI_ACTIVITY     "mri"
   :DISEASE_PROGRESSION         "disprog"
   :SCHEDULED_STOP              "scheduled"})

(pco/defresolver treatment
  [{:t_medication/keys [id date_from date_to reason_for_stopping medication]}]
  {::pco/input [:t_medication/id
                :t_medication/date_from
                :t_medication/date_to
                :t_medication/reason_for_stopping
                {:t_medication/medication
                 [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]
   ::pco/output [:org.msbase.pharmaTrts/localId
                 :org.msbase.pharmaTrts/currDisease
                 :org.msbase.pharmaTrts/type
                 :org.msbase.pharmaTrts/startDate
                 :org.msbase.pharmaTrts/endDate
                 :org.msbase.pharmaTrts/name
                 :org.msbase.pharmaTrts/dose
                 :org.msbase.pharmaTrts/unit
                 :org.msbase.pharmaTrts/period
                 :org.msbase.pharmaTrts/route
                 :org.msbase.pharmaTrts/stopCause]}
  {:org.msbase.pharmaTrts/localId (str id)
   :org.msbase.pharmaTrts/currDisease nil
   :org.msbase.pharmaTrts/type nil
   :org.msbase.pharmaTrts/startDate (format-iso-date date_from)
   :org.msbase.pharmaTrts/endDate (format-iso-date date_to)
   :org.msbase.pharmaTrts/name (get-in medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term)
   :org.msbase.pharmaTrts/dose nil
   :org.msbase.pharmaTrts/unit nil
   :org.msbase.pharmaTrts/period nil
   :org.msbase.pharmaTrts/route nil
   :org.msbase.pharmaTrts/stopCause (get stop-causes reason_for_stopping)})

(def all-resolvers
  [patient-identification
   demographics
   medical-history
   ms-event-symptoms
   ms-diagnosis
   visits
   visit
   visit->edss
   relapse
   treatment])



(comment
  (require '[dev.nu.morse :as morse])
  (morse/launch-in-proc)
  (require '[com.eldrix.pc4.system :as pc4])
  (pc4/load-namespaces :dev)
  (def system (pc4/init :dev/dell))
  (pc4/halt! system)
  (def pathom (:pathom/boundary-interface system))
  (morse/inspect (:pathom/env system))
  (do (pc4/halt! system) (def system (pc4/init :dev/dell)) (def pathom (:pathom/boundary-interface system)))
  (keys system)

  (morse/inspect (pathom [{[:t_patient/patient_identifier 8876]
                           [:t_patient/patient_identifier
                            {:>/patientIdentification
                             [:org.msbase.identification/localId
                              :org.msbase.identification/isActive
                              :org.msbase.identification/gender
                              :org.msbase.identification/ethnicity]}
                            {:>/medicalHistory
                             [:org.msbase.medicalHistory/entryDate]}
                            {:>/msDiagnosis
                             [:org.msbase.msDiagnosis/onsetDate
                              :org.msbase.msDiagnosis/diagDate
                              :org.msbase.msDiagnosis/firstSympt
                              :org.msbase.msDiagnosis/progOnset
                              :org.msbase.msDiagnosis/progDate
                              :org.msbase.msDiagnosis/diagConfBy
                              :org.msbase.msDiagnosis/clMcDonald
                              :org.msbase.msDiagnosis/clPoser]}
                            {:>/demographics
                             [:org.msbase.demographics/birthDate
                              :org.msbase.demographics/firstName
                              :org.msbase.demographics/lastName
                              :org.msbase.demographics/nhsNumber]}
                            {:org.msbase/visits
                             [:org.msbase.visit/localId
                              :org.msbase.visit/currDisease
                              :org.msbase.visit/visitDate
                              :org.msbase.visit/status
                              :org.msbase.visit/edss]}]}]))


  (morse/inspect (pathom [{[:t_patient/patient_identifier 120980]
                           [{:t_patient/encounters [:t_encounter/date_time
                                                    :t_encounter/form_ms_relapse
                                                    {:t_encounter/form_edss [:t_form_edss/score :t_form_edss_fs/score]}]}]}])))
