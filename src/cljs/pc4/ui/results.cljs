(ns pc4.ui.results
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.algorithms.merge :as merge]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.ui.core :as ui]
            [pc4.ui.patients :as patients]
            [pc4.ui.snomed :as snomed]
            [taoensso.timbre :as log]))

(defsc ResultListItem
  [this {:t_result/keys [id date summary]
         entity-name    :t_result_type/result_entity_name
         result-name    :t_result_type/name
         result-desc    :t_result_type/description} computed-props]
  {:ident :t_result/id
   :query [:t_result/id :t_result/date :t_result/summary
           :t_result_type/result_entity_name :t_result_type/id
           :t_result_type/name :t_result_type/description]}
  (ui/ui-table-row computed-props
    (ui/ui-table-cell {} (ui/format-date date))
    (ui/ui-table-cell {} result-name)
    (ui/ui-table-cell {} (div :.overflow-hidden (ui/truncate summary 120)))))

(def ui-result-list-item (comp/computed-factory ResultListItem {:keyfn :t_result/id}))

(defsc PatientResults
  [this {:t_patient/keys [id patient_identifier results] :as patient
         :>/keys         [banner]}]
  {:ident         :t_patient/patient_identifier
   :route-segment ["pt" :t_patient/patient_identifier "results"]
   :query         [:t_patient/patient_identifier
                   :t_patient/id
                   {:>/banner (comp/get-query patients/PatientBanner)}
                   {:t_patient/results (comp/get-query ResultListItem)}]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientResults
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))}
  (when patient_identifier
    (patients/ui-layout
      {:banner (patients/ui-patient-banner banner)
       :menu   (patients/ui-pseudonymous-menu
                 patient
                 {:selected-id :results
                  :sub-menu    {:items []}})

       :content
       (ui/ui-table {}
         (ui/ui-table-head {}
           (ui/ui-table-row {}
             (for [heading ["Date/time" "Investigation" "Result"]]
               (ui/ui-table-heading {:react-key heading} heading))))
         (ui/ui-table-body {}
           (for [result (sort-by #(some-> :t_result/date .valueOf) results)]
             (ui-result-list-item result
                                     {:onClick #(println "edit" result)
                                      :classes ["cursor-pointer" "hover:bg-gray-200"]}))))})))