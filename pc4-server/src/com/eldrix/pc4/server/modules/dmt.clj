(ns com.eldrix.pc4.server.modules.dmt
  "Experimental approach to data downloads, suitable for short-term
   requirements for multiple sclerosis disease modifying drug
   post-marketing surveillance."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.deprivare.core :as deprivare]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.pc4.server.rsdb.db :as db]
            [com.eldrix.pc4.server.codelists :as codelists]
            [com.eldrix.pc4.server.system :as pc4]
            [com.eldrix.pc4.server.rsdb :as rsdb]
            [com.eldrix.pc4.server.rsdb.patients :as patients]
            [com.eldrix.pc4.server.rsdb.projects :as projects]
            [com.eldrix.pc4.server.rsdb.users :as users]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [honey.sql :as sql]
            [next.jdbc.plan :as plan])
  (:import (java.time LocalDate LocalDateTime)
           (java.time.temporal ChronoUnit Temporal)))

(def study-master-date
  (LocalDate/of 2014 05 01))

(def study-medications
  "A list of interesting drugs for studies of multiple sclerosis.
  They are classified as either platform DMTs or highly efficacious DMTs.
  We define by the use of the SNOMED CT expression constraint language, usually
  on the basis of active ingredients together with ATC codes. "
  [{:id          :dmf
    :description "Dimethyl fumarate"
    :atc         "L04AX07"
    :brand-names ["Tecfidera"]
    :class       :platform-dmt
    :codelist    {:ecl
                       "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR
                       (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
                  :atc "L04AX07"}}
   {:id          :glatiramer
    :description "Glatiramer acetate"
    :brand-names ["Copaxone" "Brabio"]
    :atc         "L03AX13"
    :class       :platform-dmt
    :codelist    {:ecl
                       "<<108754007|Glatiramer| OR <<9246601000001104|Copaxone| OR <<13083901000001102|Brabio| OR <<8261511000001102 OR <<29821211000001101
                       OR (<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<108755008|Glatiramer acetate|)"
                  :atc "L03AX13"}}
   {:id          :ifn-beta-1a
    :description "Interferon beta 1-a"
    :brand-names ["Avonex" "Rebif"]
    :atc         "L03AB07 NOT L03AB13"
    :class       :platform-dmt
    :codelist    {:inclusions {:ecl
                                    "(<<9218501000001109|Avonex| OR <<9322401000001109|Rebif| OR
                                    (<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386902004|Interferon beta-1a|))"
                               :atc "L03AB07"}
                  :exclusions {:ecl "<<12222201000001108|PLEGRIDY|"
                               :atc "L03AB13"}}}
   {:id          :ifn-beta-1b
    :description "Interferon beta 1-b"
    :brand-names ["Betaferon®" "Extavia®"]
    :atc         "L03AB08"
    :class       :platform-dmt
    :codelist    {:ecl "(<<9222901000001105|Betaferon|) OR (<<10105201000001101|Extavia|) OR
                     (<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386903009|Interferon beta-1b|)"
                  :atc "L03AB08"}}
   {:id          :peg-ifn-beta-1a
    :description "Peginterferon beta 1-a"
    :brand-names ["Plegridy®"]
    :atc         "L03AB13"
    :class       :platform-dmt
    :codelist    {:ecl "<<12222201000001108|Plegridy|"
                  :atc "L03AB13"}}
   {:id          :teriflunomide
    :description "Teriflunomide"
    :brand-names ["Aubagio®"]
    :atc         "L04AA31"
    :class       :platform-dmt
    :codelist    {:ecl "<<703786007|Teriflunomide| OR <<12089801000001100|Aubagio| "
                  :atc "L04AA31"}}
   {:id          :rituximab
    :description "Rituximab"
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
   {:id          :ocrelizumab
    :description "Ocrelizumab"
    :brand-names ["Ocrevus"]
    :atc         "L04AA36"
    :class       :he-dmt
    :codelist    {:ecl "(<<35058611000001103|Ocrelizumab|) OR (<<13096001000001106|Ocrevus|)"
                  :atc "L04AA36"}}
   {:id          :cladribine
    :description "Cladribine"
    :brand-names ["Mavenclad"]
    :atc         "L04AA40"
    :class       :he-dmt
    :codelist    {:ecl "<<108800000|Cladribine| OR <<13083101000001100|Mavenclad|"
                  :atc "L04AA40"}}
   {:id          :mitoxantrone
    :description "Mitoxantrone"
    :brand-names ["Novantrone"]
    :atc         "L01DB07"
    :class       :he-dmt
    :codelist    {:ecl "<<108791001 OR <<9482901000001102"
                  :atc "L01DB07"}}
   {:id          :fingolimod
    :description "Fingolimod"
    :brand-names ["Gilenya"]
    :atc         "L04AA27"
    :class       :he-dmt
    :codelist    {:ecl "<<715640009 OR <<10975301000001100"
                  :atc "L04AA27"}}
   {:id          :natalizumab
    :description "Natalizumab"
    :brand-names ["Tysabri"]
    :atc         "L04AA23"
    :class       :he-dmt
    :codelist    {:ecl "<<414804006 OR <<9375201000001103"
                  :atc "L04AA23"}}
   {:id          :alemtuzumab
    :description "Alemtuzumab"
    :brand-names ["Lemtrada"]
    :atc         "L04AA34"
    :class       :he-dmt
    :codelist    {:ecl "(<<391632007|Alemtuzumab|) OR (<<12091201000001101|Lemtrada|)"
                  :atc "L04AA34"}}
   {:id          :statins
    :description "Statins"
    :class       :other
    :codelist    {:atc "C10AA"}}
   {:id          :anti-hypertensives
    :description "Anti-hypertensive"
    :class       :other
    :codelist    {:atc "C02"}}
   {:id          :anti-platelets
    :description "Anti-platelets"
    :class       :other
    :codelist    {:atc "B01AC"}}
   {:id          :proton-pump-inhibitors
    :description "Proton pump inhibitors"
    :class       :other
    :codelist    {:atc "A02BC"}}
   {:id          :immunosuppressants
    :description "Immunosuppressants"
    :class       :other
    :codelist    {:inclusions {:atc ["L04AA" "L04AB" "L04AC" "L04AD" "L04AX"]}
                  :exclusions {:atc ["L04AA23" "L04AA27" "L04AA31" "L04AA34" "L04AA36" "L04AA40" "L04AX07"]}}}
   {:id          :antidepressants
    :description "Anti-depressants"
    :class       :other
    :codelist    {:atc "N06A"}}
   {:id          :benzodiazepines
    :description "Benzodiazepines"
    :class       :other
    :codelist    {:atc ["N03AE" "N05BA"]}}
   {:id          :antiepileptics
    :description "Anti-epileptics"
    :class       :other
    :codelist    {:atc "N03A"}}
   {:id          :antidiabetic
    :description "Anti-diabetic"
    :class       :other
    :codelist    {:atc "A10"}}
   {:id          :nutritional
    :description "Nutritional supplements and vitamins"
    :class       :other
    :codelist    {:atc ["A11" "B02B" "B03C"]}}])

(def study-diagnosis-categories
  "These are project specific criteria for diagnostic classification."
  {:cardiovascular
   {:description "Cardiovascular disorders"
    :codelist    {:icd10 ["I"]}}

   :cancer
   {:description "Cancer, except skin cancers"
    :codelist    {:inclusions {:icd10 "C"} :exclusions {:icd10 "C44"}}}

   :connective-tissue
   {:description "Connective tissue disorders"
    :codelist    {:icd10 ["M45." "M33." "M35.3" "M05." "M35.0" "M32.8" "M34."
                          "M31.3" "M30.1" "L95." "D89.1" "D69.0" "M31.7" "M30.3"
                          "M30.0" "M31.6" "I73." "M31.4" "M35.2" "M94.1" "M02.3"
                          "M06.1" "E85.0" "D86."]}}
   :endocrine
   {:codelist {:icd10 ["E27.4" "E10" "E06.3" "E05.0"]}}

   :gastrointestinal
   {:codelist {:icd10 ["K75.4" "K90.0" "K50." "K51." "K74.3"]}}

   :respiratory-disease
   {:codelist {:icd10 ["J"]}}                               ;; note I'm using different ICD-10 codes to that specified!

   :hair-and-skin
   {:codelist {:icd10 ["L63." "L10.9" "L40." "L80.0"]}}

   :mood
   {:codelist {:icd10 ["F3"]}}

   :epilepsy
   {:codelist {:icd10 ["G40"]}}

   :other
   {:codelist {:icd10 ["G61.0" "D51.0" "D59.1" "D69.3" "D68.8" "D68.9"
                       "N02.8" "M31.0" "D76.1"
                       "M05.3" "I01.2" "I40.8" "I40.9" "I09.0" "G04.0"
                       "E31.0" "D69.3" "I01." "G70.0" "G70.8" "G73.1"]}}
   })


(defn make-diagnostic-category-fn
  "Returns a function that will test a collection of concept identifiers against the diagnostic categories specified."
  [system categories]
  (let [codelists (reduce-kv (fn [acc k v] (assoc acc k (codelists/make-codelist system (:codelist v))))
                             {}
                             categories)]
    (fn [concept-ids]
      (reduce-kv (fn [acc k v] (assoc acc k (codelists/member? v concept-ids))) {} codelists))))

(comment
  (def ct-disorders (codelists/make-codelist system {:icd10 ["M45." "M33." "M35.3" "M05." "M35.0" "M32.8" "M34."
                                                             "M31.3" "M30.1" "L95." "D89.1" "D69.0" "M31.7" "M30.3"
                                                             "M30.0" "M31.6" "I73." "M31.4" "M35.2" "M94.1" "M02.3"
                                                             "M06.1" "E85.0" "D86."]}))
  (codelists/member? ct-disorders [9631008])


  (def diag-cats (make-diagnostic-category-fn system study-diagnosis-categories))
  (diag-cats [9631008 12295008 46635009 34000006 9014002 40956001])
  (diag-cats [6204001])
  
  (def codelists (reduce-kv (fn [acc k v] (assoc acc k (codelists/make-codelist system (:codelist v)))) {} study-diagnosis-categories))
  codelists
  (reduce-kv (fn [acc k v] (assoc acc k (codelists/member? v [9631008 24700007]))) {} codelists)



  (def calcium-channel-blockers (codelists/make-codelist system {:atc "C08" :exclusions {:atc "C08CA01"}}))
  (codelists/member? calcium-channel-blockers [108537001])
  (take 2 (map #(:term (hermes/get-fully-specified-name (:com.eldrix/hermes system) %)) (codelists/expand calcium-channel-blockers)))
  (codelists/expand (codelists/make-codelist system {:icd10 "G37.3"}))
  (codelists/member? (codelists/make-codelist system {:icd10 "I"}) [22298006])
  (count (codelists/expand (codelists/make-codelist system {:icd10 "I"})))
  (get-in study-diagnosis-categories [:connective-tissue :codelist])

  (def cancer (codelists/make-codelist system {:inclusions {:icd10 "C"} :exclusions {:icd10 "C44"}}))
  (codelists/disjoint? (codelists/expand cancer) (codelists/expand (codelists/make-codelist system {:icd10 "C44"})))
  (map ps (codelists/expand cancer))
  (defn ps [id] (:term (hermes/get-preferred-synonym (:com.eldrix/hermes system) id "en-GB")))
  (map #(:term (hermes/get-preferred-synonym (:com.eldrix/hermes system) % "en-GB")) (set (map :referencedComponentId (hermes/reverse-map-range (:com.eldrix/hermes system) 447562003 "C44"))))
  (has-diagnoses? system [24700007 24700007] (get-in study-diagnosis-categories [:connective-tissue :codelist]))


  (vals (hermes/source-historical-associations (:com.eldrix/hermes system) 24700007))

  (hermes/get-preferred-synonym (:com.eldrix/hermes system) 445130008 "en-GB"))


(defn make-atc-regexps [{:keys [codelist] :as med}]
  (when-let [atc (or (:atc codelist) (get-in codelist [:inclusions :atc]))]
    (if (coll? atc)
      (mapv #(vector (if (string? %) (re-pattern %) %) (dissoc med :codelist)) atc)
      (vector (vector (if (string? atc) (re-pattern atc) atc) (dissoc med :codelist))))))

(def study-medication-atc
  "A sequence containing ATC code regexp and study medication class information."
  (mapcat make-atc-regexps study-medications))

(defn get-study-classes [atc]
  (keep identity (map (fn [[re-atc med]] (when (re-matches re-atc atc) med)) study-medication-atc)))


(comment
  study-medication-atc
  (first (get-study-classes "N05BA"))
  )

(defn all-ms-dmts
  "Returns a collection of multiple sclerosis disease modifying medications with
  SNOMED CT (dm+d) identifiers included. For validation, checks that each
  logical set of concepts is disjoint."
  [{:com.eldrix/keys [hermes dmd] :as system}]
  (let [result (map #(assoc % :codes (codelists/expand (codelists/make-codelist system (:codelist %)))) (remove #(= :other (:class %)) study-medications))]
    (if (apply codelists/disjoint? (map :codes (remove #(= :other (:class %)) result)))
      result
      (throw (IllegalStateException. "DMT specifications incorrect; sets not disjoint.")))))

(defn fetch-patients [{conn :com.eldrix.rsdb/conn} patient-ids]
  (db/execute! conn (sql/format {:select    [:patient_identifier :sex :date_birth :date_death :part1a :part1b :part1c :part2]
                                 :from      :t_patient
                                 :left-join [:t_death_certificate [:= :patient_fk :t_patient/id]]
                                 :where     [:in :t_patient/patient_identifier patient-ids]})))

(defn validate-only-one-death-certificate
  [{conn :com.eldrix.rsdb/conn}]
  (when-let [patients (seq (plan/select! conn :patient_fk (sql/format {:select   [:patient_fk]
                                                                       :from     [:t_death_certificate]
                                                                       :group-by [:patient_fk]
                                                                       :having   [:> :%count.patient_fk 1]})))]
    (throw (ex-info "validation failure: patients with more than one death certificate" {:patients patients}))))

(defn addresses-for-patients
  "Returns a map of patient identifiers to a collection of sorted addresses."
  [{conn :com.eldrix.rsdb/conn} patient-ids]
  (group-by :t_patient/patient_identifier
            (db/execute! conn (sql/format {:select   [:t_patient/patient_identifier :t_address/date_from :t_address/date_to :t_address/postcode_raw]
                                           :from     [:t_address]
                                           :order-by [[:t_patient/patient_identifier :asc] [:date_to :desc] [:date_from :desc]]
                                           :join     [:t_patient [:= :t_patient/id :t_address/patient_fk]]
                                           :where    [:in :t_patient/id patient-ids]}))))
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
                                                      [:in :t_patient/id patient-ids]
                                                      [:= :t_encounter/is_deleted "false"]]}))))

(defn ms-events-for-patients [{conn :com.eldrix.rsdb/conn} patient-ids]
  (sql/format {:select [:patient_fk :date :source :impact
                        [:abbreviation :type]]
               :from   [:t_ms_event]
               :join   [:t_summary_multiple_sclerosis [:= :summary_multiple_sclerosis_fk :t_summary_multiple_sclerosis.id]
                        :t_ms_event_type [:= :ms_event_type_fk :t_ms_event_type.id]]
               :where  [:in :patient_fk patient-ids]}))



(defn lsoa-for-postcode [{:com.eldrix/keys [clods]} postcode]
  (get (com.eldrix.clods.core/fetch-postcode clods postcode) "LSOA11"))

(defn deprivation-decile-for-lsoa [{:com.eldrix/keys [deprivare]} lsoa]
  (:uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile (deprivare/fetch-lsoa deprivare lsoa)))

(defn deprivation-deciles-for-patients
  "Determine deprivation decile for the patients specified.
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
   (deprivation-deciles-for-patients system [17497 22776] (LocalDate/of 2010 1 1)
   (deprivation-deciles-for-patients system [17497 22776] {17497 (LocalDate/of 2004 1 1)}"
  ([system patient-ids] (deprivation-deciles-for-patients system patient-ids (LocalDate/now)))
  ([system patient-ids on-date]
   (let [date-fn (if (ifn? on-date) on-date (constantly on-date))]
     (update-vals (addresses-for-patients system patient-ids)
                  #(->> (when-let [date (date-fn (:t_patient/patient_identifier (first %)))]
                          (rsdb/address-for-date % date))   ;; address-for-date will use 'now' if date nil, so wrap
                        :t_address/postcode_raw
                        (lsoa-for-postcode system)
                        (deprivation-decile-for-lsoa system))))))

(defn all-recorded-medications [{conn :com.eldrix.rsdb/conn}]
  (into #{} (map :t_medication/medication_concept_fk)
        (next.jdbc/plan conn
                        (sql/format {:select-distinct :t_medication/medication_concept_fk
                                     :from            :t_medication}))))

(defn medications-for-patients [{conn :com.eldrix.rsdb/conn :as system} patient-ids]
  (->> (db/execute! conn
                    (sql/format {:select [:t_patient/patient_identifier
                                          :t_medication/medication_concept_fk :t_medication/date_from :t_medication/date_to]
                                 :from   [:t_medication :t_patient]
                                 :where  [:and
                                          [:= :t_medication/patient_fk :t_patient/id]
                                          [:in :t_patient/id patient-ids]]}))
       (sort-by (juxt :t_patient/patient_identifier :t_medication/date_from))))

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
                  (infer-atc-for-non-dmd system concept-id))
          category (when atc (first (get-study-classes atc)))]
      (cond-> {:nm (or (:VTM/NM product) (:VMP/NM product) (:AMP/NM product) (:VMPP/NM product) (:AMPP/NM product))}
              atc (assoc :atc atc)
              category (assoc :category (:id category)
                              :class (:class category))))
    (let [atc (infer-atc-for-non-dmd system concept-id)
          term (:term (hermes/get-preferred-synonym hermes concept-id "en-GB"))
          category (when atc (first (get-study-classes atc)))]
      (cond-> {:nm term}
              atc (assoc :atc atc)
              category (assoc :category (:id category) :class (:class category))))))

