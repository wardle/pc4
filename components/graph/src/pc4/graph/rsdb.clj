(ns pc4.graph.rsdb
  "Integration with the rsdb backend.
  `rsdb` is the Apple WebObjects 'legacy' application that was PatientCare v1-3.

  Depending on real-life usage, we could pre-fetch relationships and therefore
  save multiple database round-trips, particularly for to-one relationships.
  Such a change would be trivial because pathom would simply not bother
  trying to resolve data that already exists."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.server.api-middleware :as api-middleware]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [pc4.dates.interface :as dates]
            [pc4.nhs-number.interface :as nhs-number]
            [pc4.nhspd.interface :as nhspd]
            [pc4.nhs-number.interface :as nnn]
            [pc4.ods.interface :as clods]
            [pc4.rsdb.interface :as rsdb]
            [pc4.snomedct.interface :as hermes])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time LocalDateTime LocalDate)
           (java.util Locale)
           (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)))

(s/def :uk.gov.ons.nhspd/PCD2 string?)
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
  [{authenticated-user :session/authenticated-user, rsdb :com.eldrix/rsdb}
   {:t_patient/keys [patient_identifier break_glass]}]
  {:t_patient/permissions
   (cond
     ;; system user gets all known permissions
     (:t_role/is_system authenticated-user)
     rsdb/all-permissions
     ;; break-glass provides only "limited user" access
     break_glass
     (:LIMITED_USER rsdb/permission-sets)
     ;; otherwise, generate permissions based on intersection of patient and user
     :else
     (let [patient-active-project-ids (rsdb/patient->active-project-identifiers rsdb patient_identifier)
           roles-by-project-id (:t_user/active_roles authenticated-user) ; a map of project-id to PermissionSets for that project
           roles (reduce-kv (fn [acc _ v] (into acc v)) #{} (select-keys roles-by-project-id patient-active-project-ids))]
       (rsdb/expand-permission-sets roles)))})

(pco/defresolver project->permissions
  "Return authorization permissions for current user to access given project."
  [{authenticated-user :session/authenticated-user}
   {project-id :t_project/id}]
  {:t_project/permissions
   (if (:t_role/is_system authenticated-user)
     rsdb/all-permissions
     (let [roles (get (:t_user/active_roles authenticated-user) project-id)]
       (rsdb/expand-permission-sets roles)))})

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
                     (if (or (:disable-auth env) (and permissions (permissions permission)))
                       (resolve env params)                 ;; new resolver calls old resolver if permitted
                       (do (log/warn "unauthorized call to resolver" params)
                           {:t_patient/authorization permissions})))))))))

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
  [{rsdb :com.eldrix/rsdb} {patient_identifier :t_patient/patient_identifier :as params}]
  {::pco/input     [:t_patient/patient_identifier]
   ::pco/transform wrap-tap-resolver
   ::pco/output    patient-properties}
  (when patient_identifier
    (rsdb/fetch-patient rsdb params)))

(pco/defresolver patient-by-pk
  [{rsdb :com.eldrix/rsdb} {patient-pk :t_patient/id :as params}]
  {::pco/output patient-properties}
  (when patient-pk
    (rsdb/fetch-patient rsdb params)))

(pco/defresolver patient-by-pseudonym
  "Resolves patient identifier by a tuple of project id and pseudonym."
  [{rsdb :com.eldrix/rsdb} {project-pseudonym :t_patient/project_pseudonym}]
  {::pco/output [:t_patient/patient_identifier]}
  (when-let [[project-id pseudonym] project-pseudonym]
    (rsdb/patient-by-project-pseudonym rsdb project-id pseudonym)))

(pco/defresolver patient->current-age
  [{:t_patient/keys [date_birth date_death]}]
  {:t_patient/current_age (when-not date_death (dates/age-display date_birth (LocalDate/now)))})

(pco/defresolver address->isb1500-horiz                     ;; TODO: could normalize the raw stored postcode for display here using nhspd library
  [{:t_address/keys [address1 address2 address3 address4 postcode]}]
  {:uk.nhs.cfh.isb1500/address-horizontal
   (str/join ", " (remove str/blank? [address1 address2 address3 address4 postcode]))})

(pco/defresolver patient->isb1504-nhs-number
  [{:t_patient/keys [nhs_number]}]
  {:uk.nhs.cfh.isb1504/nhs-number (nhs-number/format-nnn nhs_number)})

(pco/defresolver patient->isb1505-display-age
  [{:t_patient/keys [date_birth date_death]}]
  {:uk.nhs.cfh.isb1505/display-age (when-not date_death (dates/age-display date_birth (LocalDate/now)))})

(pco/defresolver patient->isb1506-name
  [{:t_patient/keys [first_names last_name title]}]
  {:uk.nhs.cfh.isb1506/patient-name
   (str (str/upper-case last_name) ", "
        first_names
        (when-not (str/blank? title) (str " (" title ")")))})

(pco/defresolver patient->hospitals
  [{rsdb :com.eldrix/rsdb} {patient-pk :t_patient/id}]
  {::pco/output [{:t_patient/hospitals [:t_patient_hospital/hospital_fk
                                        :t_patient_hospital/patient_fk
                                        :t_patient_hospital/hospital_identifier
                                        :t_patient_hospital/patient_identifier
                                        :t_patient_hospital/authoritative]}]}
  {:t_patient/hospitals (vec (rsdb/patient-pk->hospitals rsdb patient-pk))})

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
  [{rsdb :com.eldrix/rsdb} patient]
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
  (let [certificate (rsdb/patient->death-certificate rsdb patient)]
    (assoc certificate :t_patient/death_certificate certificate)))

(def diagnosis-properties
  [:t_diagnosis/concept_fk
   :t_diagnosis/patient_fk
   {:t_diagnosis/diagnosis [:info.snomed.Concept/id]}
   :t_diagnosis/date_diagnosis
   :t_diagnosis/date_diagnosis_accuracy
   :t_diagnosis/date_onset
   :t_diagnosis/date_onset_accuracy
   :t_diagnosis/date_to
   :t_diagnosis/date_to_accuracy
   :t_diagnosis/status
   :t_diagnosis/full_description])

