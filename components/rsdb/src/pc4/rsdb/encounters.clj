(ns pc4.rsdb.encounters
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [pc4.rsdb.patients :as patients])
  (:import (java.time LocalDate LocalDateTime)))

(defn gen-local-date
  "Generate a [[java.time.LocalDate]] in the last ten years."
  []
  (gen/fmap (fn [days] (.minusDays (LocalDate/now) days))
            (s/gen (s/int-in 1 (* 365 10)))))

(defn gen-local-date-time
  "Generate a [[java.time.LocalDateTime]] in the last ten years"
  []
  (gen/fmap (fn [seconds] (.minusSeconds (LocalDateTime/now) (long seconds)))
            (s/gen (s/int-in 1 (* 365 10 24 60 60)))))

(s/def ::local-date
  (s/with-gen #(instance? LocalDate %) #(gen-local-date)))

(s/def ::local-date-time
  (s/with-gen #(instance? LocalDateTime %) #(gen-local-date-time)))

(s/def ::patient-identifier int?)
(s/def ::patient-pk int?)
(s/def ::user-id int?)
(s/def ::project-id int?)
(s/def ::episode-id int?)
(s/def ::encounter-template-id int?)
(s/def ::deleted boolean?)
(s/def ::in-person (s/nilable boolean?))
(s/def ::from (s/or :local-date ::local-date :local-date-time ::local-date-time))
(s/def ::to (s/or :local-date ::local-date :local-date-time ::local-date-time))
(s/def ::limit pos-int?)
(s/def ::offset pos-int?)
(s/def ::view #{:notes :users :ninflamm :mnd})
(s/def ::params
  (s/keys :req-un [(or ::patient-identifier ::patient-pk ::user-id ::project-id ::episode-id ::encounter-template-id)]
          :opt-un [::deleted ::in-person
                   ::from ::to
                   ::limit ::offset
                   ::view]))

(defn ^:private q-encounters-base
  "Builds a base query to fetch encounter data with various filtering and view options."
  [{:keys [patient-identifier patient-pk with-patient with-crns with-address with-notes
           user-id project-id episode-id encounter-template-id
           deleted in-person from to limit offset]
    :or   {deleted false}}]
  (cond->
    (-> {:select [:t_encounter/id :t_encounter/date_time :t_encounter_template/title :t_project/title] :from :t_encounter}
        (h/inner-join :t_encounter_template [:= :t_encounter_template/id :t_encounter/encounter_template_fk])
        (h/inner-join :t_project [:= :t_project/id :t_encounter_template/project_fk])
        (h/order-by [:t_encounter/date_time :desc]))

    (or patient-identifier with-patient with-address with-crns)
    (h/left-join :t_patient [:= :t_encounter/patient_fk :t_patient/id])

    patient-identifier
    (h/where := :t_patient/patient_identifier patient-identifier)

    patient-pk
    (h/where := :t_encounter/patient_fk patient-pk)

    (some? deleted)
    (h/where := :t_encounter/is_deleted (str deleted))

    with-patient
    (h/select [:t_patient/id :patient-pk] :t_patient/patient_identifier :t_patient/nhs_number :t_patient/title :t_patient/first_names :t_patient/last_name :t_patient/date_birth)

    with-crns
    (patients/with-hospital-crns (assoc with-crns :update-select? true))

    with-address
    (patients/with-current-address {:update-select? true})

    with-notes
    (h/select :t_encounter/notes)

    user-id
    (-> (h/left-join [:t_encounter_user :eu] [:= :eu/encounterid :t_encounter/id])
        (h/where [:= :eu/userid user-id]))

    project-id
    (h/where [:= :t_encounter_template/project_fk project-id])

    episode-id
    (h/where [:= :t_encounter/episode_fk episode-id])

    (some? in-person)
    (-> (h/inner-join :t_encounter_type [:= :t_encounter_template/encounter_type_fk :t_encounter_type/id])
        (h/where := :t_encounter_type/seen_in_person (str in-person)))

    encounter-template-id
    (h/where [:= :t_encounter/encounter_template_fk encounter-template-id])

    from
    (h/where :>= :t_encounter/date_time from)

    to
    (h/where [:< :t_encounter/date_time to])

    limit
    (h/limit limit)

    offset
    (h/offset offset)))

(defn ^:private q-encounters-users
  "Encounters view with senior responsible user and list of users."
  [params]
  (-> {:with   [[:base (h/select (q-encounters-base params) :t_encounter/consultant_user_fk)]]
       :select :base/*
       :from   :base}
      (h/select [[:raw "conuser.title || ' ' || conuser.first_names || ' ' || conuser.last_name AS sro"]]
                :encounter-users/users)
      (h/left-join [:t_user :conuser]
                   [:= :base/consultant_user_fk :conuser/id]
                   [{:select     [:encounterid
                                  [[:raw "string_agg(t_user.title || ' ' || t_user.first_names || ' ' || t_user.last_name, ', '
                                         ORDER BY t_user.last_name, t_user.first_names) as users"]]]
                     :from       :t_encounter_user
                     :where      [:in :encounterid {:select :base/id :from :base}]
                     :inner-join [:t_user [:= :t_encounter_user/userid :t_user/id]]
                     :group-by   [:encounterid]}
                    :encounter-users]
                   [:= :encounter-users/encounterid :base/id])
      (h/order-by [:base/date_time :desc])))

(defn ^:private q-encounters-ninflamm
  "Neuroinflammatory view with latest EDSS scores and MS relapse data.
  Uses CTEs with DISTINCT ON for optimal performance."
  [params]
  (let [base-query (q-encounters-base params)]
    {:with      [[:base_encounters base-query]
                 [:latest_edss
                  {:select-distinct-on [[:encounter_fk] :encounter_fk :edss_score]
                   :from               :t_form_edss
                   :where              [:and
                                        [:in :encounter_fk {:select :id :from :base_encounters}]
                                        [:= :is_deleted "false"]]
                   :order-by           [:encounter_fk [:t_form_edss/id :desc]]}]
                 [:latest_ms_relapse
                  {:select-distinct-on [[:encounter_fk] :encounter_fk :in_relapse :t_ms_disease_course/name]
                   :from               :t_form_ms_relapse
                   :left-join          [:t_ms_disease_course [:= :t_form_ms_relapse/ms_disease_course_fk :t_ms_disease_course/id]]
                   :where              [:and
                                        [:in :encounter_fk {:select :id :from :base_encounters}]
                                        [:= :is_deleted "false"]]
                   :order-by           [:encounter_fk [:t_form_ms_relapse/id :desc]]}]]
     :select    [:base_encounters/* :latest_edss/edss_score :latest_ms_relapse/in_relapse :latest_ms_relapse/name]
     :from      :base_encounters
     :left-join [:latest_edss [:= :latest_edss/encounter_fk :base_encounters/id]
                 :latest_ms_relapse [:= :latest_ms_relapse/encounter_fk :base_encounters/id]]
     :order-by  [[:base_encounters/date_time :desc]]}))

(defn ^:private q-encounters-mnd
  "Motor neuron disease view with latest weight, ALSFRS, and lung function data.
  Uses CTEs with DISTINCT ON for optimal performance."
  [params]
  (let [base-query (q-encounters-base params)]
    {:with      [[:base_encounters base-query]
                 [:latest_weight
                  {:select-distinct-on [[:encounter_fk] :encounter_fk :weight_kilogram]
                   :from               :t_form_weight_height
                   :where              [:and
                                        [:in :encounter_fk {:select :id :from :base_encounters}]
                                        [:= :is_deleted "false"]]
                   :order-by           [:encounter_fk [:t_form_weight_height/id :desc]]}]
                 [:latest_alsfrs
                  {:select-distinct-on [[:encounter_fk] :*]
                   :from               :t_form_alsfrs
                   :where              [:and
                                        [:in :encounter_fk {:select :id :from :base_encounters}]
                                        [:= :is_deleted "false"]]
                   :order-by           [:encounter_fk [:t_form_alsfrs/id :desc]]}]
                 [:latest_lung
                  {:select-distinct-on [[:encounter_fk] :*]
                   :from               :t_form_lung_function
                   :where              [:and
                                        [:in :encounter_fk {:select :id :from :base_encounters}]
                                        [:= :is_deleted "false"]]
                   :order-by           [:encounter_fk [:t_form_lung_function/id :desc]]}]]

     :select    [:base_encounters/* :latest_weight/weight_kilogram
                 :latest_alsfrs/* :latest_lung/*]
     :from      :base_encounters
     :left-join [:latest_weight [:= :latest_weight/encounter_fk :base_encounters/id]
                 :latest_alsfrs [:= :latest_alsfrs/encounter_fk :base_encounters/id]
                 :latest_lung [:= :latest_lung/encounter_fk :base_encounters/id]]
     :order-by  [[:base_encounters/date_time :desc]]}))

(s/fdef q-encounters
  :args (s/cat :params ::params))
(defn q-encounters
  " Parameters:
  - patient-identifier: patient identifier to filter encounters (alternative to patient-pk)
  - patient-pk: patient primary key to filter encounters (alternative to patient-identifier)
  - with-patient : include patient name / date of birth / nhs number, default false
  - with-crns : include patient CRNs for a given organisation
  - with-address : include patient current address
  - with-notes : include encounter notes
  - user-id: filter encounters by associated user id
  - project-id: filter encounters by project id
  - episode-id: filter encounters by episode id
  - encounter-template-id: filter encounters by encounter template ID
  - deleted: filter encounters based on whether deleted (true, false or nil, default false)
  - in-person: filter encounters whether in person (true, false or nil; default nil)
  - from: filter encounters equal to, or after this date
  - to: filter encounters before this date
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
  [{:keys [view] :or {view :notes} :as params}]
  (when-not (s/valid? ::params params)
    (throw (ex-info "invalid parameters" (s/explain-data ::params params))))
  (case view
    :notes (q-encounters-base (assoc params :with-notes true))
    :users (q-encounters-users params)
    :ninflamm (q-encounters-ninflamm params)
    :mnd (q-encounters-mnd params)))

(comment
  (require '[next.jdbc :as jdbc])
  (def conn (jdbc/get-connection "jdbc:postgresql:rsdb"))
  conn
  (jdbc/execute! conn (sql/format (q-encounters {:view :mnd :project-id 20 :limit 10})))
  (jdbc/execute! conn (sql/format (q-encounters {:view :users :project-id 20 :limit 20})))
  (jdbc/execute! conn (sql/format (q-encounters {:view :mnd :patient-identifier 14032})))
  (jdbc/execute! conn (sql/format (q-encounters {:view :ninflamm :project-id 5 :with-patient true :limit 50})))
  (jdbc/execute! conn (sql/format (q-encounters {:view :ninflamm :patient-identifier 13115}))))



