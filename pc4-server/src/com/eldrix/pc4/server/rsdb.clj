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
            [com.eldrix.pc4.server.rsdb.db :as parse]
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
            [malli.core :as m])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time LocalDate)
           (java.util Base64)
           (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)))

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
  (parse/parse-entity (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_patient] :where [:= :patient_identifier patient_identifier]}))))

(pco/defresolver patient->hospitals
  [{conn :com.eldrix.rsdb/conn} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/hospitals [:t_patient_hospital/hospital_fk
                                        :t_patient_hospital/patient_identifier
                                        :t_patient_hospital/authoritative]}]}
  {:t_patient/hospitals (->> (jdbc/execute! conn (sql/format {:select [:*]
                                                              :from   [:t_patient_hospital]
                                                              :where  [:= :patient_fk patient-id]}))
                             (map parse/parse-entity))})

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

(defn fetch-patient-addresses
  "Returns patient addresses.
  The backend database stores the from and to dates as timestamps without
  timezones, so we convert to instances of java.time.LocalDate."
  [conn patient-id]
  (->> (jdbc/execute! conn (sql/format {:select   [:address1 :address2 :address3 :address4 [:postcode_raw :postcode]
                                                   :date_from :date_to :housing_concept_fk :ignore_invalid_address]
                                        :from     [:t_address]
                                        :where    [:= :patient_fk patient-id]
                                        :order-by [[:date_to :desc] [:date_from :desc]]}))
       (map parse/parse-entity)))

(defn address-for-date
  "Determine the address on a given date, the current date if none given."
  ([sorted-addresses]
   (address-for-date sorted-addresses nil))
  ([sorted-addresses ^LocalDate date]
   (->> sorted-addresses
        (filter #(dates/in-range? (:t_address/date_from %) (:t_address/date_to %) date))
        first)))

(pco/defresolver patient->addresses
  [{:com.eldrix.rsdb/keys [conn]} {id :t_patient/id}]
  {::pco/output [{:t_patient/addresses address-properties}]}
  {:t_patient/addresses (fetch-patient-addresses conn id)})

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
        addresses' (or addresses (fetch-patient-addresses conn id))]
    {:t_patient/address (address-for-date addresses' date')}))

(pco/defresolver address->housing
  [{concept-id :t_address/housing_concept_fk}]
  {::pco/output [{:t_address/housing [:info.snomed.Concept/id]}]}
  (when concept-id {:t_address/housing {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient->episodes
  [{conn :com.eldrix.rsdb/conn} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/episodes [:t_episode/date_discharge
                                       :t_episode/date_referral
                                       :t_episode/date_registration
                                       :t_episode/discharge_user_fk
                                       :t_episode/id
                                       :t_episode/notes
                                       :t_episode/project_fk
                                       :t_episode/referral_user_fk
                                       :t_episode/registration_user_fk
                                       :t_episode/stored_pseudonym
                                       :t_episode/external_identifier]}]}
  {:t_patient/episodes (jdbc/execute! conn (sql/format {:select [:*]
                                                        :from   [:t_episode]
                                                        :where  [:= :patient_fk patient-id]}))})


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
  {:t_episode/project (parse/parse-entity (jdbc/execute-one! conn (sql/format {:select [:*]
                                                                               :from   [:t_project]
                                                                               :where  [:= :id project-id]})))})

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

(pco/defresolver project->slug
  [{title :t_project/title}]
  {::pco/output [:t_project/slug]}
  {:t_project/slug (projects/make-slug title)})

(pco/defresolver project->long-description-text
  [{desc :t_project/long_description}]
  {::pco.output [:t_project/long_description_text]}
  {:t_project/long_description_text (html->text desc)})

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


(def ^:private register-patient-by-pseudonym-params
  (m/schema [:map
             [:user-id int?]
             [:project-name string?]
             [:nhs-number string?]
             [:sex [:enum :MALE :FEMALE :UNKNOWN]]
             [:date-birth [:fn #(instance? LocalDate %)]]]))

(pco/defmutation register-patient-by-pseudonym
  "Register a legacy pseudonymous patient. This will be deprecated in the
  future."
  [{conn :com.eldrix.rsdb/conn config :com.eldrix.rsdb/config} {:keys [user-id project-name nhs-number sex date-birth] :as params}]
  {::pco/op-name 'pc4.rsdb/register-patient-by-pseudonym
   ::pco/output  [:t_patient/patient_identifier]}
  (if-let [global-salt (:legacy-global-pseudonym-salt config)]
    (if (m/validate register-patient-by-pseudonym-params params)
      (projects/register-legacy-pseudonymous-patient conn (assoc params :salt global-salt))
      (log/error "invalid call" (m/explain register-patient-by-pseudonym-params params)))
    (log/error "unable to register patient by pseudonym; missing global salt"
               {:expected [:com.eldrix.rsdb/config :legacy-global-pseudonym-salt]
                :config   config})))

(pco/defmutation search-patient-by-pseudonym
  "Search for a patient using a pseudonymous project-specific identifier.
  This uses the legacy approach, which *will* be deprecated."
  [{conn :com.eldrix.rsdb/conn} {:keys [project-name pseudonym] :as params}]
  {::pco/op-name 'pc4.rsdb/search-patient-by-pseudonym
   ::pco/output  [:t_patient/patient_identifier]}
  (log/info "search-patient-by-pseudonym" params)
  (when-let [project (projects/project-with-name conn project-name)]
    (projects/search-by-project-pseudonym conn (:t_project/id project) pseudonym)))

(def all-resolvers
  [patient-by-identifier
   patient->hospitals
   patient-hospital->hospital
   patient->country-of-birth
   patient->ethnic-origin
   patient->racial-group
   patient->occupation
   patient->surgery
   patient->medications
   patient->addresses
   patient->address
   (pbir/alias-resolver :t_address/postcode :uk.gov.ons.nhspd/PCDS)
   address->housing
   patient->episodes
   episode->project
   project-by-identifier
   project->parent
   project->specialty
   project->slug
   project->long-description-text
   project->users
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
   patient->fhir-human-name
   patient->fhir-gender
   register-patient-by-pseudonym
   search-patient-by-pseudonym])

(comment

  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 10}))
  (jdbc/execute! conn ["select id from t_encounter where patient_fk=?" 1726])
  (jdbc/execute! conn
                 ["select t_form_edss.*,t_encounter.date_time,t_encounter.is_deleted from t_form_edss,t_encounter where t_form_edss.encounter_fk=t_encounter.id and encounter_fk in (select id from t_encounter where patient_fk=?);" 1726])

  (jdbc/execute! conn (sql/format {:select [:*]
                                   :from   [:t_patient]
                                   :where  [:= :id 14232]}))

  (def env (-> (pci/register all-resolvers)
               (assoc :com.eldrix.rsdb/conn conn)))
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

  (parse-entity (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_template]
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