(pco/defresolver patient->diagnoses
  "Returns diagnoses for a patient. Optionally takes a parameter:
  - :ecl - a SNOMED ECL used to constrain the list of diagnoses returned."
  [{hermes :com.eldrix/hermes, rsdb :com.eldrix/rsdb :as env} {patient-pk :t_patient/id}]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/output    [{:t_patient/diagnoses diagnosis-properties}]}
  (let [diagnoses (rsdb/patient->diagnoses rsdb patient-pk)

        ecl (:ecl (pco/params env))
        diagnoses' (if (str/blank? ecl)
                     diagnoses
                     (let [concept-ids (hermes/intersect-ecl hermes (map :t_diagnosis/concept_fk diagnoses) ecl)]
                       (filter #(concept-ids (:t_diagnosis/concept_fk %)) diagnoses)))]
    {:t_patient/diagnoses
     (mapv #(assoc % :t_diagnosis/diagnosis {:info.snomed.Concept/id (:t_diagnosis/concept_fk %)}) diagnoses')}))

(pco/defresolver diagnosis-by-id
  [{rsdb :com.eldrix/rsdb, :as env} {:t_diagnosis/keys [id]}]
  {::pco/input [:t_diagnosis/id]
   ::pco/output diagnosis-properties}
  (when-let [diag (rsdb/diagnosis-by-id rsdb id)]
    (println "diagnosis:" diag)
    (assoc diag :t_diagnosis/diagnosis {:info.snomed.Concept/id (:t_diagnosis/concept_fk diag)})))

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
                            (filter #(rsdb/diagnosis-active? % on-date'))
                            (map :t_diagnosis/concept_fk diagnoses))]
       (boolean (seq (hermes/intersect-ecl hermes concept-ids ecl))))}))

(pco/defresolver patient->summary-multiple-sclerosis        ;; this is misnamed, but belies the legacy system's origins.
  [{rsdb :com.eldrix/rsdb} {patient-identifier :t_patient/patient_identifier}]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/output    [{:t_patient/summary_multiple_sclerosis
                     [:t_summary_multiple_sclerosis/id
                      :t_summary_multiple_sclerosis/ms_diagnosis_fk
                      {:t_summary_multiple_sclerosis/patient [:t_patient/patient_identifier]}
                      {:t_summary_multiple_sclerosis/ms_diagnosis [:t_ms_diagnosis/id :t_ms_diagnosis/name]}
                      :t_ms_diagnosis/id                    ; we flatten this to-one attribute
                      :t_ms_diagnosis/name]}]}
  (let [sms (rsdb/patient->summary-multiple-sclerosis rsdb patient-identifier)
        ms-diagnosis-id (:t_ms_diagnosis/id sms)]
    {:t_patient/summary_multiple_sclerosis
     (assoc sms :t_summary_multiple_sclerosis/patient {:t_patient/patient_identifier patient-identifier}
                :t_summary_multiple_sclerosis/ms_diagnosis (when ms-diagnosis-id {:t_ms_diagnosis/id   ms-diagnosis-id
                                                                                  :t_ms_diagnosis/name (:t_ms_diagnosis/name sms)}))}))

(def ms-event-properties
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
   :t_ms_event_type/abbreviation])

(pco/defresolver summary-multiple-sclerosis->events
  [{rsdb :com.eldrix/rsdb} {sms-id :t_summary_multiple_sclerosis/id}]
  {::pco/output [{:t_summary_multiple_sclerosis/events
                  ms-event-properties}]}
  (let [events (rsdb/patient->ms-events rsdb sms-id)]
    {:t_summary_multiple_sclerosis/events events}))

(pco/defresolver ms-event-by-id
  [{rsdb :com.eldrix/rsdb} {id :t_ms_event/id}]
  {::pco/output ms-event-properties}
  (rsdb/ms-event-by-id rsdb id))

(pco/defresolver ms-events->ordering-errors
  [{:t_summary_multiple_sclerosis/keys [events]}]
  {:t_summary_multiple_sclerosis/event_ordering_errors
   (mapv rsdb/ms-event-ordering-error->en-GB
         (rsdb/ms-event-ordering-errors (sort-by :t_ms_event/date events)))})

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

(defn medication-event->event-concept
  [{evt-concept-id :t_medication_event/event_concept_fk :as evt}]
  (assoc evt :t_medication_event/event_concept
             (when evt-concept-id {:info.snomed.Concept/id evt-concept-id})))

(pco/defresolver patient->medications
  [{rsdb :com.eldrix/rsdb, hermes :com.eldrix/hermes, :as env} patient]
  {::pco/transform (make-wrap-patient-authorize)
   ::pco/input     [:t_patient/id]
   ::pco/output    [{:t_patient/medications
                     (into medication-properties
                           [{:t_medication/events medication-event-properties}])}]}
  {:t_patient/medications
   (mapv #(-> %
              (assoc :t_medication/medication {:info.snomed.Concept/id (:t_medication/medication_concept_fk %)})
              (update :t_medication/events (fn [evts] (mapv medication-event->event-concept evts))))
         (rsdb/patient->medications-and-events rsdb patient {:ecl (:ecl (pco/params env))}))})

(pco/defresolver medication-by-id
  [{rsdb :com.eldrix/rsdb, :as env} {:t_medication/keys [id]}]
  {::pco/output medication-properties}
  (when-let [med (rsdb/medication-by-id rsdb id)]
    (assoc med :t_medication/medication {:info.snomed.Concept/id (:t_medication/medication_concept_fk med)})))

(pco/defresolver medication->patient
  [{patient-pk :t_medication/patient_fk}]
  {:t_medication/patient {:t_patient/id patient-pk}})

(pco/defresolver medication->events
  [{rsdb :com.eldrix/rsdb} {:t_medication/keys [id]}]
  {::pco/output [{:t_medication/events medication-event-properties}]}
  {:t_medication/events
   (->> (get (rsdb/medications->events rsdb [id]) 0)
        (mapv medication-event->event-concept))})

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
  [{rsdb :com.eldrix/rsdb} patient]
  {::pco/input  [:t_patient/id]
   ::pco/output [{:t_patient/addresses address-properties}]}
  {:t_patient/addresses (rsdb/patient->addresses rsdb patient)})

(pco/defresolver patient->address
  "Returns the current address, or the address for the specified date.
  Will make use of existing data in t_patient/addresses, if key exists.
  This resolver takes an optional parameter :date. If provided, the address
  for the specified date will be given.
  Parameters:
  - :date - a ISO LOCAL DATE string e.g \"2020-01-01\" or an instance of
            java.time.LocalDate."
  [{rsdb :com.eldrix/rsdb :as env} {addresses :t_patient/addresses :as patient}]
  {::pco/input  [:t_patient/id
                 (pco/? :t_patient/addresses)]
   ::pco/output [{:t_patient/address address-properties}]}
  (let [date (:date (pco/params env))                       ;; doesn't matter if nil
        date' (cond (nil? date) nil
                    (string? date) (LocalDate/parse date)
                    :else date)
        addresses' (or addresses (rsdb/patient->addresses rsdb patient))]
    {:t_patient/address (rsdb/address-for-date addresses' date')}))

