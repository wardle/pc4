(ns com.eldrix.pc4.rsdb.forms
  "We have an issue here.

  The legacy WebObjects application uses horizontal inheritance. That means:

  a) we must look in multiple tables and,
  b) the primary key generation occurs in the parent table ('t_form') not in the
  individual form tables.
  c) it places a limit of numbers of forms, simply due to performance.

  I tried to fix later with 't_form_generic' but rather than using a jsonb, it
  uses a separate system of tables and properties in tables 't_form_item' and
  't_form_item_result' acting as a loose schema and key value store
  respectively.

  These are the main scenarios we need to consider:
  1. Get all forms of a specific type, or types for a patient.
  2. Get all forms for a specific encounter.
  3. Get most recent version of a type of form for a patient.

  In order to be performant, and knowing that a specific encounter is associated
  with only a small number of forms, we can do two things:
  1. Use a union query to build an index of all forms indexed by encounter.
  2. When a user wants to look at a specific encounter, or look at the results
  of a specific form across encounters, we use that index to fetch the data we
  need.

  It is most unlikely a single patient will have data in all form tables, as
  the use of forms is dependent on the clinical condition(s) at hand.

  To complicate matters, the legacy application uses 't_form_type' to list all
  form types. This table includes name, description and form_entity_name and
  is used to drive the configuration of forms to encounter templates.

  At some point, we'll just have relatively flat lists of namespaced properties.
  In order to make it easier to migrate, it would be ideal to represent most
  forms flattened within an encounter. For the few forms that permit multiple
  forms per encounter, they would be represented as a to-many property."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.tools.logging.readable :as log]
    [com.eldrix.pc4.rsdb.db :as db]
    [com.eldrix.pc4.rsdb.patients :as patients]
    [honey.sql :as sql]
    [next.jdbc :as jdbc])
  (:import
    (java.time LocalDateTime)))

(s/def ::conn any?)
(s/def ::form-type-id (s/nilable int?))
(s/def ::table string?)
(s/def ::nm string?)
(s/def ::parse fn?)
(s/def ::unparse fn?)
(s/def ::summary fn?)
(s/def ::spec some?)
(s/def ::normalized-form-type
  (s/keys :req-un [::form-type-id ::table ::table-kw ::nm ::parse ::unparse ::summary]
          :opt-un [::spec]))

(def edss-score->score
  {"SCORE0_0"          "0.0"
   "SCORE1_0"          "1.0"
   "SCORE1_5"          "1.5"
   "SCORE2_0"          "2.0"
   "SCORE2_5"          "2.5"
   "SCORE3_0"          "3.0"
   "SCORE3_5"          "3.5"
   "SCORE4_0"          "4.0"
   "SCORE4_5"          "4.5"
   "SCORE5_0"          "5.0"
   "SCORE5_5"          "5.5"
   "SCORE6_0"          "6.0"
   "SCORE6_5"          "6.5"
   "SCORE7_0"          "7.0"
   "SCORE7_5"          "7.5"
   "SCORE8_0"          "8.0"
   "SCORE8_5"          "8.5"
   "SCORE9_0"          "9.0"
   "SCORE9_5"          "9.5"
   "SCORE10_0"         "10.0"
   "SCORE_LESS_THAN_4" "<4"})

(s/def :t_form_edss/encounter_fk pos-int?)
(s/def :t_form_edss/id (s/nilable pos-int?))
(s/def :t_form_edss/edss_score (set (keys edss-score->score)))
(s/def :t_form_edss/user_fk int?)
(s/def ::t_form_edss (s/keys :req [:t_form_edss/id :t_form_edss/encounter_fk :t_form_edss/user_fk :t_form_edss/edss_score]))

