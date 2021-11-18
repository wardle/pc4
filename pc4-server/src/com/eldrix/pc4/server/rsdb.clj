(ns com.eldrix.pc4.server.rsdb
  "Integration with the rsdb backend.
  `rsdb` is the Apple WebObjects 'legacy' application that was PatientCare v1-3.

  Depending on real-life usage, we could pre-fetch relationships and therefore
  save multiple database round-trips, particularly for to-one relationships.
  Such a change would be trivial because pathom would simply not bother
  trying to resolve data that already exists. "
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.pc4.server.dates :as dates]
            [com.eldrix.pc4.server.rsdb.patients :as patients]
            [com.eldrix.pc4.server.rsdb.projects :as projects]
            [com.eldrix.pc4.server.rsdb.users :as users]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [com.eldrix.pc4.server.rsdb.auth :as auth]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [clojure.spec.alpha :as s]
            [com.eldrix.pc4.server.rsdb.patients :as patients]
            [com.eldrix.hermes.core :as hermes])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time LocalDate)
           (java.util Base64)
           (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)
           (com.eldrix.pc4.server.rsdb.auth AuthorizationManager)))

(s/def :t_patient/id int?)
(s/def :t_patient/patient_identifier int?)
(s/def :t_patient/nhs_number (s/and string? com.eldrix.concierge.nhs-number/valid?))
(s/def :uk.gov.ons.nhspd/PCD2 string?)
(s/def :info.snomed.Concept/id (s/and int? com.eldrix.hermes.verhoeff/valid?))
(s/def :t_diagnosis/id int?)
(s/def :t_diagnosis/date_diagnosis (s/nilable #(instance? LocalDate %)))
(s/def :t_diagnosis/date_onset (s/nilable #(instance? LocalDate %)))
(s/def :t_diagnosis/date_to (s/nilable #(instance? LocalDate %)))
(s/def :t_diagnosis/status #{"INACTIVE_REVISED" "ACTIVE" "INACTIVE_RESOLVED" "INACTIVE_IN_ERROR"})
(s/def :t_diagnosis/diagnosis (s/keys :req [:info.snomed.Concept/id]))
(s/def :t_medication/id int?)
(s/def :t_medication/date_from (s/nilable #(instance? LocalDate %)))
(s/def :t_medication/date_to (s/nilable #(instance? LocalDate %)))
(s/def :t_ms_event/id int?)
(s/def :t_ms_event/date #(instance? LocalDate %))
(s/def :t_ms_event/impact string?)
(s/def :t_ms_event/summary_multiple_sclerosis_fk int?)
(s/def :t_encounter/date_time #(instance? LocalDateTime %))
(s/def ::user-id int?)
(s/def ::project-id int?)
(s/def ::nhs-number #(com.eldrix.concierge.nhs-number/valid? (clojure.string/replace % " " "")))
(s/def ::sex #{:MALE :FEMALE :UNKNOWN})
(s/def ::date-birth #(instance? LocalDate %))
(s/def ::register-patient-by-pseudonym
  (s/keys :req-un [::user-id ::project-id ::nhs-number ::sex ::date-birth]))

(defn ordered-diagnostic-dates? [{:t_diagnosis/keys [date_onset date_diagnosis date_to]}]
  (and
    (or (nil? date_onset) (nil? date_diagnosis) (.isBefore date_onset date_diagnosis) (.equals date_onset date_diagnosis))
    (or (nil? date_onset) (nil? date_to) (.isBefore date_onset date_to) (.equals date_onset date_to))
    (or (nil? date_diagnosis) (nil? date_to) (.isBefore date_diagnosis date_to) (.equals date_diagnosis date_to))))

(defn valid-diagnosis-status? [{:t_diagnosis/keys [status date_to]}]
  (case status
    "ACTIVE" (nil? date_to)
    "INACTIVE_IN_ERROR" date_to
    "INACTIVE_RESOLVED" date_to
    "INACTIVE_REVISED" date_to
    false))

(defn html->text
  "Convert a string containing HTML to plain text."
  [^String html]
  (Jsoup/clean html (Safelist.)))

(pco/defresolver patient-by-identifier
  [{:com.eldrix.rsdb/keys [conn]} {patient_identifier :t_patient/patient_identifier}]
  {::pco/output [:t_patient/id
                 :t_patient/patient_identifier
                 :t_patient/sex
                 :t_patient/status
                 :t_patient/title
                 :t_patient/first_names
                 :t_patient/last_name
                 :t_patient/email
                 :t_patient/country_of_birth_concept_fk
                 :t_patient/date_birth
                 :t_patient/date_death
                 :t_patient/nhs_number
                 :t_patient/surgery_fk
                 :t_patient/ethnic_origin_concept_fk
                 :t_patient/racial_group_concept_fk
                 :t_patient/occupation_concept_fk]}
  (db/execute-one! conn (sql/format {:select [:*] :from [:t_patient] :where [:= :patient_identifier patient_identifier]})))

(pco/defresolver patient->hospitals
  [{conn :com.eldrix.rsdb/conn} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/hospitals [:t_patient_hospital/hospital_fk
                                        :t_patient_hospital/patient_identifier
                                        :t_patient_hospital/authoritative]}]}
  {:t_patient/hospitals (db/execute! conn (sql/format {:select [:*]
                                                       :from   [:t_patient_hospital]
                                                       :where  [:= :patient_fk patient-id]}))})

(pco/defresolver patient-hospital->hospital
  [{hospital_fk :t_patient_hospital/hospital_fk}]
  {:t_patient_hospital/hospital {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id hospital_fk}})

(pco/defresolver patient->country-of-birth
  [{concept-id :t_patient/country_of_birth_concept_fk}]
  {::pco/output [{:t_patient/country_of_birth [:info.snomed.Concept/id]}]}
  (when concept-id {:t_patient/country_of_birth {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient->ethnic-origin
  [{concept-id :t_patient/ethnic_origin_concept_fk}]
  {::pco/output [{:t_patient/ethnic_origin [:info.snomed.Concept/id]}]}
  (when concept-id {:t_patient/ethnic_origin {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient->racial-group
  [{concept-id :t_patient/racial_group_concept_fk}]
  {::pco/output [{:t_patient/racial_group [:info.snomed.Concept/id]}]}
  (when concept-id {:t_patient/racial_group {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient->occupation
  [{concept-id :t_patient/occupation_concept_fk}]
  {::pco/output [{:t_patient/occupation [:info.snomed.Concept/id]}]}
  (when concept-id {:t_patient/occupation {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient->surgery
  [{surgery-fk :t_patient/surgery_fk}]
  {::pco/output [{:t_patient/surgery [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id]}]}
  (when surgery-fk {:t_patient/surgery {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id surgery-fk}}))

(pco/defresolver patient->diagnoses
  [{conn :com.eldrix.rsdb/conn} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/diagnoses [:t_diagnosis/concept_fk
                                        {:t_diagnosis/diagnosis [:info.snomed.Concept/id]}
                                        :t_diagnosis/date_diagnosis
                                        :t_diagnosis/date_diagnosis_accuracy
                                        :t_diagnosis/date_onset
                                        :t_diagnosis/date_onset_accuracy
                                        :t_diagnosis/date_to
                                        :t_diagnosis/date_to_accuracy
                                        :t_diagnosis/status
                                        :t_diagnosis/full_description]}]}
  (let [diagnoses (db/execute! conn (sql/format {:select [:*]
                                                 :from   [:t_diagnosis]
                                                 :where  [:= :patient_fk patient-id]}))]
    {:t_patient/diagnoses
     (map #(assoc % :t_diagnosis/diagnosis {:info.snomed.Concept/id (:t_diagnosis/concept_fk %)}) diagnoses)}))

(pco/defresolver patient->summary-multiple-sclerosis        ;; this is misnamed, but belies the legacy system's origins.
  [{conn :com.eldrix.rsdb/conn} {patient-identifier :t_patient/patient_identifier}]
  {::pco/output [{:t_patient/summary_multiple_sclerosis [:t_summary_multiple_sclerosis/id
                                                         :t_summary_multiple_sclerosis/ms_diagnosis_fk
                                                         :t_ms_diagnosis/id ; we flatten this to-one attribute
                                                         :t_ms_diagnosis/name]}]}
  (let [sms (patients/fetch-summary-multiple-sclerosis conn patient-identifier)]
    {:t_patient/summary_multiple_sclerosis sms}))

(pco/defresolver summary-multiple-sclerosis->events
  [{conn :com.eldrix.rsdb/conn} {sms-id :t_summary_multiple_sclerosis/id}]
  {::pco/output [{:t_summary_multiple_sclerosis/events
                  [:t_ms_event/id
                   :t_ms_event/date
                   :t_ms_event/is_relapse
                   :t_ms_event/site_arm_motor :t_ms_event/site_ataxia
                   :t_ms_event/site_bulbar :t_ms_event/site_cognitive
                   :t_ms_event/site_diplopia :t_ms_event/site_face_motor
                   :t_ms_event/site_face_sensory :t_ms_event/site_leg_motor
                   :t_ms_event/site_limb_sensory :t_ms_event/site_optic_nerve
                   :t_ms_event/site_other :t_ms_event/site_psychiatric
                   :t_ms_event/site_sexual :t_ms_event/site_sphincter
                   :t_ms_event/site_unknown :t_ms_event/site_vestibular
                   :t_ms_event_type/id                      ;; the event type is a to-one relationship, so we flatten this
                   :t_ms_event_type/name
                   :t_ms_event_type/abbreviation]}]}
  (let [events (patients/fetch-ms-events conn sms-id)]
    {:t_summary_multiple_sclerosis/events events}))

(pco/defresolver patient->medications
  [{conn :com.eldrix.rsdb/conn} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/medications [:t_medication/date_from
                                          :t_medication/date_to
                                          :t_medication/date_from_accuracy
                                          :t_medication/date_to_accuracy
                                          :t_medication/indication
                                          :t_medication/medication_concept_fk
                                          {:t_medication/medication [:info.snomed.Concept/id]}
                                          :t_medication/more_information
                                          :t_medication/temporary_stop
                                          :t_medication/reason_for_stopping
                                          :t_medication/dose
                                          :t_medication/frequency
                                          :t_medication/units
                                          :t_medication/as_required
                                          :t_medication/route
                                          :t_medication/type
                                          :t_medication/prescriptions]}]}
  (let [medication (db/execute! conn (sql/format {:select [:*]
                                                  :from   [:t_medication]
                                                  :where  [:= :patient_fk patient-id]}))]
    {:t_patient/medications
     (map #(assoc % :t_medication/medication {:info.snomed.Concept/id (:t_medication/medication_concept_fk %)}) medication)}))

(def address-properties [:t_address/address1
                         :t_address/address2
                         :t_address/address3
                         :t_address/address4
                         :t_address/date_from
                         :t_address/date_to
                         :t_address/housing_concept_fk
                         :t_address/postcode
                         :t_address/ignore_invalid_address])

(pco/defresolver patient->addresses
  [{:com.eldrix.rsdb/keys [conn]} {id :t_patient/id}]
  {::pco/output [{:t_patient/addresses address-properties}]}
  {:t_patient/addresses (patients/fetch-patient-addresses conn id)})

(pco/defresolver patient->address
  "Returns the current address, or the address for the specified date.
  Will make use of existing data in t_patient/addresses, if key exists.
  This resolver takes an optional parameter :date. If provided, the address
  for the specified date will be given.
  Parameters:
  - :date - a ISO LOCAL DATE string e.g \"2020-01-01\" or an instance of
            java.time.LocalDate."
  [{conn :com.eldrix.rsdb/conn :as env} {:t_patient/keys [id addresses]}]
  {::pco/input  [:t_patient/id
                 (pco/? :t_patient/addresses)]
   ::pco/output [:t_patient/address address-properties]}
  (let [date (:date (pco/params env))                       ;; doesn't matter if nil
        date' (cond (nil? date) nil
                    (string? date) (LocalDate/parse date)
                    :else date)
        addresses' (or addresses (patients/fetch-patient-addresses conn id))]
    {:t_patient/address (patients/address-for-date addresses' date')}))

(def lsoa-re #"^[a-zA-Z]\d{8}$")

(pco/defresolver address->stored-lsoa
  "Returns a stored LSOA for the address specified.

  In general, LSOA should be determined by navigating to the PCD2 postal code
  and then to the LSOA wanted, such as LSOA11.

  This resolver returns an LSOA if one is stored in the address itself.
  Currently, we simply look in the address1 field for a match, as we cannot
  currently make database schema changes from this application. In the future,
  this resolver could also use a dedicated stored LSOA field without clients
  needing to know about the implementation change."
  [{:t_address/keys [address1]}]
  {::pco/output [:t_address/lsoa]}
  (when (re-matches lsoa-re address1)
    {:t_address/lsoa address1}))

(pco/defresolver address->housing
  [{concept-id :t_address/housing_concept_fk}]
  {::pco/output [{:t_address/housing [:info.snomed.Concept/id]}]}
  (when concept-id {:t_address/housing {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient->episodes
  [{conn :com.eldrix.rsdb/conn} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/episodes [:t_episode/date_discharge
                                       :t_episode/date_referral
                                       :t_episode/date_registration
                                       :t_episode/status
                                       :t_episode/discharge_user_fk
                                       :t_episode/id
                                       :t_episode/notes
                                       :t_episode/project_fk
                                       :t_episode/referral_user_fk
                                       :t_episode/registration_user_fk
                                       :t_episode/stored_pseudonym
                                       :t_episode/external_identifier]}]}
  {:t_patient/episodes (->> (jdbc/execute! conn (sql/format {:select   [:*]
                                                             :from     [:t_episode]
                                                             :where    [:= :patient_fk patient-id]
                                                             :order-by [[:t_episode/date_registration :asc]
                                                                        [:t_episode/date_referral :asc]
                                                                        [:t_episode/date_discharge :asc]]}))
                            (map #(assoc % :t_episode/status (projects/episode-status %))))})


(def project-properties
  [:t_project/id :t_project/name :t_project/title
   :t_project/long_description
   :t_project/type :t_project/date_from :t_project/date_to
   :t_project/exclusion_criteria :t_project/inclusion_criteria
   :t_project/address1 :t_project/address2 :t_project/address3
   :t_project/address4 :t_project/postcode
   :t_project/parent_project_fk
   :t_project/virtual :t_project/can_own_equipment
   :t_project/specialty_concept_fk
   :t_project/care_plan_information
   :t_project/is_private])

(pco/defresolver episode->project
  [{conn :com.eldrix.rsdb/conn} {project-id :t_episode/project_fk}]
  {::pco/output [{:t_episode/project project-properties}]}
  {:t_episode/project (db/execute-one! conn (sql/format {:select [:*]
                                                         :from   [:t_project]
                                                         :where  [:= :id project-id]}))})

(pco/defresolver project-by-identifier
  [{conn :com.eldrix.rsdb/conn} {project-id :t_project/id}]
  {::pco/output [:t_project/id :t_project/name
                 :t_project/title :t_project/long_description
                 :t_project/type
                 :t_project/date_from :t_project/date_to
                 :t_project/exclusion_criteria :t_project/inclusion_criteria
                 {:t_project/administrator_user [:t_user/id]}
                 :t_project/address1 :t_project/address2
                 :t_project/address3 :t_project/address4
                 :t_project/postcode
                 :t_project/ethics
                 {:t_project/parent_project [:t_project/id]}
                 :t_project/virtual
                 :t_project/can_own_equipment
                 {:t_project/specialty [:info.snomed.Concept/id]}
                 :t_project/advertise_to_all
                 :t_project/care_plan_information
                 :t_project/is_private]}
  (when-let [p (projects/fetch-project conn project-id)]
    (cond-> p
            (:t_project/administrator_user_fk p)
            (assoc :t_project/administrator_user {:t_user/id (:t_project/administrator_user_fk p)})
            (:t_project/parent_project_fk p)
            (assoc :t_project/parent_project {:t_project/id (:t_project/parent_project_fk p)}))))

(pco/defresolver project->count_registered_patients         ;; TODO: should include child projects?
  [{conn :com.eldrix.rsdb/conn} {project-id :t_project/id}]
  {::pco/output [:t_project/count_registered_patients]}
  {:t_project/count_registered_patients (projects/count-registered-patients conn [project-id])})

(pco/defresolver project->count_pending_referrals           ;; TODO: should include child projects?
  [{conn :com.eldrix.rsdb/conn} {project-id :t_project/id}]
  {::pco/output [:t_project/count_pending_referrals]}
  {:t_project/count_pending_referrals (projects/count-pending-referrals conn [project-id])})

(pco/defresolver project->count_discharged_episodes         ;; TODO: should include child projects?
  [{conn :com.eldrix.rsdb/conn} {project-id :t_project/id}]
  {::pco/output [:t_project/count_registered_patients]}
  {:t_project/count_discharged_episodes (projects/count-discharged-episodes conn [project-id])})

(pco/defresolver project->slug
  [{title :t_project/title}]
  {::pco/output [:t_project/slug]}
  {:t_project/slug (projects/make-slug title)})

(pco/defresolver project->active?
  [{date-from :t_project/date_from date-to :t_project/date_to :as project}]
  {::pco/output [:t_project/active?]}
  {:t_project/active? (projects/active? project)})

(pco/defresolver project->long-description-text
  [{desc :t_project/long_description}]
  {::pco/output [:t_project/long_description_text]}
  {:t_project/long_description_text (html->text desc)})

(pco/defresolver project->encounter_templates
  [{conn :com.eldrix.rsdb/conn} {project-id :t_project/id}]
  {::pco/output [{:t_project/encounter_templates [:t_encounter_template/id
                                                  :t_encounter_template/encounter_type_fk
                                                  :t_encounter_template/is_deleted
                                                  :t_encounter_template/title
                                                  :t_encounter_type/id
                                                  {:t_encounter_template/encounter_type [:t_encounter_type/id]}]}]}
  {:t_project/encounter_templates
   (->> (db/execute! conn (sql/format {:select [:id :encounter_type_fk :is_deleted :title]
                                       :from   [:t_encounter_template]
                                       :where  [:= :project_fk project-id]}))
        (map #(let [encounter-type-id (:t_encounter_template/encounter_type_fk %)]
                (assoc % :t_encounter_type/id encounter-type-id
                         :t_encounter_template/encounter_type {:t_encounter_type/id encounter-type-id}))))})

(pco/defresolver project->parent
  [{parent-id :t_project/parent_project_fk}]
  {::pco/output [{:t_project/parent [:t_project/id]}]}
  {:t_project/parent {:t_project/id parent-id}})

(pco/defresolver project->all-parents
  [{conn :com.eldrix.rsdb/conn} {project-id :t_project/id}]
  {::pco/output [{:t_project/all-parents [:t_project/id]}]}
  {:t_project/all-parents (projects/all-parents conn project-id)})

(pco/defresolver project->all-children
  [{conn :com.eldrix.rsdb/conn} {project-id :t_project/id}]
  {::pco/output [{:t_project/all-children [:t_project/id]}]}
  {:t_project/all-parents (projects/all-children conn project-id)})

(pco/defresolver project->specialty
  [{specialty-concept-fk :t_project/specialty_concept_fk}]
  {::pco/output [{:t_project/specialty [:info.snomed.Concept/id]}]}
  {:t_project/specialty {:info.snomed.Concept/id specialty-concept-fk}})

(pco/defresolver project->common-concepts
  "Resolve common concepts for the project, optionally filtering by a SNOMED
  expression (ECL)."
  [{conn :com.eldrix.rsdb/conn hermes :com.eldrix/hermes :as env} {:t_project/keys [id]}]
  {::pco/input  [:t_project/id]
   ::pco/output [{:t_project/common_concepts [:info.snomed.Concept/id]}]}
  (let [concept-ids (projects/common-concepts conn id)
        ecl (:ecl (pco/params env))]
    (when (seq concept-ids)
      (if (str/blank? ecl)
        (reduce (fn [acc v] (conj acc {:info.snomed.Concept/id v})) [] concept-ids)
        (let [concepts (str/join " OR " concept-ids)]
          (->> (com.eldrix.hermes.core/expand-ecl hermes (str "(" concepts ") AND " ecl))
               (map #(hash-map :info.snomed.Concept/id (:conceptId %)))))))))

(pco/defresolver project->users
  [{conn :com.eldrix.rsdb/conn} {id :t_project/id}]
  {::pco/output [{:t_project/users [:t_user/id]}]}
  {:t_project/users (projects/fetch-users conn id)})

(pco/defresolver patient->encounters
  [{:com.eldrix.rsdb/keys [conn]} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/encounters [:t_encounter/id
                                         :t_encounter/date_time
                                         :t_encounter/active
                                         :t_encounter/hospital_fk
                                         :t_encounter/ward
                                         :t_encounter/episode_fk
                                         :t_encounter/consultant_user_fk
                                         :t_encounter/encounter_template_fk
                                         :t_encounter/notes]}]}
  {:t_patient/encounters (jdbc/execute! conn (sql/format {:select   [:*]
                                                          :from     [:t_encounter]
                                                          :where    [:= :patient_fk patient-id]
                                                          :order-by [[:date_time :desc]]}))})

(pco/defresolver encounter->users
  "Return the users for the encounter.
  We flatten the relationship here, avoiding the join table."
  [{conn :com.eldrix.rsdb/conn} {encounter-id :t_encounter/id}]
  {::pco/output [{:t_encounter/users [:t_user/id]}]}
  {:t_encounter/users
   (->> (jdbc/execute! conn (sql/format {:select [:userid]
                                         :from   [:t_encounter_user]
                                         :where  [:= :encounterid encounter-id]}))
        (map :t_encounter_user/userid)
        (map #(hash-map :t_user/id %)))})

(pco/defresolver encounter->hospital
  [{hospital-id :t_encounter/hospital_fk}]
  {::pco/output [{:t_encounter/hospital [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id]}]}
  (when hospital-id {:t_encounter/hospital {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id hospital-id}}))

(pco/defresolver encounter->encounter_template
  [{:com.eldrix.rsdb/keys [conn]} {encounter-template-fk :t_encounter/encounter_template_fk}]
  {::pco/output [{:t_encounter/encounter_template [:t_encounter_template/id
                                                   :t_encounter_template/encounter_type_fk]}]}
  {:t_encounter/encounter_template (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_template]
                                                                        :where  [:= :id encounter-template-fk]}))})

(pco/defresolver encounter_template->encounter_type
  [{:com.eldrix.rsdb/keys [conn]} {encounter-type-id :t_encounter_template/encounter_type_fk}]
  {::pco/output [{:t_encounter_template/encounter_type [:t_encounter_type/id
                                                        :t_encounter_type/name
                                                        :t_encounter_type/seen_in_person]}]}
  {:t_encounter_template/encounter_type (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_type] :where [:= :id encounter-type-id]}))})

(def user-properties
  [:t_user/username
   :t_user/title
   :t_user/first_names
   :t_user/last_name
   :t_user/postnomial
   :t_user/custom_initials
   :t_user/email
   :t_user/custom_job_title
   :t_user/job_title_fk
   :t_user/send_email_for_messages
   :t_user/authentication_method
   :t_user/professional_registration])

(pco/defresolver user-by-username
  [{conn :com.eldrix.rsdb/conn} {username :t_user/username}]
  {::pco/output user-properties}
  (users/fetch-user conn username))

(pco/defresolver user-by-id
  [{conn :com.eldrix.rsdb/conn} {id :t_user/id}]
  {::pco/output user-properties}
  (users/fetch-user-by-id conn id))


(pco/defresolver user-by-nadex
  "Resolves rsdb user properties from a NADEX username if that user is
   registered with rsdb with NADEX authentication."
  [{conn :com.eldrix.rsdb/conn} {username :wales.nhs.nadex/sAMAccountName}]
  {::pco/output user-properties}
  (when-let [user (users/fetch-user conn username)]
    (when (= (:t_user/authentication_method user) "NADEX")
      user)))

(pco/defresolver user->photo
  [{conn :com.eldrix.rsdb/conn} {:t_user/keys [username]}]
  {::pco/output [{:t_user/photo [:t_photo/data
                                 :t_photo/mime_type]}]}
  (when-let [photo (com.eldrix.pc4.server.rsdb.users/fetch-user-photo conn username)]
    {:t_user/photo {:t_photo/data      (.encodeToString (Base64/getEncoder) (:data photo))
                    :t_photo/mime_type (:mimetype photo)}}))

(pco/defresolver user->full-name
  [{:t_user/keys [title first_names last_name]}]
  {::pco/output [:t_user/full_name]}
  {:t_user/full_name (str title " " first_names " " last_name)})

(pco/defresolver user->initials
  [{:t_user/keys [first_names last_name custom_initials]}]
  {::pco/output [:t_user/initials]}
  {:t_user/initials (if custom_initials
                      custom_initials
                      (str (apply str (map first (str/split first_names #"\s"))) (first last_name)))})

(pco/defresolver user->nadex
  "Turn rsdb-derived user data into a representation from NADEX. This means
  other resolvers (e.g. providing a FHIR view) can work on rsdb data!"
  [{:t_user/keys [username first_names last_name title custom_job_title email professional_registration]
    job_title    :t_job_title/name
    regulator    :t_professional_registration_authority/abbreviation}]
  {::pco/output [:wales.nhs.nadex/sAMAccountName
                 :wales.nhs.nadex/sn
                 :wales.nhs.nadex/givenName
                 :wales.nhs.nadex/personalTitle
                 :wales.nhs.nadex/mail
                 :urn:oid:2.5.4/commonName
                 {:wales.nhs.nadex/professionalRegistration [:regulator :code]}
                 :wales.nhs.nadex/title]}
  {:wales.nhs.nadex/sAMAccountName           username
   :wales.nhs.nadex/sn                       last_name
   :wales.nhs.nadex/givenName                first_names
   :wales.nhs.nadex/personalTitle            title
   :urn:oid:2.5.4/commonName                 (str/join " " [title first_names last_name])
   :wales.nhs.nadex/mail                     email
   :wales.nhs.nadex/professionalRegistration {:regulator regulator :code professional_registration}
   :wales.nhs.nadex/title                    (or custom_job_title job_title)})

(pco/defresolver user->fhir-name
  [{:t_user/keys [first_names last_name title postnomial]}]
  {::pco/output [{:org.hl7.fhir.Practitioner/name [:org.hl7.fhir.HumanName/prefix
                                                   :org.hl7.fhir.HumanName/given
                                                   :org.hl7.fhir.HumanName/family
                                                   :org.hl7.fhir.HumanName/suffix
                                                   :org.hl7.fhir.HumanName/use]}]}
  {:org.hl7.fhir.Practitioner/name [{:org.hl7.fhir.HumanName/prefix [title]
                                     :org.hl7.fhir.HumanName/given  (str/split first_names #"\s")
                                     :org.hl7.fhir.HumanName/family last_name
                                     :org.hl7.fhir.HumanName/suffix (str/split postnomial #"\s")
                                     :org.hl7.fhir.HumanName/use    :org.hl7.fhir.name-use/usual}]})

(pco/defresolver user->active-projects
  [{conn :com.eldrix.rsdb/conn} {username :t_user/username}]
  {::pco/output [{:t_user/active_projects [:t_project/id]}]}
  {:t_user/active_projects (filter projects/active? (users/projects conn username))})

(pco/defresolver user->latest-news
  [{conn :com.eldrix.rsdb/conn} {username :t_user/username}]
  {::pco/output [{:t_user/latest_news [:t_news/id
                                       :t_news/title
                                       :t_news/body
                                       {:t_news/author [:t_user/id]}]}]}
  {:t_user/latest_news (users/fetch-latest-news conn username)})

(def sex->fhir-patient
  {"MALE"    :org.hl7.fhir.administrative-gender/male
   "FEMALE"  :org.hl7.fhir.administrative-gender/female
   "UNKNOWN" :org.hl7.fhir.administrative-gender/unknown})

(pco/defresolver patient->fhir-gender
  [{sex :t_patient/sex}]
  {:org.hl7.fhir.Patient/gender (get sex->fhir-patient sex)})

(pco/defresolver patient->fhir-human-name
  [{:t_patient/keys [title first_names last_name]}]
  {:org.hl7.fhir.Patient/name [{:org.hl7.fhir.HumanName/prefix (str/split title #" ")
                                :org.hl7.fhir.HumanName/given  (str/split first_names #" ")
                                :org.hl7.fhir.HumanName/family last_name}]})

(comment
  (s/explain-data ::register-patient-by-pseudonym {:user-id    5
                                                   :project-id 1
                                                   :nhs-number "111 111 1111"
                                                   :sex        :MALE
                                                   :date-birth (LocalDate/of 1970 1 1)})
  )

(pco/defmutation register-patient-by-pseudonym!
  "Register a legacy pseudonymous patient. This will be deprecated in the
  future.
  TODO: switch to more pluggable and routine coercion of data, rather than
  managing by hand
  TODO: fix :authenticated-user so it includes a fuller dataset for each request
  - we are already fetching user once, so just include properties and pass along"
  [{conn    :com.eldrix.rsdb/conn
    config  :com.eldrix.rsdb/config
    manager :authorization-manager
    user    :authenticated-user}
   {:keys [project-id nhs-number gender date-birth] :as params}]
  {::pco/op-name 'pc4.rsdb/register-patient-by-pseudonym
   ::pco/output  [:t_patient/patient_identifier
                  :t_episode/stored_pseudonym
                  :t_episode/project_fk]}
  (log/info "register patient by pseudonym: " {:user user :params params})
  (if-not (and manager (auth/authorized? manager #{project-id} :PATIENT_REGISTER))
    (throw (ex-info "Not authorized" {:authorization-manager manager}))
    (if-let [global-salt (:legacy-global-pseudonym-salt config)]
      (let [params' (assoc params :user-id (:t_user/id (users/fetch-user conn (:value user))) ;; TODO: remove fetch
                                  :salt global-salt
                                  :nhs-number (str/replace nhs-number #"\s" "") ;; TODO: better automated coercion
                                  :date-birth (LocalDate/parse date-birth))] ;; TODO: better automated coercion
        (if (s/valid? ::register-patient-by-pseudonym params')
          (projects/register-legacy-pseudonymous-patient conn params')
          (log/error "invalid call" (s/explain-data ::register-patient-by-pseudonym params'))))
      (log/error "unable to register patient by pseudonym; missing global salt: check configuration"
                 {:expected [:com.eldrix.rsdb/config :legacy-global-pseudonym-salt]
                  :config   config}))))

(pco/defmutation search-patient-by-pseudonym
  "Search for a patient using a pseudonymous project-specific identifier.
  This uses the legacy approach, which *will* be deprecated."
  [{conn :com.eldrix.rsdb/conn} {:keys [project-id pseudonym] :as params}]
  {::pco/op-name 'pc4.rsdb/search-patient-by-pseudonym
   ::pco/output  [:t_patient/patient_identifier
                  :t_episode/stored_pseudonym
                  :t_episode/project_fk]}
  (log/debug "search-patient-by-pseudonym" params)
  (projects/search-by-project-pseudonym conn project-id pseudonym))

(defn guard-can-for-patient?                                ;; TODO: turn into a macro for defmutation?
  [{conn :com.eldrix.rsdb/conn manager :authorization-manager} patient-identifier permission]
  (let [project-ids (patients/active-project-identifiers conn patient-identifier)]
    (when-not (auth/authorized? manager project-ids permission)
      (throw (ex-info "You are not authorised to perform this operation" {:patient-identifier patient-identifier
                                                                          :permission         permission})))))

(s/def ::save-diagnosis
  (s/and
    (s/keys :req [:t_patient/patient_identifier
                  :t_diagnosis/diagnosis
                  :t_diagnosis/status]
            :opt [:t_diagnosis/date_onset
                  :t_diagnosis/date_diagnosis
                  :t_diagnosis/date_onset_accuracy
                  :t_diagnosis/date_diagnosis_accuracy
                  :t_diagnosis/date_to
                  :t_diagnosis/date_to_accuracy])
    valid-diagnosis-status?
    ordered-diagnostic-dates?))

(pco/defmutation save-diagnosis!
  [{conn    :com.eldrix.rsdb/conn
    manager :authorization-manager
    user    :authenticated-user
    :as     env} params]
  {::pco/op-name 'pc4.rsdb/save-diagnosis}
  (log/info "save diagnosis request: " params "user: " user)
  (let [params' (assoc params ::user-id (:t_user/id (users/fetch-user conn (:value user))) ;; TODO: remove fetch of user id
                              :t_diagnosis/concept_fk (get-in params [:t_diagnosis/diagnosis :info.snomed.Concept/id]))]
    (if-not (s/valid? ::save-diagnosis params')
      (do (log/error "invalid call" (s/explain-data ::create-diagnosis params'))
          (throw (ex-info "Invalid data" (s/explain-data ::create-diagnosis params'))))
      (do (guard-can-for-patient? env (:t_patient/patient_identifier params) :PATIENT_EDIT)
          (let [diag (if (:t_diagnosis/id params')
                       (patients/update-diagnosis conn params')
                       (patients/create-diagnosis conn params'))]
            (assoc-in diag [:t_diagnosis/diagnosis :info.snomed.Concept/id] (:t_diagnosis/concept_fk diag)))))))


(s/def ::save-medication
  (s/and
    (s/keys :req [::user-id
                  :t_patient/patient_identifier]
            :opt [:t_medication/id
                  :t_medication/medication
                  :t_medication/date_from
                  :t_medication/date_to])))

(pco/defmutation save-medication!
  [{conn    :com.eldrix.rsdb/conn
    manager :authorization-manager
    user    :authenticated-user
    :as     env} params]
  {::pco/op-name 'pc4.rsdb/save-medication}
  (log/info "save medication request: " params "user: " user)
  (let [params' (assoc params ::user-id (:t_user/id (users/fetch-user conn (:value user))) ;; TODO: remove fetch of user id
                              :t_medication/medication_concept_fk (get-in params [:t_medication/medication :info.snomed.Concept/id]))]
    (if-not (s/valid? ::save-medication params')
      (do (log/error "invalid call" (s/explain-data ::save-medication params'))
          (throw (ex-info "Invalid data" (s/explain-data ::save-medication params'))))
      (do (guard-can-for-patient? env (:t_patient/patient_identifier params) :PATIENT_EDIT)
          (let [med (if (:t_medication/id params')
                      (if (:t_medication/medication_concept_fk params')
                        (patients/update-medication conn params')
                        (patients/delete-medication conn params'))
                      (patients/create-medication conn params'))]
            (assoc-in med [:t_medication/medication :info.snomed.Concept/id] (:t_medication/medication_concept_fk med)))))))

(s/def ::save-ms-diagnosis
  (s/keys :req [:t_user/id
                :t_ms_diagnosis/id
                :t_patient/patient_identifier]))

(pco/defmutation save-patient-ms-diagnosis!                 ;; TODO: could update main diagnostic list...
  [{conn    :com.eldrix.rsdb/conn
    manager :authorization-manager
    user    :authenticated-user
    :as     env} {patient-identifier :t_patient/patient_identifier :as params}]
  {::pco/op-name 'pc4.rsdb/save-ms-diagnosis}
  (log/info "save ms diagnosis:" params " user:" user)
  (let [params' (assoc params :t_user/id (:t_user/id (users/fetch-user conn (:value user))))]
    (if-not (s/valid? ::save-ms-diagnosis params')
      (do (log/error "invalid call" (s/explain-data ::save-ms-diagnosis params'))
          (throw (ex-info "Invalid data" (s/explain-data ::save-ms-diagnosis params'))))
      (do (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
          (patients/save-ms-diagnosis! conn params')
          (patient->summary-multiple-sclerosis env params)))))

(s/def ::save-pseudonymous-postal-code
  (s/keys :req [:t_patient/patient_identifier
                :uk.gov.ons.nhspd/PCD2]))

(pco/defmutation save-pseudonymous-patient-postal-code!
  [{conn :com.eldrix.rsdb/conn
    ods  :com.eldrix.clods.graph/svc
    user :authenticated-user}
   {patient-identifier :t_patient/patient_identifier
    postcode           :uk.gov.ons.nhspd/PCD2 :as params}]
  {::pco/op-name 'pc4.rsdb/save-pseudonymous-patient-postal-code}
  (log/info "saving pseudonymous postal code" {:params params :ods ods})
  (if-not (s/valid? ::save-pseudonymous-postal-code params)
    (throw (ex-info "invalid request" (s/explain-data ::save-pseudonymous-postal-code params)))
    (if (str/blank? postcode)
      (patients/save-pseudonymous-patient-lsoa! conn {:t_patient/patient_identifier patient-identifier
                                                      :uk.gov.ons.nhspd/LSOA11      ""})
      (let [pc (com.eldrix.clods.core/fetch-postcode ods postcode)]
        (patients/save-pseudonymous-patient-lsoa! conn {:t_patient/patient_identifier patient-identifier
                                                        :uk.gov.ons.nhspd/LSOA11      (get pc "LSOA11")})))))

(s/def ::save-ms-event
  (s/keys :req [:t_ms_event/date
                :t_ms_event_type/id
                :t_ms_event/impact
                :t_ms_event/summary_multiple_sclerosis_fk]))

(pco/defmutation save-ms-event!
  [{conn    :com.eldrix.rsdb/conn
    manager :authorization-manager
    user    :authenticated-user
    :as     env} params]
  {::pco/op-name 'pc4.rsdb/save-ms-event}
  (log/info "save ms event request: " params "user: " user)
  (if-not (s/valid? ::save-ms-event params)
    (do (log/error "invalid call" (s/explain-data ::save-ms-event params))
        (throw (ex-info "Invalid data" (s/explain-data ::save-ms-event params))))
    (let [patient-identifier (or (:t_patient/patient-identifier params)
                                 (patients/patient-identifier-for-ms-event conn params))]
      (do (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
          (patients/save-ms-event! conn (-> params
                                            (dissoc :t_patient/patient_identifier
                                                    :t_ms_event_type/id
                                                    :t_ms_event_type/abbreviation
                                                    :t_ms_event_type/name
                                                    :t_ms_event/is_relapse)
                                            (assoc :t_ms_event/ms_event_type_fk (:t_ms_event_type/id params))))))))

(s/def ::delete-ms-event
  (s/keys :req [:t_user/id
                :t_ms_event/id]))

(pco/defmutation delete-ms-event!
  [{conn    :com.eldrix.rsdb/conn
    manager :authorization-manager
    user    :authenticated-user
    :as     env} {ms-event-id :t_ms_event/id :as params}]
  {::pco/op-name 'pc4.rsdb/delete-ms-event}
  (log/info "delete ms event:" params " user:" user)
  (let [params' (assoc params :t_user/id (:t_user/id (users/fetch-user conn (:value user))))] ;; TODO: need a better way than this...
    (if-not (s/valid? ::delete-ms-event params')
      (do (log/error "invalid call" (s/explain-data ::delete-ms-event params'))
          (throw (ex-info "Invalid data" (s/explain-data ::delete-ms-event params'))))
      (when-let [patient-identifier (patients/patient-identifier-for-ms-event conn params')]
        (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
        (patients/delete-ms-event! conn params')))))



(pco/defresolver multiple-sclerosis-diagnoses
  [{conn :com.eldrix.rsdb/conn} _]
  {::pco/output [{:com.eldrix.rsdb/all-ms-diagnoses [:t_ms_diagnosis/id
                                                     :t_ms_diagnosis/name]}]}
  {:com.eldrix.rsdb/all-ms-diagnoses (db/execute! conn
                                                  (sql/format {:select [:id :name]
                                                               :from   [:t_ms_diagnosis]}))})
(pco/defresolver all-ms-event-types
  [{conn :com.eldrix.rsdb/conn} _]
  {::pco/output [{:com.eldrix.rsdb/all-ms-event-types [:t_ms_event_type/id
                                                       :t_ms_event_type/abbreviation
                                                       :t_ms_event_type/name]}]}
  {:com.eldrix.rsdb/all-ms-event-types (db/execute! conn
                                                    (sql/format {:select [:id :abbreviation :name]
                                                                 :from   [:t_ms_event_type]}))})

(def all-resolvers
  [patient-by-identifier
   patient->hospitals
   patient-hospital->hospital
   patient->country-of-birth
   patient->ethnic-origin
   patient->racial-group
   patient->occupation
   patient->surgery
   patient->diagnoses
   patient->summary-multiple-sclerosis
   summary-multiple-sclerosis->events
   patient->medications
   patient->addresses
   patient->address
   (pbir/alias-resolver :t_address/postcode :uk.gov.ons.nhspd/PCDS)
   address->housing
   address->stored-lsoa
   patient->episodes
   episode->project
   project-by-identifier
   project->parent
   project->specialty
   project->common-concepts
   project->active?
   project->slug
   project->long-description-text
   project->encounter_templates
   project->users
   project->count_registered_patients
   project->count_pending_referrals
   project->count_discharged_episodes
   project->all-children
   project->all-parents
   patient->encounters
   encounter->users
   encounter->encounter_template
   encounter->hospital
   encounter_template->encounter_type
   user-by-username
   user-by-id
   user-by-nadex
   user->nadex
   user->fhir-name
   user->photo
   user->full-name
   user->initials
   user->active-projects
   user->latest-news
   patient->fhir-human-name
   patient->fhir-gender
   multiple-sclerosis-diagnoses
   all-ms-event-types
   ;; mutations - VERBS
   register-patient-by-pseudonym!
   search-patient-by-pseudonym
   save-diagnosis!
   save-medication!
   save-patient-ms-diagnosis!
   save-pseudonymous-patient-postal-code!
   save-ms-event!
   delete-ms-event!])

(comment
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 1}))
  (jdbc/execute! conn ["select id from t_encounter where patient_fk=?" 1726])
  (jdbc/execute! conn
                 ["select t_form_edss.*,t_encounter.date_time,t_encounter.is_deleted from t_form_edss,t_encounter where t_form_edss.encounter_fk=t_encounter.id and encounter_fk in (select id from t_encounter where patient_fk=?);" 1726])

  (jdbc/execute! conn (sql/format {:select [:*]
                                   :from   [:t_patient]
                                   :where  [:= :id 14232]}))

  (def env (-> (pci/register all-resolvers)
               (assoc :com.eldrix.rsdb/conn conn)))
  (p.eql/process env [{[:t_patient/patient_identifier 6175]
                       [:t_patient/summary_multiple_sclerosis]}])
  (p.eql/process env [{:com.eldrix.rsdb/all-ms-diagnoses [:t_ms_diagnosis/name :t_ms_diagnosis/id]}])
  (p.eql/process env [{}])
  (patient-by-identifier {:com.eldrix.rsdb/conn conn} {:t_patient/patient_identifier 12999})
  (p.eql/process env [{[:t_patient/patient_identifier 17371] [:t_patient/id
                                                              :t_patient/email
                                                              :t_patient/first_names
                                                              :t_patient/last_name
                                                              :t_patient/status
                                                              :t_patient/surgery
                                                              :t_patient/alerts
                                                              `(:t_patient/address {:date ~"2010-06-01"})]}])

  (p.eql/process env
                 [{[:t_patient/patient_identifier 17371]
                   [:t_patient/id
                    :t_patient/first_names
                    :t_patient/last_name
                    :t_patient/status
                    :t_patient/surgery
                    {:t_patient/address [:uk.gov.ons.nhspd/PCDS]}
                    {:t_patient/episodes [:t_episode/date_registration
                                          :t_episode/date_discharge
                                          :t_episode/project_fk
                                          {:t_episode/project [:t_project/title]}]}]}])

  (time (p.eql/process env
                       [{[:t_patient/patient_identifier 12182]
                         [:t_patient/id
                          :t_patient/first_names
                          :t_patient/last_name
                          :t_patient/status
                          :t_patient/surgery
                          {:t_patient/encounters [:t_encounter/date_time
                                                  :t_encounter/is_deleted
                                                  :t_encounter/hospital
                                                  {:t_encounter/users [:t_user/id
                                                                       :t_user/initials
                                                                       :t_user/full_name]}]}]}]))

  (p.eql/process env
                 [{[:t_patient/patient_identifier 12182]
                   [:t_patient/id
                    :t_patient/first_names
                    :t_patient/last_name
                    :t_patient/status
                    :t_patient/surgery
                    {:t_patient/hospitals [:t_patient_hospital/patient_identifier
                                           :t_patient_hospital/hospital]}]}])

  (db/parse-entity (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_template]
                                                        :where  [:= :id 15]})))
  (sql/format {:select [[:postcode_raw :postcode]] :from [:t_address]})

  (def ^LocalDate date (LocalDate/now))
  (fetch-patient-addresses conn 119032)
  (address-for-date (fetch-patient-addresses conn 7382))

  (fetch-patient-addresses conn 119032)
  (episode->project {:com.eldrix.rsdb/conn conn} {:t_episode/project_fk 34})

  (def user (jdbc/execute-one! conn (sql/format {:select [:*]
                                                 :from   [:t_user]
                                                 :where  [:= :username "system"]})))
  (users/fetch-user conn "ma090906")
  user


  (time (map #(assoc % :t_project/slug (projects/make-slug (:t_project/title %)))
             (jdbc/execute! conn (sql/format {:select [:t_project/id :t_project/title :t_project/name]
                                              :from   [:t_project]}))))


  (user-by-id {:com.eldrix.rsdb/conn conn} {:t_user/id 12})
  (project->all-parents {:com.eldrix.rsdb/conn conn} {:t_project/id 5})

  (require '[com.eldrix.pc4.server.rsdb.patients])
  (def project-ids (com.eldrix.pc4.server.rsdb.patients/active-project-identifiers conn 14032))
  (def manager (users/make-authorization-manager conn "ma090906"))
  (def sys-manager (users/make-authorization-manager conn "system"))
  (def unk-manager (users/make-authorization-manager conn "unknown"))
  (auth/authorized? manager project-ids :PATIENT_VIEW)
  (auth/authorized? sys-manager project-ids :PATIENT_VIEW)
  (auth/authorized? unk-manager project-ids :PATIENT_VIEW)
  (auth/authorized? manager project-ids :BIOBANK_CREATE_LOCATION)
  )
