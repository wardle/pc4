(ns com.eldrix.pc4.server.rsdb.db
  (:require [next.jdbc.date-time]
            [next.jdbc :as jdbc])
  (:import (java.time LocalDate)))

(next.jdbc.date-time/read-as-local)

(def parse-local-date #(when % (LocalDate/from %)))
(def parse-boolean #(Boolean/parseBoolean %))

(def property-parsers
  {:t_patient/status                           keyword
   :t_address/ignore_invalid_address           parse-boolean
   :t_address/date_from                        parse-local-date
   :t_address/date_to                          parse-local-date
   :t_encounter/is_deleted                     parse-boolean
   :t_encounter_template/can_change_consultant parse-boolean
   :t_encounter_template/is_deleted            parse-boolean
   :t_encounter_template/mandatory             parse-boolean
   :t_encounter_template/can_change_hospital   parse-boolean
   :t_encounter_template/allow_multiple        parse-boolean
   :t_job_title/can_be_responsible_clinician   parse-boolean
   :t_job_title/is_clinical                    parse-boolean
   :t_patient/date_birth                       parse-local-date
   :t_patient/date_death                       parse-local-date
   :t_project/advertise_to_all                 parse-boolean
   :t_project/virtual                          parse-boolean
   :t_project/is_private                       parse-boolean
   :t_project/can_own_equipment                parse-boolean
   :t_project/type                             keyword
   :t_project/date_from                        parse-local-date
   :t_project/date_to                          parse-local-date
   :t_project_user/role                        keyword
   :t_role/is_system                           parse-boolean
   :t_user/authentication_method               keyword
   :t_user/must_change_password                parse-boolean
   :t_user/send_email_for_messages             parse-boolean
   })

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