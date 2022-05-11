(ns com.eldrix.pc4.server.modules.dmt
  "Experimental approach to data downloads, suitable for short-term
   requirements for multiple sclerosis disease modifying drug
   post-marketing surveillance."
  (:require [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.math :as math]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.deprivare.core :as deprivare]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [com.eldrix.pc4.server.codelists :as codelists]
            [com.eldrix.pc4.server.system :as pc4]
            [com.eldrix.pc4.server.rsdb.patients :as patients]
            [com.eldrix.pc4.server.rsdb.projects :as projects]
            [com.eldrix.pc4.server.rsdb.results :as results]
            [com.eldrix.pc4.server.rsdb.users :as users]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [honey.sql :as sql]
            [next.jdbc.plan :as plan])

  (:import (java.time LocalDate LocalDateTime Period)
           (java.time.temporal ChronoUnit Temporal)
           (java.time.format DateTimeFormatter)
           (java.io PushbackReader File)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute)))

(def study-master-date
  (LocalDate/of 2014 05 01))


(def study-centres
  "This defines each logical centre with a list of internal 'projects' providing
  a potential hook to create combined cohorts in the future should need arise."
  {:cardiff   {:projects ["NINFLAMMCARDIFF"] :prefix "CF"}
   :cambridge {:projects ["CAMBRIDGEMS"] :prefix "CB"}
   :plymouth  {:projects ["PLYMOUTH"] :prefix "PL"}})

(s/def ::centre (set (keys study-centres)))


(def study-medications
  "A list of interesting drugs for studies of multiple sclerosis.
  They are classified as either platform DMTs or highly efficacious DMTs.
  We define by the use of the SNOMED CT expression constraint language, usually
  on the basis of active ingredients together with ATC codes. "
  {:dmf
   {:description "Dimethyl fumarate"
    :atc         "L04AX07"
    :brand-names ["Tecfidera"]
    :class       :platform-dmt
    :codelist    {:ecl
                  "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR
                  (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
                  :atc "L04AX07"}}

   :glatiramer
   {:description "Glatiramer acetate"
    :brand-names ["Copaxone" "Brabio"]
    :atc         "L03AX13"
    :class       :platform-dmt
    :codelist    {:ecl
                  "<<108754007|Glatiramer| OR <<9246601000001104|Copaxone| OR <<13083901000001102|Brabio| OR <<8261511000001102 OR <<29821211000001101
                  OR (<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<108755008|Glatiramer acetate|)"
                  :atc "L03AX13"}}

   :ifn-beta-1a
   {:description "Interferon beta 1-a"
    :brand-names ["Avonex" "Rebif"]
    :atc         "L03AB07 NOT L03AB13"
    :class       :platform-dmt
    :codelist    {:inclusions {:ecl
                               "(<<9218501000001109|Avonex| OR <<9322401000001109|Rebif| OR
                               (<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386902004|Interferon beta-1a|))"
                               :atc "L03AB07"}
                  :exclusions {:ecl "<<12222201000001108|PLEGRIDY|"
                               :atc "L03AB13"}}}

   :ifn-beta-1b
   {:description "Interferon beta 1-b"
    :brand-names ["Betaferon®" "Extavia®"]
    :atc         "L03AB08"
    :class       :platform-dmt
    :codelist    {:ecl "(<<9222901000001105|Betaferon|) OR (<<10105201000001101|Extavia|) OR
                     (<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386903009|Interferon beta-1b|)"
                  :atc "L03AB08"}}

   :peg-ifn-beta-1a
   {:description "Peginterferon beta 1-a"
    :brand-names ["Plegridy®"]
    :atc         "L03AB13"
    :class       :platform-dmt
    :codelist    {:ecl "<<12222201000001108|Plegridy|"
                  :atc "L03AB13"}}

   :teriflunomide
   {:description "Teriflunomide"
    :brand-names ["Aubagio®"]
    :atc         "L04AA31"
    :class       :platform-dmt
    :codelist    {:ecl "<<703786007|Teriflunomide| OR <<12089801000001100|Aubagio| "
                  :atc "L04AA31"}}

   :rituximab
   {:description "Rituximab"
    :brand-names ["MabThera®" "Rixathon®" "Riximyo" "Blitzima" "Ritemvia" "Rituneza" "Ruxience" "Truxima"]
    :atc         "L01XC02"
    :class       :he-dmt
    :codelist    {:ecl
                  (str/join " "
                            ["(<<108809004|Rituximab product|)"
                             "OR (<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<386919002|Rituximab|)"
                             "OR (<<9468801000001107|Mabthera|) OR (<<13058501000001107|Rixathon|)"
                             "OR (<<226781000001109|Ruxience|)  OR (<<13033101000001108|Truxima|)"])
                  :atc "L01XC02"}}

   :ocrelizumab
   {:description "Ocrelizumab"
    :brand-names ["Ocrevus"]
    :atc         "L04AA36"
    :class       :he-dmt
    :codelist    {:ecl "(<<35058611000001103|Ocrelizumab|) OR (<<13096001000001106|Ocrevus|)"
                  :atc "L04AA36"}}

   :cladribine
   {:description "Cladribine"
    :brand-names ["Mavenclad"]
    :atc         "L04AA40"
    :class       :he-dmt
    :codelist    {:ecl "<<108800000|Cladribine| OR <<13083101000001100|Mavenclad|"
                  :atc "L04AA40"}}

   :mitoxantrone
   {:description "Mitoxantrone"
    :brand-names ["Novantrone"]
    :atc         "L01DB07"
    :class       :he-dmt
    :codelist    {:ecl "<<108791001 OR <<9482901000001102"
                  :atc "L01DB07"}}

   :fingolimod
   {:description "Fingolimod"
    :brand-names ["Gilenya"]
    :atc         "L04AA27"
    :class       :he-dmt
    :codelist    {:ecl "<<715640009 OR <<10975301000001100"
                  :atc "L04AA27"}}

   :natalizumab
   {:description "Natalizumab"
    :brand-names ["Tysabri"]
    :atc         "L04AA23"
    :class       :he-dmt
    :codelist    {:ecl "<<414804006 OR <<9375201000001103"
                  :atc "L04AA23"}}
   :alemtuzumab
   {:description "Alemtuzumab"
    :brand-names ["Lemtrada"]
    :atc         "L04AA34"
    :class       :he-dmt
    :codelist    {:ecl "(<<391632007|Alemtuzumab|) OR (<<12091201000001101|Lemtrada|)"
                  :atc "L04AA34"}}
   :statin
   {:description "Statins"
    :class       :other
    :codelist    {:atc "C10AA"}}

   :anti-hypertensive
   {:description "Anti-hypertensive"
    :class       :other
    :codelist    {:atc "C02"}}

   :anti-platelet
   {:description "Anti-platelets"
    :class       :other
    :codelist    {:atc "B01AC"
                  :ecl "<<7947003"}}

   :anti-coagulant
   {:description "Anticoagulants"
    :class       :other
    :codelist    {:atc ["B01AA" "B01AF" "B01AE" "B01AD" "B01AX04" "B01AX05"]}}

   :proton-pump-inhibitor
   {:description "Proton pump inhibitors"
    :class       :other
    :codelist    {:atc "A02BC"}}

   :immunosuppressant                                       ;; NB: need to double check the list for LEM-PASS vs LEM-DUS and see if match
   {:description "Immunosuppressants"
    :class       :other
    :codelist    {:inclusions {:atc ["L04AA" "L04AB" "L04AC" "L04AD" "L04AX"]}
                  :exclusions {:atc ["L04AA23" "L04AA27" "L04AA31" "L04AA34" "L04AA36" "L04AA40" "L04AX07"]}}}

   :anti-retroviral
   {:description "Antiretrovirals"
    :class       :other
    :codelist    {:inclusions {:atc ["J05AE" "J05AF" "J05AG" "J05AR" "J05AX"]}
                  :exclusions {:atc ["J05AE11" "J05AE12" "J05AE13"
                                     "J05AF07" "J05AF08" "J05AF10"
                                     "J05AX15" "J05AX65"]}}}
   :anti-infectious
   {:description "Anti-infectious medications"
    :class       :other
    :codelist    {:inclusions {:atc ["J01." "J02." "J03." "J04." "J05."]}
                  :exclusions {:atc ["J05A."]}}}

   :antidepressant
   {:description "Anti-depressants"
    :class       :other
    :codelist    {:atc "N06A"}}

   :benzodiazepine
   {:description "Benzodiazepines"
    :class       :other
    :codelist    {:atc ["N03AE" "N05BA"]}}

   :antiepileptic
   {:description "Anti-epileptics"
    :class       :other
    :codelist    {:atc "N03A"}}

   :antidiabetic
   {:description "Anti-diabetic"
    :class       :other
    :codelist    {:atc "A10"}}

   :nutritional
   {:description "Nutritional supplements and vitamins"
    :class       :other
    :codelist    {:atc ["A11" "B02B" "B03C"] :ecl "<<9552901000001103"}}})

(def flattened-study-medications
  "A flattened sequence of study medications for convenience."
  (reduce-kv (fn [acc k v] (conj acc (assoc v :id k))) [] study-medications))

(def study-diagnosis-categories
  "These are project specific criteria for diagnostic classification."
  {:multiple_sclerosis
   {:description "Multiple sclerosis"
    :codelist    {:ecl "<<24700007"}}

   :allergic_reaction
   {:description "Any type of allergic reaction, including anaphylaxis"
    :codelist    {:ecl "<<419076005 OR <<243865006"}}

   :cardiovascular
   {:description "Cardiovascular disorders"
    :codelist    {:icd10 ["I"]}}

   :cancer
   {:description "Cancer, except skin cancers"
    :codelist    {:inclusions {:icd10 "C"} :exclusions {:icd10 "C44"}}}

   :connective_tissue
   {:description "Connective tissue disorders"
    :codelist    {:icd10 ["M45." "M33." "M35.3" "M05." "M35.0" "M32.8" "M34."
                          "M31.3" "M30.1" "L95." "D89.1" "D69.0" "M31.7" "M30.3"
                          "M30.0" "M31.6" "I73." "M31.4" "M35.2" "M94.1" "M02.3"
                          "M06.1" "E85.0" "D86."]}}
   :endocrine
   {:codelist {:icd10 ["E27.4" "E10" "E06.3" "E05.0"]}}

   :gastrointestinal
   {:codelist {:icd10 ["K75.4" "K90.0" "K50." "K51." "K74.3"]}}

   :severe_infection                                        ;; note: also significance based on whether admitted for problem
   {:codelist {:icd10 ["A" "B" "U07.1" "U07.2" "U08." "U09." "U10."]}}

   :arterial_dissection
   {:codelist {:icd10 ["I67.0" "I72.5"]}}

   :stroke
   {:codelist {:icd10 ["I6"]}}

   :angina_or_myocardial_infarction
   {:codelist {:icd10 ["I20" "I21" "I22" "I23" "I24" "I25"]}}

   :coagulopathy
   {:codelist {:icd10 ["D65" "D66" "D67" "D68" "D69" "Y44.2" "Y44.3" "Y44.4" "Y44.5"]}}

   :respiratory
   {:codelist {:icd10 ["J0" "J1" "J20" "J21" "J22"]}}

   :hiv
   {:codelist {:icd10 ["B20.", "B21.", "B22.", "B24.", "F02.4" "O98.7" "Z21", "R75"]}}

   :autoimmune_disease
   {:codelist {:icd10 ["M45." "M33." "M35.3" "M05." "M35.0" "M32." "M34." "M31.3" "M30.1" "L95." "D89.1" "D69.0" "M31.7" "M30.3" "M30.0"
                       "M31.6" "I73.0" "M31.4" "M35.2" "M94.1" "M02.3" "M06.1" "E85.0" "D86." "E27.1" "E27.2" "E27.4" "E10" "E06.3" "E05.0" "K75.4" "K90.0"
                       "K50." "K51." "K74.3" "L63." "L10.9" "L40." "L80." "G61.0" "D51.0" "D59.1" "D69.3" "D68" "N02.8" "M31.0" "D76.1"
                       "I01.2" "I40.8" "I40.9" "I09.0" "G04.0" "E31.0" "I01." "G70.0" "G73.1"]}}

   :uncontrolled_hypertension                               ;; I have used a different definition to the protocol as R03.0 is wrong
   {:codelist {:ecl "<<706882009"}}                         ;; this means 'hypertensive emergency'

   :urinary_tract
   {:codelist {:icd10 ["N10." "N11." "N12." "N13." "N14." "N15." "N16."]}}

   :hair_and_skin
   {:codelist {:icd10 ["L63." "L10.9" "L40." "L80.0"]}}

   :mental_behavioural
   {:codelist {:icd10 ["F"]}}

   :epilepsy
   {:codelist {:icd10 ["G40"]}}

   :other
   {:codelist {:icd10 ["G61.0" "D51.0" "D59.1" "D69.3" "D68.8" "D68.9"
                       "N02.8" "M31.0" "D76.1"
                       "M05.3" "I01.2" "I40.8" "I40.9" "I09.0" "G04.0"
                       "E31.0" "D69.3" "I01." "G70.0" "G70.8" "G73.1"]}}})