(comment
  (ns-unalias *ns* 'gen)
  (require '[clojure.spec.gen.alpha :as gen])
  (gen/generate (s/gen ::t_form_edss)))

(def forms*
  "Support for legacy forms. Here each form is represented by a database table and a form type."
  [{:form-type-id 1
    :table        "t_form_ace_r"
    :title        "ACE-R"
    :key          nil
    :entity-name  "FormAceR"}
   {:form-type-id 2
    :table        "t_form_edss"
    :title        "EDSS (short form)"
    :key          nil
    :entity-name  "FormEdss"
    :parse        (fn [{:t_form_edss/keys [edss_score] :as form}]
                    (assoc form :t_form_edss/score (edss-score->score edss_score)))
    :unparse      (fn [form] (dissoc form :t_form_edss/score))
    :summary      (fn [{:t_form_edss/keys [score]}] score)
    :spec         ::t_form_edss}
   {:form-type-id 3
    :table        "t_form_edss_full"
    :title        "EDSS (Neurostatus)"
    :key          nil
    :entity-name  "FormEdssFull"}
   {:form-type-id 4
    :table        "t_form_hospital_anxiety_and_depression_brief"
    :title        "HAD (short form)"
    :key          nil
    :entity-name  "FormHospitalAnxietyAndDepressionBrief"}
   {:form-type-id 5
    :table        "t_form_hospital_anxiety_and_depression"
    :title        "HAD"
    :key          nil
    :entity-name  "FormHospitalAnxietyAndDepression"}
   {:form-type-id 6
    :table        "t_form_icars"
    :title        "ICARS"
    :key          nil
    :entity-name  "FormIcars"}
   {:form-type-id 7
    :table        "t_form_mmse"
    :title        "MMSE"
    :key          nil
    :entity-name  "FormMmse"}
   {:form-type-id 8
    :table        "t_form_motor_updrs"
    :title        "Motor-UPDRS"
    :key          nil
    :entity-name  "FormMotorUpdrs"}
   {:form-type-id 9
    :table        "t_form_ms_relapse"
    :title        "MS Relapse / disease course"
    :key          nil
    :entity-name  "FormMSRelapse"
    :parse        (fn [form] (-> form
                                 (update :t_form_ms_relapse/in_relapse parse-boolean)
                                 (update :t_form_ms_relapse/strict_validation parse-boolean)))}
   {:form-type-id 10
    :table        "t_form_nine_hole_peg"
    :title        "Nine-hole peg"
    :key          nil
    :entity-name  "FormNineHolePeg"}
   {:form-type-id 11
    :table        "t_form_sara"
    :title        "SARA"
    :key          nil
    :entity-name  " FormSara "}
   {:form-type-id 12
    :table        "t_form_timed_walk"
    :title        "Timed walk"
    :key          nil
    :entity-name  " FormTimedWalk "}
   {:form-type-id 13
    :table        "t_form_soap"
    :title        "SOAP"
    :key          nil
    :entity-name  "FormSoap"}
   {:form-type-id 14
    :table        "t_form_eq5d"
    :title        "EQ5D"
    :key          nil
    :entity-name  "FormEq5d"}
   {:form-type-id 15
    :table        "t_form_bedside_swallow"
    :title        "Bedside swallow"
    :key          nil
    :entity-name  "FormBedsideSwallow"}
   {:form-type-id 16
    :table        "t_form_edss_fs"
    :title        "EDSS (FS)"
    :key          nil
    :entity-name  "FormEdssFs"}
   {:form-type-id 23
    :table        "t_form_logmar_visual_acuity"
    :title        "Visual acuity (LogMAR chart)"
    :key          nil
    :entity-name  "FormLogmarVisualAcuity"}
   {:form-type-id 24
    :table        "t_form_snellen_visual_acuity"
    :title        "Visual acuity (Snellen chart)"
    :key          nil
    :entity-name  "FormSnellenVisualAcuity"}
   {:form-type-id 25
    :table        "t_form_use_of_services"
    :title        "Use of services questionnaire"
    :key          nil
    :entity-name  "FormUseOfServices"}
   {:form-type-id 26
    :table        "t_form_city_colour"
    :title        "City University Colour Vision Test"
    :key          nil
    :entity-name  "FormCityColour"}
   {:form-type-id 28
    :table        "t_form_msis29"
    :title        "MSIS-29"
    :key          nil
    :entity-name  "FormMsis29"}
   {:form-type-id 29
    :table        "t_form_weight_height"
    :title        "Weight and height"
    :key          nil
    :entity-name  "FormWeightHeight"}
   {:form-type-id 30
    :table        "t_form_ishihara"
    :title        "Ishihara"
    :key          nil
    :entity-name  "FormIshihara"}
   {:form-type-id 32
    :table        "t_form_routine_observations"
    :title        "Routine observations (P/BP)"
    :key          nil :entity-name "FormRoutineObservations"}
   {:form-type-id 35
    :table        "t_form_alsfrs"
    :title        "ALS Functional Rating Scale"
    :key          nil
    :entity-name  "FormALSFRS"}
   {:form-type-id 36
    :table        "t_form_lung_function"
    :title        "Lung function"
    :key          nil
    :entity-name  "FormLungFunction"}
   {:form-type-id 37
    :nm           "form_smoking_history"
    :table        "t_smoking_history"
    :title        "Smoking history"
    :key          nil
    :entity-name  "FormSmokingHistory"}
   {:form-type-id 38
    :table        "t_form_walking_distance"
    :title        "Walking distance"
    :key          nil
    :entity-name  "FormWalkingDistance"}
   {:form-type-id 39
    :table        "t_form_neurology_curriculum"
    :title        "Neurology curriculum"
    :key          nil
    :entity-name  "FormNeurologyCurriculum"}
   {:form-type-id 40
    :table        "t_form_follow_up"
    :title        "Follow up"
    :key          nil
    :entity-name  "FormFollowUp"}
   {:form-type-id 41
    :table        "t_form_presenting_complaints"
    :title        "Presenting complaints / problems"
    :key          nil
    :entity-name  "FormPresentingComplaints"}
   {:form-type-id 42
    :table        "t_form_gorelick_hydration"
    :title        "Gorelick hydration (not implemented)"
    :key          nil
    :entity-name  "FormGorelickHydration"}
   {:form-type-id 43
    :table        "t_form_asthma_pram"
    :title        "PRAM (Asthma)"
    :key          nil
    :entity-name  "FormAsthmaPram"}
   {:form-type-id 44
    :table        "t_form_paediatric_observations"
    :title        "Paediatric observations"
    :key          nil
    :entity-name  "FormPaediatricObservations"}
   {:form-type-id 45
    :table        "t_form_bronchiolitis_tal_score"
    :title        "Tal score (Bronchiolitis)"
    :key          nil
    :entity-name  "FormBronchiolitisTalScore"}
   {:form-type-id 46
    :table        "t_form_croup_westley"
    :title        "Westley score (Croup)"
    :key          nil
    :entity-name  "FormCroupWestley"}
   {:form-type-id 47
    :table        "t_form_nice_feverish_child"
    :title        "NICE feverish child assessment"
    :key          nil
    :entity-name  "FormNiceFeverishChild"}
   {:form-type-id    48
    :table           "t_form_prescription"
    :title           "Prescription"
    :key             nil
    :entity-name     "FormPrescription"
    :allow-multiple? true}
   {:form-type-id 49
    :table        "t_form_mnd_symptom"
    :title        "MND symptoms"
    :key          nil
    :entity-name  "FormMndSymptom"}
   {:form-type-id 50
    :table        "t_form_adrt"
    :title        "Advance Decisions to Refuse Treatment"
    :key          nil
    :entity-name  "FormAdrt"}
   {:form-type-id 52
    :table        "t_form_multiple_sclerosis_dmt"
    :title        "MS DMT Assessment"
    :key          nil
    :entity-name  "FormMultipleSclerosisDmt"}
   {:form-type-id 53
    :table        "t_form_hoehn_yahr"
    :title        "Hoehn and Yahr"
    :key          nil
    :entity-name  "FormHoehnYahr"}
   {:form-type-id 54
    :table        "t_form_parkinson_non_motor"
    :title        "Non-motor symptoms in PD"
    :key          nil
    :entity-name  "FormParkinsonNonMotor"}
   {:form-type-id 55
    :table        "t_form_moca"
    :title        "MOCA"
    :key          nil
    :entity-name  "FormMoca"}
   {:form-type-id    56
    :table           "t_form_procedure_generic"
    :title           "Generic procedure"
    :key             nil
    :entity-name     "FormProcedureGeneric"
    :allow-multiple? true}
   {:form-type-id    57
    :table           "t_form_procedure_lumbar_puncture"
    :title           "Lumbar Puncture"
    :key             nil
    :entity-name     "FormProcedureLumbarPuncture"
    :allow-multiple? true}
   {:form-type-id 58
    :table        "t_form_medication"
    :title        "Medication review"
    :key          nil
    :entity-name  "FormMedication"}
   {:form-type-id    60
    :table           "t_form_procedure_botulinum_toxin"
    :title           "Botulinum toxin"
    :key             nil
    :entity-name     "FormProcedureBotulinumToxin"
    :allow-multiple? true}
   {:form-type-id 61
    :table        "t_form_botulinum_toxin_outcome"
    :title        "Botulinum toxin outcome"
    :key          nil
    :entity-name  "FormBotulinumToxinOutcome"}
   {:form-type-id 62
    :table        "t_form_seizure_frequency"
    :title        "Seizure frequency"
    :key          nil
    :entity-name  "FormSeizureFrequency"}
   {:form-type-id 64
    :table        "t_form_ambulation"
    :title        "Ambulation"
    :key          nil
    :entity-name  "FormAmbulation"}
   {:form-type-id 65
    :table        "t_form_berg_balance"
    :title        "Berg balance score"
    :key          nil
    :entity-name  "FormBergBalance"}
   {:form-type-id 66
    :table        "t_form_epilepsy_surgery_mdt"
    :title        "Epilepsy surgery pathway"
    :key          nil
    :entity-name  "FormEpilepsySurgeryMdt"}
   {:form-type-id 67
    :table        "t_form_future_planning"
    :title        "Future planning"
    :key          nil
    :entity-name  "FormFuturePlanning"}
   {:form-type-id 68
    :table        "t_form_generic"
    :title        "Symbol digit modality test"
    :key          "FormSymbolDigit"
    :entity-name  "FormGeneric"}
   {:form-type-id 69
    :table        "t_form_generic"
    :title        "RODS-CIDP"
    :key          "FormRodsCidp"
    :entity-name  "FormGeneric"}
   {:form-type-id 70
    :table        "t_form_generic"
    :title        "Valproate annual risk acknowledgement form"
    :key          "FormValproate"
    :entity-name  "FormGeneric"}
   {:form-type-id 71
    :table        "t_form_generic"
    :title        "Haemophilia Joint Health Score"
    :key          "FormHJHS"
    :entity-name  "FormGeneric"}
   {:form-type-id 72
    :table        "t_form_rehabilitation_assessment"
    :title        "Rehabilitation assessment "
    :key          nil
    :entity-name  "FormRehabilitationAssessment"}
   {:form-type-id 73
    :table        "t_form_mnd_symptom"
    :title        "MND phenotype"
    :key          nil
    :entity-name  "FormMotorNeuroneDiseasePhenotype"}
   {:form-type-id nil                                       ;; the ECAS form has no implementation in the legacy application
    :table        "t_form_ecas"
    :title        "ECAS"
    :key          nil
    :entity-name  nil}
   {:form-type-id nil
    :table        "t_form_fimfam"
    :title        "FIMFAM"
    :key          nil
    :entity-name  nil}
   {:form-type-id nil                                       ;;; the consent form is a special form, that is not listed as an official form in t_form_type.
    :table        "t_form_consent"
    :title        "Consent form"
    :key          nil
    :entity-name  "FormConsent"}])

(s/fdef normalize-form-type
  :args (s/cat :form-type (s/keys :req-un [::form-type-id ::table] :opt-un [::nm ::parse ::summary])))
(defn normalize-form-type
  "Normalizes a form type by ensuring it provides a name as well as default 
  no-op parse, unparse and summary functions if not explicitly provided."
  [{:keys [nm table parse unparse summary] :as form-type}]
  (assoc form-type
    :nm (or nm (if (str/starts-with? table "t_form") (subs table 2) (throw (ex-info "Cannot determine default form name" form-type))))
    :table-kw (keyword table)
    :summary (or summary (constantly nil))
    :parse (or parse identity)
    :unparse (or unparse identity)))

(def forms
  "A sequence of all known 'forms', each with a defined name and default no-op
  parse, unparse and summary functions if not explicitly provided."
  (map normalize-form-type forms*))

(def form-type-by-table
  "Return form type by table
  e.g.,
  ```
    (form-type-by-table \"t_form_edss\")
  ```
  "
  (reduce (fn [acc {:keys [table] :as form-type}] (assoc acc table form-type)) {} forms))

(def form-type-by-name
  "Return form type by name.
  e.g.,
  ```
    (form-type-by-name \"form_edss\")
  ```"
  (reduce (fn [acc {:keys [nm] :as form-type}] (assoc acc nm form-type)) {} forms))

(def form-type-by-id
  (reduce (fn [acc {:keys [form-type-id] :as form-type}]
            (assoc acc form-type-id form-type)) {} forms))

(s/fdef parse-form
  :args (s/cat :form-type ::normalized-form-type :form any?))
(defn parse-form
  "Parse form data from the database - annotating with type information,
  generating a summary and converting properties when appropriate."
  [{:keys [form-type-id table table-kw title allow-multiple? summary parse]} form]
  (let [form-id (get form (keyword table "id"))
        k-is-deleted (keyword table "is_deleted")
        is-deleted (parse-boolean (get form k-is-deleted))
        form' (parse form)]
    (-> form'
        (assoc k-is-deleted is-deleted)
        (assoc :t_form/id             form-id
               :t_form/is_deleted     is-deleted
               :t_form/summary_result (summary form')   ;; care to generate summary using parsed form and not raw form fetch result
               :t_form/form_type      {:t_form_type/id                form-type-id
                                       :t_form_type/table             table
                                       :t_form_type/namespace         table-kw
                                       :t_form_type/title             title
                                       :t_form_type/one_per_encounter (not allow-multiple?)}))))

(s/fdef form-for-encounter-sql
  :args (s/cat :encounter-id int? :table keyword? :options (s/keys :opt-un [::include-deleted])))
(defn form-for-encounter-sql
  "Generate SQL to return a form for the given encounter.
  - encounter-id    : encounter identifier
  - table           : table e.g. `:t_form_edss`
  - include-deleted : whether to include deleted forms, default `false`."
  [encounter-id table {:keys [include-deleted]}]
  {:select :*
   :from   table
   :where  [:and
            [:= :encounter_fk encounter-id]
            (when-not include-deleted [:= :is_deleted "false"])]})

(s/fdef forms-for-encounter
  :args (s/cat :conn ::conn :encounter-id int? :opts (s/? (s/keys :opt-un [::include-deleted]))))
(defn forms-for-encounter
  "Return forms for the given encounter. Returns only 'active' non-deleted forms by default. 
  Normalizes each result, annotating with form type information as well as a result
  summary. To include deleted forms, set `include-deleted` to be `true`."
  ([conn encounter-id]
   (forms-for-encounter conn encounter-id nil))
  ([conn encounter-id {:keys [include-deleted] :or {include-deleted false}}]
   (reduce
     (fn [acc {:keys [table-kw allow-multiple?] :as form-type}]
       (let [stmt (form-for-encounter-sql encounter-id table-kw {:include-deleted include-deleted})]
         (if allow-multiple?
           (if-let [forms (seq (jdbc/execute! conn (sql/format stmt)))]
             (into acc (map #(parse-form form-type %)) forms)
             acc)
           (if-let [form (jdbc/execute-one! conn (sql/format stmt))]
             (conj acc (parse-form form-type form))
             acc))))
     [] forms)))

(defn ^:private form-types-for-encounter
  "Return the available form types for a given encounter based on the
  encounter template for that encounter. Returns a map of form status to a sequence
  of form types, which will include ordering, id and title information.
  e.g.,
  (form-types-for-encounter conn 246234)
  ;; =>
  ;;  {:available
  ;;   (#:t_form_type{:ordering 0,
  ;;                  :id 30,
  ;;                  :title \"Ishihara\",
  ;;                  :table \"t_form_ishihara\"}
  ;;    #:t_form_type{:ordering 0,
  ;;                  :id 7,
  ;;                  :title \"MMSE\",
  ;;                  :table \"t_form_mmse\"}
  ;;    #:t_form_type{:ordering 0,
  ;;                  :id 13,
  ;;                  :title \"SOAP\",
  ;;                  :table \"t_form_soap\"})} "
  [conn encounter-id]
  (reduce
    (fn [acc {:t_encounter_template__form_type/keys [formtypeid status ordering]}]
      (let [{:keys [title table table-kw allow-multiple?]} (form-type-by-id formtypeid)]
        (update acc (keyword (str/lower-case status))
                conj
                (hash-map :t_form_type/id                formtypeid
                          :t_form_type/table             table
                          :t_form_type/namespace         table-kw
                          :t_form_type/title             title
                          :t_form_type/ordering          ordering
                          :t_form_type/one_per_encounter (not allow-multiple?)))))
    {}
    (jdbc/plan conn (sql/format
                      {:select :* :from :t_encounter_template__form_type
                       :where [:= :encountertemplateid {:select :encounter_template_fk :from :t_encounter :where [:= :id encounter-id]}]}))))

(defn forms-and-form-types-in-encounter
  "For a given encounter, returns the forms already completed, and any other available forms. 
  Depending on the encounter template, there will be mandatory, available or optional forms. 
  Where there are mandatory forms, a user interface can force the user to complete as per the 
  legacy application. Available forms are forms that should be easily accessible to the end-user.
  Optional forms can be less accessible to the end-user because they are less frequently used.
  Returns a map with the following keys: 
  - :available-form-types - an ordered sequence of form types, ordered by `ordering` (as per encounter template) and title
  - :optional-form-types  - an ordered sequence of form types as per :available, but less often used as per template
  - :mandatory-form-types - an ordered sequence of form types as per :available but mandatory as per template
  - :existing-form-types  - a sequence of form types that have already been recorded
  - :completed-forms      - a sequence of forms (each with `:t_form/form_type`) already completed
  - :deleted-forms        - a sequence of forms (each with `:t_form/form_type`) deleted from the encounter."
  [conn encounter-id]
  (let [forms (forms-for-encounter conn encounter-id {:include-deleted true})   ;; all forms
        completed (remove :t_form/is_deleted forms)                       ;; already completed forms
        existing (map :t_form/form_type completed)                         ;; get form types for the completed forms
        existing-type-ids (into #{} (map :t_form_type/id) existing)        ;; get a set of form-type ids for completed forms
        form-types-by-status (form-types-for-encounter conn encounter-id)  ;; get available forms as per encounter template
        {:keys [mandatory available optional]}    ;; remove already completed forms from each category, and sort
        (update-vals form-types-by-status                     
                   (fn [form-types]
                     (->> form-types                        ;; remove any existing and sort by ordering and title
                          (remove #(existing-type-ids (:t_form_type/id %)))
                          (sort-by (juxt :t_form_type/ordering :t_form_type/title)))))]
    {:available-form-types available
     :optional-form-types  optional
     :mandatory-form-types mandatory
     :existing-form-types  (sort-by :t_form_type/title existing)
     :completed-forms      (sort-by (comp :t_form_type/title :t_form/form_type) completed)
     :deleted-forms        (sort-by (comp :t_form_type/title :t_form/form_type) (filter :t_form/is_deleted forms))}))

(defn all-active-encounter-ids
  "Return a set of encounter ids for active encounters of the given patient."
  [conn patient-identifier]
  (into #{}
        (map :t_encounter/id)
        (jdbc/plan conn (sql/format
                          {:select [:t_encounter/id]
                           :from   :t_encounter
                           :where  [:and
                                    [:= :patient_fk {:select [:t_patient/id]
                                                     :from   [:t_patient]
                                                     :where  [:= :patient_identifier patient-identifier]}]
                                    [:<> :t_encounter/is_deleted "true"]]}))))

(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (sql/format {:select :* :from :t_form_edss :where [:and [:= :encounter_fk 1]]})
  (all-active-encounter-ids conn 14031)
  (def results (forms-for-encounter conn 8042))
  results
  (forms-for-encounter conn 1834)
  (forms-for-encounter conn 246234)
  (form-types-for-encounter conn 246234)
  (forms-and-form-types-in-encounter conn 246234)
  (forms-and-form-types-in-encounter conn 280071)
  (map #(forms-and-form-types-in-encounter conn %) (all-active-encounter-ids conn 14031)))

;;
;;
;;
;;
;;

(defn encounter->form_smoking_history
  "Return a form smoking history for the encounter."
  [conn encounter-id]
  (db/execute-one! conn (sql/format {:select [:t_smoking_history/id :t_smoking_history/status
                                              :t_smoking_history/current_cigarettes_per_day]
                                     :from   [:t_smoking_history]
                                     :where  [:and
                                              [:= :t_smoking_history/encounter_fk encounter-id]
                                              [:<> :t_smoking_history/is_deleted "true"]]})))

(defn select-keys-by-namespace
  "Like select-keys, but selects based on namespace.
  Keys will be selected if there are not namespaced, or of the namespace
  specified."
  [m nspace]
  (let [nspace' (name nspace)]
    (reduce-kv (fn [a k v]
                 (if (or (nil? (namespace k)) (= nspace' (namespace k)))
                   (assoc a (name k) v) a)) {} m)))

(defn some-keys-in-namespace? [nspace m]
  (some #(= (name nspace) (namespace %)) (keys m)))

(defn all-keys-in-namespace? [pred m]
  (every? pred (map namespace (keys m))))

(defn delete-all-forms-sql [encounter-id table-name]
  {:update (keyword table-name)
   :where  [:and [:= :encounter_fk encounter-id] [:= :is_deleted "false"]]
   :set    {:is_deleted "true"}})

(defn insert-form-sql
  [encounter-id table-name form-data]
  {:insert-into (keyword table-name)                        ;; note as this table uses legacy WO horizontal inheritance, we use t_form_seq to generate identifiers manually.
   :values      [(merge {:id           {:select [[[:nextval "t_form_seq"]]]}
                         :is_deleted   "false"
                         :date_created (LocalDateTime/now)
                         :encounter_fk encounter-id}
                        form-data)]})

(defn save-form-in-encounter-sql
  "Return a vector of SQL to save the form data `data` to the given table.
  There are four alternatives:
  1. If there is no form-data, all forms of the type specified that are linked
     to the encounter will be marked as deleted.
  2. If the form-data contains a form id (e.g. `:t_form_edss/id`), then existing
     data with the specified identifier will be updated with the form data.
  3. If there is no form id, the data will be inserted as a new record.
  3. If the form data is a collection of form data, each will be processed, but
     after all forms of the type specified linked to that encounter are deleted."
  ([encounter table-name form-data]
   (save-form-in-encounter-sql encounter table-name form-data true))
  ([{encounter-id :t_encounter/id :as encounter} table-name form-data delete-before-insert]
   (when table-name
     (let [user-key (keyword table-name "user_fk")
           form-data (if (and (map? form-data) (nil? (get form-data user-key)))
                       (assoc form-data user-key (:t_user/id encounter))
                       form-data)
           id-key (keyword table-name "id")
           id (get form-data id-key)
           encounter-fk (get form-data (keyword table-name "encounter_fk"))]
       (cond
         ;; if there is no form data, delete all forms of this type
         (nil? form-data)
         [(delete-all-forms-sql encounter-id table-name)]

         ;; if the encounter identifiers don't match, throw
         (and encounter-fk (not= encounter-id encounter-fk))
         (throw (ex-info "form data has different encounter fk to encounter" {:encounter encounter :form-data form-data}))

         ;; if the form data isn't correct, throw
         (and (map? form-data) (not (all-keys-in-namespace? #{table-name} form-data)))
         (throw (ex-info "form data keys must all use the same namespace" form-data))

         ;; if we're updating existing data, update
         (and (map? form-data) id)
         [{:update (keyword table-name) :set form-data :where [:and [:= :id id] [:= :encounter_fk encounter-id]]}]

         ;; we've got a new form => delete any existing and insert a new
         (and (map? form-data) delete-before-insert)
         [(delete-all-forms-sql encounter-id table-name)
          (insert-form-sql encounter-id table-name form-data)]

         ;; just insert without deleting
         (map? form-data)
         [(insert-form-sql encounter-id table-name form-data)]

         ;; we have multiple forms of this type => save each one in turn
         (seq form-data)
         (into [(delete-all-forms-sql encounter-id table-name)]
               (mapcat #(save-form-in-encounter-sql encounter table-name (dissoc % id-key) false) form-data))

         ;; we have an empty sequence of forms => delete all active forms
         :else
         [(delete-all-forms-sql encounter-id table-name)])))))

(defn save-encounter-forms-sql
  "Returns a sequence of SQL statements (as Clojure data structures) to either
   delete, update or insert the form data. It is expected that 'forms' are
   nested within an encounter. They should be nested using their form name
   (e.g. `:t_encounter/form_edss`)."
  [encounter]
  (->> (keys encounter)
       (reduce
         (fn [acc k]
           (if-let [{:keys [table]} (form-type-by-name (name k))]
             (into acc (save-form-in-encounter-sql encounter table (get encounter k)))
             acc)) [])
       (remove nil?)))

(defn save-encounter-and-forms-sql
  "Generates a sequence of SQL to write the encounter and any nested forms
  to the database. The SQL to write the encounter is always returned first."
  [encounter]
  (into [(patients/save-encounter-sql encounter)]
        (save-encounter-forms-sql encounter)))

(s/def ::save-encounter-and-forms
  (s/keys :req [:t_encounter/date_time
                :t_encounter/encounter_template_fk]
          :opt [:t_encounter/id :t_encounter/notes]))

(s/fdef save-encounter-and-forms!
  :args (s/cat :txn ::db/txn :encounter ::save-encounter-and-forms))
(defn save-encounter-and-forms!
  "Saves the given encounter, which can include form data."
  [txn encounter]
  (log/info "saving encounter and forms" encounter)
  (let [[encounter-sql & forms-sql-stmts] (save-encounter-and-forms-sql encounter)
        encounter' (db/execute! txn (sql/format encounter-sql) {:return-keys true})]
    (doseq [stmt forms-sql-stmts]
      (db/execute! txn (sql/format stmt)))
    encounter'))                                            ;; take care to return updated encounter entity.

;;
;;
;;
;;
;;

(defn unparse-form
  "Prepare a form to be written to the database. Returns a map containing:
  - form-type : the form type for this form
  - data      : 'unparsed' form data appropriate for the database schema
  The form data is validated according to the form type specification, with 
  any custom form-type 'unparse' function called, and then form data is 
  returned as a map of keys without namespaces." 
  [form]
  (if-let [nspace (some namespace (keys form))]
    (if-let [{:keys [spec unparse] :as form-type} (or (form-type-by-table nspace) (form-type-by-name))]
      (if (and spec (not (s/valid? spec form))) ;; if there is a spec, and it is invalid, throw
        (throw (ex-info "invalid form" (s/explain-data spec form)))
        {:form-type form-type
         :data (reduce-kv 
                 (fn [acc k v]
                   (let [nspace' (namespace k)]
                     (cond
                       ;; add non-namespaced keys as-is 
                       (nil? nspace')      (assoc acc k v)
                       ;; if the namespace matches the form namespace, add non-namespaced version of key
                       (= nspace nspace')  (assoc acc (keyword (name k)) v)
                       ;; if the key has the special namespace :t_form, it is a computed property, so ignore it
                       (= "t_form" nspace') acc
                       ;; otherwise, it is a key we do not recognise, so throw an exception
                       :else (throw (ex-info (str "all keys in a form must be in same namespace; expected:'" nspace "', got:" nspace') form)))))
                 {} 
                 (unparse form))})
      (throw (ex-info "unable to determine form type for form" form)))
    (throw (ex-info "keys in a form must have a namespace" form))))

(comment
  (unparse-form {:date_time (LocalDateTime/now)
                 :t_form_edss/id 3
                 :t_form_edss/encounter_fk 123
                 :t_form_edss/edss_score "SCORE1_0"}))

(defn form->type-and-save-sql
  "Returns a map containing :form-type and :sql for the given form.
  - :form-type  : the form type for the given form
  - :sql        : SQL to either insert or update a form as appropriate 
  Returns an update statement if there is an existing id, or an insert
  statement if not. As currently all supported forms uses legacy WO horizontal
  inheritance, we use t_form_seq to generate identifiers manually. "
  [form]
  (let [{:keys [form-type data]} (unparse-form form)
        {:keys [table-kw]} form-type]
    {:form-type form-type
     :sql       (if-let [id (:id data)] 
                  {:update    table-kw 
                   :set       (dissoc data :id) 
                   :where     [:= :id id]
                   :returning :*}
                  {:insert-into table-kw                        
                   :values      [(merge {:id           {:select [[[:nextval "t_form_seq"]]]}
                                         :is_deleted   "false" 
                                         :date_created (LocalDateTime/now)}
                                        (dissoc data :id))]}) }))

(defn form->type-and-delete-sql
  "Returns a map containing :form-type and :sql for the given form.
  Parameters:
  - :form   - the form to delete (a map)
  - :delete - to delete or undelete
  Result is a map containing:
  - :form-type: the form type for the given form
  - :sql       : SQL to delete the form; nil if does not exist" 
  [form delete]
  (let [{:keys [form-type data]} (unparse-form form)
        {:keys [table-kw]} form-type]
    {:form-type form-type
     :sql       (when-let [id (:id data)]  
                  {:update table-kw
                   :set {:is_deleted (if delete "true" "false")}
                   :where [:= :id id]
                   :returning :*})}))

(defn save-form! 
  "Saves a form to the database. Matches the form to its form-type using the 
  namespace and handles either 'insert' or 'update' appropriately depending
  on whether there is a valid id. Returns the inserted/updated form data. It
  should be possible to use the return of `save-form!` in another call of 
  `save-form!` without changing the result."
  [conn form]
  (let [{:keys [form-type sql]} (form->type-and-save-sql form)]
    (parse-form form-type (db/execute-one! conn (sql/format sql) {:return-keys true}))))

(defn delete-form!
  "'Deletes' a form. Forms are never actually deleted, but merely marked as 
  deleted. Returns the updated 'deleted' form. Throws an exception if the form does
  not exist and does not have an existing id."
  [conn form]
  (let [{:keys [form-type sql]} (form->type-and-delete-sql form true)]
    (if sql
      (parse-form form-type (db/execute-one! conn (sql/format sql) {:return-keys true}))
      (throw (ex-info "cannot delete form; no existing id" form)))))

(defn undelete-form!
  "'Undelete' a form."
  [conn form]
  (let [{:keys [form-type sql]} (form->type-and-delete-sql form false)]
    (if sql
      (parse-form form-type (db/execute-one! conn (sql/format sql) {:return-keys true}))
      (throw (ex-info "cannot undelete form; no existing id" form)))))

(comment
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (form->type-and-sql {:t_form_edss/id nil
                       :t_form_edss/user_fk 1 
                       :t_form_edss/encounter_fk 123 
                       :t_form_edss/edss_score "SCORE1_0"})
  (honey.sql/format (:save-sql (form->type-and-sql {:t_form_edss/id 55 
                                               :t_form_edss/user_fk 1
                                               :t_form_edss/encounter_fk 123 
                                               :t_form_edss/edss_score "SCORE1_0"})))
  (def encounters (mapv (fn [id] {:t_encounter/id id}) (all-active-encounter-ids conn 124018)))
  (com.eldrix.pc4.rsdb.patients/active-episodes conn 124010)
  (all-active-encounter-ids conn 124010)
  (all-active-encounter-ids conn 10432)
  (def form (save-form! conn {:t_form_edss/id nil
                              :t_form_edss/encounter_fk 1608
                              :t_form_edss/edss_score "SCORE1_0"
                              :t_form_edss/user_fk 1}))
  (def form' (save-form! conn form))
  (= form form'))

