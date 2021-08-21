(ns com.eldrix.pc4.server.modules.dmt
  "Experimental approach to data downloads, suitable for short-term
   requirements for multiple sclerosis disease modifying drug
   post-marketing surveillance."
  (:require
    [clojure.set :as set]
    [com.eldrix.pc4.server.system :as pc4]
    [com.eldrix.pc4.server.rsdb.patients :as patients]
    [com.eldrix.pc4.server.rsdb.users :as users]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [com.eldrix.hermes.snomed :as snomed]
    [com.eldrix.hermes.core :as hermes]
    [clojure.string :as str]))

(def multiple-sclerosis-dmts
  "A list of disease modifying drugs in multiple sclerosis.
  They are classified as either platform DMTs or highly efficacious DMTs.
  We define by the use of the SNOMED CT expression constraint language, usually
  on the basis of active ingredients."
  [{:id             :dmf
    :description    "Dimethyl fumarate"
    :brand-names    ["Tecfidera"]
    :class          :platform
    :concepts       {:info.snomed/ECL
                     "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR
                     (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"}
    :uk.nhs.dmd/ATC "L04AX07"}
   {:id             :glatiramer
    :description    "Glatiramer acetate"
    :brand-names    ["Copaxone"]
    :class          :platform
    :concepts       {:info.snomed/ECL
                     "<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<108755008|Glatiramer acetate|"}
    :uk.nhs.dmd/ATC "L03AX13"}
   {:id             :ifn-beta-1a
    :description    "Interferon beta 1-a"
    :brand-names    ["Avonex" "Rebif"]
    :class          :platform
    :concepts       {:info.snomed/ECL
                     "(<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386902004|Interferon beta-1a|)
                     MINUS <<12222201000001108|PLEGRIDY|"}
    :uk.nhs.dmd/ATC "L03AB07"}
   {:id             :ifn-beta-1b
    :description    "Interferon beta 1-b"
    :brand-names    ["Betaferon®" "Extavia®"]
    :class          :platform
    :concepts       {:info.snomed/ECL "(<<9222901000001105|Betaferon|) OR (<<10105201000001101|Extavia|) OR
    (<10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386903009|Interferon beta-1b|)"}
    :uk.nhs.dmd/ATC "L03AB08"}
   {:id             :peg-ifn-beta-1a
    :description    "Peginterferon beta 1-a"
    :brand-names    ["Plegridy®"]
    :class          :platform
    :concepts       {:info.snomed/ECL
                     "<<12222201000001108|Plegridy|"}
    :uk.nhs.dmd/ATC "L03AB13"}
   {:id             :teriflunomide
    :description    "Teriflunomide"
    :brand-names    ["Aubagio®"]
    :class          :platform
    :concepts       {:info.snomed/ECL "<<703786007|Teriflunomide| OR <<12089801000001100|Aubagio| "}
    :uk.nhs.dmd/ATC "L04AA31"}
   {:id             :rituximab
    :description    "Rituximab"
    :brand-names    ["MabThera®" "Rixathon®" "Riximyo" "Blitzima" "Ritemvia" "Rituneza" "Ruxience" "Truxima"]
    :class          :highly-efficacious
    :concepts       {:info.snomed/ECL
                     (str/join " "
                               ["(<<108809004|Rituximab product|)"
                                "OR (<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<386919002|Rituximab|)"
                                "OR (<<9468801000001107|Mabthera|) OR (<<13058501000001107|Rixathon|)"
                                "OR (<<226781000001109|Ruxience|)  OR (<<13033101000001108|Truxima|)"])}
    :uk.nhs.dmd/ATC "L01XC02"}
   {:id             :ocrelizumab
    :description    "Ocrelizumab"
    :brand-names    ["Ocrevus"]
    :class          :highly-efficacious
    :concepts       {:info.snomed/ECL "(<<35058611000001103|Ocrelizumab|) OR (<<13096001000001106|Ocrevus|)"}
    :uk.nhs.dmd/ATC "L04AA36"}
   {:id             :cladribine
    :description    "Cladribine"
    :brand-names    ["Mavenclad"]
    :class          :highly-efficacious
    :concepts       {:info.snomed/ECL "<<108800000 OR <<13083101000001100"}
    :uk.nhs.dmd/ATC "L04AA40"}
   {:id             :mitoxantrone
    :description    "Mitoxantrone"
    :brand-names    ["Novantrone"]
    :class          :highly-efficacious
    :concepts       {:info.snomed/ECL "<<108791001 OR <<9482901000001102"}
    :uk.nhs.dmd/ATC "L01DB07"}
   {:id             :fingolimod
    :description    "Fingolimod"
    :brand-names    ["Gilenya"]
    :class          :highly-efficacious
    :concepts       {:info.snomed/ECL "<<715640009 OR <<10975301000001100"}
    :uk.nhs.dmd/ATC "L04AA27"}
   {:id             :natalizumab
    :description    "Natalizumab"
    :brand-names    ["Tysabri"]
    :class          :highly-efficacious
    :concepts       {:info.snomed/ECL "<<414804006 OR <<9375201000001103"}
    :uk.nhs.dmd/ATC "L04AA23"}
   {:id             :alemtuzumab
    :description    "Alemtuzumab"
    :brand-names    ["Lemtrada"]
    :class          :highly-efficacious
    :concepts       {:info.snomed/ECL "(<<391632007|Alemtuzumab|) OR (<<12091201000001101|Lemtrada|)"}
    :uk.nhs.dmd/ATC "L04AA34"}])

(defn disjoint?
  "Are sets disjoint, so that no set shares a member with any other set?
  Note this is different to determining the intersection between the sets.
  e.g.
    (clojure.set/intersection #{1 2} #{2 3} #{4 5})  => #{}   ; no intersection
    (disjoint? [#{1 2} #{2 3} #{4 5}])               => false ; not disjoint."
  [sets]
  (loop [sets' sets]
    (let [s1 (first sets')]
      (if-not s1
        true
        (when-not (seq (set/intersection s1 (apply set/union (rest sets'))))
          (recur (rest sets')))))))

(comment

  (set/intersection #{1 2} #{2 3} #{4 5})
  (disjoint? [#{1 2} #{2 3} #{4 5}])
  (disjoint? [#{8} #{1 8}])
  (disjoint? [#{1 2 3} #{4 5 6}])
  (disjoint? [#{1 2 3} #{4 5 6} #{5 7 8}])

  (def svc (:com.eldrix/hermes system))
  (hermes/expand-ecl-historic svc)
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
  (def system (pc4/init :dev))
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