(ns com.eldrix.pc4.server.patientcare
  "PatientCare provides functionality to integrate with the rsdb backend.
  `rsdb` is the Apple WebObjects 'legacy' application."
  (:require [clojure.tools.logging.readable :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as h]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.time LocalDate)))

(next.jdbc.date-time/read-as-local)

(defn date-in-range?
  "Is the date in the range specified, or is the range 'current'?"
  ([^LocalDate from ^LocalDate to]
   (date-in-range? from to nil))
  ([^LocalDate from ^LocalDate to ^LocalDate date]
   (let [date' (or date (LocalDate/now))]
     (and (or (nil? from) (not (.isBefore date' from)))
          (or (nil? to) (.isBefore date' to))))))

(def property-parsers
  {:t_address/ignore_invalid_address #(Boolean/parseBoolean %)
   :t_address/date_from              #(LocalDate/from %)
   :t_address/date_to                #(LocalDate/from %)})

(defn parse-entity
  [m & {:keys [remove-nils?] :or {remove-nils? false}}]
  (reduce-kv
    (fn [m k v]
      (when (or (not remove-nils?) v)
        (assoc m k (let [f (get property-parsers k)]
                     (if (and f v) (f v) v))))) {} m))


(pco/defresolver patient-by-identifier
  [{:com.eldrix.patientcare/keys [conn]} {patient-identifier :t_patient/patient-identifier}]
  {::pco/output [:t_patient/id
                 :t_patient/patient-identifier
                 :t_patient/first_names
                 :t_patient/last_name
                 :t_patient/email
                 :t_patient/country_of_birth_concept_fk
                 :t_patient/date_birth
                 :t_patient/date_death
                 :t_patient/nhs-number
                 :t_patient/surgery_fk
                 :t_patient/ethnic_origin_concept_fk
                 :t_patient/racial_group_concept_fk
                 :t_patient/occupation_concept_fk]}
  (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_patient] :where [:= :patient_identifier patient-identifier]})))

(pco/defresolver patient-country-of-birth
  [{concept-id :t_patient/country_of_birth_concept_fk}]
  {::pco/output [{:t_patient/country_of_birth [:info.snomed.Concept/id]}]}
  (when concept-id {:t_patient/country_of_birth {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient-ethnic-origin
  [{concept-id :t_patient/ethnic_origin_concept_fk}]
  {::pco/output [{:t_patient/ethnic_origin [:info.snomed.Concept/id]}]}
  (when concept-id {:t_patient/ethnic_origin {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient-racial-group
  [{concept-id :t_patient/racial_group_concept_fk}]
  {::pco/output [{:t_patient/racial_group [:info.snomed.Concept/id]}]}
  (when concept-id {:t_patient/racial_group {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient-occupation
  [{concept-id :t_patient/occupation_concept_fk}]
  {::pco/output [{:t_patient/occupation [:info.snomed.Concept/id]}]}
  (when concept-id {:t_patient/occupation {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient-surgery
  [{surgery-fk :t_patient/surgery_fk}]
  {::pco/output [{:t_patient/surgery [:urn.oid:2.16.840.1.113883.2.1.3.2.4.18.48/id]}]}
  (when surgery-fk {:t_patient/surgery {:urn.oid.2.16.840.1.113883.2.1.3.2.4.18.48/id surgery-fk}}))

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
  Unfortunately, the backend database stores the from and to dates as timestamps
  without timezones, so we convert to instances of java.time.LocalDate."
  [conn patient-id]
  (->> (jdbc/execute! conn (sql/format {:select   [:address1 :address2 :address3 :address4 [:postcode_raw :postcode]
                                                   :date_from :date_to :housing_concept_fk :ignore_invalid_address]
                                        :from     [:t_address]
                                        :where    [:= :patient_fk patient-id]
                                        :order-by [[:date_to :desc] [:date_from :desc]]}))
       (map parse-entity)))

(defn address-for-date
  "Determine the address on a given date, the current date if none given."
  ([sorted-addresses]
   (address-for-date sorted-addresses nil))
  ([sorted-addresses ^LocalDate date]
   (->> sorted-addresses
        (filter #(date-in-range? (:t_address/date_from %) (:t_address/date_to %) date))
        first)))

(pco/defresolver patient-addresses
  [{:com.eldrix.patientcare/keys [conn]} {id :t_patient/id}]
  {::pco/output [{:t_patient/addresses address-properties}]}
  {:t_patient/addresses (fetch-patient-addresses conn id)})

(pco/defresolver patient-address
  "Returns the current address, or the address for the specified date.
  Will make use of existing data in t_patient/addresses, if key exists.
  This resolver takes an optional parameter :date. If provided, the address
  for the specified date will be given.
  Parameters:
  - :date - a ISO LOCAL DATE string e.g \"2020-01-01\" or an instance of
            java.time.LocalDate."
  [{conn :com.eldrix.patientcare/conn :as env} {:t_patient/keys [id addresses]}]
  {::pco/input  [:t_patient/id
                 (pco/? :t_patient/addresses)]
   ::pco/output [:t_patient/address address-properties]}
  (let [date (:date (pco/params env))                       ;; doesn't matter if nil
        date' (cond (nil? date) nil
                    (string? date) (LocalDate/parse date)
                    :else date)
        addresses' (or addresses (fetch-patient-addresses conn id))]
    (println "determining address for date: " date " = " date')
    {:t_patient/address (address-for-date addresses' date')}))

(pco/defresolver address-housing
  [{concept-id :t_address/housing_concept_fk}]
  {::pco/output [{:t_address/housing [:info.snomed.Concept/id]}]}
  (when concept-id {:t_address/housing {:info.snomed.Concept/id concept-id}}))

(pco/defresolver patient-episodes
  [{conn :com.eldrix.patientcare/conn} {patient-id :t_patient/id}]
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

(pco/defresolver patient-encounters
  [{:com.eldrix.patientcare/keys [conn]} {patient-id :t_patient/id}]
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

(pco/defresolver encounter-encounter_template
  [{:com.eldrix.patientcare/keys [conn]} {encounter-template-fk :t_encounter/encounter_template_fk}]
  {::pco/output [{:t_encounter/encounter_template [:t_encounter_template/id
                                                   :t_encounter_template/encounter_type_fk]}]}
  {:t_encounter/encounter_template (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_template]
                                                                        :where  [:= :id encounter-template-fk]}))})

(pco/defresolver encounter_template-encounter_type
  [{:com.eldrix.patientcare/keys [conn]} {encounter-type-id :t_encounter_template/encounter_type_fk}]
  {::pco/output [{:t_encounter_template/encounter_type [:t_encounter_type/id
                                                        :t_encounter_type/name
                                                        :t_encounter_type/seen_in_person]}]}
  {:t_encounter_template/encounter_type (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_type] :where [:= :id encounter-type-id]}))})

(comment

  (def conn (next.jdbc.connection/->pool HikariDataSource {:dbtype          "postgresql"
                                                           :dbname          "rsdb"
                                                           :maximumPoolSize 10}))
  (jdbc/execute! conn ["select id from t_encounter where patient_fk=?" 1726])
  (jdbc/execute! conn
                 ["select t_form_edss.*,t_encounter.date_time,t_encounter.is_deleted from t_form_edss,t_encounter where t_form_edss.encounter_fk=t_encounter.id and encounter_fk in (select id from t_encounter where patient_fk=?);" 1726])

  (jdbc/execute! conn (sql/format {:select [:*]
                                   :from   [:t_patient]
                                   :where  [:= :id 14232]}))

  (def env (-> (pci/register [patient-by-identifier
                              patient-country-of-birth
                              patient-ethnic-origin
                              patient-racial-group
                              patient-occupation
                              patient-surgery
                              patient-addresses
                              patient-address
                              (pbir/alias-resolver :t_address/postcode :uk.gov.ons.nhspd/PCDS)
                              address-housing
                              patient-episodes
                              patient-encounters
                              encounter-encounter_template
                              encounter_template-encounter_type])
               (assoc :com.eldrix.patientcare/conn conn)))
  (patient-by-identifier {:com.eldrix.patientcare/conn conn} {:t_patient/patient-identifier 12999})
  (p.eql/process env [{[:t_patient/patient-identifier 17371] [:t_patient/id :t_patient/email
                                                             :t_patient/first_names
                                                             :t_patient/last_name
                                                             :t_patient/surgery
                                                             :t_patient/ethnic_origin_concept_fk
                                                             :t_patient/ethnic_origin
                                                             `(:t_patient/address {:date ~"2020-06-01"})]}])

  (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_template]
                                       :where  [:= :id 15]}))
  (encounter-encounter_template {:com.eldrix.patientcare/conn conn} {:t_encounter/encounter_template_fk 15})
  (sql/format {:select [[:postcode_raw :postcode]] :from [:t_address]})

  (def ^LocalDate date (LocalDate/now))
  (fetch-patient-addresses conn 119032)
  (address-for-date (fetch-patient-addresses conn 7382))

  (fetch-patient-addresses conn 119032)
  (.isBefore date (:date_to (first (fetch-patient-addresses conn 119032))))

  (jdbc/execute! conn (sql/format {:select [:*]
                                   :from   [:t_episode]
                                   :where  [:= :patient_fk 5448]}))
  )