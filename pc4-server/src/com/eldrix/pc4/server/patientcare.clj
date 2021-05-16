(ns com.eldrix.pc4.server.patientcare
  "PatientCare provides functionality to integrate with the rsdb backend.
  `rsdb` is the Apple WebObjects 'legacy' application."
  (:require [clojure.tools.logging.readable :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as h]
            [next.jdbc :as jdbc]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql])
  (:import (com.zaxxer.hikari HikariDataSource)))

(pco/defresolver patient-by-identifier
  [{:com.eldrix.patientcare/keys [conn]} {patient-identifier :t_patient/patient-identifier}]
  {::pco/output [:t_patient/id
                 :t_patient/patient-identifier
                 :t_patient/first_names
                 :t_patient/last_name
                 :t_patient/email
                 :t_patient/nhs-number]}
  (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_patient] :where [:= :id patient-identifier]})))

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
                              patient-encounters
                              encounter-encounter_template
                              encounter_template-encounter_type])
               (assoc :com.eldrix.patientcare/conn conn)))
  (patient-by-identifier {:com.eldrix.patientcare/conn conn} {:t_patient/patient-identifier 14242})
  (p.eql/process env [{[:t_patient/patient-identifier 14242] [:t_patient/id :t_patient/email
                                                              :t_patient/first_names
                                                              :t_patient/last_name
                                                              {:t_patient/encounters [:t_encounter/date_time
                                                                                      {:t_encounter/encounter_template [:t_encounter_template/name :t_encounter_template/encounter_type]}]}]}])

  (jdbc/execute-one! conn (sql/format {:select [:*] :from [:t_encounter_template]
                                       :where  [:= :id 15]}))
  (encounter-encounter_template {:com.eldrix.patientcare/conn conn} {:t_encounter/encounter_template_fk 15})
  )