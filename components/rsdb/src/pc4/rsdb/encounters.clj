(ns pc4.rsdb.encounters
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [honey.sql :as sql]
    [honey.sql.helpers :as h]))

(s/def ::patient-identifier int?)
(s/def ::patient-pk int?)
(s/def ::user-id int?)
(s/def ::project-id int?)
(s/def ::encounter-template-id int?)
(s/def ::encounter-template int?)
(s/def ::s string?)
(s/def ::view #{:notes :users :ninflamm :mnd})
(s/def ::query (s/keys :opt-un [::patient-identifier ::patient-pk ::user-id ::project-id ::encounter-template-id ::s ::view]))


(defn q-encounters-s
  [query s]
  (-> (h/select :*)
      (h/from query)
      (h/where)))

(defn q-encounters
  "Builds a query to fetch encounter data with various filtering and view options.
  
  Parameters:
  - patient-identifier: patient identifier to filter encounters (alternative to patient-pk)
  - patient-pk: patient primary key to filter encounters (alternative to patient-identifier)
  - user-id: filter encounters by associated user id
  - project-id: filter encounters by project id
  - episode-id: filter encounters by episode id
  - encounter-template-id: filter encounters by encounter template ID
  - in-person: filter encounters whether in person (true, false or nil)
  - from-date: filter encounters equal to, or after this date
  - to-date: filter encounters before this date
  - limit: use for pagination
  - offset: use for pagination
  - view: Data view type, one of:
    - :notes (default) - basic encounter data with notes
    - :users - encounter data with consultant and associated users
    - :ninflamm - neuroinflammatory data with EDSS scores and MS relapse info
    - :mnd - motor neuron disease data with weight, ALSFRS, and lung function

  Dates can be either a [[java.time.LocalDate]], or [[java.time.LocalDateTime]]
  Returns a query map that can be formatted using 'honey sql'.
  Encounters are ordered by date_time descending."
  [{:keys [patient-identifier patient-pk user-id project-id episode-id encounter-template-id in-person
           from-date to-date limit offset view]
    :or   {view :notes} :as query}]
  (when-not (s/valid? ::query query)
    (throw (ex-info "invalid parameters" (s/explain-data ::query query))))
  (cond->
    (-> {:select [:t_encounter/id :t_encounter/date_time [:t_encounter_template/title :encounter_template] [:t_project/title :project]] :from :t_encounter}
        (h/inner-join :t_encounter_template [:= :t_encounter_template/id :t_encounter/encounter_template_fk])
        (h/inner-join :t_project [:= :t_project/id :t_encounter_template/project_fk])
        (h/order-by [:t_encounter/date_time :desc]))

    patient-identifier
    (h/where := :patient_fk (-> (h/select :t_patient/id) (h/from :t_patient)
                                (h/where := :t_patient/patient_identifier patient-identifier)))
    patient-pk
    (h/where := :patient_fk patient-pk)

    (or (= :users view) user-id)
    (-> (h/left-join :t_encounter_user [:= :t_encounter_user/encounterid :t_encounter/id])
        (h/left-join :t_user [:= :t_encounter_user/userid :t_user/id]))

    (= :ninflamm view)
    (-> (h/select :form_edss/edss_score :form_ms_relapse/in_relapse :disease_course)
        (h/left-join [(-> (h/select :edss_score :encounter_fk
                                    [[:raw "row_number() over (partition by encounter_fk order by id desc) as edss_row_number"]])
                          (h/from :t_form_edss)
                          (h/where :<> :t_form_edss/is_deleted "true")) :form_edss]
                     [:and
                      [:= :edss_row_number 1]
                      [:= :form_edss/encounter_fk :t_encounter/id]])
        (h/left-join [(-> (h/select :in_relapse :encounter_fk [:t_ms_disease_course/name :disease_course]
                                    [[:raw "row_number() over (partition by encounter_fk order by t_form_ms_relapse.id desc) as relapse_row_number"]])
                          (h/from :t_form_ms_relapse)
                          (h/left-join :t_ms_disease_course [:= :t_form_ms_relapse/ms_disease_course_fk :t_ms_disease_course/id])
                          (h/where :<> :is_deleted "true")) :form_ms_relapse]
                     [:and
                      [:= :relapse_row_number 1]
                      [:= :form_ms_relapse.encounter_fk :t_encounter/id]]))

    (= :mnd view)
    (-> (h/select :form_weight_height/weight_kilogram :form_alsfrs/* :form_lung_function/*)
        (h/left-join [(-> (h/select :weight_kilogram :encounter_fk
                                    [[:raw "row_number() over (partition by encounter_fk order by t_form_weight_height.id desc) as wh_row_number"]])
                          (h/from :t_form_weight_height)
                          (h/where :<> :is_deleted "true"))
                      :form_weight_height]
                     [:and
                      [:= :wh_row_number 1]
                      [:= :form_weight_height/encounter_fk :t_encounter/id]])
        (h/left-join [(-> (h/select :*
                                    [[:raw "row_number() over (partition by encounter_fk order by id desc) as alsfrs_row_number"]])
                          (h/from :t_form_alsfrs)
                          (h/where :<> :is_deleted "true"))
                      :form_alsfrs]
                     [:and
                      [:= :alsfrs_row_number 1]
                      [:= :form_alsfrs/encounter_fk :t_encounter/id]])
        (h/left-join [(-> (h/select :*
                                    [[:raw "row_number() over (partition by encounter_fk order by id desc) as lf_row_number"]])
                          (h/from :t_form_lung_function)
                          (h/where :<> :t_form_lung_function/is_deleted "true"))
                      :form_lung_function]
                     [:and
                      [:= :lf_row_number 1]
                      [:= :form_lung_function/encounter_fk :t_encounter/id]]))

    user-id
    (-> (h/left-join [:t_encounter_user :eu] [:= :eu/encounterid :t_encounter/id])
        (h/where [:= :eu/userid user-id]))

    (= :users view)
    (-> (h/select [[:raw " conuser.title || ' ' || conuser.first_names || ' ' || conuser.last_name AS sro"]]
                  [[:raw " string_agg (t_user.title || ' ' || t_user.first_names || ' ' || t_user.last_name, ', ' order by t_user.last_name, t_user.first_names) AS users"]])
        (h/left-join [:t_user :conuser] [:= :t_encounter/consultant_user_fk :conuser/id])
        (h/group-by :t_encounter/id :t_encounter/date_time :encounter_template :project :sro))

    (= :notes view)
    (h/select :t_encounter/notes)

    project-id
    (h/where [:= :t_encounter_template/project_fk project-id])

    episode-id
    (h/where [:= :t_encounter/episode_fk episode-id])

    (some? in-person)
    (-> (h/inner-join :t_encounter_type [:= :t_encounter_template/encounter_type_fk :t_encounter_type/id])
        (h/where := :t_encounter_type/seen_in_person (str in-person)))

    encounter-template-id
    (h/where [:= :t_encounter/encounter_template_fk encounter-template-id])

    from-date
    (h/where :>= :t_encounter/date_time from-date)

    to-date
    (h/where [:< :t_encounter/date_time to-date])

    limit
    (h/limit limit)

    offset
    (h/offset offset)))

(comment
  (require '[next.jdbc :as jdbc])
  (def conn (jdbc/get-connection "jdbc:postgresql:rsdb"))
  conn
  (jdbc/execute! conn
                 (-> (q-encounters {:project-id 5 :view :users :in-person false
                                    :from-date (java.time.LocalDate/of 2020 1 1 )
                                    :to-date (java.time.LocalDate/of 2021 1 1)
                                    :limit 5
                                    :offset 200})
                     (sql/format)))
  (-> (q-encounters {:project-id 5 :view :users :in-person false :from-date (java.time.LocalDate/of 2000 1 1 )
                     :to-date (java.time.LocalDate/of 2001 1 1)})
      (sql/format {:inline true :pretty true})))



