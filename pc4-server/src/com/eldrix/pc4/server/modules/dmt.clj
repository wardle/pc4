(ns com.eldrix.pc4.server.modules.dmt
  "Experimental approach to data downloads, suitable for short-term
   requirements for multiple sclerosis disease modifying drug
   post-marketing surveillance."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [com.eldrix.pc4.server.codelists :as codelists]
            [com.eldrix.pc4.server.system :as pc4]
            [com.eldrix.pc4.server.rsdb.patients :as patients]
            [com.eldrix.pc4.server.rsdb.projects :as projects]
            [com.eldrix.pc4.server.rsdb.users :as users]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [clojure.string :as str]
            [com.eldrix.pc4.server.rsdb.db :as db])
  (:import (java.time LocalDate)
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
    :brand-names ["Tecfidera"]
    :class       :platform-dmt
    :codelist    {:ecl
                       "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR
                       (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
                  :atc "L04AX07"}}
   {:id          :glatiramer
    :description "Glatiramer acetate"
    :brand-names ["Copaxone" "Brabio"]
    :class       :platform-dmt
    :codelist    {:ecl
                       "<<108754007|Glatiramer| OR <<9246601000001104|Copaxone| OR <<13083901000001102|Brabio| OR <<8261511000001102 OR <<29821211000001101
                       OR (<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<108755008|Glatiramer acetate|)"
                  :atc "L03AX13"}}
   {:id          :ifn-beta-1a
    :description "Interferon beta 1-a"
    :brand-names ["Avonex" "Rebif"]
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
    :class       :platform-dmt
    :codelist    {:ecl "(<<9222901000001105|Betaferon|) OR (<<10105201000001101|Extavia|) OR
                     (<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386903009|Interferon beta-1b|)"
                  :atc "L03AB08"}}
   {:id          :peg-ifn-beta-1a
    :description "Peginterferon beta 1-a"
    :brand-names ["Plegridy®"]
    :class       :platform-dmt
    :codelist    {:ecl "<<12222201000001108|Plegridy|"
                  :atc "L03AB13"}}
   {:id          :teriflunomide
    :description "Teriflunomide"
    :brand-names ["Aubagio®"]
    :class       :platform-dmt
    :codelist    {:ecl "<<703786007|Teriflunomide| OR <<12089801000001100|Aubagio| "
                  :atc "L04AA31"}}
   {:id          :rituximab
    :description "Rituximab"
    :brand-names ["MabThera®" "Rixathon®" "Riximyo" "Blitzima" "Ritemvia" "Rituneza" "Ruxience" "Truxima"]
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
    :class       :he-dmt
    :codelist    {:ecl "(<<35058611000001103|Ocrelizumab|) OR (<<13096001000001106|Ocrevus|)"
                  :atc "L04AA36"}}
   {:id          :cladribine
    :description "Cladribine"
    :brand-names ["Mavenclad"]
    :class       :he-dmt
    :codelist    {:ecl "<<108800000|Cladribine| OR <<13083101000001100|Mavenclad|"
                  :atc "L04AA40"}}
   {:id          :mitoxantrone
    :description "Mitoxantrone"
    :brand-names ["Novantrone"]
    :class       :he-dmt
    :codelist    {:ecl "<<108791001 OR <<9482901000001102"
                  :atc "L01DB07"}}
   {:id          :fingolimod
    :description "Fingolimod"
    :brand-names ["Gilenya"]
    :class       :he-dmt
    :codelist    {:ecl "<<715640009 OR <<10975301000001100"
                  :atc "L04AA27"}}
   {:id          :natalizumab
    :description "Natalizumab"
    :brand-names ["Tysabri"]
    :class       :he-dmt
    :codelist    {:ecl "<<414804006 OR <<9375201000001103"
                  :atc "L04AA23"}}
   {:id          :alemtuzumab
    :description "Alemtuzumab"
    :brand-names ["Lemtrada"]
    :class       :he-dmt
    :codelist    {:ecl "(<<391632007|Alemtuzumab|) OR (<<12091201000001101|Lemtrada|)"
                  :atc "L04AA34"}}
   {:id          :statins
    :description "Statins"
    :class       :other
    :codelist    {:atc #"C10AA.*"}}
   {:id          :anti-hypertensives
    :description "Anti-hypertensive"
    :class       :other
    :codelist    {:atc #"C02.*"}}
   {:id          :anti-platelets
    :description "Anti-platelets"
    :class       :other
    :codelist    {:atc #"B01AC.*"}}
   {:id          :proton-pump-inhibitors
    :description "Proton pump inhibitors"
    :class       :other
    :codelist    {:atc #"A02BC.*"}}
   {:id          :immunosuppressants
    :description "Immunosuppressants"
    :class       :other
    :codelist    {:inclusions {:atc [#"L04AA.*" #"L04AB.*" #"L04AC.*" #"L04AD.*" #"L04AX.*"]}
                  :exclusions {:atc ["L04AA23" "L04AA27" "L04AA31" "L04AA34" "L04AA36" "L04AA40" "L04AX07"]}}}
   {:id          :antidepressants
    :description "Anti-depressants"
    :class       :other
    :codelist    {:atc #"N06A.*"}}
   {:id          :benzodiazepines
    :description "Benzodiazepines"
    :class       :other
    :codelist    {:atc [#"N03AE.*" #"N05BA.*"]}}
   {:id          :antiepileptics
    :description "Anti-epileptics"
    :class       :other
    :codelist    {:atc #"N03A.*"}}
   {:id          :antidiabetic
    :description "Anti-diabetic"
    :class       :other
    :codelist    {:atc #"A10.*"}}
   {:id          :nutritional
    :description "Nutritional supplements and vitamins"
    :class       :other
    :codelist    {:atc [#"A11.*" #"B02B.*" #"B03C.*"]}}])

(defn all-ms-dmts
  "Returns a collection of multiple sclerosis disease modifying medications with
  identifiers included. For basic validation, checks that each logical set of
  concepts is disjoint."
  [{:com.eldrix/keys [hermes dmd] :as system}]
  (let [result (map #(assoc % :codes (codelists/make-codelist system (:codelist %))) (remove #(= :other (:class %)) study-medications))]
    (if (apply codelists/disjoint? (map :codes (remove #(= :other (:class %)) result)))
      result
      (throw (IllegalStateException. "DMT specifications incorrect; sets not disjoint.")))))

(defn medications-for-patients [{conn :com.eldrix.rsdb/conn :as system} patient-ids]
  (->> (db/execute! conn
                    (honey.sql/format {:select [:t_patient/patient_identifier
                                                :t_medication/medication_concept_fk :t_medication/date_from :t_medication/date_to]
                                       :from   [:t_medication :t_patient]
                                       :where  [:and
                                                [:= :t_medication/patient_fk :t_patient/id]
                                                [:in :t_patient/id patient-ids]]}))
       (sort-by (juxt :t_patient/patient_identifier :t_medication/date_from))))

(defn first-he-dmt-after-date
  "Given a list of medications for a single patient, return the first
  highly-effective medication given after the date specified."
  [medications ^LocalDate date]
  (->> medications
       (filter #(= :he-dmt (:dmt-class %)))
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
         (count (filter #(= dmt-class (:dmt-class %)) prior-meds)))))))

(defn days-between
  "Return number of days between dates, or nil or when-invalid if one date nil."
  ([^Temporal d1 ^Temporal d2] (days-between d1 d2 nil))
  ([^Temporal d1 ^Temporal d2 when-invalid]
   (if (and d1 d2)
     (.between ChronoUnit/DAYS d1 d2)
     when-invalid)))

(defn process-dmts [medications]
  (->> medications
       (keep-indexed (fn [i medication] (cond-> (assoc medication :switch? false)
                                                (> i 0) (assoc :switch? true :switch-from (:dmt (get medications (dec i)))))))
       (map #(assoc %
               :exposure-days (days-between (:t_medication/date_from %) (:t_medication/date_to %))
               :n-prior-dmts (count-dmts-before medications (:t_medication/date_from %))
               :n-prior-platform-dmts (count-dmts-before medications (:t_medication/date_from %) :platform-dmt)
               :n-prior-he-dmts (count-dmts-before medications (:t_medication/date_from %) :he-dmt)))))

(defn patient-dmt-medications
  "Returns DMT medications grouped by patient, annotated with additional DMT
  information. Medications are sorted in ascending date order."
  [{conn :com.eldrix.rsdb/conn :as system} patient-ids]
  (let [dmt-lookup (apply merge (map (fn [dmt] (zipmap (:codes dmt) (repeat {:dmt-class (:class dmt) :dmt (:id dmt)}))) (all-ms-dmts system)))]
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


(comment
  (def system (pc4/init :dev [:pathom/env]))
  (def conn (:com.eldrix.rsdb/conn system))
  (keys system)
  (def all-dmts (all-ms-dmts system))
  (def all-he-dmt-identifiers (set (apply concat (map :codes (filter #(= :he-dmt (:class %)) all-dmts)))))
  all-he-dmt-identifiers
  (def dmt-lookup (apply merge (map (fn [dmt] (zipmap (:codes dmt) (repeat (vector (:class dmt) (:id dmt))))) all-dmts)))

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
  (def columns {:t_patient/patient_identifier       :patient_id
                :t_medication/medication_concept_fk :medication_id
                :t_medication/date_from             :date_from
                :t_medication/date_to               :date_to
                :dmt                                :dmt
                :dmt-class                          :class
                :switch?                            :switch?
                :n-prior-platform-dmts              :n-prior-platform-dmts
                :n-prior-he-dmts                    :n-prior-he-dmts})
  (def headers (mapv name (vals columns)))
  headers

  (clojure.pprint/print-table (get pt-dmts 12314))
  (def rows (mapcat identity (vals pt-dmts)))
  (into [headers] (mapv #(mapv % (keys columns)) rows))
  (with-open [writer (io/writer "out-file.csv")]
    (csv/write-csv writer
                   (into [headers] (mapv #(mapv % (keys columns)) rows))))








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