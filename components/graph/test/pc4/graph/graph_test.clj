(ns pc4.graph.graph-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [is deftest use-fixtures]]
            [integrant.core :as ig]
            [pc4.config.interface :as config]
            [pc4.graph.interface :as graph])
  (:import (java.time LocalDate)))

(stest/instrument)
(def ^:dynamic *system* nil)
(def ^:dynamic *pathom* nil)

(defn with-system
  [f]
  (let [config (config/config :dev)
        _      (ig/load-namespaces config [::graph/boundary-interface])
        system (ig/init config [::graph/boundary-interface])]
    (binding [*system* system
              *pathom* (::graph/boundary-interface system)]
      (f)
      (ig/halt! system))))

(use-fixtures :once with-system)

(deftest test-lsoa
  (let [b301hl (*pathom* {:pathom/entity {:uk.gov.ons.nhspd/PCDS "b30 1hl"}
                          :pathom/eql [:uk.gov.ons.nhspd/LSOA11
                                       :uk.gov.ons.nhspd/OSNRTH1M :uk.gov.ons.nhspd/OSEAST1M
                                       :urn.ogc.def.crs.EPSG.4326/latitude
                                       :urn.ogc.def.crs.EPSG.4326/longitude
                                       {:uk.gov.ons.nhspd/LSOA-2011 [:uk.gov.ons/lsoa
                                                                     :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
                                       :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile
                                       :uk-composite-imd-2020-mysoc/UK_IMD_E_rank
                                       {:uk.gov.ons.nhspd/PCT_ORG [:uk.nhs.ord/name :uk.nhs.ord/active :uk.nhs.ord/orgId]}]})]
    (is (= "E01008956" (:uk.gov.ons.nhspd/LSOA11 b301hl)))
    (is (= "SOUTH BIRMINGHAM PCT" (get-in b301hl [:uk.gov.ons.nhspd/PCT_ORG :uk.nhs.ord/name])))))

(deftest test-snomed-fetch
  (let [concept (*pathom* {:pathom/entity {:info.snomed.Concept/id 80146002}
                           :pathom/eql [:info.snomed.Concept/id
                                        :info.snomed.Concept/active
                                        {:>/en-GB ['(:info.snomed.Concept/preferredDescription {:accept-language "en-GB"})]}
                                        {:>/en-US ['(:info.snomed.Concept/preferredDescription {:accept-language "en-US"})]}
                                        {:info.snomed.Concept/descriptions
                                         [:info.snomed.Description/active :info.snomed.Description/lowercaseTerm]}]})]
    (is (= 80146002 (:info.snomed.Concept/id concept)))
    (is (= "Appendicectomy" (get-in concept [:>/en-GB :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
    (is (= "Appendectomy" (get-in concept [:>/en-US :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
    (is (get-in concept [:>/en-GB :info.snomed.Concept/preferredDescription :info.snomed.Description/active]))
    (is (get-in concept [:>/en-US :info.snomed.Concept/preferredDescription :info.snomed.Description/active]))))

(deftest test-snomed-search
  (let [results (-> (*pathom* [{'(info.snomed.Search/search
                                  {:s          "mult scl"
                                   :constraint "<404684003"
                                   :max-hits   10})
                                [:info.snomed.Concept/id
                                 :info.snomed.Description/id
                                 :info.snomed.Description/term
                                 {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
                                 :info.snomed.Concept/active]}])
                    (get 'info.snomed.Search/search))]
    (is (= 24700007 (get-in results [0 :info.snomed.Concept/id])))
    (is (= "Multiple sclerosis" (get-in results [0 :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))))

(deftest test-user-common-concepts
  (is (->> (*pathom* {:pathom/entity {:t_user/id 1}
                      :pathom/eql [(list :t_user/common_concepts {:ecl "<64572001|Disease|"})]})
           :t_user/common_concepts
           (map #(get-in % [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
           (some #{"Multiple sclerosis"}))))

(comment
  (with-system
    test-user-common-concepts))