(def flattened-study-diagnoses
  "A flattened sequence of study diagnoses for convenience."
  (reduce-kv (fn [acc k v] (conj acc (assoc v :id k))) [] study-diagnosis-categories))

(defn ^:deprecated make-diagnostic-category-fn
  "Returns a function that will test a collection of concept identifiers against the diagnostic categories specified."
  [system categories]
  (let [codelists (reduce-kv (fn [acc k v] (assoc acc k (codelists/make-codelist system (:codelist v))))
                             {}
                             categories)]
    (fn [concept-ids]
      (reduce-kv (fn [acc k v] (assoc acc k (codelists/member? v concept-ids))) {} codelists))))

(defn expand-codelists [system categories]
  (update-vals categories
               (fn [{codelist :codelist :as v}]
                 (assoc v :codes (codelists/expand (codelists/make-codelist system codelist))))))

(defn make-codelist-category-fn
  "Creates a function that will return a map of category to boolean, for each
  category. "
  [system categories]
  (let [cats' (expand-codelists system categories)]
    (fn [concept-ids]
      (reduce-kv (fn [acc k v] (assoc acc k (boolean (some true? (map #(contains? (:codes v) %) concept-ids))))) {} cats'))))

(comment

  (def ct-disorders (codelists/make-codelist system {:icd10 ["M45." "M33." "M35.3" "M05." "M35.0" "M32.8" "M34."
                                                             "M31.3" "M30.1" "L95." "D89.1" "D69.0" "M31.7" "M30.3"
                                                             "M30.0" "M31.6" "I73." "M31.4" "M35.2" "M94.1" "M02.3"
                                                             "M06.1" "E85.0" "D86."]}))
  (codelists/member? ct-disorders [9631008])


  (def diag-cats (make-codelist-category-fn system study-diagnosis-categories))
  (diag-cats [9631008 12295008 46635009 34000006 9014002 40956001])
  (diag-cats [6204001])

  (def codelists (reduce-kv (fn [acc k v] (assoc acc k (codelists/make-codelist system (:codelist v)))) {} study-diagnosis-categories))
  codelists
  (reduce-kv (fn [acc k v] (assoc acc k (codelists/member? v [9631008 24700007]))) {} codelists)


  (map #(hash-map :title (:term %) :release-date (:effectiveTime %)) (hermes/get-release-information (:com.eldrix/hermes system)))
  (def calcium-channel-blockers (codelists/make-codelist system {:atc "C08" :exclusions {:atc "C08CA01"}}))
  (codelists/member? calcium-channel-blockers [108537001])
  (take 2 (map #(:term (hermes/get-fully-specified-name (:com.eldrix/hermes system) %)) (codelists/expand calcium-channel-blockers)))
  (codelists/expand (codelists/make-codelist system {:icd10 "G37.3"}))
  (codelists/member? (codelists/make-codelist system {:icd10 "I"}) [22298006])
  (count (codelists/expand (codelists/make-codelist system {:icd10 "I"})))
  (get-in study-diagnosis-categories [:connective-tissue :codelist])

  (def cancer (codelists/make-codelist system {:inclusions {:icd10 "C"} :exclusions {:icd10 "C44"}}))
  (codelists/disjoint? (codelists/expand cancer) (codelists/expand (codelists/make-codelist system {:icd10 "C44"})))
  (defn ps [id] (:term (hermes/get-preferred-synonym (:com.eldrix/hermes system) id "en-GB")))
  (map ps (codelists/expand cancer))
  (map #(:term (hermes/get-preferred-synonym (:com.eldrix/hermes system) % "en-GB")) (hermes/member-field-prefix (:com.eldrix/hermes system) 447562003 "mapTarget" "C44"))

  (vals (hermes/source-historical-associations (:com.eldrix/hermes system) 24700007))

  (hermes/get-preferred-synonym (:com.eldrix/hermes system) 445130008 "en-GB"))


(defn make-atc-regexps [{:keys [codelist] :as med}]
  (when-let [atc (or (:atc codelist) (get-in codelist [:inclusions :atc]))]
    (if (coll? atc)
      (mapv #(vector (if (string? %) (re-pattern %) %) (dissoc med :codelist)) atc)
      (vector (vector (if (string? atc) (re-pattern atc) atc) (dissoc med :codelist))))))

(def study-medication-atc
  "A sequence containing ATC code regexp and study medication class information."
  (mapcat make-atc-regexps flattened-study-medications))

(defn get-study-classes [atc]
  (keep identity (map (fn [[re-atc med]] (when (re-matches re-atc atc) med)) study-medication-atc)))


(defmulti to-local-date class)

(defmethod to-local-date LocalDateTime [^LocalDateTime x]
  (.toLocalDate x))

(defmethod to-local-date LocalDate [x] x)

(defmethod to-local-date nil [_] nil)

(comment
  study-medication-atc
  (first (get-study-classes "N05BA")))


(defn all-ms-dmts
  "Returns a collection of multiple sclerosis disease modifying medications with
  SNOMED CT (dm+d) identifiers included. For validation, checks that each
  logical set of concepts is disjoint."
  [{:com.eldrix/keys [hermes dmd] :as system}]
  (let [result (map #(assoc % :codes (codelists/expand (codelists/make-codelist system (:codelist %)))) (remove #(= :other (:class %)) flattened-study-medications))]
    (if (apply codelists/disjoint? (map :codes (remove #(= :other (:class %)) result)))
      result
      (throw (IllegalStateException. "DMT specifications incorrect; sets not disjoint.")))))

(defn all-dmt-identifiers
  "Return a set of identifiers for all DMTs"
  [system]
  (set (apply concat (map :codes (all-ms-dmts system)))))

(defn all-he-dmt-identifiers
  "Returns a set of identifiers for all highly-efficacious DMTs"
  [system]
  (set (apply concat (map :codes (filter #(= :he-dmt (:class %)) (all-ms-dmts system))))))

(defn fetch-most-recent-encounter-date-time [{conn :com.eldrix.rsdb/conn}]
  (db/execute-one! conn (sql/format {:select [[:%max.date_time :most_recent_encounter_date_time]]
                                     :from   :t_encounter})))

(defn fetch-patients [{conn :com.eldrix.rsdb/conn} patient-ids]
  (db/execute! conn (sql/format {:select    [:patient_identifier :sex :date_birth :date_death :part1a :part1b :part1c :part2]
                                 :from      :t_patient
                                 :left-join [:t_death_certificate [:= :patient_fk :t_patient/id]]
                                 :where     [:in :t_patient/patient_identifier patient-ids]})))

(defn addresses-for-patients
  "Returns a map of patient identifiers to a collection of sorted addresses."
  [{conn :com.eldrix.rsdb/conn} patient-ids]
  (group-by :t_patient/patient_identifier
            (db/execute! conn (sql/format {:select   [:t_patient/patient_identifier :t_address/date_from :t_address/date_to :t_address/postcode_raw]
                                           :from     [:t_address]
                                           :order-by [[:t_patient/patient_identifier :asc] [:date_to :desc] [:date_from :desc]]
                                           :join     [:t_patient [:= :t_patient/id :t_address/patient_fk]]
                                           :where    [:in :t_patient/patient_identifier patient-ids]}))))
(defn active-encounters-for-patients
  "Returns a map of patient identifiers to a collection of sorted, active encounters.
  Encounters are sorted in descending date order."
  [{conn :com.eldrix.rsdb/conn} patient-ids]
  (group-by :t_patient/patient_identifier
            (db/execute! conn (sql/format {:select   [:t_patient/patient_identifier
                                                      :t_encounter/id
                                                      :t_encounter/date_time]
                                           :from     [:t_encounter]
                                           :join     [:t_patient [:= :t_patient/id :t_encounter/patient_fk]]
                                           :order-by [[:t_encounter/date_time :desc]]
                                           :where    [:and
                                                      [:in :t_patient/patient_identifier patient-ids]
                                                      [:<> :t_encounter/is_deleted "true"]]}))))

(defn ms-events-for-patients
  "Returns MS events (e.g. relapses) grouped by patient identifier, sorted in descending date order."
  [{conn :com.eldrix.rsdb/conn} patient-ids]

  (group-by :t_patient/patient_identifier
            (->> (db/execute! conn (sql/format
                                     {:select    [:t_patient/patient_identifier
                                                  :date :source :impact :abbreviation :name]
                                      :from      [:t_ms_event]
                                      :join      [:t_summary_multiple_sclerosis [:= :t_ms_event/summary_multiple_sclerosis_fk :t_summary_multiple_sclerosis/id]
                                                  :t_patient [:= :t_patient/id :t_summary_multiple_sclerosis/patient_fk]]
                                      :left-join [:t_ms_event_type [:= :t_ms_event_type/id :t_ms_event/ms_event_type_fk]]
                                      :order-by  [[:t_ms_event/date :desc]]
                                      :where     [:in :t_patient/patient_identifier patient-ids]}))
                 (map #(assoc % :t_ms_event/is_relapse (patients/ms-event-is-relapse? %))))))

(defn jc-virus-for-patients [{conn :com.eldrix.rsdb/conn} patient-ids]
  (->> (db/execute! conn (sql/format
                           {:select [:t_patient/patient_identifier
                                     :date :jc_virus :titre]
                            :from   [:t_result_jc_virus]
                            :join   [:t_patient [:= :t_result_jc_virus/patient_fk :t_patient/id]]
                            :where  [:and
                                     [:<> :t_result_jc_virus/is_deleted "true"]
                                     [:in :t_patient/patient_identifier patient-ids]]}))
       (map #(update % :t_result_jc_virus/date to-local-date))))


(defn mri-brains-for-patient [conn patient-identifier]
  (let [results (->> (results/fetch-mri-brain-results conn nil {:t_patient/patient_identifier patient-identifier})
                     (map #(assoc % :t_patient/patient_identifier patient-identifier))
                     results/all-t2-counts
                     (map results/gad-count-range))
        results-by-id (zipmap (map :t_result_mri_brain/id results) results)]
    (map #(if-let [compare-id (:t_result_mri_brain/compare_to_result_mri_brain_fk %)]
            (assoc % :t_result_mri_brain/compare_to_result_date (:t_result_mri_brain/date (get results-by-id compare-id)))
            %) results)))

(defn mri-brains-for-patients [{conn :com.eldrix.rsdb/conn} patient-ids]
  (->> (mapcat #(mri-brains-for-patient conn %) patient-ids)))

(defn multiple-sclerosis-onset
  "Derive dates of onset based on recorded date of onset, first MS event or date
  of diagnosis."
  [{conn :com.eldrix.rsdb/conn hermes :com.eldrix/hermes :as system} patient-ids]
  (let [ms-diagnoses (set (map :conceptId (hermes/expand-ecl-historic hermes "<<24700007")))
        first-ms-events (update-vals (ms-events-for-patients system patient-ids) #(:t_ms_event/date (last %)))
        pt-diagnoses (group-by :t_patient/patient_identifier
                               (db/execute! conn (sql/format {:select [:t_patient/patient_identifier
                                                                       :t_diagnosis/concept_fk
                                                                       :t_diagnosis/date_diagnosis
                                                                       :t_diagnosis/date_onset
                                                                       :t_diagnosis/date_to]
                                                              :from   [:t_diagnosis]
                                                              :join   [:t_patient [:= :t_diagnosis/patient_fk :t_patient/id]]
                                                              :where  [:and
                                                                       [:in :t_diagnosis/concept_fk ms-diagnoses]
                                                                       [:in :t_diagnosis/status ["ACTIVE" "INACTIVE_RESOLVED"]]
                                                                       [:in :t_patient/patient_identifier patient-ids]]})))]
    (when-let [dup-diagnoses (seq (reduce-kv (fn [acc k v] (when (> (count v) 1) (assoc acc k v))) {} pt-diagnoses))]
      (throw (ex-info "patients with duplicate MS diagnoses" {:duplicates dup-diagnoses})))
    (update-vals pt-diagnoses (fn [diags]
                                (let [diag (first diags)
                                      first-event (get first-ms-events (:t_patient/patient_identifier diag))]
                                  (assoc diag
                                    :has_multiple_sclerosis (boolean diag)
                                    :date_first_event first-event
                                    ;; use first recorded event as onset, or date onset in diagnosis
                                    :calculated-onset (or first-event (:t_diagnosis/date_onset diag))))))))


(defn fetch-patient-diagnoses
  [{conn :com.eldrix.rsdb/conn} patient-ids]
  (db/execute! conn (sql/format {:select [:t_patient/patient_identifier
                                          :t_diagnosis/concept_fk
                                          :t_diagnosis/date_diagnosis
                                          :t_diagnosis/date_onset
                                          :t_diagnosis/date_to]
                                 :from   [:t_diagnosis]
                                 :join   [:t_patient [:= :t_diagnosis/patient_fk :t_patient/id]]
                                 :where  [:and
                                          [:in :t_diagnosis/status ["ACTIVE" "INACTIVE_RESOLVED"]]
                                          [:in :t_patient/patient_identifier patient-ids]]})))

(defn fetch-patient-admissions
  [{conn :com.eldrix.rsdb/conn} project-name patient-ids]
  (db/execute! conn (sql/format {:select [:t_patient/patient_identifier :t_episode/date_registration :t_episode/date_discharge]
                                 :from   [:t_episode]
                                 :join   [:t_patient [:= :t_episode/patient_fk :t_patient/id]
                                          :t_project [:= :t_episode/project_fk :t_project/id]]
                                 :where  [:and
                                          [:= :t_project/name project-name]
                                          [:in :t_patient/patient_identifier patient-ids]]})))

(defn fetch-smoking-status
  [{conn :com.eldrix.rsdb/conn} patient-ids]
  (-> (group-by :t_patient/patient_identifier
                (db/execute! conn (sql/format {:select   [:t_patient/patient_identifier
                                                          :t_encounter/date_time
                                                          :t_smoking_history/status]
                                               :from     [:t_smoking_history]
                                               :join     [:t_encounter [:= :t_encounter/id :t_smoking_history/encounter_fk]
                                                          :t_patient [:= :t_patient/id :t_encounter/patient_fk]]
                                               :order-by [[:t_encounter/date_time :desc]]
                                               :where    [:and
                                                          [:<> :t_smoking_history/is_deleted "true"]
                                                          [:<> :t_encounter/is_deleted "true"]
                                                          [:in :t_patient/patient_identifier patient-ids]]})))
      (update-vals first)))

(defn fetch-weight-height
  [{conn :com.eldrix.rsdb/conn} patient-ids]
  (let [results (-> (group-by :t_patient/patient_identifier
                              (db/execute! conn (sql/format {:select   [:t_patient/patient_identifier
                                                                        :t_encounter/date_time
                                                                        :t_form_weight_height/weight_kilogram
                                                                        :t_form_weight_height/height_metres]
                                                             :from     [:t_form_weight_height]
                                                             :join     [:t_encounter [:= :t_encounter/id :t_form_weight_height/encounter_fk]
                                                                        :t_patient [:= :t_patient/id :t_encounter/patient_fk]]
                                                             :order-by [[:t_encounter/date_time :desc]]
                                                             :where    [:and
                                                                        [:<> :t_form_weight_height/is_deleted "true"]
                                                                        [:<> :t_encounter/is_deleted "true"]
                                                                        [:in :t_patient/patient_identifier patient-ids]]}))))]
    (update-vals results (fn [forms]
                           (let [default-ht (first (map :t_form_weight_height/height_metres forms))]
                             (map #(let [wt (:t_form_weight_height/weight_kilogram %)
                                         ht (or (:t_form_weight_height/height_metres %) default-ht)]
                                     (if (and wt ht) (assoc % :body_mass_index (with-precision 2 (/ wt (* ht ht))))
                                                     %)) forms))))))

(defn fetch-form-ms-relapse
  "Get a longitudinal record of MS disease activity, results keyed by patient identifier."
  [{conn :com.eldrix.rsdb/conn} patient-ids]
  (group-by :t_patient/patient_identifier
            (db/execute! conn (sql/format
                                {:select    [:t_patient/patient_identifier
                                             :t_encounter/date_time
                                             :t_form_ms_relapse/in_relapse
                                             :t_ms_disease_course/name
                                             :t_form_ms_relapse/activity
                                             :t_form_ms_relapse/progression]
                                 :from      [:t_form_ms_relapse]
                                 :join      [:t_encounter [:= :t_encounter/id :t_form_ms_relapse/encounter_fk]
                                             :t_patient [:= :t_encounter/patient_fk :t_patient/id]]
                                 :left-join [:t_ms_disease_course [:= :t_ms_disease_course/id :t_form_ms_relapse/ms_disease_course_fk]]
                                 :where     [:and
                                             [:<> :t_encounter/is_deleted "true"]
                                             [:<> :t_form_ms_relapse/is_deleted "true"]
                                             [:in :t_patient/patient_identifier patient-ids]]}))))


(def convert-edss-score
  {"SCORE10_0"         10.0
   "SCORE6_5"          6.5
   "SCORE1_0"          1.0
   "SCORE2_5"          2.5
   "SCORE5_5"          5.5
   "SCORE9_0"          9.0
   "SCORE7_0"          7.0
   "SCORE3_0"          3.0
   "SCORE5_0"          5.0
   "SCORE4_5"          4.5
   "SCORE8_5"          8.5
   "SCORE7_5"          7.5
   "SCORE8_0"          8.0
   "SCORE1_5"          1.5
   "SCORE6_0"          6.0
   "SCORE3_5"          3.5
   "SCORE0_0"          0.0
   "SCORE4_0"          4.0
   "SCORE2_0"          2.0
   "SCORE9_5"          9.5
   "SCORE_LESS_THAN_4" "<4"})

(defn get-most-recent
  "Given a date 'date', find the preceding map in the collection 'coll' within the limit, default 12 weeks."
  ([coll date-fn ^LocalDate date]
   (get-most-recent coll date-fn date (Period/ofWeeks 12)))
  ([coll date-fn ^LocalDate date ^Period max-period]
   (->> coll
        (remove #(.isAfter (to-local-date (date-fn %)) date)) ;; remove any entries after our date
        (remove #(.isBefore (to-local-date (date-fn %)) (.minus date max-period)))
        (sort-by date-fn)
        reverse
        first)))

(def simplify-ms-disease-course
  {"Secondary progressive with relapses" "SPMS"
   "Unknown"                             "UK"
   "Primary progressive"                 "PPMS"
   "Secondary progressive"               "SPMS"
   "Primary progressive with relapses"   "PPMS"
   "Clinically isolated syndrome"        "CIS"
   "Relapsing with sequelae"             "RRMS"
   "Relapsing remitting"                 "RRMS"})

(defn edss-scores
  "EDSS scores for the patients specified, grouped by patient identifier and ordered by date ascending."
  [{conn :com.eldrix.rsdb/conn :as system} patient-ids]
  (let [form-relapses (fetch-form-ms-relapse system patient-ids)]
    (->> (db/execute! conn (sql/format {:union-all
                                        [{:select [:t_patient/patient_identifier
                                                   :t_encounter/date_time
                                                   :t_form_edss/edss_score]
                                          :from   [:t_encounter]
                                          :join   [:t_patient [:= :t_patient/id :t_encounter/patient_fk]
                                                   :t_form_edss [:= :t_form_edss/encounter_fk :t_encounter/id]]
                                          :where  [:and
                                                   [:<> :t_form_edss/edss_score nil]
                                                   [:<> :t_encounter/is_deleted "true"]
                                                   [:<> :t_form_edss/is_deleted "true"]
                                                   [:in :t_patient/patient_identifier patient-ids]]}
                                         {:select [:t_patient/patient_identifier
                                                   :t_encounter/date_time
                                                   :t_form_edss_fs/edss_score]
                                          :from   [:t_encounter]
                                          :join   [:t_patient [:= :t_patient/id :t_encounter/patient_fk]
                                                   :t_form_edss_fs [:= :t_form_edss_fs/encounter_fk :t_encounter/id]]
                                          :where  [:and
                                                   [:<> :t_form_edss_fs/edss_score nil]
                                                   [:<> :t_encounter/is_deleted "true"]
                                                   [:<> :t_form_edss_fs/is_deleted "true"]
                                                   [:in :t_patient/patient_identifier patient-ids]]}]}))
         (map #(update-keys % {:patient_identifier :t_patient/patient_identifier
                               :date_time          :t_encounter/date_time
                               :edss_score         :t_form_edss/edss_score}))
         (map #(if-let [form-relapse (get-most-recent (get form-relapses (:t_patient/patient_identifier %)) :t_encounter/date_time (to-local-date (:t_encounter/date_time %)) (Period/ofWeeks 12))]
                 (merge form-relapse % {:t_form_ms_relapse/date_status_recorded (to-local-date (:t_encounter/date_time form-relapse))})
                 %))
         (map #(assoc % :t_form_edss/edss_score (convert-edss-score (:t_form_edss/edss_score %))
                        :t_ms_disease_course/type (simplify-ms-disease-course (:t_ms_disease_course/name %))
                        :t_encounter/date (to-local-date (:t_encounter/date_time %))))
         (group-by :t_patient/patient_identifier)
         (reduce-kv (fn [acc k v] (assoc acc k (sort-by :date_time v))) {}))))


(defn date-at-edss-4
  "Given an ordered sequence of EDSS scores, identify the first that is 4 or
  above and not in relapse."
  [edss]
  (->> edss
       (remove :t_form_ms_relapse/in_relapse)
       (filter #(number? (:t_form_edss/edss_score %)))
       (filter #(>= (:t_form_edss/edss_score %) 4))
       first
       :t_encounter/date_time))


(defn relapses-between-dates
  "Filter the event list for a patient to only include relapse-type events between the two dates specified, inclusive."
  [events ^LocalDate from-date ^LocalDate to-date]
  (->> events
       (filter :t_ms_event/is_relapse)
       (filter #(let [date (:t_ms_event/date %)]
                  (and (or (.isEqual date from-date) (.isAfter date from-date))
                       (or (.isEqual date to-date) (.isBefore date to-date)))))))

(comment
  (get (ms-events-for-patients system [5506]) 5506)
  (def d1 (LocalDate/of 2014 1 1))
  (relapses-between-dates (get (ms-events-for-patients system [5506]) 5506) (.minusMonths d1 24) d1))


(defn lsoa-for-postcode [{:com.eldrix/keys [clods]} postcode]
  (get (com.eldrix.clods.core/fetch-postcode clods postcode) "LSOA11"))

(defn deprivation-decile-for-lsoa [{:com.eldrix/keys [deprivare]} lsoa]
  (:uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile (deprivare/fetch-lsoa deprivare lsoa)))

(def fetch-max-depriv-rank (memoize deprivare/fetch-max))

(defn deprivation-quartile-for-lsoa [{:com.eldrix/keys [deprivare]} lsoa]
  (when-let [data (deprivare/fetch-lsoa deprivare lsoa)]
    (let [rank (:uk-composite-imd-2020-mysoc/UK_IMD_E_rank data)
          max-rank (fetch-max-depriv-rank deprivare :uk-composite-imd-2020-mysoc/UK_IMD_E_rank)
          x (/ rank max-rank)]
      (cond
        (>= x 3/4) 4
        (>= x 2/4) 3
        (>= x 1/4) 2
        :else 1))))

(defn deprivation-quartiles-for-patients
  "Determine deprivation quartile for the patients specified.
  Deprivation indices are calculated based on address history, on the date
  specified, or today.

  Parameters:
  - system      : a pc4 'system' containing clods, deprivare and rsdb modules.
  - patient-ids : a sequence of patient identifiers for the cohort in question
  - on-date     : a date to use for all patients, or a map, or function, to
                  return a date for each patient-id.

  If 'on-date' is provided but no date is returned for a given patient-id, no
  decile will be returned by design.

  Examples:
   (deprivation-quartiles-for-patients system [17497 22776] (LocalDate/of 2010 1 1)
   (deprivation-quartiles-for-patients system [17497 22776] {17497 (LocalDate/of 2004 1 1)}"
  ([system patient-ids] (deprivation-quartiles-for-patients system patient-ids (LocalDate/now)))
  ([system patient-ids on-date]
   (let [date-fn (if (ifn? on-date) on-date (constantly on-date))]
     (update-vals (addresses-for-patients system patient-ids)
                  #(->> (when-let [date (date-fn (:t_patient/patient_identifier (first %)))]
                          (patients/address-for-date % date)) ;; address-for-date will use 'now' if date nil, so wrap
                        :t_address/postcode_raw
                        (lsoa-for-postcode system)
                        (deprivation-quartile-for-lsoa system))))))

(defn all-recorded-medications [{conn :com.eldrix.rsdb/conn}]
  (into #{} (map :t_medication/medication_concept_fk)
        (next.jdbc/plan conn
                        (sql/format {:select-distinct :t_medication/medication_concept_fk
                                     :from            :t_medication}))))

(defn convert-product-pack
  "Convert a medication that is a type of product pack to the corresponding VMP."
  [{:com.eldrix/keys [dmd hermes]} {concept-id :t_medication/medication_concept_fk :as medication}]
  (if (or (hermes/subsumed-by? hermes concept-id 8653601000001108)
          (hermes/subsumed-by? hermes concept-id 10364001000001104))
    (if-let [vmp (first (hermes/get-parent-relationships-of-type hermes concept-id 10362601000001103))]
      (assoc medication :t_medication/medication_concept_fk vmp :t_medication/converted_from_pp concept-id)
      (if-let [amp (first (hermes/get-parent-relationships-of-type hermes concept-id 10362701000001108))]
        (assoc medication :t_medication/medication_concept_fk amp :t_medication/converted_from_pp concept-id)
        (throw (ex-info "unable to convert product pack" medication))))
    medication))

(defn medications-for-patients [{conn :com.eldrix.rsdb/conn :as system} patient-ids]
  (->> (db/execute! conn
                    (sql/format {:select [:t_patient/patient_identifier
                                          :t_medication/id
                                          :t_medication/medication_concept_fk :t_medication/date_from :t_medication/date_to
                                          :t_medication/reason_for_stopping]
                                 :from   [:t_medication :t_patient]
                                 :where  [:and
                                          [:= :t_medication/patient_fk :t_patient/id]
                                          [:in :t_patient/patient_identifier patient-ids]
                                          [:<> :t_medication/reason_for_stopping "RECORDED_IN_ERROR"]]}))
       (sort-by (juxt :t_patient/patient_identifier :t_medication/date_from))
       (map #(convert-product-pack system %))))

(defn make-dmt-lookup [system]
  (apply merge (map (fn [dmt] (zipmap (:codes dmt) (repeat {:dmt_class (:class dmt) :dmt (:id dmt) :atc (:atc dmt)}))) (all-ms-dmts system))))

(defn infer-atc-for-non-dmd
  "Try to reach a dmd product from a non-dmd product to infer an ATC code.
  TODO: improve"
  [{:com.eldrix/keys [hermes dmd]} concept-id]
  (or (first (keep identity (map #(dmd/atc-for-product dmd %) (hermes/get-child-relationships-of-type hermes concept-id 116680003))))
      (first (keep identity (map #(dmd/atc-for-product dmd %) (hermes/get-parent-relationships-of-type hermes concept-id 116680003))))))

(defn fetch-drug
  "Return basic information about a drug.
  Most products are in the UK dm+d, but not all, so we supplement with data
  from SNOMED when possible."
  [{:com.eldrix/keys [hermes dmd] :as system} concept-id]
  (if-let [product (dmd/fetch-product dmd concept-id)]
    (let [atc (or (com.eldrix.dmd.store2/atc-code dmd product)
                  (infer-atc-for-non-dmd system concept-id))]
      (cond-> {:nm (or (:VTM/NM product) (:VMP/NM product) (:AMP/NM product) (:VMPP/NM product) (:AMPP/NM product))}
              atc (assoc :atc atc)))
    (let [atc (infer-atc-for-non-dmd system concept-id)
          term (:term (hermes/get-preferred-synonym hermes concept-id "en-GB"))]
      (cond-> {:nm term}
              atc (assoc :atc atc)))))

(defn first-he-dmt-after-date
  "Given a list of medications for a single patient, return the first
  highly-effective medication given after the date specified."
  [medications ^LocalDate date]
  (->> medications
       (filter #(= :he-dmt (:dmt_class %)))
       (filter #(or (nil? (:t_medication/date_from %)) (.isAfter (:t_medication/date_from %) date)))
       (sort-by :t_medication/date_from)
       first))

(defn count-dmts-before
  "Returns counts of the specified DMT, or class of DMT, or any DMT, used before the date specified."
  ([medications ^LocalDate date] (count-dmts-before medications date nil nil))
  ([medications ^LocalDate date dmt-class] (count-dmts-before medications date dmt-class nil))
  ([medications ^LocalDate date dmt-class dmt]
   (when date
     (let [prior-meds (filter #(or (nil? (:t_medication/date_from %)) (.isBefore (:t_medication/date_from %) date)) medications)]
       (if-not dmt-class
         (count (filter :dmt prior-meds))                   ;; return any DMT
         (if-not dmt
           (count (filter #(= dmt-class (:dmt_class %)) prior-meds)) ;; return any DMT of same class HE vs platform
           (count (filter #(= dmt (:dmt %)) prior-meds)))))))) ;; return same DMT

(defn days-between
  "Return number of days between dates, or nil, or when-invalid if one date nil."
  ([^Temporal d1 ^Temporal d2] (days-between d1 d2 nil))
  ([^Temporal d1 ^Temporal d2 when-invalid]
   (if (and d1 d2)
     (.between ChronoUnit/DAYS d1 d2)
     when-invalid)))

(defn count-dmts [medications]
  (->> medications
       (keep-indexed (fn [i medication] (cond-> (assoc medication :switch? false)
                                                (> i 0) (assoc :switch? true :switch_from (:dmt (nth medications (dec i)))))))
       (map #(assoc %
               :exposure_days (days-between (:t_medication/date_from %) (:t_medication/date_to %))
               :n_prior_dmts (count-dmts-before medications (:t_medication/date_from %))
               :n_prior_platform_dmts (count-dmts-before medications (:t_medication/date_from %) :platform-dmt)
               :n_prior_he_dmts (count-dmts-before medications (:t_medication/date_from %) :he-dmt)
               :first_use (= 0 (count-dmts-before medications (:t_medication/date_from %) (:dmt_class %) (:dmt %)))))))

(defn patient-raw-dmt-medications
  [{conn :com.eldrix.rsdb/conn :as system} patient-ids]
  (let [dmt-lookup (make-dmt-lookup system)]
    (->> (medications-for-patients system patient-ids)
         ; (filter #(let [start-date (:t_medication/date_from %)] (or (nil? start-date) (.isAfter (:t_medication/date_from %) study-master-date))))
         (map #(merge % (get dmt-lookup (:t_medication/medication_concept_fk %))))
         ;   (filter #(all-he-dmt-identifiers (:t_medication/medication_concept_fk %)))
         (filter :dmt)
         (group-by :t_patient/patient_identifier))))

(defn patient-dmt-sequential-regimens
  "Returns DMT medications grouped by patient, organised by *regimen*.
  For simplicity, the regimen is defined as a sequential group of treatments
  of the same DMT. This does not work for overlapping regimens, which would need
  to be grouped by a unique, and likely manually curated unique identifier
  for the regimen. It also does not take into account gaps within the treatment
  course. If a patient is listed as having nataluzimab on one date, and another
  dose on another date, this will show the date from and to as those two dates.
  If the medication date-to is nil, then the last active contact of the patient
  is used."
  [{conn :com.eldrix.rsdb/conn :as system} patient-ids]
  (let [active-encounters (active-encounters-for-patients system patient-ids)
        last-contact-dates (update-vals active-encounters #(:t_encounter/date_time (first %)))]
    (update-vals
      (patient-raw-dmt-medications system patient-ids)
      (fn [v]
        (->> v
             (partition-by :dmt)                            ;; this partitions every time :dmt changes, which gives us sequential groups of medications
             (map #(let [start (first %)                    ;; we then simply look at the first, and the last in that group.
                         end (last %)
                         date-to (or (:t_medication/date_to end) (to-local-date (get last-contact-dates (:t_patient/patient_identifier (first %)))))]
                     (if date-to
                       (assoc start :t_medication/date_to date-to) ;; return a single entry with start date from the first and end date from the last
                       start)))
             count-dmts)))))

(defn cohort-entry-meds
  "Return a map of patient id to cohort entry medication.
  This is defined as date of first prescription of a HE-DMT after the
  defined study date.
  This is for the LEM-PASS study. Cohort entry date defined differently for DUS."
  [system patient-ids study-date]
  (->> (patient-dmt-sequential-regimens system patient-ids)
       vals
       (map (fn [medications] (first-he-dmt-after-date medications study-date)))
       (keep identity)
       (map #(vector (:t_patient/patient_identifier %) %))
       (into {})))


(defn alemtuzumab-medications
  "Returns alemtuzumab medication records for the patients specified.
  Returns a map keyed by patient identifier, with a collection of ordered
  medications."
  [system patient-ids]
  (-> (patient-raw-dmt-medications system patient-ids)
      (update-vals (fn [meds] (seq (filter #(= (:dmt %) :alemtuzumab) meds))))))

(defn valid-alemtuzumab-record?
  "Is this a valid alemtuzumab record?
  A record must never more than 5 days between the 'from' date and the 'to' date."
  [med]
  (let [from (:t_medication/date_from med)
        to (:t_medication/date_to med)]
    (and from to (> 5 (.between ChronoUnit/DAYS from to)))))

(defn make-daily-infusions [med]
  (let [from (:t_medication/date_from med)
        to (:t_medication/date_to med)]
    (if (valid-alemtuzumab-record? med)
      (let [days (.between ChronoUnit/DAYS from to)
            dates (map #(.plusDays from %) (range 0 (inc days)))]
        (map #(-> med
                  (assoc :t_medication/date % :valid true)
                  (dissoc :t_medication/date_from :t_medication/date_to :t_medication/reason_for_stopping)) dates))
      (-> med
          (assoc :valid false :t_medication/date from)
          (dissoc :t_medication/date_from :t_medication/date_to :t_medication/reason_for_stopping)))))

(defn alemtuzumab-infusions
  "Returns a map keyed by patient identifier of all infusions of alemtuzumab."
  [system patient-ids]
  (->> (alemtuzumab-medications system patient-ids)
       vals
       (remove nil?)
       flatten
       (map make-daily-infusions)
       flatten
       (group-by :t_patient/patient_identifier)))

(defn course-and-infusion-rank
  "Given a sequence of infusions, generate a 'course-rank' and 'infusion-rank'.
  We do this by generating a sequence of tuples that slide over the infusions.
  Each tuple contains two items: the prior item and the item.
  Returns the sequence of infusions, but with :course-rank and :infusion-rank
  properties."
  [infusions]
  (loop [partitions (partition 2 1 (concat [nil] infusions))
         course-rank 1
         infusion-rank 1
         results []]
    (let [[prior item] (vec (first partitions))]
      (if-not item
        results
        (let [new-course? (and (:t_medication/date prior) (:t_medication/date item) (> (.between ChronoUnit/DAYS (:t_medication/date prior) (:t_medication/date item)) 15))
              course-rank (if new-course? (inc course-rank) course-rank)
              infusion-rank (if new-course? 1 infusion-rank)
              item' (assoc item :course-rank course-rank :infusion-rank infusion-rank)]
          (recur (rest partitions) course-rank (inc infusion-rank) (conj results item')))))))

(defn ranked-alemtuzumab-infusions
  "Returns a map keyed by patient-id of alemtuzumab infusions, with course
  and infusion rank included."
  [system patient-ids]
  (-> (alemtuzumab-infusions system patient-ids)
      (update-vals course-and-infusion-rank)))

(defn all-patient-diagnoses [system patient-ids]
  (let [diag-fn (make-codelist-category-fn system study-diagnosis-categories)]
    (->> (fetch-patient-diagnoses system patient-ids)
         (map #(assoc % :icd10 (first (codelists/to-icd10 system [(:t_diagnosis/concept_fk %)]))))
         (map #(merge % (diag-fn [(:t_diagnosis/concept_fk %)])))
         (map #(assoc % :term (:term (hermes/get-preferred-synonym (:com.eldrix/hermes system) (:t_diagnosis/concept_fk %) "en-GB")))))))

(defn merge-diagnostic-categories
  [diagnoses]
  (let [categories (keys study-diagnosis-categories)
        diagnoses' (map #(select-keys % categories) diagnoses)
        all-false (zipmap (keys study-diagnosis-categories) (repeat false))]
    (apply merge-with #(or %1 %2) (conj diagnoses' all-false))))

(defn fetch-non-dmt-medications [system patient-ids]
  (let [drug-fn (make-codelist-category-fn system study-medications)
        all-dmts (all-dmt-identifiers system)
        fetch-drug' (memoize fetch-drug)]
    (->> (medications-for-patients system patient-ids)
         (remove #(all-dmts (:t_medication/medication_concept_fk %)))
         (map #(merge %
                      (fetch-drug' system (:t_medication/medication_concept_fk %))
                      (drug-fn [(:t_medication/medication_concept_fk %)]))))))

(s/fdef write-table
  :args (s/cat :system any?
               :table (s/keys :req-un [::filename ::data-fn] :opt-un [::columns ::title-fn])
               :centre ::centre
               :patient-ids (s/coll-of pos-int?)))
(defn write-table
  "Write out a data export table. Parameters:
  - system
  - table       - a map defining the table, to include:
      - :filename - filename to use
      - :data-fn  - function to create data
      - :columns  - a vector of columns to write; optional
      - :title-fn - a function, or map, to convert each column key for output.
                  - optional, default `name`.
  - centre      - keyword representing centre
  - patient-ids - a sequence of patient identifiers

  Injects special properties into each row:
  - ::patient-id  : a centre prefixed patient identifier
  - ::centre      : the centre set during export."
  [system {:keys [filename data-fn columns title-fn]} centre patient-ids]
  (let [make-pt-id #(str (get-in study-centres [centre :prefix]) (format "%06d" %))
        rows (->> (data-fn system patient-ids)
                  (map #(assoc % ::centre (name centre)
                                 ::patient-id (make-pt-id (:t_patient/patient_identifier %)))))
        columns' (or columns (keys (first rows)))
        title-fn' (merge {::patient-id "patient_id" ::centre "centre"} title-fn) ;; add a default column titles
        headers (mapv #(name (or (title-fn' %) %)) columns')]
    (with-open [writer (io/writer filename)]
      (csv/write-csv writer (into [headers] (map (fn [row] (mapv (fn [col] (col row)) columns')) rows))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;
;;;;;;;
;;;;;;;
;;;;;;;
;;;;;;;
;;;;;;;
;;;;;;;
;;;;;;;

(defn fetch-project-patient-identifiers
  "Returns patient identifiers for those registered to one of the projects
  specified. Discharged patients are *not* included."
  [{conn :com.eldrix.rsdb/conn} project-names]
  (let [project-ids (set (map #(:t_project/id (projects/project-with-name conn %)) project-names))
        child-project-ids (set (mapcat #(projects/all-children-ids conn %) project-ids))
        all-project-ids (set/union project-ids child-project-ids)]
    (patients/patient-ids-in-projects conn all-project-ids)))

(s/fdef fetch-study-patient-identifiers
  :args (s/cat :system any? :centre ::centre))
(defn fetch-study-patient-identifiers
  "Returns a collection of patient identifiers for the DMT study."
  [system centre]
  (let [all-dmts (all-he-dmt-identifiers system)]
    (->> (fetch-project-patient-identifiers system (get-in study-centres [centre :projects]))
         (medications-for-patients system)
         (filter #(all-dmts (:t_medication/medication_concept_fk %)))
         (map :t_patient/patient_identifier)
         set)))

(defn patients-with-more-than-one-death-certificate
  [{conn :com.eldrix.rsdb/conn}]
  (when-let [patient-fks (seq (plan/select! conn :patient_fk (sql/format {:select   [:patient_fk]
                                                                          :from     [:t_death_certificate]
                                                                          :group-by [:patient_fk]
                                                                          :having   [:> :%count.patient_fk 1]})))]
    (patients/pks->identifiers conn patient-fks)))

(defn dmts-recorded-as-product-packs
  "Return a sequence of medications in which a DMT has been recorded as a
  product pack, rather than VTM, VMP or AMP. "
  [system patient-ids]
  (let [all-dmts (all-he-dmt-identifiers system)
        all-meds (medications-for-patients system patient-ids)]
    (->> all-meds
         (filter :t_medication/converted_from_pp)
         (filter #(all-dmts (:t_medication/medication_concept_fk %))))))

(defn patients-with-local-demographics [system patient-ids]
  (map :patient_identifier (next.jdbc.plan/select! (:com.eldrix.rsdb/conn system) [:patient_identifier]
                                                   (sql/format {:select :patient_identifier
                                                                :from   :t_patient
                                                                :where  [:and
                                                                         [:in :patient_identifier patient-ids]
                                                                         [:= :authoritative_demographics "LOCAL"]]}))))


(defn make-metadata [system]
  (let [deps (edn/read (PushbackReader. (io/reader "deps.edn")))]
    {:data      (fetch-most-recent-encounter-date-time system)
     :pc4       {:url "https://github.com/wardle/pc4"
                 :sha (str/trim (:out (clojure.java.shell/sh "/bin/sh" "-c" "git rev-parse HEAD")))}
     :hermes    {:url     "https://github.com/wardle/hermes"
                 :version (get-in deps [:deps 'com.eldrix/hermes :mvn/version])
                 :data    (map #(hash-map :title (:term %) :date (:effectiveTime %)) (hermes/get-release-information (:com.eldrix/hermes system)))}
     :dmd       {:url     "https://github.com/wardle/dmd"
                 :version (get-in deps [:deps 'com.eldrix/dmd :mvn/version])
                 :data    {:title "UK Dictionary of Medicines and Devices (dm+d)"
                           :date  (com.eldrix.dmd.core/fetch-release-date (:com.eldrix/dmd system))}}
     :codelists {:study-medications study-medications
                 :study-diagnoses   study-diagnosis-categories}}))

(defn make-problem-report [system patient-ids]
  {
   ;; generate a list of patients with more than one death certificate
   :more-than-one-death-certificate
   (or (patients-with-more-than-one-death-certificate system) [])

   ;; generate a list of medications recorded as product packs
   :patients-with-dmts-as-product-packs
   {:description "This is a list of patients who have their DMT recorded as an AMPP or VMPP, rather
   than as a VTM,AMP or VMP. This is simply for internal centre usage, as it does not impact data."
    :patients    (map :t_patient/patient_identifier (dmts-recorded-as-product-packs system patient-ids))}

   ;; generate a list of patients in the study without multiple sclerosis
   :patients-without-ms-diagnosis
   (let [with-ms (set (map :t_patient/patient_identifier (filter :has_multiple_sclerosis (vals (multiple-sclerosis-onset system patient-ids)))))]
     (set/difference patient-ids with-ms))

   ;;  generate a list of incorrect alemtuzumab records
   :incorrect-alemtuzumab-course-dates
   (->> (alemtuzumab-medications system patient-ids)
        vals
        (remove nil?)
        flatten
        (remove valid-alemtuzumab-record?))

   ;; patients not linked to NHS demographics
   :not-linked-nhs-demographics (let [local-patient-ids (patients-with-local-demographics system patient-ids)]
                                  {:description "This is a list of patients who are not linked to NHS Wales' systems for status updates.
                                  This should be 0% for the Cardiff group, and 100% for other centres."
                                   :score       (str (math/round (* 100 (/ (count local-patient-ids) (count patient-ids)))) "%")
                                   :patient-ids local-patient-ids})})

(defn update-all
  "Applies function 'f' to the values of the keys 'ks' in the map 'm'."
  [m ks f]
  (reduce (fn [v k] (update v k f)) m ks))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-patients-table
  [system patient-ids]
  (let [onsets (multiple-sclerosis-onset system patient-ids)
        cohort-entry-meds (cohort-entry-meds system patient-ids study-master-date)
        cohort-entry-dates (update-vals cohort-entry-meds :t_medication/date_from)
        deprivation (deprivation-quartiles-for-patients system patient-ids cohort-entry-dates)
        active-encounters (active-encounters-for-patients system patient-ids)
        earliest-contacts (update-vals active-encounters #(:t_encounter/date_time (last %)))
        latest-contacts (update-vals active-encounters #(:t_encounter/date_time (first %)))
        edss (edss-scores system patient-ids)
        smoking (fetch-smoking-status system patient-ids)
        ms-events (ms-events-for-patients system patient-ids)]
    (->> (fetch-patients system patient-ids)
         (map #(let [patient-id (:t_patient/patient_identifier %)
                     cohort-entry-med (get cohort-entry-meds patient-id)
                     onsets (get onsets patient-id)
                     onset-date (:calculated-onset onsets)
                     has-multiple-sclerosis (:has_multiple_sclerosis onsets)
                     date-death (when-let [d (:t_patient/date_death %)] (.withDayOfMonth d 15))]
                 (-> (merge % cohort-entry-med)
                     (update :t_patient/sex (fnil name ""))
                     (update :dmt (fnil name ""))
                     (update :dmt_class (fnil name ""))
                     (assoc :depriv_quartile (get deprivation patient-id)
                            :start_follow_up (to-local-date (get earliest-contacts patient-id))
                            :year_birth (when (:t_patient/date_birth %) (.getYear (:t_patient/date_birth %)))
                            :date_death date-death
                            :onset onset-date
                            :has_multiple_sclerosis has-multiple-sclerosis
                            :smoking (get-in smoking [patient-id :t_smoking_history/status])
                            :disease_duration_years (when (and (:t_medication/date_from cohort-entry-med) onset-date)
                                                      (.getYears (Period/between ^LocalDate onset-date ^LocalDate (:t_medication/date_from cohort-entry-med))))
                            :most_recent_edss (when (:t_medication/date_from cohort-entry-med) ;; calculate most recent non-relapsing EDSS at cohort entry date
                                                (:t_form_edss/edss_score (get-most-recent (remove :t_form_ms_relapse/in_relapse (get edss patient-id)) :t_encounter/date (:t_medication/date_from cohort-entry-med))))
                            :date_edss_4 (when (:t_medication/date_from cohort-entry-med)
                                           (to-local-date (date-at-edss-4 (get edss patient-id))))
                            :nc_time_edss_4 (when (:t_medication/date_from cohort-entry-med)
                                              (when-let [date (to-local-date (date-at-edss-4 (get edss patient-id)))]
                                                (when onset-date
                                                  (.between ChronoUnit/DAYS onset-date date))))
                            :nc_relapses (when (:t_medication/date_from cohort-entry-med)
                                           (count (relapses-between-dates (get ms-events patient-id) (.minusYears (:t_medication/date_from cohort-entry-med) 1) (:t_medication/date_from cohort-entry-med))))
                            :end_follow_up (or date-death (to-local-date (get latest-contacts patient-id))))
                     (dissoc :t_patient/date_birth)))))))

(def patients-table
  {:filename "patients.csv"
   :data-fn  make-patients-table
   :columns  [::patient-id
              ::centre
              :t_patient/sex :year_birth :date_death
              :depriv_quartile :start_follow_up :end_follow_up
              :t_death_certificate/part1a :t_death_certificate/part1b :t_death_certificate/part1c :t_death_certificate/part2
              :atc :dmt :dmt_class :t_medication/date_from
              :exposure_days
              :smoking
              :most_recent_edss
              :has_multiple_sclerosis
              :onset
              :date_edss_4
              :nc_time_edss_4
              :disease_duration_years
              :nc_relapses
              :n_prior_he_dmts :n_prior_platform_dmts
              :first_use :switch?]
   :title-fn {:t_medication/date_from "cohort_entry_date"
              :switch?                "switch"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-patient-identifiers-table [system patient-ids]
  (db/execute! (:com.eldrix.rsdb/conn system)
               (sql/format {:select   [:patient_identifier :stored_pseudonym :t_project/name] :from [:t_episode :t_patient :t_project]
                            :where    [:and [:= :patient_fk :t_patient/id]
                                       [:= :project_fk :t_project/id]
                                       [:in :t_patient/patient_identifier patient-ids]]
                            :order-by [[:t_patient/id :asc]]})))

(def patient-identifiers-table
  {:filename "patient-identifiers.csv"
   :data-fn make-patient-identifiers-table
   :columns [::patient-id
             :t_project/name
             :t_episode/stored_pseudonym]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-raw-dmt-medications-table
  [system patient-ids]
  (->> (patient-raw-dmt-medications system patient-ids)
       vals
       (mapcat identity)
       (map #(update-all % [:dmt :dmt_class :t_medication/reason_for_stopping] name))))

(def raw-dmt-medications-table
  {:filename "patient-raw-dmt-medications.csv"
   :data-fn  make-raw-dmt-medications-table
   :columns  [::patient-id
              :t_medication/medication_concept_fk
              :atc :dmt :dmt_class
              :t_medication/date_from :t_medication/date_to
              :t_medication/reason_for_stopping]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-dmt-regimens-table
  [system patient-ids]
  (->> (patient-dmt-sequential-regimens system patient-ids)
       vals
       (mapcat identity)
       (map #(update-all % [:dmt :dmt_class :switch_from :t_medication/reason_for_stopping] (fnil name "")))))

(def dmt-regimens-table
  {:filename "patient-dmt-regimens.csv"
   :data-fn  make-dmt-regimens-table
   :columns  [::patient-id
              :t_medication/medication_concept_fk
              :atc :dmt :dmt-class
              :t_medication/date_from :t_medication/date_to :exposure_days
              :switch_from :n_prior_platform_dmts :n_prior_he_dmts]
   :title-fn {:dmt-class "dmt_class"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dates-of-anaphylaxis-reactions
  "Creates a map containing anaphylaxis diagnoses, keyed by a vector of
  patient identifier and date. This is derived from a diagnosis of anaphylaxis
  in the problem list.

  TODO: Use the t_medication_event table to impute life-threatening events from
  the historic record."
  [system patient-ids]
  (let [anaphylaxis-diagnoses (set (map :conceptId (hermes/expand-ecl-historic (:com.eldrix/hermes system) "<<39579001")))
        pt-diagnoses (->> (fetch-patient-diagnoses system patient-ids)
                          (filter #(anaphylaxis-diagnoses (:t_diagnosis/concept_fk %))))]
    (zipmap (map #(vector (:t_patient/patient_identifier %)
                          (:t_diagnosis/date_onset %)) pt-diagnoses)
            pt-diagnoses)))

(defn make-alemtuzumab-infusions
  "Create ordered sequence of individual infusions. Each item will include
  properties with date, drug, course-rank, infusion-rank and whether that
  infusion coincided with an allergic reaction."
  [system patient-ids]
  (let [anaphylaxis-reactions (dates-of-anaphylaxis-reactions system patient-ids)]
    (->> patient-ids
         (ranked-alemtuzumab-infusions system)
         vals
         flatten
         (map #(update-all % [:dmt :dmt_class] name))
         (map #(assoc % :anaphylaxis (boolean (get anaphylaxis-reactions [(:t_patient/patient_identifier %) (:t_medication/date %)]))))
         (sort-by (juxt :t_patient/patient_identifier :course-rank :infusion-rank)))))

(def alemtuzumab-infusions-table
  {:filename "alemtuzumab-infusions.csv"
   :data-fn  make-alemtuzumab-infusions
   :columns  [::patient-id
              :dmt
              :t_medication/medication_concept_fk
              :atc
              :t_medication/date
              :valid
              :course-rank
              :infusion-rank
              :anaphylaxis]
   :title-fn {:course-rank   "course_rank"
              :infusion-rank "infusion_rank"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn date-in-range-inclusive?
  [^LocalDate date-from ^LocalDate date-to ^LocalDate date]
  (when (and date-from date-to date)
    (or (.isEqual date date-from)
        (.isEqual date date-to)
        (and (.isAfter date date-from) (.isBefore date date-to)))))

(defn make-admissions-table
  [system patient-ids]
  (let [diagnoses (group-by :t_patient/patient_identifier (all-patient-diagnoses system patient-ids))
        get-diagnoses (fn [patient-id ^LocalDate date-from ^LocalDate date-to] ;; create a function to fetch a patient's diagnoses within a date range
                        (->> (get diagnoses patient-id)
                             (filter #(date-in-range-inclusive? date-from date-to (or (:t_diagnosis/date_diagnosis %) (:t_diagnosis/date_onset %))))))]
    (->> (fetch-patient-admissions system "ADMISSION" patient-ids)
         (map (fn [{patient-id      :t_patient/patient_identifier
                    :t_episode/keys [date_registration date_discharge] :as admission}]
                (-> admission
                    (assoc :t_episode/duration_days
                           (when (and date_registration date_discharge)
                             (.between ChronoUnit/DAYS date_registration date_discharge)))
                    (merge (merge-diagnostic-categories (get-diagnoses patient-id date_registration date_discharge)))))))))

(comment
  (fetch-patient-admissions system "ADMISSION" [17490])
  (group-by :t_patient/patient_identifier (all-patient-diagnoses system [17490]))
  (time (make-admissions-table system [17490])))



(def admissions-table
  {:filename "patient-admissions.csv"
   :data-fn  make-admissions-table
   :columns  (into [::patient-id
                    :t_episode/date_registration
                    :t_episode/date_discharge
                    :t_episode/duration_days]
                   (keys study-diagnosis-categories))
   :title-fn {:t_episode/date_registration "date_from"
              :t_episode/date_discharge    "date_to"
              :t_episode/duration_days     "duration_days"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-non-dmt-medications
  [system patient-ids]
  (fetch-non-dmt-medications system patient-ids))

(def non-dmt-medications-table
  {:filename "patient-non-dmt-medications.csv"
   :data-fn  make-non-dmt-medications
   :columns  [::patient-id :t_medication/medication_concept_fk
              :t_medication/date_from :t_medication/date_to :nm :atc
              :anti-hypertensive :antidepressant :anti-retroviral :statin :anti-platelet :immunosuppressant
              :benzodiazepine :antiepileptic :proton-pump-inhibitor :nutritional :antidiabetic
              :anti-coagulant :anti-infectious]
   :title-fn {:anti-hypertensive     :anti_hypertensive
              :anti-retroviral       :anti_retroviral
              :anti-platelet         :anti_platelet
              :proton-pump-inhibitor :proton_pump_inhibitor
              :anti-coagulant        :anti_coagulant
              :anti-infectious       :anti_infectious}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-ms-events-table [system patient-ids]
  (mapcat identity (vals (ms-events-for-patients system patient-ids))))

(def ms-events-table
  {:filename "patient-ms-events.csv"
   :data-fn  make-ms-events-table
   :columns  [::patient-id
              :t_ms_event/date :t_ms_event/impact :t_ms_event_type/abbreviation
              :t_ms_event/is_relapse :t_ms_event_type/name]
   :title-fn {:t_ms_event_type/abbreviation "type"
              :t_ms_event_type/name         "description"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def diagnoses-table
  {:filename "patient-diagnoses.csv"
   :data-fn  all-patient-diagnoses
   :columns  (into [::patient-id
                    :t_diagnosis/concept_fk
                    :t_diagnosis/date_onset :t_diagnosis/date_diagnosis :t_diagnosis/date_to
                    :icd10
                    :term]
                   (keys study-diagnosis-categories))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-edss-table [system patient-ids]
  (mapcat identity (vals (edss-scores system patient-ids))))

(def edss-table
  {:filename "patient-edss.csv"
   :data-fn  make-edss-table
   :columns  [::patient-id :t_encounter/date :t_form_edss/edss_score
              :t_ms_disease_course/type :t_ms_disease_course/name
              :t_form_ms_relapse/in_relapse :t_form_ms_relapse/date_status_recorded]
   :title-fn {:t_ms_disease_course/type "disease_status"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn fetch-results-for-patient [{:com.eldrix.rsdb/keys [conn]} patient-identifier]
  (->> (com.eldrix.pc4.server.rsdb.results/results-for-patient conn patient-identifier)
       (map #(assoc % :t_patient/patient_identifier patient-identifier))))

(defn make-results-table [system patient-ids]
  (->> (mapcat #(fetch-results-for-patient system %) patient-ids)
       (map #(select-keys % [:t_patient/patient_identifier :t_result/date :t_result_type/name]))))

(def results-table
  {:filename "patient-results.csv"
   :data-fn  make-results-table
   :columns  [::patient-id :t_result/date :t_result_type/name]
   :title-fn {:t_result/date      "date"
              :t_result_type/name "test"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def observations-missing
  "A set of patient pseudonyms with observation data missing"
  #{})

(defn observation-missing?
  "Returns whether the patient specified has observations missing."
  [system patient-id]
  (let [pseudonyms (set (map :t_episode/stored_pseudonym
                             (db/execute! (:com.eldrix.rsdb/conn system)
                                          (sql/format {:select :stored_pseudonym
                                                       :from   [:t_episode :t_patient]
                                                       :where  [:and
                                                                [:= :t_episode/patient_fk :t_patient/id]
                                                                [:= :patient_identifier patient-id]]}))))]
    (seq (set/intersection observations-missing pseudonyms))))

(def observations-table
  "The observations table is generated by exception reporting. If a patient does
  not have an exception filed by the team, then we impute that observations were
  performed."
  {:filename "patient-obs.csv"
   :data-fn  (fn [system patient-ids]
               (map #(let [not-missing? (not (observation-missing? system %))]
                       (hash-map :t_patient/patient_identifier %
                                 :pulse not-missing?
                                 :blood_pressure not-missing?))
                    patient-ids))
   :columns  [::patient-id :pulse :blood_pressure]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-weight-height-table [system patient-ids]
  (->> (mapcat identity (vals (fetch-weight-height system patient-ids)))
       (map #(update % :t_encounter/date_time to-local-date))))

(def weight-height-table
  {:filename "patient-weight.csv"
   :data-fn  make-weight-height-table
   :columns  [::patient-id :t_encounter/date_time
              :t_form_weight_height/weight_kilogram :t_form_weight_height/height_metres
              :body_mass_index]
   :title-fn {:t_encounter/date_time "date"}})

(def jc-virus-table
  {:filename "patient-jc-virus.csv"
   :data-fn  jc-virus-for-patients
   :columns  [::patient-id
              :t_result_jc_virus/date
              :t_result_jc_virus/jc_virus
              :t_result_jc_virus/titre]})

(def mri-brain-table
  {:filename "patient-mri-brain.csv"
   :data-fn  mri-brains-for-patients
   :columns  [::patient-id
              :t_result_mri_brain/date
              :t_result_mri_brain/id
              :t_result_mri_brain/multiple_sclerosis_summary
              :t_result_mri_brain/with_gadolinium
              :t_result_mri_brain/total_gad_enhancing_lesions
              :t_result_mri_brain/gad_range_lower
              :t_result_mri_brain/gad_range_upper
              :t_result_mri_brain/total_t2_hyperintense
              :t_result_mri_brain/t2_range_lower
              :t_result_mri_brain/t2_range_upper
              :t_result_mri_brain/compare_to_result_mri_brain_fk
              :t_result_mri_brain/compare_to_result_date
              :t_result_mri_brain/change_t2_hyperintense
              :t_result_mri_brain/calc_change_t2]
   :title-fn {:t_result_mri_brain/multiple_sclerosis_summary     "ms_summary"
              :t_result_mri_brain/with_gadolinium                "with_gad"
              :t_result_mri_brain/total_gad_enhancing_lesions    "gad_count"
              :t_result_mri_brain/gad_range_lower                "gad_lower"
              :t_result_mri_brain/gad_range_upper                "gad_upper"
              :t_result_mri_brain/total_t2_hyperintense          "t2_count"
              :t_result_mri_brain/t2_range_lower                 "t2_lower"
              :t_result_mri_brain/t2_range_upper                 "t2_upper"
              :t_result_mri_brain/compare_to_result_date         "compare_to_date"
              :t_result_mri_brain/compare_to_result_mri_brain_fk "compare_to_id"
              :t_result_mri_brain/change_t2_hyperintense         "t2_change"
              :t_result_mri_brain/calc_change_t2                 "calc_t2_change"}})


(defn write-local-date [^LocalDate o ^Appendable out _options]
  (.append out \")
  (.append out (.format (DateTimeFormatter/ISO_DATE) o))
  (.append out \"))

(defn write-local-date-time [^LocalDateTime o ^Appendable out _options]
  (.append out \")
  (.append out (.format (DateTimeFormatter/ISO_DATE_TIME) o))
  (.append out \"))

(extend LocalDate json/JSONWriter {:-write write-local-date})
(extend LocalDateTime json/JSONWriter {:-write write-local-date-time})



(def export-tables
  [patients-table
   patient-identifiers-table
   raw-dmt-medications-table
   dmt-regimens-table
   alemtuzumab-infusions-table
   admissions-table
   non-dmt-medications-table
   ms-events-table
   diagnoses-table
   edss-table
   results-table
   weight-height-table
   jc-virus-table
   mri-brain-table
   observations-table])

(s/fdef write-data
  :args (s/cat :system any? :centre ::centre))
(defn write-data [system centre]
  (let [patient-ids (fetch-study-patient-identifiers system centre)]
    (doseq [table export-tables]
      (log/info "writing table:" (:filename table))
      (write-table system table centre patient-ids))
    (log/info "writing metadata")
    (with-open [writer (io/writer "metadata.json")]
      (json/write (make-metadata system) writer :indent true))
    (log/info "writing problem report")
    (with-open [writer (io/writer "problems.json")]
      (json/write (make-problem-report system patient-ids) writer :indent true))
    (log/info "writing medication codes")
    (csv/write-csv (io/writer "medication-codes.csv")
                   (->> (expand-codelists system flattened-study-medications)
                        (mapcat #(map (fn [concept-id] (vector (:id %) concept-id (:term (hermes/get-fully-specified-name (:com.eldrix/hermes system) concept-id))))
                                      (:codes %)))))
    (log/info "writing diagnosis codes")
    (csv/write-csv (io/writer "diagnosis-codes.csv")
                   (->> (expand-codelists system flattened-study-diagnoses)
                        (mapcat #(map (fn [concept-id] (vector (:id %) concept-id (:term (hermes/get-fully-specified-name (:com.eldrix/hermes system) concept-id))))
                                      (:codes %)))))))

(defn matching-cav-demog?
  [{:t_patient/keys [last_name date_birth sex nhs_number] :as pt}
   {:wales.nhs.cavuhb.Patient/keys [HOSPITAL_ID LAST_NAME DATE_BIRTH SEX NHS_NO] :as cavpt}]
  (let [SEX' (get {"M" :MALE "F" :FEMALE} SEX)]
    (boolean (and cavpt HOSPITAL_ID LAST_NAME DATE_BIRTH
                  (or (nil? NHS_NO) (nil? nhs_number)
                      (and (.equalsIgnoreCase nhs_number NHS_NO)))
                  (= DATE_BIRTH date_birth)
                  (= SEX' sex)
                  (.equalsIgnoreCase LAST_NAME last_name)))))


(def ^:private demographic-eql
  [:t_patient/id
   :t_patient/patient_identifier
   :t_patient/first_names
   :t_patient/last_name :t_patient/nhs_number
   :t_patient/date_birth
   :t_patient/sex
   :t_patient/authoritative_demographics
   :t_patient/demographics_authority
   :t_patient/authoritative_last_updated
   {:t_patient/hospitals [:t_patient_hospital/id
                          :t_patient_hospital/hospital_fk
                          :t_patient_hospital/patient_fk
                          :t_patient_hospital/patient_identifier
                          :wales.nhs.cavuhb.Patient/HOSPITAL_ID
                          :wales.nhs.cavuhb.Patient/NHS_NUMBER
                          :wales.nhs.cavuhb.Patient/LAST_NAME
                          :wales.nhs.cavuhb.Patient/FIRST_NAMES
                          :wales.nhs.cavuhb.Patient/DATE_BIRTH
                          :wales.nhs.cavuhb.Patient/DATE_DEATH
                          :wales.nhs.cavuhb.Patient/SEX]}])

(defn check-patient-demographics
  "Check a single patient's demographics. This will call out to backend live
  demographic authorities in order to provide demographic data for the given
  patient, so there is an optionally sleep-millis parameter to avoid
  denial-of-service attacks if used in batch processes."
  [{pathom :pathom/boundary-interface} patient-identifier & {:keys [sleep-millis]}]
  (when sleep-millis (Thread/sleep sleep-millis))
  (let [ident [:t_patient/patient_identifier patient-identifier]
        pt (get (pathom [{ident demographic-eql}]) ident)
        pt2 (when pt (update pt
                             :t_patient/hospitals
                             (fn [hosps]
                               (map #(assoc % :demographic-match (matching-cav-demog? pt %)) hosps))))]
    (when pt (assoc pt2 :potential-authoritative-demographics
                        (first (filter :demographic-match (:t_patient/hospitals pt2)))))))

(s/def ::profile keyword?)
(s/def ::export-options (s/keys :req-un [::profile]))

(defn export
  "Export research data.
  Run as:
  ```
  clj -X com.eldrix.pc4.server.modules.dmt/export :profile :cvx :centre :cardiff
  clj -X com.eldrix.pc4.server.modules.dmt/export :profile :pc4 :centre :plymouth
  clj -X com.eldrix.pc4.server.modules.dmt/export :profile :dev :centre :cambridge
  ```
  Profile determines the environment in which to use. There are four running
  pc4 environments currrently:
  * dev - development
  * nuc - development
  * pc4 - Amazon AWS infrastructure
  * cvx - NHS Wales infrastructure

  Centre determines the choice of projects from which to analyse patients.
  * :cardiff
  * :plymouth
  * :cambridge"
  [{:keys [profile centre] :as opts}]
  (when-not (s/valid? ::export-options opts)
    (throw (ex-info "Invalid options:" (s/explain-data ::export-options opts))))
  (let [system (pc4/init profile [:pathom/env])]
    (write-data system centre)))

(defn make-demography-check [system profile patient-ids]
  (->> patient-ids
       (patients-with-local-demographics system)
       (map #(check-patient-demographics system % :sleep-millis (get {:cvx 500} profile)))
       (map #(hash-map
               :t_patient/patient_identifier (:t_patient/patient_identifier %)
               :first_names (:t_patient/first_names %)
               :last_name (:t_patient/last_name %)
               :date_birth (:t_patient/date_birth %)
               :nhs_number (:t_patient/nhs_number %)
               :demog (:t_patient/authoritative_demographics %)
               :match_org (get-in % [:potential-authoritative-demographics :t_patient_hospital/hospital_fk])
               :crn (get-in % [:potential-authoritative-demographics :t_patient_hospital/patient_identifier])
               :cav_match (boolean (get-in % [:potential-authoritative-demographics :demographic-match]))
               :cav_crn (get-in % [:potential-authoritative-demographics :wales.nhs.cavuhb.Patient/HOSPITAL_ID])
               :cav_nnn (get-in % [:potential-authoritative-demographics :wales.nhs.cavuhb.Patient/NHS_NUMBER])
               :cav_dob (get-in % [:potential-authoritative-demographics :wales.nhs.cavuhb.Patient/DATE_BIRTH])
               :cav_fname (get-in % [:potential-authoritative-demographics :wales.nhs.cavuhb.Patient/FIRST_NAMES])
               :cav_lname (get-in % [:potential-authoritative-demographics :wales.nhs.cavuhb.Patient/LAST_NAME])))))

(defn check-demographics
  "Check demographics report. Run as:
  ```
  clj -X com.eldrix.pc4.server.modules.dmt/check-demographics :profile :cvx :centre :cardiff
  ```"
  [{:keys [profile centre] :as opts}]
  (when-not (s/valid? ::export-options opts)
    (throw (ex-info "Invalid options:" (s/explain-data ::export-options opts))))
  (log/info "check-demographics with " opts)
  (let [system (pc4/init profile [:pathom/boundary-interface :wales.nhs.cavuhb/pms])
        patient-ids (fetch-study-patient-identifiers system centre)]
    (write-table system {:filename "demography-check.csv"
                         :data-fn  (fn [system patient-ids] (make-demography-check system profile patient-ids))}
                 centre patient-ids)
    (pc4/halt! system)))

(defn update-demographic-authority
  "Update demographic authorities. Run as:
  ```
  clj -X com.eldrix.pc4.server.modules.dmt/update-demographic-authority :profile :cvx :centre :cardiff
  ```"
  [{:keys [profile centre] :as opts}]
  (when-not (s/valid? ::export-options opts)
    (throw (ex-info "Invalid options:" (s/explain-data ::export-options opts))))
  (let [system (pc4/init profile [:pathom/boundary-interface :wales.nhs.cavuhb/pms])
        patient-ids (fetch-study-patient-identifiers system centre)
        patients (->> patient-ids
                      (patients-with-local-demographics system)
                      (map #(check-patient-demographics system % :sleep-millis (get {:cvx 500} profile)))
                      (filter #(get-in % [:potential-authoritative-demographics :demographic-match])))]
    (doseq [patient patients]
      (patients/set-cav-authoritative-demographics! (:com.eldrix/clods system)
                                                    (:com.eldrix.rsdb/conn system)
                                                    patient
                                                    (:potential-authoritative-demographics patient)))

    (pc4/halt! system)))



(defn admission->episode [patient-pk user-id {:wales.nhs.cavuhb.Admission/keys [DATE_FROM DATE_TO] :as admission}]
  (when (and admission DATE_FROM DATE_TO)
    {:t_episode/patient_fk        patient-pk
     :t_episode/user_fk           user-id
     :t_episode/date_registration (.toLocalDate DATE_FROM)
     :t_episode/date_discharge    (.toLocalDate DATE_TO)}))

(defn admissions-for-patient [system patient-identifier]
  (let [ident [:t_patient/patient_identifier patient-identifier]
        admissions (p.eql/process (:pathom/env system) [{ident
                                                         [:t_patient/id
                                                          {:t_patient/demographics_authority
                                                           [:wales.nhs.cavuhb.Patient/ADMISSIONS]}]}])
        patient-pk (get-in admissions [ident :t_patient/id])
        admissions' (get-in admissions [ident :t_patient/demographics_authority :wales.nhs.cavuhb.Patient/ADMISSIONS])]
    (map #(admission->episode patient-pk 1 %) admissions')))

(defn update-cav-admissions
  "Update admission data from CAVPMS. Run as:
  ```
  clj -X com.eldrix.pc4.server.modules.dmt/update-cav-admissions :profile :cvx :centre :cardiff
  ```"
  [{:keys [profile centre]}]
  (let [system (pc4/init profile)
        project (projects/project-with-name (:com.eldrix.rsdb/conn system) "ADMISSION")
        project-id (:t_project/id project)]
    (dorun
      (->> (fetch-study-patient-identifiers system centre)
           (mapcat #(admissions-for-patient system %))
           (remove nil?)
           (map #(assoc % :t_episode/project_fk project-id))
           (map #(projects/register-completed-episode! (:com.eldrix.rsdb/conn system) %))))
    (pc4/halt! system)))


(defn matching-filenames
  "Return a sequence of filenames from the directory specified, in a map keyed
  by the filename."
  [dir]
  (->> (file-seq (io/as-file dir))
       (filter #(.isFile %))
       (reduce (fn [acc v] (update acc (.getName v) conj v)) {})))

(defn copy-csv-file
  [writer csv-file]
  (with-open [reader (io/reader csv-file)]
    (->> (csv/read-csv reader)
         (csv/write-csv writer))))

(defn append-csv-file
  "Write data to writer from the csv-file, removing the first row"
  [writer csv-file]
  (with-open [reader (io/reader csv-file)]
    (->> (csv/read-csv reader)
         (rest)
         (csv/write-csv writer))))

(defn merge-csv-files
  [out files]
  (with-open [writer (io/writer out)]
    (copy-csv-file writer (first files))
    (dorun (map #(append-csv-file writer %) (rest files)))))

(defn merge-matching-data
  [dir out-dir]
  (let [out-path (.toPath ^File (io/as-file out-dir))
        files (->> (matching-filenames dir)
                   (filter (fn [[filename _files]]
                             (.endsWith (str/lower-case filename) ".csv"))))]
    (println "Writing merged files to" out-path)
    (Files/createDirectories out-path (make-array FileAttribute 0))
    (dorun (map (fn [[filename csv-files]]
                  (let [out (.resolve out-path filename)]
                    (merge-csv-files (.toFile out) csv-files))) files))))

(defn merge-csv
  "Merge directories of csv files based on matching filename.
  Unfortunately, one must escape strings.
  ```
  clj -X com.eldrix.pc4.server.modules.dmt/merge-csv :dir '\"/tmp/csv-files\"' :out '\"/tmp/merged\"'
  ```"
  [{:keys [dir out]}]
  (merge-matching-data (str dir) (str out)))

;;;
;;; Write out data
;;; zip all csv files with the metadata.json
;;; zip

(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (def system (pc4/init :dev [:pathom/boundary-interface :wales.nhs.cavuhb/pms]))
  (pc4/halt! system)
  (time (def a (fetch-project-patient-identifiers system (get-in study-centres [:cardiff :projects]))))
  (def patient-ids (fetch-study-patient-identifiers system :cardiff))
  (get-in study-centres [:cardiff :projects])
  (projects/project-with-name (:com.eldrix.rsdb/conn system) "NINFLAMMCARDIFF")
  (time (def b (patients/patient-ids-in-projects (:com.eldrix.rsdb/conn system) #{5} :patient-status #{:FULL :PSEUDONYMOUS :STUB :FAKE :DELETED :MERGED})))
  (count b)
  (set/difference a b)
  (def patient-ids (fetch-study-patient-identifiers system :cardiff))
  (count patient-ids)
  (take 5 patient-ids)
  (tap> (take 5 patient-ids))
  (time (write-data system :cardiff))
  (write-table system patients-table :cardiff patient-ids)
  (write-table system diagnoses-table :cardiff patient-ids)
  (spit "metadata.json" (json/write-str (make-metadata system)))
  (check-patient-demographics system 14232)
  (clojure.pprint/pprint (check-patient-demographics system 13936))
  (clojure.pprint/pprint (make-demography-check system :cardiff [13936]))
  (check-demographics {:profile :dev :centre :cardiff})

  (matching-filenames "/Users/mark/Desktop/lempass")
  (merge-matching-data "/Users/mark/Desktop/lempass" "/Users/mark/Desktop/lempass-merged")

  (com.eldrix.concierge.wales.cav-pms/fetch-admissions (:wales.nhs.cavuhb/pms system) :crn "A647963")

  (def pathom (:pathom/boundary-interface system))
  (p.eql/process (:pathom/env system) [{[:t_patient/patient_identifier 93718]
                                        [{:t_patient/demographics_authority
                                          [:wales.nhs.cavuhb.Patient/ADMISSIONS]}]}])


  (let [project (projects/project-with-name (:com.eldrix.rsdb/conn system) "ADMISSION")
        project-id (:t_project/id project)]
    (doseq [episode (remove nil? (mapcat #(admissions-for-patient system %) [93718]))]
      (projects/register-completed-episode! (:com.eldrix.rsdb/conn system)
                                            (assoc episode :t_episode/project_fk project-id))))
  (mapcat #(admissions-for-patient system %) (take 3 (fetch-study-patient-identifiers system :cardiff)))
  (take 5 (fetch-study-patient-identifiers system :cardiff))
  (admissions-for-patient system 94967)
  (projects/register-completed-episode! (:com.eldrix.rsdb/conn system)
                                        (first (admissions-for-patient system 93718)))
  (p.eql/process (:pathom/env system) [{[:t_patient/patient_identifier 94967]
                                        [{:t_patient/demographics_authority
                                          [:wales.nhs.cavuhb.Patient/ADMISSIONS]}]}])
  (pathom [{[:t_patient/patient_identifier 78213] demographic-eql}])
  (pathom [{[:wales.nhs.cavuhb.Patient/FIRST_FORENAME "Mark"]
            [:wales.nhs.cavuhb.Patient/FIRST_FORENAME
             :wales.nhs.cavuhb.Patient/FIRST_NAMES]}])
  (pathom [{[:wales.nhs.cavuhb.Patient/HOSPITAL_ID "A542488"]
            [:wales.nhs.cavuhb.Patient/FIRST_FORENAME
             :wales.nhs.cavuhb.Patient/FIRST_NAMES
             :wales.nhs.cavuhb.Patient/LAST_NAME]}])

  (tap> (pathom [{[:t_patient/patient_identifier 94967]
                  [:t_patient/id
                   :t_patient/date_birth
                   :t_patient/date_death
                   {`(:t_patient/address {:date ~"2000-06-01"}) [:t_address/date_from
                                                                 :t_address/date_to
                                                                 {:uk.gov.ons.nhspd/LSOA-2011 [:uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
                                                                 :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
                   ;{:t_patient/addresses [:t_address/date_from :t_address/date_to :uk.gov.ons/lsoa :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
                   :t_patient/sex
                   :t_patient/status
                   {:t_patient/surgery [:uk.nhs.ord/name]}
                   {:t_patient/medications [:t_medication/date_from
                                            :t_medication/date_to
                                            {:t_medication/medication [:info.snomed.Concept/id
                                                                       {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
                                                                       :uk.nhs.dmd/NM]}]}]}])))

(comment
  (require '[portal.api :as p])
  (p/open)
  (add-tap #'p/submit)
  (pc4/prep :dev)
  (def system (pc4/init :dev [:pathom/boundary-interface]))
  (integrant.core/halt! system)
  (tap> system)
  (keys system)

  ;; get the denominator patient population here....
  (def rsdb-conn (:com.eldrix.rsdb/conn system))
  (def roles (users/roles-for-user rsdb-conn "ma090906"))
  (def manager (#'users/make-authorization-manager' roles))
  (def user-projects (set (map :t_project/id (users/projects-with-permission roles :DATA_DOWNLOAD))))
  (def requested-projects #{5})
  (def patient-ids (patients/patients-in-projects
                     rsdb-conn
                     (clojure.set/intersection user-projects requested-projects)))
  (count patient-ids)
  (take 4 patient-ids)

  (def pathom (:pathom/boundary-interface system))
  (:pathom/env system)
  (def pt (pathom [{[:t_patient/patient_identifier 17490]
                    [:t_patient/id
                     :t_patient/date_birth
                     :t_patient/date_death
                     {`(:t_patient/address {:date ~"2000-06-01"}) [:t_address/date_from
                                                                   :t_address/date_to
                                                                   {:uk.gov.ons.nhspd/LSOA-2011 [:uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
                                                                   :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
                     {:t_patient/addresses [:t_address/date_from :t_address/date_to :uk.gov.ons/lsoa :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
                     :t_patient/sex
                     :t_patient/status
                     {:t_patient/surgery [:uk.nhs.ord/name]}
                     {:t_patient/medications [:t_medication/date_from
                                              :t_medication/date_to
                                              {:t_medication/medication [:info.snomed.Concept/id
                                                                         :uk.nhs.dmd/NM]}]}]}]))
  (tap> pt)
  (tap> "Hello World")

  (pathom [{[:info.snomed.Concept/id 9246601000001104]
            [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
             :uk.nhs.dmd/NM]}])


  (def natalizumab (get
                     (pathom [{[:info.snomed.Concept/id 36030311000001108]
                               [:uk.nhs.dmd/TYPE
                                :uk.nhs.dmd/NM
                                :uk.nhs.dmd/ATC
                                :uk.nhs.dmd/VPID
                                :info.snomed.Concept/parentRelationshipIds
                                {:uk.nhs.dmd/VMPS [:info.snomed.Concept/id
                                                   :uk.nhs.dmd/VPID
                                                   :uk.nhs.dmd/NM
                                                   :uk.nhs.dmd/ATC
                                                   :uk.nhs.dmd/BNF]}]}])
                     [:info.snomed.Concept/id 36030311000001108]))
  (def all-rels (apply clojure.set/union (vals (:info.snomed.Concept/parentRelationshipIds natalizumab))))
  (map #(:term (com.eldrix.hermes.core/get-preferred-synonym (:com.eldrix/hermes system) % "en-GB")) all-rels)

  (tap> natalizumab)
  (reduce-kv (fn [m k v] (assoc m k (if (map? v) (dissoc v :com.wsscode.pathom3.connect.runner/attribute-errors) v))) {} natalizumab)



  (pathom [{'(info.snomed.Search/search
               {:constraint "<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|" ;; search UK products
                :max-hits   10})
            [:info.snomed.Concept/id
             :uk.nhs.dmd/NM
             :uk.nhs.dmd/TYPE
             :info.snomed.Description/term
             {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
             :info.snomed.Concept/active]}])
  (pathom [{'(info.snomed.Search/search {:s "glatiramer acetate"})
            [:info.snomed.Concept/id
             :info.snomed.Description/term
             {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}])

  (pathom [{'(info.snomed.Search/search {:constraint "<<9246601000001104 OR <<108755008"})
            [:info.snomed.Concept/id
             :info.snomed.Description/term]}])
  (def hermes (:com.eldrix/hermes system))
  hermes
  (require '[com.eldrix.hermes.core :as hermes])
  (def dmf (set (map :conceptId (hermes/search hermes {:constraint "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"}))))
  (contains? dmf 12086301000001102)
  (contains? dmf 24035111000001108))