(def lsoa-re #"^[a-zA-Z]\d{8}$")

(pco/defresolver patient->lsoa11
  "Returns a patient's LSOA as a top-level property"
  [{rsdb :com.eldrix/rsdb, nhspd :com.eldrix/nhspd} {addresses :t_patient/addresses :as patient}]
  {::pco/input  [:t_patient/id
                 (pco/? :t_patient/addresses)]
   ::pco/output [:t_patient/lsoa11]}
  (let [addresses' (or addresses (rsdb/patient->addresses rsdb patient))
        current-address (rsdb/address-for-date addresses')
        address1 (:t_address/address1 current-address)
        postcode (:t_address/postcode current-address)]
    {:t_patient/lsoa11
     (when current-address
       (or
         (when (and address1 (re-matches lsoa-re address1)) address1)
         (when postcode (get (nhspd/fetch-postcode nhspd postcode) "LSOA11"))))}))

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
  [{rsdb :com.eldrix/rsdb, :as env} {patient-pk :t_patient/id}]
  {::pco/output [{:t_patient/episodes episode-properties}]}
  {:t_patient/episodes
   (let [project-id-or-ids (:t_project/id (pco/params env))]
     (->> (rsdb/patient->episodes rsdb patient-pk project-id-or-ids)
          (mapv #(assoc % :t_episode/status (rsdb/episode-status %)
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
       (filter #(rsdb/authorized? manager (hash-set (:t_episode/project_fk %)) :PROJECT_REGISTER) pending-referrals)
       (throw (ex-info "invalid 'mode' for t_patient/pending_referrals" (pco/params env)))))})

(pco/defresolver patient->suggested-registrations
  "Return suggested registrations given the patient and current user. This
  could make suggestions based on current diagnoses and treatments, and project
  configurations. Such additional functionality will be made available via
  parameters."
  [{rsdb :com.eldrix/rsdb, user :session/authenticated-user, :as env} patient]
  {::pco/input  [:t_patient/patient_identifier]
   ::pco/output [{:t_patient/suggested_registrations [:t_project/id :t_project/title]}]}
  {:t_patient/suggested_registrations
   (rsdb/suggested-registrations rsdb user patient)})

(pco/defresolver patient->administrators
  "Return administrators linked to the projects to which this patient is linked."
  [{rsdb :com.eldrix/rsdb} {:t_patient/keys [patient_identifier]}]
  {::pco/output [{:t_patient/administrators [:t_user/id :t_user/username :t_user/title :t_user/first_names :t_user/last_name]}]}
  ;; TODO: patient->active-project-ids should be its own resolver to avoid duplication
  {:t_patient/administrators (rsdb/projects->administrator-users rsdb (rsdb/patient->active-project-identifiers rsdb patient_identifier))})

(pco/defresolver episode-by-id
  [{rsdb :com.eldrix/rsdb} {:t_episode/keys [id]}]
  {::pco/output episode-properties}
  (let [result (rsdb/episode-by-id rsdb id)]
    (assoc result :t_episode/status (rsdb/episode-status result)
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
  [{rsdb :com.eldrix/rsdb} {project-id :t_episode/project_fk}]
  {::pco/output [{:t_episode/project project-properties}]}
  {:t_episode/project (rsdb/project-by-id rsdb project-id)})
(pco/defresolver episode->patient
  [{patient-pk :t_episode/patient_fk}]
  {:t_episode/patient {:t_patient/id patient-pk}})

(pco/defresolver episode->encounters
  [{rsdb :com.eldrix/rsdb} {:t_episode/keys [id]}]
  {:t_episode/encounters (rsdb/episode-id->encounters rsdb id)})

(pco/defresolver project-by-identifier
  [{rsdb :com.eldrix/rsdb} {project-id :t_project/id}]
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
  (when-let [p (rsdb/project-by-id rsdb project-id)]
    (-> p
        (assoc :t_project/administrator_user
               (when-let [admin-user-id (:t_project/administrator_user_fk p)] {:t_user/id admin-user-id}))
        (assoc :t_project/parent_project
               (when-let [parent-project-id (:t_project/parent_project_fk p)]
                 {:t_project/id parent-project-id})))))

(pco/defresolver project->count_registered_patients         ;; TODO: should include child projects?
  [{rsdb :com.eldrix/rsdb} {project-id :t_project/id}]
  {::pco/output [:t_project/count_registered_patients]}
  {:t_project/count_registered_patients (rsdb/projects->count-registered-patients rsdb [project-id])})

(pco/defresolver project->count_pending_referrals           ;; TODO: should include child projects?
  [{rsdb :com.eldrix/rsdb} {project-id :t_project/id}]
  {::pco/output [:t_project/count_pending_referrals]}
  {:t_project/count_pending_referrals (rsdb/projects->count-pending-referrals rsdb [project-id])})

(pco/defresolver project->count_discharged_episodes         ;; TODO: should include child projects?
  [{rsdb :com.eldrix/rsdb} {project-id :t_project/id}]
  {::pco/output [:t_project/count_discharged_episodes]}
  {:t_project/count_discharged_episodes (rsdb/projects->count-discharged-episodes rsdb [project-id])})

(pco/defresolver project->slug
  [{title :t_project/title}]
  {::pco/output [:t_project/slug]}
  {:t_project/slug (rsdb/project-title->slug title)})

(pco/defresolver project->active?
  [project]
  {::pco/input  [:t_project/date_from :t_project/date_to]
   ::pco/output [:t_project/active?]}
  {:t_project/active? (rsdb/project->active? project)})

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
  [{rsdb :com.eldrix/rsdb} {project-id :t_project/id}]
  {::pco/output [{:t_project/encounter_templates [:t_encounter_template/id
                                                  :t_encounter_template/encounter_type_fk
                                                  :t_encounter_template/is_deleted
                                                  :t_encounter_template/title
                                                  :t_encounter_type/id
                                                  {:t_encounter_template/encounter_type [:t_encounter_type/id]}]}]}
  {:t_project/encounter_templates
   (->> (rsdb/project->encounter-templates rsdb project-id)
        (map #(let [encounter-type-id (:t_encounter_template/encounter_type_fk %)]
                (assoc % :t_encounter_type/id encounter-type-id
                         :t_encounter_template/encounter_type {:t_encounter_type/id encounter-type-id}))))})

(pco/defresolver project->parent
  [{parent-id :t_project/parent_project_fk}]
  {::pco/output [{:t_project/parent [:t_project/id]}]}
  {:t_project/parent {:t_project/id parent-id}})

(pco/defresolver project->all-parents
  [{rsdb :com.eldrix/rsdb} {project-id :t_project/id}]
  {::pco/output [{:t_project/all-parents [:t_project/id]}]}
  {:t_project/all-parents (rsdb/project->all-parents rsdb project-id)})

(pco/defresolver project->all-children
  [{rsdb :com.eldrix/rsdb} {project-id :t_project/id}]
  {::pco/output [{:t_project/all-children [:t_project/id]}]}
  {:t_project/all-parents (rsdb/project->all-children rsdb project-id)})

(pco/defresolver project->specialty
  [{specialty-concept-fk :t_project/specialty_concept_fk}]
  {::pco/output [{:t_project/specialty [:info.snomed.Concept/id]}]}
  {:t_project/specialty (when specialty-concept-fk {:info.snomed.Concept/id specialty-concept-fk})})

(pco/defresolver project->common-concepts
  "Resolve common concepts for the project, optionally filtering by a SNOMED
  expression (ECL)."
  [{rsdb :com.eldrix/rsdb hermes :com.eldrix/hermes :as env} {:t_project/keys [id]}]
  {::pco/input  [:t_project/id]
   ::pco/output [{:t_project/common_concepts [:info.snomed.Concept/id]}]}
  {:t_project/common_concepts
   (let [concept-ids (rsdb/project->common-concepts rsdb id)
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
  [{rsdb :com.eldrix/rsdb, :as env} {id :t_project/id}]
  {::pco/output [{:t_project/users [:t_user/id
                                    {:t_user/roles [:t_project_user/role
                                                    :t_project_user/date_from
                                                    :t_project_user/date_to]}
                                    :t_project_user/role
                                    :t_project_user/date_from
                                    :t_project_user/date_to
                                    :t_project_user/active?]}]}
  {:t_project/users (rsdb/project->users rsdb id (pco/params env))})

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
  [{rsdb :com.eldrix/rsdb} {patient-pk :t_patient/id}]
  {::pco/output [{:t_patient/encounters encounter-properties}]}
  {:t_patient/encounters (->> (rsdb/patient->encounters rsdb patient-pk)
                              (mapv #(assoc %
                                       :t_encounter/active (not (:t_encounter/is_deleted %))
                                       :t_encounter/is_locked (encounter-locked? %))))})

(pco/defresolver patient->paged-encounters
  "A resolver for pages of encounters."
  [{rsdb :com.eldrix/rsdb :as env} {patient-id :t_patient/id}]
  {::pco/output [{:t_patient/paged_encounters [{:paging [:cursor :results]}]}]}
  (let [{:keys [cursor page-size]} (pco/params env)]))

(pco/defresolver patient->results
  [{rsdb :com.eldrix/rsdb} {patient-identifier :t_patient/patient_identifier}]
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
     (vec (rsdb/patient->results rsdb patient-identifier)))})

(pco/defresolver encounter-by-id
  [{rsdb :com.eldrix/rsdb} {encounter-id :t_encounter/id}]
  {::pco/output encounter-properties}
  (let [{:t_encounter/keys [is_deleted] :as encounter}
        (rsdb/encounter-by-id rsdb encounter-id)]
    (assoc encounter
      :t_encounter/active (not is_deleted)
      :t_encounter/is_locked (encounter-locked? encounter))))

(pco/defresolver encounter->patient
  [{:t_encounter/keys [patient_fk]}]
  {:t_encounter/patient {:t_patient/id patient_fk}})

(pco/defresolver encounter->patient-age
  "Resolve :t_encounter/patient_age which will be the calculated age of the
  patient on the date of the encounter."
  [{:t_encounter/keys [date_time patient]}]
  {::pco/input [:t_encounter/date_time
                {:t_encounter/patient [:t_patient/date_birth
                                       :t_patient/date_death]}]}
  {:t_encounter/patient_age
   (dates/calculate-age (:t_patient/date_birth patient)
                        :date-death (:t_patient/date_death patient)
                        :on-date (.toLocalDate date_time))})

(pco/defresolver encounter->best-hospital-crn
  [{rsdb :com.eldrix/rsdb, ods-svc :com.eldrix.clods.graph/svc}
   {:t_encounter/keys [hospital_fk patient_fk]}]
  {:t_encounter/hospital_crn (rsdb/patient-pk->crn-for-org rsdb patient_fk hospital_fk)})

(pco/defresolver encounter->users
  "Return the users for the encounter.
  We flatten the relationship here, avoiding the join table."
  [{rsdb :com.eldrix/rsdb} {encounter-id :t_encounter/id}]
  {::pco/output [{:t_encounter/users [:t_user/id]}]}
  {:t_encounter/users
   (->> (rsdb/encounter->users rsdb encounter-id)
        (map :t_encounter_user/userid)
        (mapv #(hash-map :t_user/id %)))})

(pco/defresolver encounter->hospital
  [{hospital-id :t_encounter/hospital_fk}]
  {::pco/output [{:t_encounter/hospital [:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id]}]}
  {:t_encounter/hospital (when hospital-id {:urn:oid:2.16.840.1.113883.2.1.3.2.4.18.48/id hospital-id})})

(pco/defresolver encounters->encounter_template
  [{rsdb :com.eldrix/rsdb} encounters]
  {::pco/input  [:t_encounter/encounter_template_fk]
   ::pco/output [{:t_encounter/encounter_template [:t_encounter_template/id
                                                   :t_encounter_template/project_fk
                                                   :t_encounter_template/encounter_type_fk]}]
   ::pco/batch? true}
  (let [encounter-template-ids (map :t_encounter/encounter_template_fk encounters)
        encounter-templates (group-by :t_encounter_template/id
                                      (rsdb/encounter-templates-by-ids rsdb encounter-template-ids))]
    (into []
          (map (fn [id] {:t_encounter/encounter_template (first (get encounter-templates id))}))
          encounter-template-ids)))

(pco/defresolver encounter_template->encounter_type
  [{rsdb :com.eldrix/rsdb} {encounter-type-id :t_encounter_template/encounter_type_fk}]
  {::pco/output [{:t_encounter_template/encounter_type [:t_encounter_type/id
                                                        :t_encounter_type/name
                                                        :t_encounter_type/seen_in_person]}]}
  {:t_encounter_template/encounter_type (rsdb/encounter-type-by-id rsdb encounter-type-id)})

(pco/defresolver encounter_template->project
  [{:t_encounter_template/keys [project_fk]}]
  {:t_encounter_template/project {:t_project/id project_fk}})

(pco/defresolver encounters->form_edss
  [{rsdb :com.eldrix/rsdb} encounters]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/form_edss
                  [:t_form_edss/id
                   :t_form_edss/edss
                   :t_form_edss/edss_score
                   :t_form_edss_fs/id
                   :t_form_edss_fs/edss_score]}]
   ::pco/batch? true}
  (let [encounter-ids (map :t_encounter/id encounters)
        edss (group-by :t_form_edss/encounter_fk (rsdb/encounter-ids->form-edss rsdb encounter-ids))
        edss-fs (group-by :t_form_edss_fs/encounter_fk (rsdb/encounter-ids->form-edss-fs rsdb encounter-ids))]
    (into []
          (map (fn [encounter-id]
                 {:t_encounter/form_edss (merge (first (get edss encounter-id))
                                                (first (get edss-fs encounter-id)))}))
          encounter-ids)))

(pco/defresolver form_edss-score->score
  [{edss_score :t_form_edss/edss_score}]
  {::pco/input  [:t_form_edss/edss_score]
   ::pco/output [:t_form_edss/score]}
  {:t_form_edss/score (rsdb/edss-score->score edss_score)})

(pco/defresolver form_edss_fs-score->score
  [{edss_score :t_form_edss_fs/edss_score}]
  {::pco/input  [:t_form_edss_fs/edss_score]
   ::pco/output [:t_form_edss_fs/score]}
  {:t_form_edss_fs/score (rsdb/edss-score->score edss_score)})

(pco/defresolver encounters->form_ms_relapse
  [{rsdb :com.eldrix/rsdb} encounters]
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
        forms (group-by :t_form_ms_relapse/encounter_fk (rsdb/encounter-ids->form-ms-relapse rsdb encounter-ids))]
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
  [{rsdb :com.eldrix/rsdb} encounters]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/form_weight_height [:t_form_weight_height/id
                                                   :t_form_weight_height/weight_kilogram
                                                   :t_form_weight_height/height_metres]}]
   ::pco/batch? true}
  (let [encounter-ids (map :t_encounter/id encounters)
        forms (group-by :t_form_weight_height/encounter_fk (rsdb/encounter-ids->form-weight-height rsdb encounter-ids))]
    (into []
          (map (fn [encounter-id]
                 {:t_encounter/form_weight_height (first (get forms encounter-id))}))
          encounter-ids)))

