(ns pc4.msbase.core
  "Provides functionality to resolve the Unified MSBase JSON model from PatientCare."
  (:require [clojure.core.match :as m]
            [clojure.data.json :as json]
            [clojure.java.process :as proc]
            [clojure.string :as str]
            [com.wsscode.pathom3.connect.operation :as pco]
            [pc4.config.interface :as config]
            [pc4.log.interface :as log]
            [pc4.snomedct.interface :as hermes]
            [pc4.rsdb.interface :as rsdb])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.time.temporal TemporalAccessor)))

(defn format-iso-date [^TemporalAccessor d]
  (when d (.format DateTimeFormatter/ISO_LOCAL_DATE d)))

(def application-id
  "This is the unique and persistent identifier for PatientCare within MSBase."
  #uuid"c626da87-82a7-49ce-9449-bcbbd3c36dc6")

(pco/defresolver app-source []
  {:org.msbase.appSource/appId      application-id
   :org.msbase.appSource/appName    "PatientCare"
   :org.msbase.appSource/appVersion (try (proc/exec "git" "describe") (catch RuntimeException e (log/error "Failed to execute process" e)))})

(pco/defresolver resource-type []
  {:org.msbase.resourceType/objectType     "MSBaseMedicalRecords"
   :org.msbase.resourceType/schemaVersion  "1.0.0"
   :org.msbase.resourceType/generationDate (.format DateTimeFormatter/ISO_INSTANT (Instant/now))})

(def centres
  [{:id      #uuid"03ba14b7-3fda-43e0-ab9d-f8bbac648706"
    :name    "cardiff"
    :project "NINFLAMMCARDIFF"}
   {:id      #uuid"28c35eb0-22a9-4d9e-9d8b-70021002fa6a"
    :name    "cambridge"
    :project "CAMBRIDGEMS"}
   {:id      #uuid"d67203ea-7518-4f9d-84c5-128f291b25bf"
    :name    "plymouth"
    :project "PLYMOUTH"}])

(pco/defresolver centre-source
  "Resolve a 'centre' for a patient based upon the episode registration data.
  This simply iterates the known centres and looks for matching project registrations
  for the patient. TODO: This doesn't limit to active episodes."
  [{episodes :t_patient/episodes}]
  {::pco/input  [{:t_patient/episodes [{:t_episode/project [:t_project/id :t_project/name :t_project/title]}]}]
   ::pco/output [:org.msbase.centreSource/centreId
                 :org.msbase.centreSource/centreName
                 :org.msbase.centreSource/centreCountry]}
  (let [project-names (set (map #(get-in % [:t_episode/project :t_project/name]) episodes))
        centre (first (filter #(project-names (:project %)) centres))]
    {:org.msbase.centreSource/centreId      (:id centre)
     :org.msbase.centreSource/centreName    (:name centre)
     :org.msbase.centreSource/centreCountry (or (:country centre) "UK")}))

(pco/defresolver patient-identification
  "Resolves MSBase 'identification' data. See https://msbasecloud.prosynergie.ch/docs/demographics"
  [{:t_patient/keys [patient_identifier sex] :as patient}]
  {::pco/input [:t_patient/patient_identifier :t_patient/sex]}
  {:org.msbase.identification/localId  (str "com.eldrix.pc4/" patient_identifier)
   :org.msbase.identification/isActive true                 ;; should this be based upon when patient last had encounter
   :org.msbase.identification/gender   (case sex :MALE "M" :FEMALE "F" "")})

(pco/defresolver patient-ethnicity
  "Resolve MSBase 'ethnicity' data"
  [patient]
  {::pco/input [{:t_patient/ethnic_origin [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}
  {:org.msbase.identification/ethnicity (get-in patient [:t_patient/ethnic_origin :info.snomed.Concept/preferredDescription :info.snomed.Description/term])})

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
  {:org.msbase.medicalHistory/entryDate (some->> encounters first :t_encounter/date_time (.format DateTimeFormatter/ISO_LOCAL_DATE))})

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
  [{hermes :com.eldrix/hermes}
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

(pco/defresolver medical-conditions
  [{:t_patient/keys [diagnoses]}]
  {::pco/input  [:t_patient/diagnoses]
   ::pco/output [:org.msbase/medicalConditions]}
  {:org.msbase/medicalConditions
   (remove #(= "INACTIVE_IN_ERROR" (:t_diagnosis/status %)) diagnoses)})

(pco/defresolver medical-condition
  [{:t_diagnosis/keys [id date_onset date_to diagnosis]}]
  {::pco/input [:t_diagnosis/id (pco/? :t_diagnosis/date_onset) (pco/? :t_diagnosis/date_to)
                {:t_diagnosis/diagnosis [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}
  {:org.msbase.medicalCondition/localId     (str "com.eldrix.pc4.diagnosis/" id)
   :org.msbase.medicalCondition/currDisease nil
   :org.msbase.medicalCondition/startDate   (format-iso-date date_onset)
   :org.msbase.medicalCondition/endDate     (format-iso-date date_to)
   :org.msbase.medicalCondition/name        (get-in diagnosis [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])
   :org.msbase.medicalCondition/sctId       (:info.snomed.Concept/id diagnosis)})

(pco/defresolver visits
  [{encounters :t_patient/encounters}]
  {::pco/input  [{:t_patient/encounters [:t_encounter/id :t_encounter/active :t_encounter/date_time]}]
   ::pco/output [{:org.msbase/visits [:t_encounter/id :t_encounter/active :t_encounter/date_time]}]}
  {:org.msbase/visits
   (->> encounters
        (filterv :t_encounter/active))})

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
  {::pco/input  [{:t_encounter/form_edss [(pco/? :t_form_edss/score) (pco/? :t_form_edss_fs/score)]}]
   ::pco/output [{:org.msbase.visit/basicMS [:org.msbase.basicMS/edss]}]}
  (let [edss (or (some-> (:t_form_edss/score form_edss) parse-double)
                 (some-> (:t_form_edss_fs/score form_edss) parse-double))]
    {:org.msbase.visit/basicMS
     (when edss {:org.msbase.basicMS/edss edss})}))

(pco/defresolver relapses
  "Resolves 'relapse' type events for a given patient."
  [{sms :t_patient/summary_multiple_sclerosis}]
  {::pco/input  [{:t_patient/summary_multiple_sclerosis
                  [:t_summary_multiple_sclerosis/events]}]
   ::pco/output [:org.msbase/relapses]}
  {:org.msbase/relapses
   (filterv :t_ms_event/is_relapse (:t_summary_multiple_sclerosis/events sms))})

(defn relapse-site->fs
  [{:t_ms_event/keys [id date notes
                      site_arm_motor site_ataxia site_bulbar site_cognitive
                      site_diplopia site_face_motor site_face_sensory
                      site_leg_motor site_limb_sensory site_optic_nerve
                      site_other site_psychiatric site_sexual site_sphincter site_unknown
                      site_vestibular]}]
  (cond-> []
    (or site_cognitive site_psychiatric)
    (conj "neu")

    site_optic_nerve
    (conj "vis")

    site_ataxia
    (conj "cer")

    (or site_bulbar site_diplopia site_face_motor site_vestibular)
    (conj "bra")

    (or site_arm_motor site_leg_motor)
    (conj "pyr")

    (or site_limb_sensory site_face_sensory)
    (conj "sen")

    (or site_sexual site_sphincter)
    (conj "bow")))

(comment
  (relapse-site->fs {:t_ms_event/site_arm_motor true})
  (relapse-site->fs {:t_ms_event/site_face_sensory true :t_ms_event/site_face_motor true}))

(pco/defresolver relapse
  [{:t_ms_event/keys [id date notes] :as event}]
  {::pco/input  [:t_ms_event/id :t_ms_event/date :t_ms_event_type/id :t_ms_event_type/abbreviation :t_ms_event/notes
                 :t_ms_event/site_arm_motor :t_ms_event/site_ataxia :t_ms_event/site_bulbar :t_ms_event/site_cognitive
                 :t_ms_event/site_diplopia :t_ms_event/site_face_motor :t_ms_event/site_face_sensory
                 :t_ms_event/site_leg_motor :t_ms_event/site_limb_sensory :t_ms_event/site_optic_nerve
                 :t_ms_event/site_other :t_ms_event/site_psychiatric :t_ms_event/site_sexual :t_ms_event/site_sphincter :t_ms_event/site_unknown
                 :t_ms_event/site_vestibular]
   ::pco/output [:org.msbase.relapse/localId
                 :org.msbase.relapse/currDisease
                 :org.msbase.relapse/onsetDate
                 :org.msbase.relapse/fsAffected
                 :org.msbase.relapse/notes]}
  {:org.msbase.relapse/localId     (str "com.eldrix.pc4.ms-event/" id)
   :org.msbase.relapse/currDisease nil
   :org.msbase.relapse/onsetDate   (format-iso-date date)
   :org.msbase.relapse/duration    nil
   :org.msbase.relapse/impactADL   "unk"
   :org.msbase.relapse/severity    "unk"
   :org.msbase.relapse/fsAffected  (relapse-site->fs event)
   :org.msbase.relapse/fsOther     nil
   :org.msbase.relapse/notes       notes})

(def dmts
  ["(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR
                  (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
   "<<108754007|Glatiramer| OR <<9246601000001104|Copaxone| OR <<13083901000001102|Brabio| OR <<8261511000001102 OR <<29821211000001101
                  OR (<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<108755008|Glatiramer acetate|)"
   "(<<9218501000001109|Avonex| OR <<9322401000001109|Rebif| OR
                            (<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386902004|Interferon beta-1a|))"
   "(<<9222901000001105|Betaferon|) OR (<<10105201000001101|Extavia|) OR
                  (<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386903009|Interferon beta-1b|)"
   "<<12222201000001108|Plegridy|"
   "<<703786007|Teriflunomide| OR <<12089801000001100|Aubagio| "
   (str/join " OR "
             ["(<<108809004|Rituximab product|)"
              "(<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<386919002|Rituximab|)"
              "(<<9468801000001107|Mabthera|) OR (<<13058501000001107|Rixathon|)"
              "(<<226781000001109|Ruxience|)  OR (<<13033101000001108|Truxima|)"])
   "(<<35058611000001103|Ocrelizumab|) OR (<<13096001000001106|Ocrevus|)"
   "<<108800000|Cladribine| OR <<13083101000001100|Mavenclad|"
   "<<108791001|Mitoxantrone| OR <<9482901000001102|Novantrone|"
   "<<715640009|Fingolimod| OR <<10975301000001100|Gilenya|"
   "<<414804006|Natalizumab| OR <<9375201000001103|Tysabri|"
   "(<<391632007|Alemtuzumab|) OR (<<12091201000001101|Lemtrada|)"])

(def all-dmts (str/join " OR " dmts))

(defn medication-type
  "Returns medication type. At the moment, this returns nil unless the
  medication is a disease-modifying therapy (DMT)."
  [hermes {concept-id :t_medication/medication_concept_fk, :as medication}]
  (when (seq (hermes/intersect-ecl hermes [concept-id] all-dmts)) "dmt"))

(pco/defresolver treatments
  "Resolve treatments. We strip out all treatments that are not DMTs."
  [{hermes :com.eldrix/hermes} {meds :t_patient/medications}]
  {::pco/input  [:t_patient/medications]
   ::pco/output [:org.msbase/treatments]}
  {:org.msbase/treatments
   (->> meds
        (remove #(= :RECORDED_IN_ERROR (:t_medication/reason_for_stopping %)))
        (map #(assoc % :org.msbase.pharmaTrts/type (medication-type hermes %)))
        (filter #(= "dmt" (:org.msbase.pharmaTrts/type %))))})

(def stop-causes
  {:CHANGE_OF_DOSE              "scheduled"
   :ADVERSE_EVENT               "adverse"
   :NOT_APPLICABLE              nil
   :PREGNANCY                   "pregconf"
   :LACK_OF_EFFICACY            "efficacy"
   :PLANNING_PREGNANCY          "pregplan"
   :RECORDED_IN_ERROR           :error
   :ALLERGIC_REACTION           "allergic"
   :ANTI_JCV_POSITIVE__PML_RISK "antijcv"
   :LACK_OF_TOLERANCE           "tolerance"
   :NON_ADHERENCE               "adherence"
   :OTHER                       nil
   :PATIENT_CHOICE_CONVENIENCE  "convenience"
   :PERSISTENCE_OF_RELAPSES     "relapses"
   :PERSISTING_MRI_ACTIVITY     "mri"
   :DISEASE_PROGRESSION         "disprog"
   :SCHEDULED_STOP              "scheduled"})

(pco/defresolver treatment
  [{hermes :com.eldrix/hermes}
   {:t_medication/keys [id medication_concept_fk date_from date_to reason_for_stopping medication]}]
  {::pco/input  [:t_medication/id
                 :t_medication/medication_concept_fk
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
  {:org.msbase.pharmaTrts/localId     (str "com.eldrix.pc4.medication/" id)
   :org.msbase.pharmaTrts/currDisease nil
   :org.msbase.pharmaTrts/type        (:org.msbase.pharmaTrts/type medication)
   :org.msbase.pharmaTrts/startDate   (format-iso-date date_from)
   :org.msbase.pharmaTrts/endDate     (format-iso-date date_to)
   :org.msbase.pharmaTrts/name        (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])
   :org.msbase.pharmaTrts/sctId       medication_concept_fk
   :org.msbase.pharmaTrts/dose        (:t_medication/dose medication)
   :org.msbase.pharmaTrts/unit        (:t_medication/units medication)
   :org.msbase.pharmaTrts/period      nil
   :org.msbase.pharmaTrts/route       (some-> (:t_medication/route medication) name)
   :org.msbase.pharmaTrts/stopCause   (get stop-causes reason_for_stopping)
   :org.msbase.pharmaTrts/notes       (:t_medication/notes medication)})

(pco/defresolver magnetic-resonance-imaging-results
  [{results :t_patient/results}]
  {::pco/input  [:t_patient/results]
   ::pco/output [:org.msbase/magneticResonanceImaging]}
  {:org.msbase/magneticResonanceImaging
   (filterv #(#{"ResultMriBrain" "ResultMriSpine"} (:t_result_type/result_entity_name %)) results)})

(defn parse-lesions
  [s]
  (when-not (str/blank? s)
    (if-let [n (parse-long s)]
      n
      ())))

(comment)

(pco/defresolver magnetic-resonance-imaging
  [{id          :t_result/id, date :t_result/date
    entity-name :t_result_type/result_entity_name
    spine-type  :t_result_mri_spine/type :as result}]
  {::pco/input [:t_result/id :t_result/date :t_result_type/result_entity_name
                (pco/? :t_result_mri_brain/with_gadolinium)
                (pco/? :t_result_mri_brain/total_gad_enhancing_lesions)
                (pco/? :t_result_mri_brain/total_t2_hyperintense)
                (pco/? :t_result_mri_brain/calc_change_t2)
                (pco/? :t_result_mri_spine/type)]}
  {:org.msbase.mri/localId     (str "com.eldrix.pc4.t_result_mri_brain/" id)
   :org.msbase.mri/currDisease nil
   :org.msbase.mri/examDate    (format-iso-date date)
   :org.msbase.mri/cnsRegion   (m/match [entity-name spine-type]
                                 ["ResultMriBrain" nil] "brai"
                                 ["ResultMriSpine" "CERVICAL_AND_THORACIC"] "spin"
                                 ["ResultMriSpine" "CERVICAL"] "cerv"
                                 ["ResultMriSpine" "WHOLE_SPINE"] "spin"
                                 ["ResultMriSpine" "THORACIC"] "thor"
                                 ["ResultMriSpine" nil] "spin"
                                 :else nil)
   :org.msbase.mri/isT1        nil
   :org.msbase.mri/t1Status    nil
   :org.msbase.mri/nbT1Les     nil
   :org.msbase.mri/isT1Gd      (:t_result_mri_brain/with_gadolinium result)
   :org.msbase.mri/t1GdStatus  nil
   :org.msbase.mri/nbT1GdLes   (some-> (:t_result_mri_brain/total_gad_enhancing_lesions result) parse-long)
   :org.msbase.mri/t1GdLes     (:t_result_mri_brain/has_gad_enhancing_lesions result)
   :org.msbase.mri/isT2        nil
   :org.msbase.mri/t2Status    nil
   :org.msbase.mri/nbT2Les     (some-> (:t_result_mri_brain/total_t2_hyperintense result) parse-long)
   :org.msbase.mri/nbNewEnlarg (:t_result_mri_brain/calc_change_t2 result)
   :org.msbase.mri/newEnlarg   (boolean (some-> (:t_result_mri_brain/calc_change_t2 result) pos-int?))})

(pco/defresolver cerebrospinal-fluid-results
  [{results :t_patient/results}]
  {::pco/input  [:t_patient/results]
   ::pco/output [:org.msbase/cerebrospinalFluid]}
  {:org.msbase/cerebrospinalFluid
   (filterv #(#{"ResultCsfOcb"} (:t_result_type/result_entity_name %)) results)})

(pco/defresolver csf
  [{id :t_result/id, date :t_result/date, ocb :t_result_csf_ocb/result}]
  {::pco/input [:t_result/id :t_result/date :t_result_csf_ocb/result]}
  {:org.msbase.csf/localId     (str "com.eldrix.pc4.t_result_csf_ocb/" id)
   :org.msbase.csf/currDisease nil
   :org.msbase.csf/examDate    (format-iso-date date)
   :org.msbase.csf/csf         nil
   :org.msbase.csf/olgBand     (case ocb "POSITIVE" "det"
                                     "PAIRED" "det"
                                     "NEGATIVE" "abs" nil)
   :org.msbase.csf/nbOlig      nil
   :org.msbase.csf/matchOCB    (case ocb "POSITIVE" "no"
                                     "PAIRED" "yes" nil)})

(def all-resolvers
  [app-source
   resource-type
   centre-source
   patient-identification
   patient-ethnicity
   demographics
   medical-history
   ms-event-symptoms
   ms-diagnosis
   medical-conditions
   medical-condition
   visits
   visit
   visit->edss
   relapses
   relapse
   treatments
   treatment
   magnetic-resonance-imaging-results
   magnetic-resonance-imaging
   cerebrospinal-fluid-results
   csf])

(def msbase-query
  "A query for data from PatientCare matching the Unified MSBase data model."
  [:t_patient/patient_identifier
   {:>/appSource
    [:org.msbase.appSource/appId
     :org.msbase.appSource/appName
     :org.msbase.appSource/appVersion]}
   {:>/resourceType
    [:org.msbase.resourceType/objectType
     :org.msbase.resourceType/schemaVersion
     :org.msbase.resourceType/generationDate]}
   {:>/centreSource
    [:org.msbase.centreSource/centreId
     :org.msbase.centreSource/centreName
     :org.msbase.centreSource/centreCountry]}
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
   {:org.msbase/medicalConditions
    [:org.msbase.medicalCondition/localId
     :org.msbase.medicalCondition/currDisease
     :org.msbase.medicalCondition/startDate
     :org.msbase.medicalCondition/endDate
     :org.msbase.medicalCondition/name
     :org.msbase.medicalCondition/sctId]}
   {:org.msbase/visits
    [:org.msbase.visit/localId
     :org.msbase.visit/currDisease
     :org.msbase.visit/visitDate
     :org.msbase.visit/status
     {:org.msbase.visit/basicMS [:org.msbase.basicMS/edss]}]}
   {:org.msbase/relapses
    [:org.msbase.relapse/localId
     :org.msbase.relapse/currDisease
     :org.msbase.relapse/onsetDate
     :org.msbase.relapse/fsAffected
     :org.msbase.relapse/notes]}
   {:org.msbase/treatments
    [:org.msbase.pharmaTrts/localId
     :org.msbase.pharmaTrts/currDisease
     :org.msbase.pharmaTrts/type
     :org.msbase.pharmaTrts/startDate
     :org.msbase.pharmaTrts/endDate
     :org.msbase.pharmaTrts/name
     :org.msbase.pharmaTrts/sctId
     :org.msbase.pharmaTrts/dose
     :org.msbase.pharmaTrts/unit
     :org.msbase.pharmaTrts/period
     :org.msbase.pharmaTrts/route
     :org.msbase.pharmaTrts/stopCause]}
   {:org.msbase/cerebrospinalFluid
    [:org.msbase.csf/localId
     :org.msbase.csf/currDisease
     :org.msbase.csf/examDate
     :org.msbase.csf/csf
     :org.msbase.csf/olgBand
     :org.msbase.csf/nbOlig
     :org.msbase.csf/matchOCB]}
   {:org.msbase/magneticResonanceImaging
    [:org.msbase.mri/localId
     :org.msbase.mri/currDisease
     :org.msbase.mri/examDate
     :org.msbase.mri/cnsRegion
     :org.msbase.mri/isT1
     :org.msbase.mri/t1Status
     :org.msbase.mri/nbT1Les
     :org.msbase.mri/isT1Gd
     :org.msbase.mri/t1GdStatus
     :org.msbase.mri/nbT1GdLes
     :org.msbase.mri/t1GdLes
     :org.msbase.mri/isT2
     :org.msbase.mri/t2Status
     :org.msbase.mri/nbT2Les
     :org.msbase.mri/nbNewEnlarg
     :org.msbase.mri/newEnlarg]}])

(defn fetch-patient
  "Generates a single patient record in a format matching the Unified MSBase JSON model.
  Parameters:
  - pathom             : a Pathom boundary interface
  - patient-identifier : patient identifier."
  [pathom patient-identifier]
  (let [result (get (pathom [{[:t_patient/patient_identifier patient-identifier] msbase-query}])
                    [:t_patient/patient_identifier patient-identifier])]
    {:identifiers    {:resourceType (:>/resourceType result)
                      :centreSource (:>/centreSource result)
                      :appSource    (:>/appSource result)}
     :medicalRecords {:profile           {:identification (:>/patientIdentification result)
                                          :demographics   (:>/demographics result)
                                          :medicalHistory (:>/medicalHistory result)
                                          :msDiagnosis    (:>/msDiagnosis result)}
                      :visits            (filter :org.msbase.visit/basicMS (:org.msbase/visits result))
                      :relapses          (:org.msbase/relapses result)
                      :malignancies      []
                      :medicalConditions (:org.msbase/medicalConditions result)
                      :mri               (:org.msbase/magneticResonanceImaging result)
                      :csf               (:org.msbase/cerebrospinalFluid result)
                      :pregnancies       []
                      :pharmaTrts        (:org.msbase/treatments result)
                      :nonPharmaTrts     []}}))

(comment
  (require '[pc4.lemtrada.interface :as lemtrada])
  (require '[dev.nu.morse :as morse])
  (morse/launch-in-proc)
  (require '[integrant.core :as ig])
  (ig/load-namespaces (config/config :dev))
  (def system (ig/init (config/config :dev) [:pc4.lemtrada.interface/env :pc4.graph.interface/boundary-interface]))
  (ig/halt! system)
  (def pathom (:pc4.graph.interface/boundary-interface system))
  (keys system)

  (morse/inspect (pathom [{[:t_patient/patient_identifier 84686] msbase-query}]))
  (morse/inspect (json/write-str (fetch-patient pathom 84686)))

  ;;; pc4 - live
  (require '[integrant.core :as ig])
  (ig/load-namespaces (config/config :pc4-dev))
  (def system (ig/init (config/config :pc4-dev) [:pc4.lemtrada.interface/env :pc4.graph.interface/boundary-interface]))
  (def lemtrada-env (:pc4.lemtrada.interface/env system))

  (def pathom (:pc4.graph.interface/boundary-interface system))
  (require '[pc4.rsdb.interface :as rsdb])
  (def rsdb (:pc4.rsdb.interface/svc system))

  ;; create a fake pathom env with required authentication... this needs to be cleaned up
  ;; TODO: clean up creation of pathom environment so it is standardised, and can be built
  ;; on the fly from data in a real session, or created ad-hoc for programmatic / REPL usage 
  ;; like this
  (def pathom-env {:session/authenticated-user
                   (assoc (rsdb/user-by-username rsdb "system")
                          :t_user/active_roles (pc4.rsdb.users/active-roles-by-project-id (:conn rsdb) "system"))
                   :session/authorization-manager (rsdb/user->authorization-manager rsdb "system")})
  (def pathom (partial (:pc4.graph.interface/boundary-interface system) pathom-env))
  (def patient-ids (lemtrada/patient-identifiers lemtrada-env :cambridge))

  (count patient-ids)
  (doseq [patient-id (take 100 (sort patient-ids))]
    (spit (str "msbase/cambridge-" patient-id ".json") (json/write-str (fetch-patient pathom patient-id))))

  (morse/inspect (fetch-patient pathom 124027)))