(def fetch-drug2 (memoize fetch-drug))

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
  "Returns counts of the specified class of DMT used before the date specified."
  ([medications ^LocalDate date] (count-dmts-before medications date nil))
  ([medications ^LocalDate date dmt-class]
   (when date
     (let [prior-meds (filter #(or (nil? (:t_medication/date_from %)) (.isBefore (:t_medication/date_from %) date)) medications)]
       (if-not dmt-class
         (count (filter :dmt prior-meds))                   ;; return any DMT
         (count (filter #(= dmt-class (:dmt_class %)) prior-meds)))))))

(defn days-between
  "Return number of days between dates, or nil, or when-invalid if one date nil."
  ([^Temporal d1 ^Temporal d2] (days-between d1 d2 nil))
  ([^Temporal d1 ^Temporal d2 when-invalid]
   (if (and d1 d2)
     (.between ChronoUnit/DAYS d1 d2)
     when-invalid)))

(defn process-dmts [medications]
  (->> medications
       (keep-indexed (fn [i medication] (cond-> (assoc medication :switch? false)
                                                (> i 0) (assoc :switch? true :switch_from (:dmt (get medications (dec i)))))))
       (map #(assoc %
               :exposure_days (days-between (:t_medication/date_from %) (:t_medication/date_to %))
               :n_prior_dmts (count-dmts-before medications (:t_medication/date_from %))
               :n_prior_platform_dmts (count-dmts-before medications (:t_medication/date_from %) :platform-dmt)
               :n_prior_he_dmts (count-dmts-before medications (:t_medication/date_from %) :he-dmt)))))

(defn patient-dmt-medications
  "Returns DMT medications grouped by patient, annotated with additional DMT
  information. Medications are sorted in ascending date order."
  [{conn :com.eldrix.rsdb/conn :as system} patient-ids]
  (let [dmt-lookup (make-dmt-lookup system)]
    (->> (medications-for-patients system patient-ids)
         ; (filter #(let [start-date (:t_medication/date_from %)] (or (nil? start-date) (.isAfter (:t_medication/date_from %) study-master-date))))
         (map #(merge % (get dmt-lookup (:t_medication/medication_concept_fk %))))
         ;   (filter #(all-he-dmt-identifiers (:t_medication/medication_concept_fk %)))
         (filter :dmt)
         (partition-by (juxt :t_patient/patient_identifier :dmt))
         (map first)
         (group-by :t_patient/patient_identifier)
         (map (fn [[patient-id dmts]] (vector patient-id (process-dmts dmts))))
         (into {}))))

(defn cohort-entry-meds
  "Return a map of patient id to cohort entry medication.
  This is defined as date of first prescription of a HE-DMT after the
  defined study date."
  [system patient-ids study-date]
  (->> (patient-dmt-medications system patient-ids)
       vals
       (map (fn [medications] (first-he-dmt-after-date medications study-date)))
       (keep identity)
       (map #(vector (:t_patient/patient_identifier %) %))
       (into {})))

(defn write-rows-csv
  "Write a collection of maps ('rows') to a CSV file. Parameters:
  - out      : anything coercible by clojure.java.io/writer such as a filename
  - rows     : a sequence of maps
  - columns  : a vector of columns to write; optional.
  - title-fn : a function (or map) to convert each column key for output; optional, default: 'name'

  e.g.
  (write-rows-csv \"wibble.csv\" [{:one 1 :two 2 :three 3}] :columns [:one :two] :title {:one \"UN\" :two \"DEUX\"})"
  [out rows & {:keys [columns title-fn] :or {title-fn name}}]
  (let [columns' (or columns (keys (first rows)))
        headers (mapv #(or (title-fn %) (name %)) columns')]
    (with-open [writer (io/writer out)]
      (csv/write-csv writer (into [headers] (mapv #(mapv % columns') rows))))))

(defmulti to-local-date class)

(defmethod to-local-date LocalDateTime [^LocalDateTime x]
  (.toLocalDate x))

(defmethod to-local-date LocalDate [x] x)

(defmethod to-local-date nil [_] nil)

(defn make-study-data [system]
  (let [_ (validate-only-one-death-certificate system)
        all-dmt-identifiers (set (apply concat (map :codes (all-ms-dmts system)))) ;; a set of identifiers for all interesting DMTs
        dmt-patient-pks (patients/patient-pks-on-medications (:com.eldrix.rsdb/conn system) all-dmt-identifiers) ;; get all patients on these drugs
        project-ids (let [project-id (:t_project/id (projects/project-with-name (:com.eldrix.rsdb/conn system) "NINFLAMMCARDIFF"))] ;; get Cardiff cohort identifiers
                      (conj (projects/all-children-ids (:com.eldrix.rsdb/conn system) project-id) project-id))
        project-patient-pks (patients/patient-pks-in-projects (:com.eldrix.rsdb/conn system) project-ids) ;; get patients in those cohorts
        study-patient-pks (set/intersection dmt-patient-pks project-patient-pks)
        study-patient-identifiers (patients/pks->identifiers (:com.eldrix.rsdb/conn system) study-patient-pks)
        cohort-entry-meds (cohort-entry-meds system study-patient-identifiers study-master-date)
        cohort-entry-dates (update-vals cohort-entry-meds :t_medication/date_from)
        deprivation (deprivation-deciles-for-patients system study-patient-identifiers cohort-entry-dates)
        active-encounters (active-encounters-for-patients system study-patient-identifiers)
        earliest-contacts (update-vals active-encounters #(:t_encounter/date_time (first %)))
        latest-contacts (update-vals active-encounters #(:t_encounter/date_time (last %)))
        patients (->> (fetch-patients system study-patient-identifiers)
                      (map #(let [patient-id (:t_patient/patient_identifier %)]
                              (-> (merge % (get cohort-entry-meds patient-id))
                                  (assoc :depriv_decile (get deprivation patient-id)
                                         :start-follow-up (to-local-date (get earliest-contacts patient-id))
                                         :end-follow-up (or (:t_patient/date_death %) (to-local-date (get latest-contacts patient-id))))))))]
    {:all-dmt-identifiers       all-dmt-identifiers
     :study-patient-identifiers study-patient-identifiers
     :patients                  patients
     :active-encounters         active-encounters
     :dmt-medications           (mapcat identity (vals (patient-dmt-medications system study-patient-identifiers)))
     :non-dmt-medications       (->> (medications-for-patients system study-patient-identifiers)
                                     (remove #(all-dmt-identifiers (:t_medication/medication_concept_fk %)))
                                     (pmap #(merge % (fetch-drug2 system (:t_medication/medication_concept_fk %)))))}))

(defn write-data [system {:keys [patients dmt-medications non-dmt-medications]}]
  (log/info "writing patient core data")
  (write-rows-csv "patients.csv" patients
                  :columns [:t_patient/patient_identifier :t_patient/sex :t_patient/date_birth :t_patient/date_death
                            :depriv_decile :start-follow-up :end-follow-up
                            :part1a :part1b :part1c :part2
                            :atc :dmt :dmt_class :t_medication/date_from
                            :exposure_days
                            :n_prior_he_dmts :n_prior_platform_dmts :switch?]
                  :title-fn {:t_patient/patient_identifier "patient_id"
                             :t_medication/date_from       "cohort_entry_date"})
  (log/info "writing non-dmt medications")
  (write-rows-csv "patient-non-dmt-medications.csv" non-dmt-medications
                  :columns [:t_patient/patient_identifier :t_medication/medication_concept_fk
                            :t_medication/date_from :t_medication/date_to :nm :atc :category :class]
                  :title-fn {:t_patient/patient_identifier "patient_id"})
  (log/info "writing dmt medications")
  (write-rows-csv "patient-dmt-medications.csv" dmt-medications
                  :columns [:t_patient/patient_identifier
                            :t_medication/medication_concept_fk
                            :atc :dmt :dmt_class
                            :t_medication/date_from :t_medication/date_to :exposure_days
                            :switch_from :n_prior_platform_dmts :n_prior_he_dmts]
                  :title-fn {:t_patient/patient_identifier "patient_id"}))

(comment
  (def system (pc4/init :dev [:pathom/env]))
  (time (def data (make-study-data system)))
  (keys data)
  (def patient-ids (take 10 (:study-patient-identifiers data)))
  (take 4 (:patients data))
  (:patients data)
  (time (write-data system data))
  (def conn (:com.eldrix.rsdb/conn system))
  (keys system)
  (def all-dmts (all-ms-dmts system))
  (def all-he-dmt-identifiers (set (apply concat (map :codes (filter #(= :he-dmt (:class %)) all-dmts)))))
  all-he-dmt-identifiers
  (def dmt-lookup (apply merge (map (fn [dmt] (zipmap (:codes dmt) (repeat (vector (:class dmt) (:id dmt))))) all-dmts)))
  (apply concat (map :codes all-dmts))
  (first all-dmts)
  (count all-dmt-identifiers)
  all-dmts
  ;; get a list of patients who have received one of the disease-modifying treatments:
  (def dmt-patient-pks (patients/patient-pks-on-medications (:com.eldrix.rsdb/conn system) all-dmt-identifiers))
  (count dmt-patient-pks)

  ;; get a list of projects from which we will select patients
  (def project-ids (let [project-id (:t_project/id (projects/project-with-name (:com.eldrix.rsdb/conn system) "NINFLAMMCARDIFF"))]
                     (conj (projects/all-children-ids (:com.eldrix.rsdb/conn system) project-id) project-id)))
  (def cardiff-patient-pks (patients/patient-pks-in-projects (:com.eldrix.rsdb/conn system) project-ids))
  (count cardiff-patient-pks)

  ;; and now it is simple to derive identifiers for all patients known to service who have received DMT:
  (def study-patient-pks (set/intersection dmt-patient-pks cardiff-patient-pks))
  (def study-patient-identifiers (patients/pks->identifiers conn study-patient-pks))
  (take 4 study-patient-identifiers)

  (def pt-dmts (patient-dmt-medications system study-patient-pks))
  pt-dmts

  (defn rows->csv
    [header rows])

  (def columns [:t_patient/patient_identifier
                :t_medication/medication_concept_fk
                :atc
                :dmt
                :dmt_class
                :t_medication/date_from
                :t_medication/date_to
                :exposure_days
                :switch_from
                :n_prior_platform_dmts
                :n_prior_he_dmts])
  (def headers (mapv name columns))
  headers

  (clojure.pprint/print-table (get pt-dmts 12314))
  (def rows (mapcat identity (vals pt-dmts)))
  (take 4 rows)
  (into [headers] (mapv #(mapv % columns) rows))
  (with-open [writer (io/writer "out-file.csv")]
    (csv/write-csv writer
                   (into [headers] (mapv #(mapv % columns) rows))))



  (write-cohort-entry-dates system study-patient-identifiers study-master-date "cohort2.csv")



  (take 5 (into [headers] (mapv (fn [[patient-id medications]] (into [patient-id] (when-let [dmt (first-he-dmt-after-date medications study-master-date)]
                                                                                    [(:t_medication/date_from dmt) (:dmt dmt) (:dmt_class dmt)
                                                                                     (:n_prior_platform_dmts dmt) (:n_prior_he_dmts dmt)]))) pt-dmts)))








  (def pathom (:pathom/boundary-interface system))

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
                                                                       :uk.nhs.dmd/NM]}]}]}]))




  (def dmts (apply merge (map #(when-let [ecl (get-in % [:concepts :info.snomed/ECL])]
                                 (hash-map (:id %) (hermes/expand-ecl-historic svc ecl))) multiple-sclerosis-dmts)))
  dmts
  (set (map :conceptId (:alemtuzumab dmts)))
  (set (map :conceptId (:rituximab dmts)))
  (:rituximab dmts)
  (disjoint? (map #(when-let [ecl (get-in % [:concepts :info.snomed/ECL])]
                     (into #{} (map :conceptId (hermes/expand-ecl-historic svc ecl)))) multiple-sclerosis-dmts))
  )

(comment
  (require '[portal.api :as p])
  (p/open)
  (add-tap #'p/submit)
  (pc4/prep :dev)
  (def system (pc4/init :dev [:pathom/env]))
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
             :uk.nhs.dmd/NM
             ]}])

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
             {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
             ]}])
  (pathom [{'(info.snomed.Search/search {:constraint "<<9246601000001104 OR <<108755008"})
            [:info.snomed.Concept/id
             :info.snomed.Description/term]}])
  (def hermes (:com.eldrix/hermes system))
  hermes
  (require '[com.eldrix.hermes.core :as hermes])
  (def dmf (set (map :conceptId (hermes/search hermes {:constraint "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"}))))
  (contains? dmf 12086301000001102)
  (contains? dmf 24035111000001108)
  )