(pco/defresolver encounter->form_smoking
  [{rsdb :com.eldrix/rsdb} {encounter-id :t_encounter/id}]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/form_smoking_history [:t_smoking_history/id
                                                     :t_smoking_history/current_cigarettes_per_day
                                                     :t_smoking_history/status]}]}
  {:t_encounter/form_smoking_history (rsdb/encounter-id->form-smoking-history rsdb encounter-id)})

(defn form-assoc-context
  [{:form/keys [encounter_fk user_fk] :as form}]
  (assoc form
    :form/encounter {:t_encounter/id encounter_fk}
    :form/user {:t_user/id user_fk}))

(pco/defresolver encounter->forms
  [{rsdb :com.eldrix/rsdb} {encounter-id :t_encounter/id}]
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
        (rsdb/encounter-id->forms-and-form-types rsdb encounter-id)]
    {:t_encounter/available_form_types  (or available-form-types [])
     :t_encounter/optional_form_types   (or optional-form-types [])
     :t_encounter/mandatory_form_types  (or mandatory-form-types [])
     :t_encounter/existing_form_types   (or existing-form-types [])
     :t_encounter/completed_forms       (map form-assoc-context completed-forms)
     :t_encounter/duplicated_form_types (or duplicated-form-types [])
     :t_encounter/deleted_forms         (map form-assoc-context deleted-forms)}))

