(ns com.eldrix.pc4.server.modules.dmt
  "Experimental approach to data downloads, suitable for short-term
   requirements for multiple sclerosis disease modifying drug
   post-marketing surveillance."
  (:require
    [com.eldrix.pc4.server.system :as pc4]
    [com.eldrix.pc4.server.rsdb.patients :as patients]
    [com.eldrix.pc4.server.rsdb.users :as users]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [com.eldrix.hermes.snomed :as snomed]
    [com.eldrix.hermes.core :as hermes]))

(def multiple-sclerosis-dmts
  "A list of disease modifying drugs in multiple sclerosis.
  They are classified as either platform DMTs or highly efficacious DMTs.
  We define by the use of the SNOMED CT expression constraint language, usually
  on the basis of active ingredients."
  [{:description    "Dimethyl fumarate"
    :brand-name     "Tecfidera"
    :concepts       {:info.snomed/ECL
                     "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR
                     (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"}
    :uk.nhs.dmd/ATC "L04AX07"}
   {:description "Glatiramer acetate"
    :brand-name  "Copaxone"
    :concepts    {:info.snomed/ECL
                  "<10363601000001109|UK Product|:10362801000001104|Has specific active ingredient|=<<108755008|Glatiramer acetate|"}}
   {:description "Avonex"
    :brand-name  "Avonex"
    :concepts    {:info.snomed/ECL
                  "(<9218501000001109|Avonex) OR (<108749003|Product containing interferon beta-1a|) OR
                  <10363601000001109|UK Product|:127489000|Has specific active ingredient|=<<386902004|Interferon beta-1a|"}}])

(comment
  (pc4/prep :dev)
  (def system (pc4/init :dev))
  (integrant.core/halt! system)

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
  (pathom [{[:t_patient/patient_identifier 12182]
            [:t_patient/id
             :t_patient/date_birth
             :t_patient/date_death
             :t_patient/sex
             :t_patient/status
             :t_patient/surgery
             {:t_patient/medications [:t_medication/date_from
                                      :t_medication/date_to
                                      {:t_medication/medication [:info.snomed.Concept/id
                                                                 :uk.nhs.dmd/NM]}]}
             ]}])

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