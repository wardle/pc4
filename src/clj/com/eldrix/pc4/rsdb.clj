(ns com.eldrix.pc4.rsdb
  "Integration with the rsdb backend.
  `rsdb` is the Apple WebObjects 'legacy' application that was PatientCare v1-3.

  Depending on real-life usage, we could pre-fetch relationships and therefore
  save multiple database round-trips, particularly for to-one relationships.
  Such a change would be trivial because pathom would simply not bother
  trying to resolve data that already exists."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.nhsnumber :as nnn]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.server.api-middleware :as api-middleware]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql]
            [com.eldrix.clods.core :as clods]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.verhoeff :as verhoeff]
            [com.eldrix.pc4.dates :as dates]
            [com.eldrix.pc4.rsdb.auth :as auth]
            [com.eldrix.pc4.rsdb.db :as db]
            [com.eldrix.pc4.rsdb.forms :as forms]
            [com.eldrix.pc4.rsdb.patients :as patients]
            [com.eldrix.pc4.rsdb.projects :as projects]
            [com.eldrix.pc4.rsdb.results :as results]
            [com.eldrix.pc4.rsdb.users :as users])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time LocalDateTime LocalDate)
           (java.util Locale)
           (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)))

(s/def :uk.gov.ons.nhspd/PCD2 string?)
(s/def :info.snomed.Concept/id (s/and int? verhoeff/valid?))
;;
;;
(s/def ::user-id int?)
(s/def ::project-id int?)
(s/def ::nhs-number #(nnn/valid? (nnn/normalise %)))
(s/def ::sex #{:MALE :FEMALE :UNKNOWN})
(s/def ::date-birth #(instance? LocalDate %))
(s/def ::register-patient
  (s/keys :req-un [::project-id ::nhs-number]))
(s/def ::register-patient-by-pseudonym
  (s/keys :req-un [::user-id ::project-id ::nhs-number ::sex ::date-birth]))

(s/def ::save-fn fn?)
(s/def ::params map?)
(s/def ::id-key keyword?)
(s/fdef create-or-save-entity
  :args (s/cat :request (s/keys :req-un [::save-fn ::params ::id-key])))
(defn create-or-save-entity
  "Save an entity which may have a tempid.
  Parameters:
  :save-fn  : function to call with parameters
  :params   : parameters
  :id-key   : keyword of id"
  [{:keys [save-fn params id-key]}]
  (if-let [id (id-key params)]
    (if (tempid/tempid? id)
      (let [result (save-fn (dissoc params id-key))]
        (println "result " {:result result :id-key id-key})
        (println "tempids" {id (id-key result)})
        (assoc result :tempids {id (id-key result)}))
      (save-fn params))
    (throw (ex-info "missing id" {:id-key id-key :params params}))))

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

(pco/defresolver patient->break-glass
  [{session :session} {:t_patient/keys [patient_identifier]}]
  {:t_patient/break_glass (= patient_identifier (:break-glass session))})

(pco/defresolver patient->permissions
  "Return authorization permissions for current user to access given patient."
  [{authenticated-user :session/authenticated-user, conn :com.eldrix.rsdb/conn}
   {:t_patient/keys [patient_identifier break_glass]}]
  {:t_patient/permissions
   (cond
     ;; system user gets all known permissions
     (:t_role/is_system authenticated-user)
     auth/all-permissions
     ;; break-glass provides only "normal user" access
     break_glass
     (:NORMAL_USER auth/permission-sets)
     ;; otherwise, generate permissions based on intersection of patient and user
     :else
     (let [patient-active-project-ids (patients/active-project-identifiers conn patient_identifier)
           roles-by-project-id (:t_user/active_roles authenticated-user) ; a map of project-id to PermissionSets for that project
           roles (reduce-kv (fn [acc _ v] (into acc v)) #{} (select-keys roles-by-project-id patient-active-project-ids))]
       (auth/expand-permission-sets roles)))})

(pco/defresolver project->permissions
  "Return authorization permissions for current user to access given project."
  [{authenticated-user :session/authenticated-user, conn :com.eldrix.rsdb/conn}
   {project-id :t_project/id}]
  {:t_project/permissions
   (if (:t_role/is_system authenticated-user)
     auth/all-permissions
     (let [roles (get (:t_user/active_roles authenticated-user) project-id)]
       (auth/expand-permission-sets roles)))})

(defn wrap-tap-resolver
  "A transform to debug inputs to a resolver"
  [{::pco/keys [op-name] :as resolver}]
  (update resolver ::pco/resolve
          (fn [resolve]
            (fn [env inputs]
              (tap> {:op-name  op-name,
                     :resolver resolver, :env env, :inputs inputs})
              (resolve env inputs)))))

(defn make-wrap-patient-authorize
  "A transform to add authorization to a resolver for a patient. Defaults to
  checking permission :PATIENT_VIEW."
  ([] (make-wrap-patient-authorize {:permission :PATIENT_VIEW}))
  ([{:keys [permission]}]
   (fn [resolver]
     (-> resolver
         (update ::pco/input                                ;; add :t_patient/permissions into resolver inputs
                 (fn [attrs]
                   (vec (distinct (conj attrs :t_patient/permissions)))))
         (update ::pco/resolve                              ;; wrap resolver to check permissions
                 (fn [resolve]                              ;; old resolver
                   (fn [env {:t_patient/keys [permissions] :as params}]
                     (if (permissions permission)
                       (resolve env params)                 ;; new resolver calls old resolver if permitted
                       {:t_patient/authorization permissions}))))))))

(def patient-properties
  [:t_patient/id
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
   :t_patient/authoritative_demographics
   :t_patient/authoritative_last_updated
   :t_patient/ethnic_origin_concept_fk
   :t_patient/racial_group_concept_fk
   :t_patient/occupation_concept_fk])

(pco/defresolver patient-by-identifier
  [{:com.eldrix.rsdb/keys [conn]} {patient_identifier :t_patient/patient_identifier}]
  {::pco/input     [:t_patient/patient_identifier]
   ::pco/transform wrap-tap-resolver
   ::pco/output    patient-properties}
  (when patient_identifier
    (db/execute-one! conn (sql/format {:select [:*] :from [:t_patient] :where [:= :patient_identifier patient_identifier]}))))

(pco/defresolver patient-by-pk
  [{:com.eldrix.rsdb/keys [conn]} {patient-pk :t_patient/id}]
  {::pco/output patient-properties}
  (when patient-pk
    (db/execute-one! conn (sql/format {:select [:*] :from [:t_patient] :where [:= :id patient-pk]}))))

(pco/defresolver patient-by-pseudonym
  "Resolves patient identifier by a tuple of project id and pseudonym."
  [{conn :com.eldrix.rsdb/conn} {project-pseudonym :t_patient/project_pseudonym}]
  {::pco/output [:t_patient/patient_identifier]}
  (when-let [[project-id pseudonym] project-pseudonym]
    (projects/search-by-project-pseudonym conn project-id pseudonym)))

(pco/defresolver patient->current-age
  [{:t_patient/keys [date_birth date_death]}]
  {:t_patient/current_age (when-not date_death (dates/age-display date_birth (LocalDate/now)))})

(pco/defresolver patient->hospitals
  [{conn :com.eldrix.rsdb/conn} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/hospitals [:t_patient_hospital/hospital_fk
                                        :t_patient_hospital/patient_fk
                                        :t_patient_hospital/hospital_identifier
                                        :t_patient_hospital/patient_identifier
                                        :t_patient_hospital/authoritative]}]}
  {:t_patient/hospitals (vec (db/execute! conn (sql/format {:select [:*]
                                                            :from   [:t_patient_hospital]
                                                            :where  [:= :patient_fk patient-id]})))})

(pco/defresolver patient-hospital->flat-hospital
  [{hospital_fk :t_patient_hospital/hospital_fk}]
  {::pco/input  [:t_patient_hospital/hospital_fk]
   ::pco/output [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id]}
  {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id hospital_fk})

(pco/defresolver patient-hospital->nested-hospital
  [{hospital_fk :t_patient_hospital/hospital_fk}]
  {::pco/input  [:t_patient_hospital/hospital_fk]
   ::pco/output [{:t_patient_hospital/hospital [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id]}]}
  {:t_patient_hospital/hospital {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id hospital_fk}})

(pco/defresolver patient-hospital->authority
  [{clods :com.eldrix.clods.graph/svc} {hospital_fk         :t_patient_hospital/hospital_fk
                                        hospital_identifier :t_patient_hospital/hospital_identifier}]
  {::pco/input  [:t_patient_hospital/hospital_fk
                 :t_patient_hospital/hospital_identifier]
   ::pco/output [:t_patient_hospital/authoritative_demographics]}
  (let [{:keys [root extension]} (if hospital_fk {:root nil :extension hospital_fk}
                                     (clods/parse-org-id hospital_identifier))
        org (clods/fetch-org clods root extension)]
    {:t_patient_hospital/authoritative_demographics
     (cond
       ;; Is this a hospital within Cardiff and Vale UHB?
       (clods/related? clods org (clods/fetch-org clods nil "7A4"))
       :CAVUHB
       ;; Is this a hospital within Aneurin Bevan UHB?
       (clods/related? clods org (clods/fetch-org clods nil "7A6"))
       :ABUHB)}))

(pco/defresolver patient-hospital->hospital-crn
  "Resolves a valid namespaced hospital identifier based on the combination of
  the hospital and the identifier within the :t_patient_hospital data.
  As this resolves to a local hospital CRN, clients can then resolve FHIR
  properties against this record to fetch FHIR-flavoured data.
  TODO: switch to using a parameterised resolver to check match status."
  [{clods :com.eldrix.clods.graph/svc}
   {auth :t_patient_hospital/authoritative_demographics
    crn  :t_patient_hospital/patient_identifier}]
  {::pco/input  [:t_patient_hospital/authoritative_demographics
                 :t_patient_hospital/patient_identifier]
   ::pco/output [:wales.nhs.abuhb.Patient/CRN
                 :wales.nhs.cavuhb.Patient/HOSPITAL_ID]}
  {:wales.nhs.abuhb.Patient/CRN         (when (= auth :CAVUHB) crn)
   :wales.nhs.cavub.Patient/HOSPITAL_ID (when (= auth :ABUHB) crn)})

(pco/defresolver patient->demographics-authority
  [{authoritative_demographics :t_patient/authoritative_demographics
    hospitals                  :t_patient/hospitals :as params}]
  {::pco/input  [:t_patient/patient_identifier
                 :t_patient/authoritative_demographics
                 {:t_patient/hospitals [:t_patient_hospital/authoritative_demographics
                                        :t_patient_hospital/hospital_fk
                                        :t_patient_hospital/hospital_identifier
                                        :t_patient_hospital/patient_identifier
                                        :t_patient_hospital/authoritative]}]
   ::pco/output [{:t_patient/demographics_authority [:t_patient_hospital/hospital_fk
                                                     :t_patient_hospital/hospital_identifier
                                                     :t_patient_hospital/authoritative
                                                     :t_patient_hospital/patient_identifier]}]}
  (let [result (->> hospitals
                    (filter #(= (:t_patient_hospital/authoritative_demographics %) authoritative_demographics))
                    (filter :t_patient_hospital/authoritative))]
    (when (> (count result) 1)
      (log/error "More than one authoritative demographic source:" params))
    {:t_patient/demographics_authority (first result)}))

(pco/defresolver patient->country-of-birth
  [{concept-id :t_patient/country_of_birth_concept_fk}]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/output    [{:t_patient/country_of_birth [:info.snomed.Concept/id]}]}
  {:t_patient/country_of_birth (when concept-id {:info.snomed.Concept/id concept-id})})

(pco/defresolver patient->ethnic-origin
  [{concept-id :t_patient/ethnic_origin_concept_fk}]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/output    [{:t_patient/ethnic_origin [:info.snomed.Concept/id]}]}
  {:t_patient/ethnic_origin (when concept-id {:info.snomed.Concept/id concept-id})})

(pco/defresolver patient->racial-group
  [{concept-id :t_patient/racial_group_concept_fk}]
  {::pco/output [{:t_patient/racial_group [:info.snomed.Concept/id]}]}
  {:t_patient/racial_group (when concept-id {:info.snomed.Concept/id concept-id})})

(pco/defresolver patient->occupation
  [{concept-id :t_patient/occupation_concept_fk}]
  {::pco/output [{:t_patient/occupation [:info.snomed.Concept/id]}]}
  {:t_patient/occupation (when concept-id {:info.snomed.Concept/id concept-id})})

(pco/defresolver patient->surgery
  [{surgery-fk :t_patient/surgery_fk}]
  {::pco/output [{:t_patient/surgery [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id]}]}
  {:t_patient/surgery (when surgery-fk {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id surgery-fk})})

(pco/defresolver patient->death_certificate
  [{conn :com.eldrix.rsdb/conn} patient]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/input     [:t_patient/id]
   ::pco/output    [:t_death_certificate/part1a             ;; flatten properties into top-level as this is a to-one relationship
                    :t_death_certificate/part1b
                    :t_death_certificate/part1c
                    :t_death_certificate/part2
                    {:t_patient/death_certificate [:t_death_certificate/part1a
                                                   :t_death_certificate/part1b
                                                   :t_death_certificate/part1c
                                                   :t_death_certificate/part2]}]}
  (let [certificate (patients/fetch-death-certificate conn patient)]
    (assoc certificate :t_patient/death_certificate certificate)))

(pco/defresolver patient->diagnoses
  "Returns diagnoses for a patient. Optionally takes a parameter:
  - :ecl - a SNOMED ECL used to constrain the list of diagnoses returned."
  [{hermes :com.eldrix/hermes, conn :com.eldrix.rsdb/conn, :as env} {patient-pk :t_patient/id}]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/output    [{:t_patient/diagnoses [:t_diagnosis/concept_fk
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
                                                 :where  [:and
                                                          [:= :patient_fk patient-pk]
                                                          [:!= :status "INACTIVE_IN_ERROR"]]}))

        ecl (:ecl (pco/params env))
        diagnoses' (if (str/blank? ecl)
                     diagnoses
                     (let [concept-ids (hermes/intersect-ecl hermes (map :t_diagnosis/concept_fk diagnoses) ecl)]
                       (filter #(concept-ids (:t_diagnosis/concept_fk %)) diagnoses)))]
    {:t_patient/diagnoses
     (mapv #(assoc % :t_diagnosis/diagnosis {:info.snomed.Concept/id (:t_diagnosis/concept_fk %)}) diagnoses')}))

(pco/defresolver diagnosis->patient
  [{patient-pk :t_diagnosis/patient_fk}]
  {:t_diagnosis/patient {:t_patient/id patient-pk}})

(pco/defresolver patient->has-diagnosis
  [{hermes :com.eldrix/hermes, :as env} {diagnoses :t_patient/diagnoses}]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/input     [{:t_patient/diagnoses [:t_diagnosis/concept_fk :t_diagnosis/date_onset :t_diagnosis/date_to]}]
   ::pco/output    [:t_patient/has_diagnosis]}
  (let [params (pco/params env)
        ecl (or (:ecl params) (throw (ex-info "Missing mandatory parameter: ecl" env)))
        on-date (:on-date params)
        on-date' (cond (nil? on-date) (LocalDate/now)
                       (string? on-date) (LocalDate/parse on-date)
                       :else on-date)]
    {:t_patient/has_diagnosis
     (let [concept-ids (->> diagnoses
                            (filter #(patients/diagnosis-active? % on-date'))
                            (map :t_diagnosis/concept_fk diagnoses))]
       (boolean (seq (hermes/intersect-ecl hermes concept-ids ecl))))}))

(pco/defresolver patient->summary-multiple-sclerosis        ;; this is misnamed, but belies the legacy system's origins.
  [{conn :com.eldrix.rsdb/conn} {patient-identifier :t_patient/patient_identifier}]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/output    [{:t_patient/summary_multiple_sclerosis
                     [:t_summary_multiple_sclerosis/id
                      :t_summary_multiple_sclerosis/ms_diagnosis_fk
                      {:t_summary_multiple_sclerosis/patient [:t_patient/patient_identifier]}
                      {:t_summary_multiple_sclerosis/ms_diagnosis [:t_ms_diagnosis/id :t_ms_diagnosis/name]}
                      :t_ms_diagnosis/id                    ; we flatten this to-one attribute
                      :t_ms_diagnosis/name]}]}
  (let [sms (patients/fetch-summary-multiple-sclerosis conn patient-identifier)
        ms-diagnosis-id (:t_ms_diagnosis/id sms)]
    {:t_patient/summary_multiple_sclerosis
     (assoc sms :t_summary_multiple_sclerosis/patient {:t_patient/patient_identifier patient-identifier}
            :t_summary_multiple_sclerosis/ms_diagnosis (when ms-diagnosis-id {:t_ms_diagnosis/id   ms-diagnosis-id
                                                                              :t_ms_diagnosis/name (:t_ms_diagnosis/name sms)}))}))

(pco/defresolver summary-multiple-sclerosis->events
  [{conn :com.eldrix.rsdb/conn} {sms-id :t_summary_multiple_sclerosis/id}]
  {::pco/output [{:t_summary_multiple_sclerosis/events
                  [:t_ms_event/id
                   :t_ms_event/date :t_ms_event/impact
                   :t_ms_event/is_relapse :t_ms_event/is_progressive
                   :t_ms_event/site_arm_motor :t_ms_event/site_ataxia
                   :t_ms_event/site_bulbar :t_ms_event/site_cognitive
                   :t_ms_event/site_diplopia :t_ms_event/site_face_motor
                   :t_ms_event/site_face_sensory :t_ms_event/site_leg_motor
                   :t_ms_event/site_limb_sensory :t_ms_event/site_optic_nerve
                   :t_ms_event/site_other :t_ms_event/site_psychiatric
                   :t_ms_event/site_sexual :t_ms_event/site_sphincter
                   :t_ms_event/site_unknown :t_ms_event/site_vestibular
                   {:t_ms_event/type [:t_ms_event_type/id
                                      :t_ms_event_type/name
                                      :t_ms_event_type/abbreviation]}
                   :t_ms_event_type/id                      ;; the event type is a to-one relationship, so we also flatten this
                   :t_ms_event_type/name
                   :t_ms_event_type/abbreviation]}]}
  (let [events (patients/fetch-ms-events conn sms-id)]
    {:t_summary_multiple_sclerosis/events events}))

(pco/defresolver ms-events->ordering-errors
  [{:t_summary_multiple_sclerosis/keys [events]}]
  {:t_summary_multiple_sclerosis/event_ordering_errors
   (mapv patients/ms-event-ordering-error->en-GB
         (patients/ms-event-ordering-errors (sort-by :t_ms_event/date events)))})

(pco/defresolver event->summary-multiple-sclerosis
  [{sms-id :t_ms_event/summary_multiple_sclerosis_fk}]
  {:t_ms_event/summary_multiple_sclerosis {:t_summary_multiple_sclerosis/id sms-id}})

(def medication-properties
  [:t_medication/id :t_medication/patient_fk
   :t_medication/date_from, :t_medication/date_to
   :t_medication/date_from_accuracy, :t_medication/date_to_accuracy
   :t_medication/indication, :t_medication/medication_concept_fk
   {:t_medication/medication [:info.snomed.Concept/id]}
   :t_medication/more_information, :t_medication/temporary_stop
   :t_medication/reason_for_stopping
   :t_medication/dose, :t_medication/frequency
   :t_medication/units, :t_medication/as_required
   :t_medication/route, :t_medication/type])

(def medication-event-properties
  [:t_medication_event/id
   :t_medication_event/type
   :t_medication_event/severity
   :t_medication_event/description_of_reaction
   :t_medication_event/event_concept_fk
   {:t_medication_event/event_concept [:info.snomed.Concept/id]}])

(pco/defresolver patient->medications
  [{conn :com.eldrix.rsdb/conn hermes :com.eldrix/hermes, :as env} patient]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/input     [:t_patient/id]
   ::pco/output    [{:t_patient/medications
                     (into medication-properties
                           [{:t_medication/events medication-event-properties}])}]}
  {:t_patient/medications
   (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
     (let [medication (patients/fetch-medications-and-events txn patient)
           ecl (:ecl (pco/params env))
           medication (if (str/blank? ecl)                  ;; if we have ecl, filter results by that expression
                        medication
                        (let [medication-concept-ids (map :t_medication/medication_concept_fk medication)
                              concept-ids (hermes/intersect-ecl hermes medication-concept-ids ecl)]
                          (filter #(concept-ids (:t_medication/medication_concept_fk %)) medication)))]
       ;; and now just add additional properties to permit walking to SNOMED CT
       (mapv #(-> %
                  (assoc :t_medication/medication {:info.snomed.Concept/id (:t_medication/medication_concept_fk %)})
                  (update :t_medication/events
                          (fn [evts] (mapv (fn [{evt-concept-id :t_medication_event/event_concept_fk :as evt}]
                                             (assoc evt :t_medication_event/event_concept
                                                    (when evt-concept-id {:info.snomed.Concept/id evt-concept-id}))) evts))))
             medication)))})

(pco/defresolver medication-by-id
  [{conn :com.eldrix.rsdb/conn, :as env} {:t_medication/keys [id]}]
  {::pco/output medication-properties}
  (when-let [med (db/execute-one! conn (sql/format {:select [:t_medication/*] :from :t_medication :where [:= :id id]}))]
    (assoc med :t_medication/medication {:info.snomed.Concept/id (:t_medication/medication_concept_fk med)})))

(pco/defresolver medication->patient
  [{patient-pk :t_medication/patient_fk}]
  {:t_medication/patient {:t_patient/id patient-pk}})

(pco/defresolver medication->events
  [{:com.eldrix.rsdb/keys [conn]} {:t_medication/keys [id]}]
  {::pco/output [{:t_medication/events medication-event-properties}]}
  {:t_medication/events
   (get (patients/fetch-medication-events conn [id]) 0)})

(def address-properties [:t_address/address1
                         :t_address/address2
                         :t_address/address3
                         :t_address/address4
                         :t_address/date_from
                         :t_address/date_to
                         :t_address/housing_concept_fk
                         :t_address/postcode
                         :t_address/ignore_invalid_address])

(pco/defresolver medication-event->concept
  [{:t_medication_event/keys [event_concept_fk]}]
  {:t_medication_event/event_concept {:info.snomed.Concept/id event_concept_fk}})

(pco/defresolver patient->addresses
  [{:com.eldrix.rsdb/keys [conn]} patient]
  {::pco/input  [:t_patient/id]
   ::pco/output [{:t_patient/addresses address-properties}]}
  {:t_patient/addresses (patients/fetch-patient-addresses conn patient)})

(pco/defresolver patient->address
  "Returns the current address, or the address for the specified date.
  Will make use of existing data in t_patient/addresses, if key exists.
  This resolver takes an optional parameter :date. If provided, the address
  for the specified date will be given.
  Parameters:
  - :date - a ISO LOCAL DATE string e.g \"2020-01-01\" or an instance of
            java.time.LocalDate."
  [{conn :com.eldrix.rsdb/conn :as env} {addresses :t_patient/addresses :as patient}]
  {::pco/input  [:t_patient/id
                 (pco/? :t_patient/addresses)]
   ::pco/output [{:t_patient/address address-properties}]}
  (let [date (:date (pco/params env))                       ;; doesn't matter if nil
        date' (cond (nil? date) nil
                    (string? date) (LocalDate/parse date)
                    :else date)
        addresses' (or addresses (patients/fetch-patient-addresses conn patient))]
    {:t_patient/address (patients/address-for-date addresses' date')}))

(def lsoa-re #"^[a-zA-Z]\d{8}$")

(pco/defresolver patient->lsoa11
  "Returns a patient's LSOA as a top-level property"
  [{conn :com.eldrix.rsdb/conn, nhspd :com.eldrix/nhspd} {addresses :t_patient/addresses :as patient}]
  {::pco/input  [:t_patient/id
                 (pco/? :t_patient/addresses)]
   ::pco/output [:t_patient/lsoa11]}
  (let [addresses' (or addresses (patients/fetch-patient-addresses conn patient))
        current-address (patients/address-for-date addresses')
        address1 (:t_address/address1 current-address)
        postcode (:t_address/postcode current-address)]
    {:t_patient/lsoa11
     (when current-address
       (or
        (when (and address1 (re-matches lsoa-re address1)) address1)
        (when postcode (get (com.eldrix.nhspd.core/fetch-postcode nhspd postcode) "LSOA11"))))}))

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
  {:t_address/lsoa (when (re-matches lsoa-re address1) address1)})

(pco/defresolver address->housing
  [{concept-id :t_address/housing_concept_fk}]
  {::pco/output [{:t_address/housing [:info.snomed.Concept/id]}]}
  {:t_address/housing (when concept-id {:info.snomed.Concept/id concept-id})})

(def episode-properties
  [:t_episode/date_discharge
   :t_episode/date_referral
   :t_episode/date_registration
   :t_episode/status
   :t_episode/discharge_user_fk
   :t_episode/id
   :t_episode/notes
   :t_episode/project_fk
   {:t_episode/project [:t_project/id]}
   {:t_episode/patient [:t_patient/id]}
   :t_episode/referral_user_fk
   :t_episode/registration_user_fk
   :t_episode/stored_pseudonym
   :t_episode/external_identifier])

(pco/defresolver patient->episodes
  [{conn :com.eldrix.rsdb/conn, :as env} {patient-pk :t_patient/id}]
  {::pco/output [{:t_patient/episodes episode-properties}]}
  {:t_patient/episodes
   (let [project-id-or-ids (:t_project/id (pco/params env))]
     (->> (jdbc/execute! conn (sql/format {:select   [:*]
                                           :from     [:t_episode]
                                           :where    (if project-id-or-ids
                                                       [:and
                                                        [:= :patient_fk patient-pk]
                                                        (if (coll? project-id-or-ids)
                                                          [:in :project_fk project-id-or-ids]
                                                          [:= :project_fk project-id-or-ids])]
                                                       [:= :patient_fk patient-pk])
                                           :order-by [[:t_episode/date_registration :asc]
                                                      [:t_episode/date_referral :asc]
                                                      [:t_episode/date_discharge :asc]]}))
          (mapv #(assoc % :t_episode/status (projects/episode-status %)
                        :t_episode/project {:t_project/id (:t_episode/project_fk %)}
                        :t_episode/patient {:t_patient/id patient-pk}))))})

(pco/defresolver patient->pending-referrals
  "Return pending referrals - either all (`:all`) or only those to which the current user
  can register (`:user`), defaulting to `:all`"
  [{manager :session/authorization-manager :as env} {:t_patient/keys [episodes]}]
  {::pco/output [{:t_patient/pending_referrals episode-properties}]}
  {:t_patient/pending_referrals
   (let [mode (or (:mode (pco/params env)) :all)
         pending-referrals (filter #(= :referred (:t_episode/status %)) episodes)]
     (case mode
       :all
       pending-referrals
       :user
       (filter #(auth/authorized? manager (hash-set (:t_episode/project_fk %)) :PROJECT_REGISTER) pending-referrals)
       (throw (ex-info "invalid 'mode' for t_patient/pending_referrals" (pco/params env)))))})

(pco/defresolver patient->suggested-registrations
  "Return suggested registrations given the patient and current user. This
  could make suggestions based on current diagnoses and treatments, and project
  configurations. Such additional functionality will be made available via
  parameters."
  [{conn :com.eldrix.rsdb/conn, user :session/authenticated-user, :as env}
   {:t_patient/keys [patient_identifier]}]
  {::pco/output [{:t_patient/suggested_registrations [:t_project/id]}]}
  {:t_patient/suggested_registrations
   (let [roles (users/roles-for-user conn (:t_user/username user))
         project-ids (patients/active-project-identifiers conn patient_identifier)]
     (->> (users/projects-with-permission roles :PATIENT_REGISTER)
          (filter :t_project/active?)                       ;; only return currently active projects
          (remove #(project-ids (:t_project/id %)))         ;; remove any projects to which patient already registered
          (map #(select-keys % [:t_project/id :t_project/title]))))}) ;; only return data relating to projects

(pco/defresolver patient->administrators
  "Return administrators linked to the projects to which this patient is linked."
  [{conn :com.eldrix.rsdb/conn} {:t_patient/keys [patient_identifier]}]
  {::pco/output [{:t_patient/administrators [:t_user/id :t_user/username :t_user/first_names :t_user/last_name]}]}
  ;; TODO: patient->active-project-ids should be its own resolver to avoid duplication
  {:t_patient/administrators (users/administrator-users conn (patients/active-project-identifiers conn patient_identifier))})

(pco/defresolver episode-by-id
  [{conn :com.eldrix.rsdb/conn} {:t_episode/keys [id]}]
  {::pco/output episode-properties}
  (let [result (jdbc/execute-one! conn (sql/format {:select :* :from :t_episode
                                                    :where  [:= :id id]}))]
    (assoc result :t_episode/status (projects/episode-status result)
           :t_episode/project {:t_project/id (:t_episode/project_fk result)}
           :t_episode/patient {:t_patient/id (:t_episode/patient_fk result)})))

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
(pco/defresolver episode->patient
  [{patient-pk :t_episode/patient_fk}]
  {:t_episode/patient {:t_patient/id patient-pk}})

(pco/defresolver episode->encounters
  [{conn :com.eldrix.rsdb/conn} {:t_episode/keys [id]}]
  {:t_episode/encounters (db/execute! conn (sql/format {:select [:id :date_time]
                                                        :from   [:t_encounter]
                                                        :where  [:= :episode_fk id]}))})

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
                 :t_project/virtual :t_project/pseudonymous
                 :t_project/can_own_equipment
                 :t_project/specialty_concept_fk
                 :t_project/advertise_to_all
                 :t_project/care_plan_information
                 :t_project/is_private]}
  (when-let [p (projects/fetch-project conn project-id)]
    (-> p
        (assoc :t_project/administrator_user
               (when-let [admin-user-id (:t_project/administrator_user_fk p)] {:t_user/id admin-user-id}))
        (assoc :t_project/parent_project
               (when-let [parent-project-id (:t_project/parent_project_fk p)]
                 {:t_project/id parent-project-id})))))

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
  {::pco/output [:t_project/count_discharged_episodes]}
  {:t_project/count_discharged_episodes (projects/count-discharged-episodes conn [project-id])})

(pco/defresolver project->slug
  [{title :t_project/title}]
  {::pco/output [:t_project/slug]}
  {:t_project/slug (projects/make-slug title)})

(pco/defresolver project->active?
  [project]
  {::pco/input  [:t_project/date_from :t_project/date_to]
   ::pco/output [:t_project/active?]}
  {:t_project/active? (projects/active? project)})

(pco/defresolver project->admission?
  "At the moment, we determine whether a project is of type 'admission' simply
  by looking at the defined name. This might be better implemented using a flag
  on a per-project basis, and with additional data linking to the encounter
  from an authoritative source, such as a patient administration system (PAS)."
  [{project-name :t_project/name}]
  {:t_project/is_admission (= "ADMISSION" project-name)})

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
  {:t_project/specialty (when specialty-concept-fk {:info.snomed.Concept/id specialty-concept-fk})})

(pco/defresolver project->common-concepts
  "Resolve common concepts for the project, optionally filtering by a SNOMED
  expression (ECL)."
  [{conn :com.eldrix.rsdb/conn hermes :com.eldrix/hermes :as env} {:t_project/keys [id]}]
  {::pco/input  [:t_project/id]
   ::pco/output [{:t_project/common_concepts [:info.snomed.Concept/id]}]}
  {:t_project/common_concepts
   (let [concept-ids (projects/common-concepts conn id)
         ecl (:ecl (pco/params env))]
     (when (seq concept-ids)
       (if (str/blank? ecl)
         (reduce (fn [acc v] (conj acc {:info.snomed.Concept/id v})) [] concept-ids)
         (->> (hermes/intersect-ecl hermes concept-ids ecl)
              (mapv #(hash-map :info.snomed.Concept/id %))))))})

(pco/defresolver project->users
  "Returns a vector of users for the project. An individual may be listed more
  than once, although this will depend on the parameters specified.
  Parameters:
   :group-by   - either :none (default when omitted) or :user
   :active     - true, false or nil.

  For example, to get a list of no longer active users for a project:
  ```
  {:pathom/entity {:t_project/id 5}
   :pathom/eql    [:t_project/id :t_project/title
                  {(list :t_project/users {:group-by :user :active false})
                   [:t_user/full_name]}]}
  ```
  This will group results by user, returning only users that are not currently
  active."
  [{conn :com.eldrix.rsdb/conn, :as env} {id :t_project/id}]
  {::pco/output [{:t_project/users [:t_user/id
                                    {:t_user/roles [:t_project_user/role
                                                    :t_project_user/date_from
                                                    :t_project_user/date_to]}
                                    :t_project_user/role
                                    :t_project_user/date_from
                                    :t_project_user/date_to
                                    :t_project_user/active?]}]}
  {:t_project/users (projects/fetch-users conn id (pco/params env))})

(def encounter-properties
  [:t_encounter/id
   :t_encounter/patient_fk
   :t_encounter/date_time
   :t_encounter/lock_date_time
   :t_encounter/is_locked
   :t_encounter/active
   :t_encounter/is_deleted
   :t_encounter/hospital_fk
   :t_encounter/ward
   :t_encounter/episode_fk
   :t_encounter/consultant_user_fk
   :t_encounter/encounter_template_fk
   :t_encounter/notes
   :t_encounter/ward
   :t_encounter/duration_minutes])

(defn encounter-locked?
  [{:t_encounter/keys [lock_date_time]}]
  (and lock_date_time (.isAfter (LocalDateTime/now) lock_date_time)))

(pco/defresolver patient->encounters
  [{:com.eldrix.rsdb/keys [conn]} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/encounters encounter-properties}]}
  {:t_patient/encounters (->> (db/execute! conn (sql/format {:select   [:*]
                                                             :from     [:t_encounter]
                                                             :where    [:= :patient_fk patient-id]
                                                             :order-by [[:date_time :desc]]}))
                              (mapv #(assoc %
                                            :t_encounter/active (not (:t_encounter/is_deleted %))
                                            :t_encounter/is_locked (encounter-locked? %))))})

(pco/defresolver patient->paged-encounters
  "A resolver for pages of encounters."
  [{:com.eldrix.rsdb/keys [conn] :as env} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/paged_encounters [{:paging [:cursor :results]}]}]}
  (let [{:keys [cursor page-size]} (pco/params env)]))

(pco/defresolver patient->results
  [{:com.eldrix.rsdb/keys [conn]} {patient-identifier :t_patient/patient_identifier}]
  {::pco/output
   [{:t_patient/results
     [:t_result_mri_brain/date
      :t_result_mri_brain/report
      :t_result_mri_brain/with_gadolinium
      :t_result_jc_virus/date :t_result_jc_virus/jc_virus
      :t_result_csf_ocb/date :t_result_csf_ocb/result
      :t_result_renal/date :t_result_renal/notes
      :t_result_full_blood_count/date :t_result_full_blood_count/notes
      :t_result_ecg/date :t_result_ecg/notes
      :t_result_urinalysis/date :t_result_urinalysis/notes
      :t_result_liver_function/date :t_result_liver_function/notes]}]}
  {:t_patient/results
   (when patient-identifier
     (vec (com.eldrix.pc4.rsdb.results/results-for-patient conn patient-identifier)))})

(pco/defresolver encounter-by-id
  [{conn :com.eldrix.rsdb/conn} {encounter-id :t_encounter/id}]
  {::pco/output encounter-properties}
  (let [{:t_encounter/keys [is_deleted] :as encounter}
        (db/execute-one! conn (sql/format {:select [:*] :from :t_encounter :where [:= :id encounter-id]}))]
    (assoc encounter
           :t_encounter/active (not is_deleted)
           :t_encounter/is_locked (encounter-locked? encounter))))

(pco/defresolver encounter->patient
  [{:t_encounter/keys [patient_fk]}]
  {:t_encounter/patient {:t_patient/id patient_fk}})

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
        (mapv #(hash-map :t_user/id %)))})

(pco/defresolver encounter->hospital
  [{hospital-id :t_encounter/hospital_fk}]
  {::pco/output [{:t_encounter/hospital [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id]}]}
  {:t_encounter/hospital (when hospital-id {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id hospital-id})})

(pco/defresolver encounters->encounter_template
  [{:com.eldrix.rsdb/keys [conn]} encounters]
  {::pco/input  [:t_encounter/encounter_template_fk]
   ::pco/output [{:t_encounter/encounter_template [:t_encounter_template/id
                                                   :t_encounter_template/project_fk
                                                   :t_encounter_template/encounter_type_fk]}]
   ::pco/batch? true}
  (let [encounter-template-ids (map :t_encounter/encounter_template_fk encounters)
        encounter-templates (group-by :t_encounter_template/id
                                      (jdbc/execute! conn (sql/format {:select [:*] :from [:t_encounter_template]
                                                                       :where  [:in :id (set encounter-template-ids)]})))]
    (into []
          (map (fn [id] {:t_encounter/encounter_template (first (get encounter-templates id))}))
          encounter-template-ids)))

(pco/defresolver encounter_template->encounter_type
  [{:com.eldrix.rsdb/keys [conn]} {encounter-type-id :t_encounter_template/encounter_type_fk}]
  {::pco/output [{:t_encounter_template/encounter_type [:t_encounter_type/id
                                                        :t_encounter_type/name
                                                        :t_encounter_type/seen_in_person]}]}
  {:t_encounter_template/encounter_type (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_type] :where [:= :id encounter-type-id]}))})

(pco/defresolver encounter_template->project
  [{:t_encounter_template/keys [project_fk]}]
  {:t_encounter_template/project {:t_project/id project_fk}})

(pco/defresolver encounters->form_edss
  [{:com.eldrix.rsdb/keys [conn]} encounters]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/form_edss
                  [:t_form_edss/id
                   :t_form_edss/edss
                   :t_form_edss/edss_score
                   :t_form_edss_fs/id
                   :t_form_edss_fs/edss_score]}]
   ::pco/batch? true}
  (let [encounter-ids (map :t_encounter/id encounters)
        edss (group-by :t_form_edss/encounter_fk
                       (db/execute! conn
                                    (sql/format {:select [:id :encounter_fk :edss_score :user_fk]
                                                 :from   :t_form_edss
                                                 :where  [:and [:<> :is_deleted "true"]
                                                          [:in :t_form_edss/encounter_fk encounter-ids]]})))
        edss-fs (group-by :t_form_edss_fs/encounter_fk
                          (db/execute! conn
                                       (sql/format {:select [:id :encounter_fk :edss_score :user_fk]
                                                    :from   :t_form_edss_fs
                                                    :where  [:and [:<> :is_deleted "true"]
                                                             [:in :encounter_fk encounter-ids]]})))]
    (into []
          (map (fn [encounter-id]
                 {:t_encounter/form_edss (merge (first (get edss encounter-id))
                                                (first (get edss-fs encounter-id)))}))
          encounter-ids)))

(pco/defresolver form_edss-score->score
  [{edss_score :t_form_edss/edss_score}]
  {::pco/input  [:t_form_edss/edss_score]
   ::pco/output [:t_form_edss/score]}
  {:t_form_edss/score (forms/edss-score->score edss_score)})

(pco/defresolver form_edss_fs-score->score
  [{edss_score :t_form_edss_fs/edss_score}]
  {::pco/input  [:t_form_edss_fs/edss_score]
   ::pco/output [:t_form_edss_fs/score]}
  {:t_form_edss_fs/score (forms/edss-score->score edss_score)})

(pco/defresolver encounters->form_ms_relapse
  [{:com.eldrix.rsdb/keys [conn]} encounters]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/form_ms_relapse
                  [:t_form_ms_relapse/id
                   :t_form_ms_relapse/in_relapse
                   :t_form_ms_relapse/activity
                   :t_form_ms_relapse/progression
                   :t_form_ms_disease_course/name           ;; TODO: probably remove as better nested?
                   {:t_form_ms_relapse/ms_disease_course
                    [:t_form_ms_disease_course/id
                     :t_form_ms_disease_course/name]}]}]
   ::pco/batch? true}
  (let [encounter-ids (map :t_encounter/id encounters)
        forms (group-by :t_form_ms_relapse/encounter_fk
                        (db/execute! conn (sql/format {:select    [:t_form_ms_relapse/id
                                                                   :t_form_ms_relapse/encounter_fk
                                                                   :t_form_ms_relapse/in_relapse
                                                                   :t_form_ms_relapse/ms_disease_course_fk
                                                                   :t_ms_disease_course/name
                                                                   :t_form_ms_relapse/activity :t_form_ms_relapse/progression]
                                                       :from      [:t_form_ms_relapse]
                                                       :left-join [:t_ms_disease_course [:= :t_form_ms_relapse/ms_disease_course_fk :t_ms_disease_course/id]]
                                                       :where     [:and [:in :t_form_ms_relapse/encounter_fk encounter-ids]
                                                                   [:<> :t_form_ms_relapse/is_deleted "true"]]})))]
    (into []
          (map (fn [encounter-id]
                 (let [{:t_form_ms_relapse/keys [ms_disease_course_fk] :as form} (first (get forms encounter-id))]
                   {:t_encounter/form_ms_relapse
                    (if ms_disease_course_fk
                      (assoc form :t_form_ms_relapse/ms_disease_course
                             {:t_ms_disease_course/id   ms_disease_course_fk
                              :t_ms_disease_course/name (:t_ms_disease_course/name form)})
                      form)})))
          encounter-ids)))

(pco/defresolver encounters->form_weight_height
  [{:com.eldrix.rsdb/keys [conn]} encounters]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/form_weight_height [:t_form_weight_height/id
                                                   :t_form_weight_height/weight_kilogram
                                                   :t_form_weight_height/height_metres]}]
   ::pco/batch? true}
  (let [encounter-ids (map :t_encounter/id encounters)
        forms (group-by :t_form_weight_height/encounter_fk
                        (db/execute! conn (sql/format {:select [:t_form_weight_height/id
                                                                :t_form_weight_height/encounter_fk
                                                                :t_form_weight_height/weight_kilogram
                                                                :t_form_weight_height/height_metres]
                                                       :from   [:t_form_weight_height]
                                                       :where  [:and
                                                                [:in :t_form_weight_height/encounter_fk encounter-ids]
                                                                [:<> :t_form_weight_height/is_deleted "true"]]})))]
    (into []
          (map (fn [encounter-id]
                 {:t_encounter/form_weight_height (first (get forms encounter-id))}))
          encounter-ids)))

(pco/defresolver encounter->form_smoking
  [{:com.eldrix.rsdb/keys [conn]} {encounter-id :t_encounter/id}]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/form_smoking_history [:t_smoking_history/id
                                                     :t_smoking_history/current_cigarettes_per_day
                                                     :t_smoking_history/status]}]}
  {:t_encounter/form_smoking_history (forms/encounter->form_smoking_history conn encounter-id)})

(defn form-assoc-context
  [{:form/keys [encounter_fk user_fk] :as form}]
  (assoc form
         :form/encounter {:t_encounter/id encounter_fk}
         :form/user {:t_user/id user_fk}))

(pco/defresolver encounter->forms
  [{:com.eldrix.rsdb/keys [conn]} {encounter-id :t_encounter/id}]
  {::pco/output
   [:t_encounter/available_form_types
    :t_encounter/optional_form_types
    :t_encounter/mandatory_form_types
    :t_encounter/existing_form_types
    :t_encounter/duplicated_form_types
    {:t_encounter/completed_forms
     [:form/id
      :form/user_fk
      :form/encounter_fk
      :form/summary_result
      {:form/user [:t_user/id]}
      {:form/encounter [:t_encounter/id]}]}
    {:t_encounter/deleted_forms
     [:form/id
      :form/user_fk
      :form/encounter_fk
      :form/summary_result
      {:form/user [:t_user/id]}
      {:form/encounter [:t_encounter/id]}]}]}
  (let [{:keys [available-form-types optional-form-types mandatory-form-types existing-form-types completed-forms duplicated-form-types deleted-forms]}
        (forms/forms-and-form-types-in-encounter conn encounter-id)]
    {:t_encounter/available_form_types  available-form-types
     :t_encounter/optional_form_types   optional-form-types
     :t_encounter/mandatory_form_types  mandatory-form-types
     :t_encounter/existing_form_types   existing-form-types
     :t_encounter/completed_forms       (map form-assoc-context completed-forms)
     :t_encounter/duplicated_form_types duplicated-form-types
     :t_encounter/deleted_forms         (map form-assoc-context deleted-forms)}))

(pco/defresolver form-by-id
  [{:com.eldrix.rsdb/keys [conn] :as env} {form-id :form/id}]
  {::pco/output [:form/id
                 :form/user_fk
                 :form/encounter_fk
                 :form/summary_result
                 {:form/user [:t_user/id]}
                 {:form/encounter [:t_encounter/id]}]}
  (if-let [encounter-id (get-in env [:query-params :encounter-id])]
    (let [forms (forms/forms-for-encounter conn encounter-id {:include-deleted true})
          by-id (reduce (fn [acc {:form/keys [id] :as form}] (assoc acc id form)) {} forms)
          form (get by-id form-id)]
      (log/debug "form by id:" {:encounter-id encounter-id :form-id form-id :result form})
      (form-assoc-context form))
    (do
      (log/error "Missing hint on parameters" (:query-params env))
      (throw (ex-info "Missing hint on parameters for form. Specify :encounter-id in load parameters" (:query-params env))))))

(pco/defresolver encounter->forms_generic_procedures
  [{:com.eldrix.rsdb/keys [conn]} encounters]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/forms_generic_procedure [:t_form_procedure_generic/id
                                                        :t_form_procedure_generic/procedure_concept_fk
                                                        :info.snomed.Concept/id
                                                        :t_form_procedure_generic/notes]}]}
  ;;TODO: this is to show we would handle forms that support multiple per encounter like this....
  (throw (ex-info "not implemented" {})))

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
   :t_user/photo_fk
   :t_user/send_email_for_messages
   :t_user/authentication_method
   :t_user/professional_registration
   :t_professional_registration_authority/abbreviation
   :t_professional_registration_authority/name])

(pco/defresolver user-by-username
  [{conn :com.eldrix.rsdb/conn} {username :t_user/username}]
  {::pco/output user-properties}
  (users/fetch-user conn username))

(pco/defresolver user-by-id
  [{conn :com.eldrix.rsdb/conn} {id :t_user/id}]
  {::pco/output user-properties}
  (users/fetch-user-by-id conn id))

(pco/defresolver user->link-to-regulator
  [{reg :t_user/professional_registration, authority :t_professional_registration_authority/abbreviation}]
  {::pco/input  [:t_user/professional_registration
                 :t_professional_registration_authority/abbreviation]
   ::pco/output [:t_user/professional_registration_url]}
  {:t_user/professional_registration_url
   (when-not (str/blank? reg)
     (case authority
       "GMC" (str "https://www.gmc-uk.org/doctors/" reg)
       nil))})

(pco/defresolver user-by-nadex
  "Resolves rsdb user properties from a NADEX username if that user is
   registered with rsdb with NADEX authentication."
  [{conn :com.eldrix.rsdb/conn} {username :wales.nhs.nadex/sAMAccountName}]
  {::pco/output user-properties}
  (when-let [user (users/fetch-user conn username)]
    (when (= (:t_user/authentication_method user) "NADEX")
      user)))

(pco/defresolver user->photo
  "Returns the user photo as binary data."
  [{conn :com.eldrix.rsdb/conn} {:t_user/keys [username]}]
  {::pco/output [{:t_user/photo [:t_photo/data
                                 :t_photo/mime_type]}]}
  (let [photo (com.eldrix.pc4.rsdb.users/fetch-user-photo conn username)]
    {:t_user/photo (when photo {:t_photo/data      (:erattachmentdata/data photo)
                                :t_photo/mime_type (:erattachment/mimetype photo)})}))

(pco/defresolver user->has-photo?
  "Does the user have an available photograph?
  At the moment, this simply checks the rsdb database, but this could use other
  logic - such as checking Active Directory if the user is managed by that
  service."
  [{:t_user/keys [photo_fk]}]
  {::pco/input  [(pco/? :t_user/photo_fk)]
   ::pco/output [:t_user/has_photo]}
  {:t_user/has_photo (boolean photo_fk)})

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
                                     :org.hl7.fhir.HumanName/suffix (when-not (str/blank? postnomial) (str/split postnomial #"\s"))
                                     :org.hl7.fhir.HumanName/use    :org.hl7.fhir.name-use/usual}]})

(pco/defresolver user->active-projects
  [{conn :com.eldrix.rsdb/conn} {username :t_user/username}]
  {::pco/output [{:t_user/active_projects [:t_project/id]}]}
  {:t_user/active_projects (filterv projects/active? (users/projects conn username))})

(pco/defresolver user->roles
  [{conn :com.eldrix.rsdb/conn, :as env} {username :t_user/username}]
  {::pco/output [{:t_user/roles [:t_project_user/date_from
                                 :t_project_user/date_to
                                 :t_project_user/active?
                                 :t_project_user/permissions
                                 :t_project/id
                                 :t_project/active?]}]}
  (let [{:keys [project-id active-roles active-projects]} (pco/params env)]
    {:t_user/roles
     (cond->> (users/roles-for-user conn username {:t_project/id project-id})
       active-roles (filter :t_project_user/active?)
       active-projects (filter :t_project/active?))}))

(pco/defresolver user->common-concepts
  "Resolve common concepts for the user, based on project membership, optionally
  filtering by a SNOMED expression (ECL). Language preferences can be specified
  using parameter `:accept-language` with a comma-separated list of preferences."
  [{conn :com.eldrix.rsdb/conn, hermes :com.eldrix/hermes, :as env} {user-id :t_user/id}]
  {::pco/output [{:t_user/common_concepts
                  [:info.snomed.Concept/id
                   {:info.snomed.Concept/preferredDescription
                    [:info.snomed.Description/id
                     :info.snomed.Description/term]}]}]}
  (let [concept-ids (users/common-concepts conn user-id)
        ecl (:ecl (pco/params env))
        lang (or (:accept-language (pco/params env)) (.toLanguageTag (Locale/getDefault)))
        concept-ids' (if (str/blank? ecl) concept-ids (hermes/intersect-ecl hermes concept-ids ecl))] ;; constrain concepts by ECL if present
    {:t_user/common_concepts
     (when (seq concept-ids)
       (let [results (hermes/search-concept-ids hermes {:language-range lang} concept-ids')]
         (mapv #(hash-map :info.snomed.Concept/id (:conceptId %) ;; and now give shape to results
                          :info.snomed.Concept/preferredDescription {:info.snomed.Description/id   (:id %)
                                                                     :info.snomed.Description/term (:term %)}) results)))}))

(pco/defresolver user->latest-news
  [{conn :com.eldrix.rsdb/conn} {username :t_user/username}]
  {::pco/output [{:t_user/latest_news [:t_news/id
                                       :t_news/title
                                       :t_news/body
                                       {:t_news/author [:t_user/id]}]}]}
  {:t_user/latest_news (->> (users/fetch-latest-news conn username)
                            (mapv #(assoc % :t_news/author (select-keys % [:t_user/id :t_user/first_names :t_user/last_name]))))})

(pco/defresolver user->job-title
  [{custom-job-title :t_user/custom_job_title job-title :t_job_title/name :as user}]
  {::pco/input  [:t_user/custom_job_title (pco/? :t_job_title/name)]
   ::pco/output [:t_user/job_title]}
  {:t_user/job_title (users/job-title user)})

(pco/defresolver user->count-unread-messages
  [{conn :com.eldrix.rsdb/conn} {username :t_user/username}]
  {:t_user/count_unread_messages (users/count-unread-messages conn username)})

(pco/defresolver user->count-incomplete-messages
  [{conn :com.eldrix.rsdb/conn} {username :t_user/username}]
  {:t_user/count_incomplete_messages (users/count-incomplete-messages conn username)})

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

(pco/defmutation register-patient!
  "Register a patient using NHS number."
  [{conn    :com.eldrix.rsdb/conn
    manager :session/authorization-manager
    user    :session/authenticated-user}
   {:keys [project-id nhs-number] :as params}]
  {::pco/op-name 'pc4.rsdb/register-patient
   ::pco/output  [:t_patient/patient_identifier
                  :t_episode/project_fk]}
  (log/info "register patient:" {:params params})
  (if-not (and manager (auth/authorized? manager #{project-id} :PATIENT_REGISTER))
    (throw (ex-info "Not authorized" {}))
    (let [user-id (:t_user/id user)]
      (if (s/valid? ::register-patient params)
        (jdbc/with-transaction [txn conn {:isolation :serializable}]
          (projects/register-patient txn project-id user-id params))
        (do (log/error "invalid call" (s/explain-data ::register-patient params))
            (throw (ex-info "invalid NHS number" {:nnn nhs-number})))))))

(comment
  (s/explain-data ::register-patient {:user-id    1
                                      :project-id 5
                                      :nhs-number "111 111 1121"})

  (s/explain-data ::register-patient-by-pseudonym {:user-id    5
                                                   :project-id 1
                                                   :nhs-number "111 111 1111"
                                                   :sex        :MALE
                                                   :date-birth (LocalDate/of 1970 1 1)}))
(pco/defmutation register-patient-by-pseudonym!
  "Register a legacy pseudonymous patient. This will be deprecated in the
  future.
  TODO: switch to more pluggable and routine coercion of data, rather than
  managing by hand."
  [{conn    :com.eldrix.rsdb/conn
    config  :com.eldrix.rsdb/config
    manager :session/authorization-manager
    user    :session/authenticated-user}
   {:keys [project-id nhs-number date-birth] :as params}]
  {::pco/op-name 'pc4.rsdb/register-patient-by-pseudonym
   ::pco/output  [:t_patient/patient_identifier
                  :t_episode/stored_pseudonym
                  :t_episode/project_fk]}
  (log/info "register patient by pseudonym: " {:user user :params params})
  (if-not (and manager (auth/authorized? manager #{project-id} :PATIENT_REGISTER))
    (throw (ex-info "Not authorized" {}))
    (if-let [global-salt (:legacy-global-pseudonym-salt config)]
      (let [params' (assoc params :user-id (:t_user/id user)
                           :salt global-salt
                           :nhs-number (nnn/normalise nhs-number)
                           :date-birth (cond
                                         (instance? LocalDate date-birth) date-birth
                                         (string? date-birth) (LocalDate/parse date-birth)
                                         :else (throw (ex-info "failed to parse date-birth" params))))] ;; TODO: better automated coercion
        (if (s/valid? ::register-patient-by-pseudonym params')
          (jdbc/with-transaction [txn conn {:isolation :serializable}]
            (projects/register-legacy-pseudonymous-patient txn params'))
          (log/error "invalid call" (s/explain-data ::register-patient-by-pseudonym params'))))
      (log/error "unable to register patient by pseudonym; missing global salt: check configuration"
                 {:expected [:com.eldrix.rsdb/config :legacy-global-pseudonym-salt]
                  :config   config}))))

(pco/defmutation search-patient-by-pseudonym
  "Search for a patient using a pseudonymous project-specific identifier.
  This uses the legacy approach, which *will* be deprecated."
  [{conn :com.eldrix.rsdb/conn} {:keys [project-id pseudonym] :as params}]
  {::pco/op-name 'pc4.rsdb/search-patient-by-pseudonym
   ::pco/output  [:t_patient/id
                  :t_patient/patient_identifier
                  :t_episode/stored_pseudonym
                  :t_episode/project_fk]}
  (log/debug "search-patient-by-pseudonym" params)
  (projects/search-by-project-pseudonym conn project-id pseudonym))

(defn guard-can-for-patient?                                ;; TODO: turn into a macro for defmutation?
  [{conn :com.eldrix.rsdb/conn manager :session/authorization-manager :as env} patient-identifier permission]
  (when-not manager
    (throw (ex-info "missing authorization manager" {:expected :session/authorization-manager, :found env})))
  (when-not patient-identifier
    (throw (ex-info "invalid request: missing patient-identifier" {})))
  (let [project-ids (patients/active-project-identifiers conn patient-identifier)]
    (when-not (auth/authorized? manager project-ids permission)
      (throw (ex-info "You are not authorised to perform this operation" {:patient-identifier patient-identifier
                                                                          :permission         permission})))))
(defn guard-can-for-project?                                ;; TODO: turn into a macro for defmutation?
  [{conn :com.eldrix.rsdb/conn manager :session/authorization-manager :as env} project-id permission]
  (when-not manager
    (throw (ex-info "missing authorization manager" {:expected :session/authorization-manager, :found env})))
  (let [project-ids #{project-id}]
    (when-not (auth/authorized? manager project-ids permission)
      (throw (ex-info "You are not authorised to perform this operation" {:project-id project-id
                                                                          :permission permission})))))

(pco/defmutation register-patient-to-project!
  [{conn :com.eldrix.rsdb/conn, user :session/authenticated-user :as env} {:keys [patient project-id] :as params}]
  {::pco/op-name 'pc4.rsdb/register-patient-to-project}
  (when (or (not (s/valid? (s/keys :req [:t_patient/id :t_patient/patient_identifier]) patient)) (not (int? project-id)))
    (throw (ex-info "invalid call" params)))
  (guard-can-for-project? env project-id :PATIENT_REGISTER)
  (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
    (try
      (let [episode (projects/register-patient-project! txn project-id (:t_user/id user) patient)]
        (log/info "registered patient to project" {:request params :result episode}))
      (catch Exception e (log/error "failed to save ms diagnosis" (ex-data e))))))

(pco/defmutation break-glass!
  "Break glass operation - registers the given patient to the break-glass.
  TODO: should send a message to appropriate users 
  TODO: should be recorded in new project-based audit trail / comms / events list"
  [{session :session user :session/authenticated-user :as env} {:keys [patient-identifier] :as params}]
  {::pco/op-name 'pc4.rsdb/break-glass}
  (when-not patient-identifier
    (throw (ex-info "invalid params" params)))
  (log/info "break glass" {:patient patient-identifier :user (:t_user/username user)})
  (api-middleware/augment-response {:t_patient/patient_identifier patient-identifier
                                    :t_patient/break_glass        true}
                                   (fn [response]
                                     (assoc response :session (assoc session :break-glass patient-identifier)))))

(s/def :t_diagnosis/diagnosis (s/keys :req [:info.snomed.Concept/id]))
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
  [{conn                 :com.eldrix.rsdb/conn
    manager              :session/authorization-manager
    {user-id :t_user/id} :session/authenticated-user
    :as                  env} params]
  {::pco/op-name 'pc4.rsdb/save-diagnosis}
  (log/info "save diagnosis request: " params "user: " user-id)
  (let [params' (assoc params :t_diagnosis/user_fk (:t_user/id user-id)
                       :t_diagnosis/concept_fk (get-in params [:t_diagnosis/diagnosis :info.snomed.Concept/id]))]
    (if-not (s/valid? ::save-diagnosis (dissoc params' :t_diagnosis/id))
      (do (log/error "invalid call" (s/explain-data ::save-diagnosis params'))
          (throw (ex-info "Invalid data" (s/explain-data ::save-diagnosis params'))))
      (do (guard-can-for-patient? env (:t_patient/patient_identifier params) :PATIENT_EDIT)
          (let [diagnosis-id (:t_diagnosis/id params')
                diag (if (or (nil? diagnosis-id) (com.fulcrologic.fulcro.algorithms.tempid/tempid? diagnosis-id))
                       (patients/create-diagnosis conn (dissoc params' :t_diagnosis/id))
                       (patients/update-diagnosis conn params'))]
            (cond-> (assoc-in diag [:t_diagnosis/diagnosis :info.snomed.Concept/id] (:t_diagnosis/concept_fk diag))
              (tempid/tempid? diagnosis-id)
              (assoc :tempids {diagnosis-id (:t_diagnosis/id diag)})))))))

(s/def ::save-medication
  (s/keys :req [:t_medication/patient_fk :t_medication/medication]
          :opt [:t_patient/patient_identifier
                :t_medication/date_from
                :t_medication/date_to
                :t_medication/more_information
                :t_medication/reason_for_stopping
                :t_medication/events]))
(pco/defmutation save-medication!
  [{conn    :com.eldrix.rsdb/conn
    manager :authorization-manager
    user    :session/authenticated-user, :as env}
   {patient-id :t_patient/patient_identifier, medication-id :t_medication/id, patient-pk :t_medication/patient_fk, :as params}]
  {::pco/op-name 'pc4.rsdb/save-medication}
  (log/info "save medication request: " params "user: " user)
  (let [create? (tempid/tempid? medication-id)
        params' (cond-> (-> params
                            (assoc :t_medication/medication_concept_fk (get-in params [:t_medication/medication :info.snomed.Concept/id]))
                            (update :t_medication/events (fn [evts] (->> evts
                                                                         (mapv #(hash-map :t_medication_event/type (:t_medication_event/type %)
                                                                                          :t_medication_event/event_concept_fk (get-in % [:t_medication_event/event_concept :info.snomed.Concept/id])))))))
                  create? (dissoc :t_medication/id))]
    (if-not (s/valid? ::save-medication params')
      (log/error "invalid call" (s/explain-data ::save-medication params'))
      (jdbc/with-transaction [txn conn {:isolation :serializable}]
        (guard-can-for-patient? env (or patient-id (patients/pk->identifier conn patient-pk)) :PATIENT_EDIT)
        (log/info "Upsert medication " {:txn txn :params params})
        (let [med (patients/upsert-medication! txn params')]
          (cond-> (assoc-in med [:t_medication/medication :info.snomed.Concept/id] (:t_medication/medication_concept_fk med))
            create? (assoc :tempids {medication-id (:t_medication/id med)})))))))

(s/def ::delete-medication
  (s/keys :req [:t_medication/id (or :t_patient/patient_identifier :t_medication/patient_fk)]))
(pco/defmutation delete-medication!
  "Delete a medication. Parameters are a map containing
    :t_medication/id : (mandatory) - the identifier for the medication
    :t_medication/patient_fk
    :t_patient/patient_identifier "
  [{conn :com.eldrix.rsdb/conn, manager :authorization-manager :as env}
   {patient-id :t_patient/patient_identifier, patient-pk :t_medication/patient_fk, :as params}]
  {::pco/op-name 'pc4.rsdb/delete-medication}
  (log/info "delete medication request" params)
  (if-not (s/valid? ::delete-medication params)
    (log/error "invalid call" (s/explain-data ::delete-medication params))
    (do (guard-can-for-patient? env (or patient-id (patients/pk->identifier conn patient-pk)) :PATIENT_EDIT)
        (patients/delete-medication! conn params))))

(s/def ::save-ms-diagnosis
  (s/keys :req [:t_user/id
                :t_ms_diagnosis/id
                :t_patient/patient_identifier]))

(pco/defmutation save-patient-ms-diagnosis!                 ;; TODO: could update main diagnostic list...
  [{conn                 :com.eldrix.rsdb/conn
    {user-id :t_user/id} :session/authenticated-user
    :as                  env} {patient-identifier :t_patient/patient_identifier :as params}]
  {::pco/op-name 'pc4.rsdb/save-ms-diagnosis}
  (log/info "save ms diagnosis:" params " user:" user-id)
  (let [params' (assoc params :t_user/id user-id)]
    (if-not (s/valid? ::save-ms-diagnosis params')
      (do (log/error "invalid call" (s/explain-data ::save-ms-diagnosis params'))
          (throw (ex-info "Invalid data" (s/explain-data ::save-ms-diagnosis params'))))
      (do (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
          (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
            (try
              (patients/save-ms-diagnosis! txn params')
              (catch Exception e (log/error "failed to save ms diagnosis" (ex-data e)))))
          (assoc (patient->summary-multiple-sclerosis env params)
                 :t_patient/patient_identifier patient-identifier)))))

(s/def ::save-pseudonymous-postal-code
  (s/keys :req [:t_patient/patient_identifier
                :uk.gov.ons.nhspd/PCD2]))

(pco/defmutation save-pseudonymous-patient-postal-code!
  [{conn :com.eldrix.rsdb/conn
    ods  :com.eldrix.clods.graph/svc
    user :session/authenticated-user}
   {patient-identifier :t_patient/patient_identifier
    postcode           :uk.gov.ons.nhspd/PCD2 :as params}]
  {::pco/op-name 'pc4.rsdb/save-pseudonymous-patient-postal-code}
  (log/info "saving pseudonymous postal code" {:params params :ods ods})
  (if-not (s/valid? ::save-pseudonymous-postal-code params)
    (throw (ex-info "invalid request" (s/explain-data ::save-pseudonymous-postal-code params)))
    (jdbc/with-transaction [txn conn {:isolation :repeatable-read}]
      (if (str/blank? postcode)
        (patients/save-pseudonymous-patient-lsoa! txn {:t_patient/patient_identifier patient-identifier
                                                       :uk.gov.ons.nhspd/LSOA11      ""})
        (let [pc (clods/fetch-postcode ods postcode)]
          (patients/save-pseudonymous-patient-lsoa! txn {:t_patient/patient_identifier patient-identifier
                                                         :uk.gov.ons.nhspd/LSOA11      (get pc "LSOA11")}))))))

(s/def ::save-ms-event
  (s/keys :req [:t_ms_event/date
                (or :t_ms_event_type/id :t_ms_event/type)
                :t_ms_event/impact
                :t_ms_event/summary_multiple_sclerosis_fk]
          :opt [:t_ms_event/notes]))

(pco/defmutation save-ms-event!
  [{conn    :com.eldrix.rsdb/conn
    manager :session/authorization-manager
    user    :session/authenticated-user
    :as     env}
   {:t_ms_event/keys [id] :as params}]
  {::pco/op-name 'pc4.rsdb/save-ms-event}
  (log/info "save ms event request: " params "user: " user)
  (if-not (s/valid? ::save-ms-event (dissoc params :t_ms_event/id))
    (do (log/error "invalid call" (s/explain-data ::save-ms-event params))
        (throw (ex-info "Invalid data" (s/explain-data ::save-ms-event params))))
    (let [patient-identifier (or (:t_patient/patient_identifier params)
                                 (patients/patient-identifier-for-ms-event conn params))]
      (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
      (create-or-save-entity
       {:id-key  :t_ms_event/id
        :save-fn #(patients/save-ms-event! conn %)
        :params  (-> params
                     (dissoc :t_patient/patient_identifier
                             :t_ms_event/type
                             :t_ms_event_type/id
                             :t_ms_event_type/abbreviation
                             :t_ms_event_type/name
                             :t_ms_event/is_relapse
                             :t_ms_event/is_progressive)
                     (assoc :t_ms_event/ms_event_type_fk
                            (or (:t_ms_event_type/id params)
                                (get-in params [:t_ms_event/type :t_ms_event_type/id]))))}))))

(s/def ::delete-ms-event
  (s/keys :req [:t_user/id
                :t_ms_event/id]))

(pco/defmutation delete-ms-event!
  [{conn                 :com.eldrix.rsdb/conn
    {user-id :t_user/id} :session/authenticated-user, :as env}
   {ms-event-id :t_ms_event/id :as params}]
  {::pco/op-name 'pc4.rsdb/delete-ms-event}
  (log/info "delete ms event:" params " user:" user-id)
  (let [params' (assoc params :t_user/id user-id)]
    (if-not (s/valid? ::delete-ms-event params')
      (do (log/error "invalid call" (s/explain-data ::delete-ms-event params'))
          (throw (ex-info "Invalid data" (s/explain-data ::delete-ms-event params'))))
      (when-let [patient-identifier (patients/patient-identifier-for-ms-event conn params')]
        (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
        (patients/delete-ms-event! conn params')))))

(s/def ::save-encounter
  (s/keys :req [:t_patient/patient_identifier
                :t_encounter/patient_fk
                :t_encounter/date_time
                :t_encounter/encounter_template_fk]
          :opt [:t_encounter/id                             ;; if we've saving an existing encounter
                :t_encounter/episode_fk]))

(pco/defmutation save-encounter!
  [{conn                 :com.eldrix.rsdb/conn
    {user-id :t_user/id} :session/authenticated-user, :as env}
   params]
  {::pco/op-name 'pc4.rsdb/save-encounter}
  (log/info "save encounter request: " params "user: " user-id)
  (let [date (:t_encounter/date_time params)
        params' (-> params
                    (assoc :t_encounter/date_time (if (instance? LocalDate date) (.atStartOfDay date) date)
                           :t_user/id (:t_user/id user-id)))]
    (when-not (s/valid? ::save-encounter params')
      (log/error "invalid call" (s/explain-data ::save-encounter params'))
      (throw (ex-info "Invalid data" (s/explain-data ::save-encounter params'))))
    (try
      (guard-can-for-patient? env (:t_patient/patient_identifier params) :PATIENT_EDIT)
      (jdbc/with-transaction [txn conn]
        (forms/save-encounter-and-forms! txn params'))
      (catch Exception e (.printStackTrace e)))))

(s/def ::delete-encounter (s/keys :req [:t_encounter/id :t_patient/patient_identifier]))
(pco/defmutation delete-encounter!
  [{conn    :com.eldrix.rsdb/conn
    manager :session/authorization-manager
    user    :session/authenticated-user
    :as     env}
   {encounter-id       :t_encounter/id
    patient-identifier :t_patient/patient_identifier :as params}]
  {::pco/op-name 'pc4.rsdb/delete-encounter}
  (log/info "delete encounter:" encounter-id " user:" user)
  (if-not (s/valid? ::delete-encounter params)
    (do (log/error "invalid delete encounter" (s/explain-data ::delete-encounter params))
        (throw (ex-info "Invalid 'delete encounter' data:" (s/explain-data ::delete-encounter params))))
    (do (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
        (patients/delete-encounter! conn encounter-id))))

(pco/defmutation save-form!
  "Save a form. Parameters are a map with
  - patient-identifier : the patient identifier
  - form               : the form to save"
  [{conn :com.eldrix.rsdb/conn :as env} {:keys [patient-identifier form] :as params}]
  {::pco/op-name 'pc4.rsdb/save-form}
  (log/info "save form" params)
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (forms/save-form! conn form))

(pco/defmutation delete-form!
  [{conn :com.eldrix.rsdb/conn :as env}
   {:keys [patient-identifier form] :as params}]
  {::pco/op-name 'pc4.rsdb/delete-form}
  (log/info "delete form" params) (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (forms/delete-form! conn form))

(pco/defmutation undelete-form!
  [{conn :com.eldrix.rsdb/conn :as env}
   {:keys [patient-identifier form] :as params}]
  {::pco/op-name 'pc4.rsdb/undelete-form!}
  (log/info "undelete form" params)
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (forms/undelete-form! conn form))

(pco/defmutation save-result!
  [{conn                 :com.eldrix.rsdb/conn
    manager              :session/authorization-manager
    {user-id :t_user/id} :session/authenticated-user
    :as                  env}
   {:keys [patient-identifier result] :as params}]
  {::pco/op-name 'pc4.rsdb/save-result}
  (log/info "save result request: " params "user: " user-id)
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (try
    (jdbc/with-transaction [txn conn]
      (if-let [result-type (results/result-type-by-entity-name (:t_result_type/result_entity_name result))]
        (let [table-name (name (::results/table result-type))
              id-key (keyword table-name "id")
              user-key (keyword table-name "user_fk")]
          (create-or-save-entity {:id-key  id-key
                                  :params  (assoc result user-key user-id)
                                  :save-fn #(results/save-result! txn %)}))
        (throw (ex-info "missing entity name" params))))
    (catch Exception e (.printStackTrace e))))

(pco/defmutation delete-result!
  [{conn    :com.eldrix.rsdb/conn
    manager :session/authorization-manager
    user    :session/authenticated-user
    :as     env}
   {:keys [patient-identifier result]}]
  {::pco/op-name 'pc4.rsdb/delete-result}
  (log/info "delete result request: " result "user: " user)
  (do (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
      (results/delete-result! conn result)))

(s/def ::notify-death (s/keys :req [:t_patient/patient_identifier
                                    :t_patient/date_death]
                              :opt [:t_death_certificate/part1a
                                    :t_death_certificate/part1b
                                    :t_death_certificate/part1c
                                    :t_death_certificate/part2]))
(pco/defmutation notify-death!
  [{conn    :com.eldrix.rsdb/conn
    manager :session/authorization-manager
    user    :session/authenticated-user
    :as     env}
   {patient-identifier :t_patient/patient_identifier :as params}]
  {::pco/op-name 'pc4.rsdb/notify-death}
  (log/info "notify death request: " params "user: " user)
  (when-not (s/valid? ::notify-death params)
    (throw (ex-info "Invalid notify-death request" (s/explain-data ::notify-death params))))
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (jdbc/with-transaction [txn conn {:isolation :serializable}]
    (patients/notify-death! txn params)))

(pco/defmutation set-date-death!
  [{conn    :com.eldrix.rsdb/conn
    manager :session/authorization-manager
    user    :session/authenticated-user :as env}
   {patient-identifier :t_patient/patient_identifier :as params}]
  {::pco/op-name 'pc4.rsdb/set-date-death}
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (patients/set-date-death conn params))

(pco/defmutation change-pseudonymous-registration
  [{conn :com.eldrix.rsdb/conn, config :com.eldrix.rsdb/config, :as env}
   {patient-pk :t_patient/id :t_patient/keys [patient_identifier nhs_number sex date_birth] :as params}]
  {::pco/op-name 'pc4.rsdb/change-pseudonymous-registration}
  (guard-can-for-patient? env patient_identifier :PATIENT_CHANGE_PSEUDONYMOUS_DATA)
  ;; check that we are not changing the existing NHS number
  (let [{old-nhs-number :t_patient/nhs_number :as existing-patient} (patients/fetch-patient conn params)]
    (when (not= old-nhs-number nhs_number)
      (throw (ex-info "You are currently not permitted to change NHS number" {:existing  existing-patient
                                                                              :requested params}))))
  (if-let [global-salt (:legacy-global-pseudonym-salt config)]
    (jdbc/with-transaction [txn conn {:isolation :serializable}]
      (projects/update-legacy-pseudonymous-patient! txn global-salt patient-pk {:nhs-number nhs_number
                                                                                :date-birth date_birth
                                                                                :sex        sex}))
    (throw (ex-info "missing global salt in configuration" config))))

(s/def :t_user/username string?)
(s/def :t_user/password string?)
(s/def :t_user/new_password string?)
(s/def ::change-password (s/keys :req [:t_user/username
                                       :t_user/new_password]
                                 :opt [:t_user/password]))

(pco/defmutation change-password!
  [{conn               :com.eldrix.rsdb/conn
    authenticated-user :session/authenticated-user, :as env}
   {username     :t_user/username
    password     :t_user/password
    new-password :t_user/new_password, :as params}]
  {::pco/op-name 'pc4.rsdb/change-password}
  (when-not (s/valid? ::change-password params)
    (throw (ex-info "invalid parameters for change-password! " (s/explain-data ::change-password params))))
  (when-not (= username (:t_user/username authenticated-user))
    (throw (ex-info "You cannot change password of a different user" {:requested-user username, :authenticated-user authenticated-user})))
  (log/info "changing password for user" username)
  (let [user (users/fetch-user conn username {:with-credentials true})]
    (if (users/authenticate env user password)
      (users/save-password conn user new-password)
      (throw (ex-info "Cannot change password: incorrect old password." {})))))

(s/def ::save-admission (s/keys :req [:t_episode/patient_fk
                                      :t_episode/date_registration
                                      :t_episode/date_discharge]))
(pco/defmutation save-admission!
  [{conn                 :com.eldrix.rsdb/conn
    {user-id :t_user/id} :session/authenticated-user
    :as                  env} params]
  {::pco/op-name 'pc4.rsdb/save-admission}
  (log/info "save admission request: " params "user: " user-id)
  (when-not (s/valid? ::save-admission (dissoc params :t_episode/id))
    (log/error "invalid save result request" (s/explain-data ::save-admission params))
    (throw (ex-info "Invalid save result request" (s/explain-data ::save-admission params))))
  (let [project-id (or (:t_episode/project_fk params)
                       (:t_project/id (next.jdbc/execute-one! conn (sql/format {:select :id :from :t_project :where [:= :name "ADMISSION"]})))
                       (throw (ex-info "No project named 'ADMISSION' available to be used for default admission episodes." {})))
        params' (assoc params :t_episode/project_fk project-id
                       :t_episode/date_referral (:t_episode/date_registration params)
                       :t_episode/registration_user_fk user-id
                       :t_episode/referral_user_fk user-id
                       :t_episode/discharge_user_fk user-id)]
    (guard-can-for-patient? env (patients/pk->identifier conn (:t_episode/patient_fk params)) :PATIENT_EDIT)
    (log/info "writing episode" params')
    (if (tempid/tempid? (:t_episode/id params'))
      (let [episode (next.jdbc.sql/insert! conn :t_episode (dissoc params' :t_episode/id))]
        (assoc episode :tempids {(:t_episode/id params') (:t_episode/id episode)}))
      (next.jdbc.sql/update! conn :t_episode params' {:id (:t_episode/id params')} {:return-keys true}))))

(pco/defmutation delete-admission!
  [{conn    :com.eldrix.rsdb/conn
    manager :session/authorization-manager
    user    :session/authenticated-user
    :as     env}
   {episode-id :t_episode/id
    patient-fk :t_episode/patient_fk
    :as        params}]
  {::pco/op-name 'pc4.rsdb/delete-admission}
  (if (and episode-id patient-fk)
    (let [patient-identifier (patients/pk->identifier conn patient-fk)]
      (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
      (next.jdbc.sql/delete! conn :t_episode {:id episode-id}))
    (throw (ex-info "Invalid parameters:" params))))

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

(pco/defresolver all-ms-disease-courses
  [{conn :com.eldrix.rsdb/conn} _]
  {::pco/output [{:com.eldrix.rsdb/all-ms-disease-courses [:t_ms_disease_course/id
                                                           :t_ms_disease_course/name]}]}
  {:com.eldrix.rsdb/all-ms-disease-courses (db/execute! conn
                                                        (sql/format {:select [:id :name]
                                                                     :from   [:t_ms_disease_course]}))})
(pco/defresolver medication->reasons-for-stopping
  [_]
  {::pco/output [{:com.eldrix.rsdb/all-medication-reasons-for-stopping
                  [:t_medication_reason_for_stopping/id
                   :t_medication_reason_for_stopping/name]}]}
  {:com.eldrix.rsdb/all-medication-reasons-for-stopping
   (mapv #(hash-map :t_medication_reason_for_stopping/id %
                    :t_medication_reason_for_stopping/name (name %))
         db/medication-reasons-for-stopping)})

(def all-resolvers
  [patient->break-glass
   patient->permissions
   project->permissions
   patient-by-identifier
   patient-by-pk
   patient-by-pseudonym
   patient->current-age
   patient->hospitals
   patient-hospital->flat-hospital
   patient-hospital->nested-hospital
   patient-hospital->authority
   patient-hospital->hospital-crn
   patient->demographics-authority
   patient->country-of-birth
   patient->ethnic-origin
   patient->racial-group
   patient->occupation
   patient->surgery
   patient->death_certificate
   patient->diagnoses
   diagnosis->patient
   patient->has-diagnosis
   patient->summary-multiple-sclerosis
   summary-multiple-sclerosis->events
   ms-events->ordering-errors
   event->summary-multiple-sclerosis
   patient->medications
   medication-by-id
   medication->patient
   medication->events
   medication-event->concept
   patient->addresses
   patient->address
   patient->lsoa11
   (pbir/alias-resolver :t_patient/lsoa11 :uk.gov.ons/lsoa)
   (pbir/alias-resolver :t_address/postcode :uk.gov.ons.nhspd/PCDS)
   address->housing
   address->stored-lsoa
   patient->episodes
   patient->pending-referrals
   patient->suggested-registrations
   patient->administrators
   episode-by-id
   episode->project
   episode->patient
   episode->encounters
   project-by-identifier
   project->parent
   project->specialty
   project->common-concepts
   project->active?
   project->admission?
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
   encounter-by-id
   encounter->patient
   encounter->users
   encounters->encounter_template
   encounter->hospital
   encounter_template->encounter_type
   encounter_template->project
   encounters->form_edss
   form_edss-score->score
   form_edss_fs-score->score
   encounters->form_ms_relapse
   encounters->form_weight_height
   encounter->form_smoking
   encounter->forms
   form-by-id
   patient->results
   user-by-username
   user-by-id
   user-by-nadex
   user->link-to-regulator
   user->nadex
   user->fhir-name
   user->photo
   user->has-photo?
   user->full-name
   user->initials
   user->active-projects
   user->roles
   user->common-concepts
   user->latest-news
   user->job-title
   user->count-unread-messages
   user->count-incomplete-messages
   patient->fhir-human-name
   patient->fhir-gender
   multiple-sclerosis-diagnoses
   all-ms-event-types
   all-ms-disease-courses
   medication->reasons-for-stopping
   ;;
   ;; mutations - VERBS
   ;;
   register-patient!
   register-patient-by-pseudonym!
   register-patient-to-project!
   break-glass!
   search-patient-by-pseudonym
   save-diagnosis!
   save-medication!
   delete-medication!
   save-patient-ms-diagnosis!
   save-pseudonymous-patient-postal-code!
   save-ms-event!
   delete-ms-event!
   ;; encounters
   save-encounter!
   delete-encounter!
   ;; forms
   save-form!
   delete-form!
   undelete-form!
   ;; results
   save-result!
   delete-result!
   notify-death!
   change-password!
   save-admission!
   delete-admission!
   set-date-death!
   change-pseudonymous-registration])

(comment
  (require '[next.jdbc.connection])
  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 1}))

  (project->encounter_templates {:com.eldrix.rsdb/conn conn} {:t_project/id 5})

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

  (require '[com.eldrix.pc4.rsdb.patients])
  (def project-ids (com.eldrix.pc4.rsdb.patients/active-project-identifiers conn 14032))
  (def manager (users/make-authorization-manager conn "ma090906"))
  (def sys-manager (users/make-authorization-manager conn "system"))
  (def unk-manager (users/make-authorization-manager conn "unknown"))
  (auth/authorized? manager project-ids :PATIENT_VIEW)
  (auth/authorized? sys-manager project-ids :PATIENT_VIEW)
  (auth/authorized? unk-manager project-ids :PATIENT_VIEW)
  (auth/authorized? manager project-ids :BIOBANK_CREATE_LOCATION))