(pco/defresolver form-by-id
  [{rsdb :com.eldrix/rsdb :as env} {form-id :form/id}]
  {::pco/output [:form/id
                 :form/user_fk
                 :form/encounter_fk
                 :form/summary_result
                 {:form/user [:t_user/id]}
                 {:form/encounter [:t_encounter/id]}]}
  (if-let [encounter-id (get-in env [:query-params :encounter-id])]
    (let [forms (rsdb/encounter-id->forms rsdb encounter-id {:include-deleted true})
          by-id (reduce (fn [acc {:form/keys [id] :as form}] (assoc acc id form)) {} forms)
          form (get by-id form-id)]
      (log/debug "form by id:" {:encounter-id encounter-id :form-id form-id :result form})
      (form-assoc-context form))
    (do
      (log/error "Missing hint on parameters" (:query-params env))
      (throw (ex-info "Missing hint on parameters for form. Specify :encounter-id in load parameters" (:query-params env))))))

(pco/defresolver encounter->forms_generic_procedures
  [{rsdb :com.eldrix/rsdb} encounters]
  {::pco/input  [:t_encounter/id]
   ::pco/output [{:t_encounter/forms_generic_procedure [:t_form_procedure_generic/id
                                                        :t_form_procedure_generic/procedure_concept_fk
                                                        :info.snomed.Concept/id
                                                        :t_form_procedure_generic/notes]}]}
  ;;TODO: this is to show we would handle forms that support multiple per encounter like this....
  (throw (ex-info "not implemented" {})))

(def user-properties
  [:t_user/id
   :t_user/username
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
  [{rsdb :com.eldrix/rsdb} {username :t_user/username}]
  {::pco/output user-properties}
  (rsdb/user-by-username rsdb username))

(pco/defresolver user-by-id
  [{rsdb :com.eldrix/rsdb} {id :t_user/id}]
  {::pco/output user-properties}
  (rsdb/user-by-id rsdb id))

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
  [{rsdb :com.eldrix/rsdb} {username :wales.nhs.nadex/sAMAccountName}]
  {::pco/output user-properties}
  (when-let [user (rsdb/user-by-username rsdb username)]
    (when (= (:t_user/authentication_method user) "NADEX")
      user)))

(pco/defresolver user->photo
  "Returns the user photo as binary data."
  [{rsdb :com.eldrix/rsdb} {:t_user/keys [username]}]
  {::pco/output [{:t_user/photo [:t_photo/data
                                 :t_photo/mime_type]}]}
  (let [photo (rsdb/fetch-user-photo rsdb username)]
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
  [{rsdb :com.eldrix/rsdb} {username :t_user/username}]
  {::pco/output [{:t_user/active_projects [:t_project/id]}]}
  {:t_user/active_projects (filterv rsdb/project->active? (rsdb/user->projects rsdb username))})

(pco/defresolver user->colleagues
  [{rsdb :com.eldrix/rsdb} {username :t_user/username}]
  {::pco/output [{:t_user/colleagues [:t_user/id
                                      :t_user/username
                                      :t_user/first_names
                                      :t_user/last_name
                                      :t_user/full_name]}]}
  (let [user-projects (rsdb/user->projects rsdb username)
        project-ids (map :t_project/id user-projects)]
    {:t_user/colleagues 
     (when (seq project-ids)
       (->> (mapcat #(rsdb/project->users rsdb % {:group-by :user :active true}) project-ids)
            (remove #(= (:t_user/username %) username))
            (distinct)))}))

(pco/defresolver user->roles
  [{rsdb :com.eldrix/rsdb, :as env} {username :t_user/username}]
  {::pco/output [{:t_user/roles [:t_project_user/date_from
                                 :t_project_user/date_to
                                 :t_project_user/active?
                                 :t_project_user/permissions
                                 :t_project/id
                                 :t_project/active?]}]}
  (let [{:keys [project-id active-roles active-projects]} (pco/params env)]
    {:t_user/roles
     (cond->> (rsdb/user->roles rsdb username {:t_project/id project-id})
              active-roles (filter :t_project_user/active?)
              active-projects (filter :t_project/active?))}))

(pco/defresolver user->common-concepts
  "Resolve common concepts for the user, based on project membership, optionally
  filtering by a SNOMED expression (ECL). Language preferences can be specified
  using parameter `:accept-language` with a comma-separated list of preferences."
  [{rsdb :com.eldrix/rsdb, hermes :com.eldrix/hermes, :as env} {user-id :t_user/id}]
  {::pco/output [{:t_user/common_concepts
                  [:info.snomed.Concept/id
                   {:info.snomed.Concept/preferredDescription
                    [:info.snomed.Description/id
                     :info.snomed.Description/term]}]}]}
  (let [concept-ids (rsdb/user->common-concepts rsdb user-id)
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
  [{rsdb :com.eldrix/rsdb} {username :t_user/username}]
  {::pco/output [{:t_user/latest_news [:t_news/id
                                       :t_news/title
                                       :t_news/body
                                       {:t_news/author [:t_user/id]}]}]}
  {:t_user/latest_news (->> (rsdb/user->latest-news rsdb username)
                            (mapv #(assoc % :t_news/author (select-keys % [:t_user/id :t_user/first_names :t_user/last_name]))))})

(pco/defresolver user->job-title
  [{custom-job-title :t_user/custom_job_title job-title :t_job_title/name :as user}]
  {::pco/input  [:t_user/custom_job_title (pco/? :t_job_title/name)]
   ::pco/output [:t_user/job_title]}
  {:t_user/job_title (rsdb/user->job-title user)})

(pco/defresolver user->count-unread-messages
  [{rsdb :com.eldrix/rsdb} {username :t_user/username}]
  {:t_user/count_unread_messages (rsdb/user->count-unread-messages rsdb username)})

(pco/defresolver user->count-incomplete-messages
  [{rsdb :com.eldrix/rsdb} {username :t_user/username}]
  {:t_user/count_incomplete_messages (rsdb/user->count-incomplete-messages rsdb username)})

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
  [{rsdb    :com.eldrix/rsdb
    manager :session/authorization-manager
    user    :session/authenticated-user}
   {:keys [project-id nhs-number] :as params}]
  {::pco/op-name 'pc4.rsdb/register-patient
   ::pco/output  [:t_patient/patient_identifier
                  :t_episode/project_fk]}
  (log/info "register patient:" {:params params})
  (if-not (and manager (rsdb/authorized? manager #{project-id} :PATIENT_REGISTER))
    (throw (ex-info "Not authorized" {}))
    (let [user-id (:t_user/id user)]
      (if (s/valid? ::register-patient params)
        (rsdb/register-patient! rsdb project-id user-id params)
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
  [{rsdb    :com.eldrix/rsdb
    manager :session/authorization-manager
    user    :session/authenticated-user}
   {:keys [project-id nhs-number date-birth] :as params}]
  {::pco/op-name 'pc4.rsdb/register-patient-by-pseudonym
   ::pco/output  [:t_patient/patient_identifier
                  :t_episode/stored_pseudonym
                  :t_episode/project_fk]}
  (log/info "register patient by pseudonym: " {:user user :params params})
  (if-not (and manager (rsdb/authorized? manager #{project-id} :PATIENT_REGISTER))
    (throw (ex-info "Not authorized" {}))
    (let [params' (assoc params :user-id (:t_user/id user)
                                :nhs-number (nnn/normalise nhs-number)
                                :date-birth (cond
                                              (instance? LocalDate date-birth) date-birth
                                              (string? date-birth) (LocalDate/parse date-birth)
                                              :else (throw (ex-info "failed to parse date-birth" params))))] ;; TODO: better automated coercion
      (if (s/valid? ::register-patient-by-pseudonym params')
        (rsdb/register-legacy-pseudonymous-patient! rsdb params')
        (log/error "invalid call" (s/explain-data ::register-patient-by-pseudonym params'))))))

(pco/defmutation search-patient-by-pseudonym
  "Search for a patient using a pseudonymous project-specific identifier.
  This uses the legacy approach, which *will* be deprecated."
  [{rsdb :com.eldrix/rsdb} {:keys [project-id pseudonym] :as params}]
  {::pco/op-name 'pc4.rsdb/search-patient-by-pseudonym
   ::pco/output  [:t_patient/id
                  :t_patient/patient_identifier
                  :t_episode/stored_pseudonym
                  :t_episode/project_fk]}
  (log/debug "search-patient-by-pseudonym" params)
  (rsdb/patient-by-project-pseudonym rsdb project-id pseudonym))

(defn guard-can-for-patient?                                ;; TODO: turn into a macro for defmutation?
  [{rsdb :com.eldrix/rsdb manager :session/authorization-manager :as env} patient-identifier permission]
  (when-not manager
    (throw (ex-info "missing authorization manager" {:expected :session/authorization-manager, :found env})))
  (when-not patient-identifier
    (throw (ex-info "invalid request: missing patient-identifier" {})))
  (let [project-ids (rsdb/patient->active-project-identifiers rsdb patient-identifier)]
    (when-not (rsdb/authorized? manager project-ids permission)
      (throw (ex-info "You are not authorised to perform this operation" {:patient-identifier patient-identifier
                                                                          :permission         permission})))))
(defn guard-can-for-project?                                ;; TODO: turn into a macro for defmutation?
  [{rsdb :com.eldrix/rsdb manager :session/authorization-manager :as env} project-id permission]
  (when-not manager
    (throw (ex-info "missing authorization manager" {:expected :session/authorization-manager, :found env})))
  (let [project-ids #{project-id}]
    (when-not (rsdb/authorized? manager project-ids permission)
      (throw (ex-info "You are not authorised to perform this operation" {:project-id project-id
                                                                          :permission permission})))))

(pco/defmutation register-patient-to-project!
  [{rsdb :com.eldrix/rsdb, user :session/authenticated-user :as env} {:keys [patient project-id] :as params}]
  {::pco/op-name 'pc4.rsdb/register-patient-to-project}
  (when (or (not (s/valid? (s/keys :req [:t_patient/id :t_patient/patient_identifier]) patient)) (not (int? project-id)))
    (throw (ex-info "invalid call" params)))
  (guard-can-for-project? env project-id :PATIENT_REGISTER)
  (rsdb/register-patient-project! rsdb project-id (:t_user/id user) patient))

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
            :opt [:t_diagnosis/id
                  :t_diagnosis/date_onset
                  :t_diagnosis/date_diagnosis
                  :t_diagnosis/date_onset_accuracy
                  :t_diagnosis/date_diagnosis_accuracy
                  :t_diagnosis/date_to
                  :t_diagnosis/date_to_accuracy])
    valid-diagnosis-status?
    ordered-diagnostic-dates?))

(pco/defmutation save-diagnosis!
  [{rsdb                 :com.eldrix/rsdb
    manager              :session/authorization-manager
    {user-id :t_user/id} :session/authenticated-user
    :as                  env}
   {:t_patient/keys [patient_identifier], diagnosis-id :t_diagnosis/id, :as params}]
  {::pco/op-name 'pc4.rsdb/save-diagnosis}
  (log/info "save diagnosis request: " params "user: " user-id)
  (let [params' (assoc params :t_diagnosis/user_fk (:t_user/id user-id)
                              :t_diagnosis/concept_fk (get-in params [:t_diagnosis/diagnosis :info.snomed.Concept/id]))]
    (if-not (s/valid? ::save-diagnosis (dissoc params' :t_diagnosis/id))
      (do (log/error "invalid call" (s/explain-data ::save-diagnosis params'))
          (throw (ex-info "Invalid data" (s/explain-data ::save-diagnosis params'))))
      (do (guard-can-for-patient? env patient_identifier :PATIENT_EDIT)
          (let [diag (if (or (nil? diagnosis-id) (com.fulcrologic.fulcro.algorithms.tempid/tempid? diagnosis-id))
                       (rsdb/create-diagnosis! rsdb {:t_patient/patient_identifier patient_identifier} (dissoc params' :t_diagnosis/id))
                       (rsdb/update-diagnosis! rsdb params'))]
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
  [{rsdb    :com.eldrix/rsdb
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
    (guard-can-for-patient? env (or patient-id (rsdb/patient-pk->patient-identifier rsdb patient-pk)) :PATIENT_EDIT)
    (if-not (s/valid? ::save-medication params')
      (log/error "invalid call" (s/explain-data ::save-medication params'))
      (let [med (rsdb/upsert-medication! rsdb params')]
        ;; add properties to make graph navigation possible from this medication
        (cond-> (assoc-in med [:t_medication/medication :info.snomed.Concept/id] (:t_medication/medication_concept_fk med))
          create? (assoc :tempids {medication-id (:t_medication/id med)}))))))

(s/def ::delete-medication
  (s/keys :req [:t_medication/id (or :t_patient/patient_identifier :t_medication/patient_fk)]))
(pco/defmutation delete-medication!
  "Delete a medication. Parameters are a map containing
    :t_medication/id : (mandatory) - the identifier for the medication
    :t_medication/patient_fk
    :t_patient/patient_identifier "
  [{rsdb :com.eldrix/rsdb, manager :authorization-manager :as env}
   {patient-id :t_patient/patient_identifier, patient-pk :t_medication/patient_fk, :as params}]
  {::pco/op-name 'pc4.rsdb/delete-medication}
  (log/info "delete medication request" params)
  (if-not (s/valid? ::delete-medication params)
    (log/error "invalid call" (s/explain-data ::delete-medication params))
    (do (guard-can-for-patient? env (or patient-id (rsdb/patient-pk->patient-identifier rsdb patient-pk)) :PATIENT_EDIT)
        (rsdb/delete-medication! rsdb params))))

(s/def ::save-ms-diagnosis
  (s/keys :req [:t_user/id
                :t_ms_diagnosis/id
                :t_patient/patient_identifier]))

(pco/defmutation save-patient-ms-diagnosis!                 ;; TODO: could update main diagnostic list...
  [{rsdb                 :com.eldrix/rsdb
    {user-id :t_user/id} :session/authenticated-user
    :as                  env}
   {patient-identifier :t_patient/patient_identifier :as params}]
  {::pco/op-name 'pc4.rsdb/save-ms-diagnosis}
  (log/info "save ms diagnosis:" params " user:" user-id)
  (let [params' (assoc params :t_user/id user-id)]
    (when-not (s/valid? ::save-ms-diagnosis params')
      (log/error "invalid call" (s/explain-data ::save-ms-diagnosis params'))
      (throw (ex-info "Invalid data" (s/explain-data ::save-ms-diagnosis params'))))
    (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
    (rsdb/save-ms-diagnosis! rsdb params')))

(s/def ::save-pseudonymous-postal-code
  (s/keys :req [:t_patient/patient_identifier
                :uk.gov.ons.nhspd/PCD2]))

(pco/defmutation save-pseudonymous-patient-postal-code!
  [{rsdb :com.eldrix/rsdb
    ods  :com.eldrix.clods.graph/svc
    user :session/authenticated-user}
   {patient-identifier :t_patient/patient_identifier
    postcode           :uk.gov.ons.nhspd/PCD2 :as params}]
  {::pco/op-name 'pc4.rsdb/save-pseudonymous-patient-postal-code}
  (log/info "saving pseudonymous postal code" {:params params :ods ods})
  (if-not (s/valid? ::save-pseudonymous-postal-code params)
    (throw (ex-info "invalid request" (s/explain-data ::save-pseudonymous-postal-code params)))
    (rsdb/save-pseudonymous-patient-lsoa! rsdb patient-identifier postcode)))

(s/def ::save-ms-event
  (s/keys :req [:t_ms_event/date
                (or :t_ms_event_type/id :t_ms_event/type)
                :t_ms_event/impact
                :t_ms_event/summary_multiple_sclerosis_fk]
          :opt [:t_ms_event/notes]))

(pco/defmutation save-ms-event!
  [{rsdb    :com.eldrix/rsdb
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
                                 (rsdb/ms-event->patient-identifier rsdb params))]
      (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
      (create-or-save-entity
        {:id-key  :t_ms_event/id
         :save-fn #(rsdb/save-ms-event! rsdb %)
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
  [{rsdb                 :com.eldrix/rsdb
    {user-id :t_user/id} :session/authenticated-user, :as env}
   {ms-event-id :t_ms_event/id :as params}]
  {::pco/op-name 'pc4.rsdb/delete-ms-event}
  (log/info "delete ms event:" params " user:" user-id)
  (let [params' (assoc params :t_user/id user-id)]
    (if-not (s/valid? ::delete-ms-event params')
      (do (log/error "invalid call" (s/explain-data ::delete-ms-event params'))
          (throw (ex-info "Invalid data" (s/explain-data ::delete-ms-event params'))))
      (when-let [patient-identifier (rsdb/ms-event->patient-identifier rsdb params')]
        (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
        (rsdb/delete-ms-event! rsdb params')))))

(s/def ::save-encounter
  (s/keys :req [:t_patient/patient_identifier
                :t_encounter/patient_fk
                :t_encounter/date_time
                :t_encounter/encounter_template_fk]
          :opt [:t_encounter/id                             ;; if we've saving an existing encounter
                :t_encounter/episode_fk]))

(pco/defmutation save-encounter!
  [{rsdb                 :com.eldrix/rsdb
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
      (rsdb/save-encounter-and-forms! rsdb params')
      (catch Exception e (.printStackTrace e)))))

(s/def ::delete-encounter (s/keys :req [:t_encounter/id :t_patient/patient_identifier]))
(pco/defmutation delete-encounter!
  [{rsdb    :com.eldrix/rsdb
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
        (rsdb/delete-encounter! rsdb encounter-id))))

(s/def ::unlock-encounter (s/keys :req [:t_encounter/id :t_patient/patient_identifier]))

(pco/defmutation unlock-encounter!
  [{rsdb :com.eldrix/rsdb
    user :session/authenticated-user, :as env}
   {encounter-id       :t_encounter/id
    patient-identifier :t_patient/patient_identifier, :as params}]
  {::pco/op-name 'pc4.rsdb/unlock-encounter}
  (log/info "unlock encounter:" encounter-id)
  (if-not (s/valid? ::unlock-encounter params)
    (do (log/error "invalid unlock encounter" (s/explain-data ::unlock-encounter params))
        (throw (ex-info "invalid 'unlock encounter' data" (s/explain-data ::unlock-encounter params))))
    (do (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
        (rsdb/unlock-encounter! rsdb encounter-id))))

(s/def ::lock-encounter (s/keys :req [:t_encounter/id :t_patient/patient_identifier]))

(pco/defmutation lock-encounter!
  [{rsdb :com.eldrix/rsdb
    user :session/authenticated-user, :as env}
   {encounter-id       :t_encounter/id
    patient-identifier :t_patient/patient_identifier, :as params}]
  {::pco/op-name 'pc4.rsdb/lock-encounter}
  (log/info "lock encounter:" encounter-id)
  (if-not (s/valid? ::lock-encounter params)
    (do (log/error "invalid lock encounter" (s/explain-data ::lock-encounter params))
        (throw (ex-info "invalid 'lock encounter' data" (s/explain-data ::lock-encounter params))))
    (do (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
        (rsdb/lock-encounter! rsdb encounter-id))))

(pco/defmutation create-form!
  "Create a new form within an encounter for a patient of the specified type"
  [{rsdb                 :com.eldrix/rsdb
    {user-id :t_user/id} :session/authenticated-user
    :as                  env}
   {:keys [patient-identifier encounter-id form-type-id]}]
  {::pco/op-name 'pc4.rsdb/create-form}
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (assoc (rsdb/create-form! rsdb {:encounter-id encounter-id
                                  :user-id      user-id
                                  :form-type-id form-type-id})
    :form/encounter {:t_encounter/id encounter-id}
    :form/user {:t_user/id user-id}))

(pco/defmutation save-form!
  "Save a form. Parameters are a map with
  - patient-identifier : the patient identifier
  - form               : the form to save"
  [{rsdb :com.eldrix/rsdb :as env} {:keys [patient-identifier form] :as params}]
  {::pco/op-name 'pc4.rsdb/save-form}
  (log/info "save form" params)
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (if (tempid/tempid? (:form/id form))                      ;; if we have a temporary id, provide a mapping from it to new identifier
    (let [form' (rsdb/save-form! rsdb form)]
      (assoc form' :tempids {(:form/id form) (:form/id form')}))
    (rsdb/save-form! rsdb form)))

(pco/defmutation delete-form!
  [{rsdb :com.eldrix/rsdb :as env}
   {:keys [patient-identifier form] :as params}]
  {::pco/op-name 'pc4.rsdb/delete-form}
  (log/info "delete form" params) (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (rsdb/delete-form! rsdb form))

(pco/defmutation undelete-form!
  [{rsdb :com.eldrix/rsdb :as env}
   {:keys [patient-identifier form] :as params}]
  {::pco/op-name 'pc4.rsdb/undelete-form!}
  (log/info "undelete form" params)
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (rsdb/undelete-form! rsdb form))

(pco/defmutation save-result!
  [{rsdb                 :com.eldrix/rsdb
    manager              :session/authorization-manager
    {user-id :t_user/id} :session/authenticated-user
    :as                  env}
   {:keys [patient-identifier result] :as params}]
  {::pco/op-name 'pc4.rsdb/save-result}
  (log/info "save result request: " params "user: " user-id)
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (try
    (if-let [result-type (rsdb/result-type-by-entity-name (:t_result_type/result_entity_name result))]
      (let [table-name (name (:table result-type))
            id-key (keyword table-name "id")
            user-key (keyword table-name "user_fk")]
        (create-or-save-entity {:id-key  id-key
                                :params  (assoc result user-key user-id)
                                :save-fn #(rsdb/save-result! rsdb %)}))
      (throw (ex-info "missing entity name" result)))
    (catch Exception e (.printStackTrace e))))

(pco/defmutation delete-result!
  [{rsdb    :com.eldrix/rsdb
    manager :session/authorization-manager
    user    :session/authenticated-user
    :as     env}
   {:keys [patient-identifier result]}]
  {::pco/op-name 'pc4.rsdb/delete-result}
  (log/info "delete result request: " result "user: " user)
  (do (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
      (rsdb/delete-result! rsdb result)))

(s/def ::notify-death (s/keys :req [:t_patient/patient_identifier
                                    :t_patient/date_death]
                              :opt [:t_death_certificate/part1a
                                    :t_death_certificate/part1b
                                    :t_death_certificate/part1c
                                    :t_death_certificate/part2]))
(pco/defmutation notify-death!
  [{rsdb    :com.eldrix/rsdb
    manager :session/authorization-manager
    user    :session/authenticated-user
    :as     env}
   {patient-identifier :t_patient/patient_identifier :as params}]
  {::pco/op-name 'pc4.rsdb/notify-death}
  (log/info "notify death request: " params "user: " user)
  (when-not (s/valid? ::notify-death params)
    (throw (ex-info "Invalid notify-death request" (s/explain-data ::notify-death params))))
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (rsdb/notify-death! rsdb params))

(pco/defmutation set-date-death!
  [{rsdb    :com.eldrix/rsdb
    manager :session/authorization-manager
    user    :session/authenticated-user :as env}
   {patient-identifier :t_patient/patient_identifier :as params}]
  {::pco/op-name 'pc4.rsdb/set-date-death}
  (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
  (rsdb/set-date-death! rsdb params))

(pco/defmutation change-pseudonymous-registration
  [{rsdb :com.eldrix/rsdb, :as env}
   {patient-pk :t_patient/id :t_patient/keys [patient_identifier nhs_number sex date_birth] :as params}]
  {::pco/op-name 'pc4.rsdb/change-pseudonymous-registration}
  (guard-can-for-patient? env patient_identifier :PATIENT_CHANGE_PSEUDONYMOUS_DATA)
  ;; check that we are not changing the existing NHS number
  (let [{old-nhs-number :t_patient/nhs_number :as existing-patient} (rsdb/fetch-patient rsdb params)]
    (when (not= old-nhs-number nhs_number)
      (throw (ex-info "You are currently not permitted to change NHS number" {:existing  existing-patient
                                                                              :requested params})))
    (rsdb/update-legacy-pseudonymous-patient!
      rsdb
      patient-pk
      {:nhs-number nhs_number
       :date-birth date_birth
       :sex        sex})))

(s/def :t_user/username string?)
(s/def :t_user/password string?)
(s/def :t_user/new_password string?)
(s/def ::change-password (s/keys :req [:t_user/username
                                       :t_user/new_password]
                                 :opt [:t_user/password]))

(pco/defmutation change-password!
  [{rsdb               :com.eldrix/rsdb
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
  (let [user (rsdb/user-by-username rsdb username {:with-credentials true})]
    (if (rsdb/authenticate env user password)
      (rsdb/save-password! rsdb user new-password)
      (throw (ex-info "Cannot change password: incorrect old password." {})))))

(s/def ::save-admission (s/keys :req [:t_episode/patient_fk
                                      :t_episode/date_registration
                                      :t_episode/date_discharge]))
(pco/defmutation save-admission!
  [{rsdb                 :com.eldrix/rsdb
    {user-id :t_user/id} :session/authenticated-user
    :as                  env} params]
  {::pco/op-name 'pc4.rsdb/save-admission}
  (log/info "save admission request: " params "user: " user-id)
  (when-not (s/valid? ::save-admission (dissoc params :t_episode/id))
    (log/error "invalid save result request" (s/explain-data ::save-admission params))
    (throw (ex-info "Invalid save result request" (s/explain-data ::save-admission params))))
  (guard-can-for-patient? env (rsdb/patient-pk->patient-identifier rsdb (:t_episode/patient_fk params)) :PATIENT_EDIT)
  (rsdb/save-admission! rsdb user-id params))

(pco/defmutation delete-admission!
  [{rsdb    :com.eldrix/rsdb
    manager :session/authorization-manager
    user    :session/authenticated-user
    :as     env}
   {episode-id :t_episode/id
    patient-fk :t_episode/patient_fk
    :as        params}]
  {::pco/op-name 'pc4.rsdb/delete-admission}
  (if (and episode-id patient-fk)
    (let [patient-identifier (rsdb/patient-pk->patient-identifier rsdb patient-fk)]
      (guard-can-for-patient? env patient-identifier :PATIENT_EDIT)
      (rsdb/delete-episode! rsdb episode-id))
    (throw (ex-info "Invalid parameters:" params))))

(pco/defresolver multiple-sclerosis-diagnoses
  [{rsdb :com.eldrix/rsdb} _]
  {::pco/output [{:com.eldrix.rsdb/all-ms-diagnoses [:t_ms_diagnosis/id
                                                     :t_ms_diagnosis/name]}]}
  {:com.eldrix.rsdb/all-ms-diagnoses (rsdb/all-multiple-sclerosis-diagnoses rsdb)})

(pco/defresolver all-ms-event-types
  [{rsdb :com.eldrix/rsdb} _]
  {::pco/output [{:com.eldrix.rsdb/all-ms-event-types [:t_ms_event_type/id
                                                       :t_ms_event_type/abbreviation
                                                       :t_ms_event_type/name]}]}
  {:com.eldrix.rsdb/all-ms-event-types (rsdb/all-ms-event-types rsdb)})

(pco/defresolver all-ms-disease-courses
  [{rsdb :com.eldrix/rsdb} _]
  {::pco/output [{:com.eldrix.rsdb/all-ms-disease-courses [:t_ms_disease_course/id
                                                           :t_ms_disease_course/name]}]}
  {:com.eldrix.rsdb/all-ms-disease-courses (rsdb/all-ms-disease-courses rsdb)})

(pco/defresolver medication->reasons-for-stopping
  [_]
  {::pco/output [{:com.eldrix.rsdb/all-medication-reasons-for-stopping
                  [:t_medication_reason_for_stopping/id
                   :t_medication_reason_for_stopping/name]}]}
  {:com.eldrix.rsdb/all-medication-reasons-for-stopping
   (mapv #(hash-map :t_medication_reason_for_stopping/id %
                    :t_medication_reason_for_stopping/name (name %))
         rsdb/medication-reasons-for-stopping)})

(def all-resolvers
  [patient->break-glass
   patient->permissions
   project->permissions
   patient-by-identifier
   patient-by-pk
   patient-by-pseudonym
   patient->current-age
   address->isb1500-horiz
   patient->isb1504-nhs-number
   patient->isb1505-display-age
   patient->isb1506-name
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
   diagnosis-by-id
   patient->summary-multiple-sclerosis
   summary-multiple-sclerosis->events
   ms-event-by-id
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
   encounter->patient-age
   encounter->best-hospital-crn
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
   user->colleagues
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
   unlock-encounter!
   lock-encounter!
   ;; forms
   create-form!
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



