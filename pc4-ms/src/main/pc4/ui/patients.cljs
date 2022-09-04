(ns pc4.ui.patients
  (:require [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.dom.events :as evt]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [pc4.app :refer [SPA]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [clojure.string :as str]
            [pc4.ui.core :as ui]
            [pc4.rsdb]
            [pc4.users]
            [taoensso.timbre :as log]
            [cljs.spec.alpha :as s])
  (:import [goog.date Date]))

(defn most-recent-edss-encounter
  "From a collection of encounters, return the most recent containing an EDSS result."
  [encounters]
  (->> encounters
       (filter :t_encounter/active)
       (sort-by :t_encounter/date_time)
       (filter #(or (:t_encounter/form_edss %) (:t_encounter/form_edss_fs %)))
       reverse
       first))

(defsc PatientBanner*
  [this {:keys [name nhs-number gender born hospital-identifier address deceased content]} {:keys [onClose]}]
  (div :.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200.relative
       (when onClose
         (div :.absolute.top-0.5.sm:-top-2.5.right-0.sm:-right-2.5
              (dom/button :.rounded.bg-white.border.hover:bg-gray-300.bg-gray-50.px-1.py-1
                          {:onClick onClose :title "Close patient record"}
                          (dom/svg {:xmlns "http://www.w3.org/2000/svg" :width "20" :height "20" :viewBox "0 0 18 18"}
                                   (dom/path {:d "M14.53 4.53l-1.06-1.06L9 7.94 4.53 3.47 3.47 4.53 7.94 9l-4.47 4.47 1.06 1.06L9 10.06l4.47 4.47 1.06-1.06L10.06 9z"})))))
       (when deceased
         (div :.grid.grid-cols-1.pb-2
              (ui/ui-badge {:label (cond
                                     (instance? goog.date.Date deceased)
                                     (str "Died " (ui/format-date deceased))
                                     (string? deceased)
                                     (str "Died " deceased)
                                     :else "Deceased")})))
       (div :.grid.grid-cols-2.lg:grid-cols-5.pt-1
            (when name (div :.font-bold.text-lg.min-w-min name))
            (div :.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min (when gender (dom/span :.text-sm.font-thin.hidden.sm:inline "Gender ")
                                                                                            (dom/span :.font-bold gender)))
            (div :.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min (dom/span :.text-sm.font-thin "Born ") (dom/span :.font-bold born))
            (div :.lg:hidden.text-right.mr-8.md:mr-0 gender " " (dom/span :.font-bold born))
            (when nhs-number (div :.lg:text-center.lg:ml-2.min-w-min (dom/span :.text-sm.font-thin "NHS No ") (dom/span :.font-bold nhs-number)))
            (when hospital-identifier (div :.text-right.min-w-min (dom/span :.text-sm.font-thin "CRN ") (dom/span :.font-bold hospital-identifier))))
       (div :.grid.grid-cols-1 {:className (if-not deceased "bg-gray-100" "bg-red-100")}
            (div :.font-light.text-sm.tracking-tighter.text-gray-500.truncate address))
       (when content
         (div content))))

(def ui-patient-banner* (comp/computed-factory PatientBanner*))

(defsc PatientBanner [this {:t_patient/keys [patient_identifier status nhs_number date_birth sex date_death episodes]
                            current-project :ui/current-project}
                      {:keys [onClose] :as computed-props}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier :t_patient/status :t_patient/nhs_number :t_patient/sex :t_patient/date_birth
           :t_patient/date_death :t_patient/episodes
           {[:ui/current-project '_] [:t_project/id]}]}
  (let [project-id (:t_project/id current-project)
        pseudonym (when project-id (:t_episode/stored_pseudonym (first (filter #(= (:t_episode/project_fk %) project-id) episodes))))]
    (ui-patient-banner* {:name     (when sex (name sex))
                         :born     (if (= :PSEUDONYMOUS status) (ui/format-month-year date_birth) (ui/format-date date_birth))
                         :address  pseudonym
                         :deceased date_death} computed-props)))

(def ui-patient-banner (comp/computed-factory PatientBanner))

(defsc EncounterListItem [this {:t_encounter/keys [date_time encounter_template form_edss form_edss_fs form_ms_relapse form_weight_height]}]
  {:ident :t_encounter/id
   :query [:t_encounter/id :t_encounter/date_time :t_encounter/active
           {:t_encounter/encounter_template [:t_encounter_template/title]}
           :t_encounter/notes
           {:t_encounter/form_edss [:t_form_edss/score]}
           {:t_encounter/form_edss_fs [:t_form_edss_fs/score]}
           {:t_encounter/form_ms_relapse [:t_form_ms_relapse/in_relapse :t_ms_disease_course/name]}
           {:t_encounter/form_weight_height [:t_form_weight_height/weight_kilogram]}]}
  (ui/ui-table-row
          [(ui/ui-table-cell (ui/format-date date_time))
           (ui/ui-table-cell (:t_encounter_template/title encounter_template))
           (ui/ui-table-cell (or (:t_form_edss/score form_edss) (:t_form_edss_fs/score form_edss_fs)))
           (ui/ui-table-cell (:t_ms_disease_course/name form_ms_relapse))
           (ui/ui-table-cell (case (:t_form_ms_relapse/in_relapse form_ms_relapse) true "Yes" false "No" ""))
           (ui/ui-table-cell (when-let [wt (:t_form_weight_height/weight_kilogram form_weight_height)] (str wt "kg")))]))

(def ui-encounter-list-item (comp/factory EncounterListItem))

(defsc MostRecentEDSS [this props]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           {:t_patient/encounters [:t_encounter/active
                                   :t_encounter/date_time
                                   :t_encounter/form_edss
                                   :t_encounter/form_edss_fs]}]})

(defsc PatientMultipleSclerosisSummary [this props]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           :t_summary_multiple_sclerosis/diagnosis
           {:>/most-recent-edss (comp/get-query MostRecentEDSS)}]})

(defsc PatientDemographics
  [this {:t_patient/keys [patient_identifier first_names last_name date_birth date_death sex]}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           :t_patient/first_names :t_patient/last_name
           :t_patient/sex :t_patient/date_birth :t_patient/date_death]}
  (comp/fragment
    (dom/h1 "Patient demographics")
    (dom/p "ID: " patient_identifier)))

(def ui-patient-demographics (comp/factory PatientDemographics))

(defsc PatientEncounters
  [this {:t_patient/keys [patient_identifier encounters] :as props}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/patient_identifier
                   [df/marker-table :patient-encounters]
                   {:t_patient/encounters (comp/query EncounterListItem)}]
   :initial-state {}}
  (let [load-marker (get props [df/marker-table :patient-encounters])]
    (comp/fragment
      (when (df/loading? load-marker) (ui/ui-loading {}))
      (ui/ui-table
        [(ui/ui-table-head
           (ui/ui-table-row
             [(ui/ui-table-heading "Date")
              (ui/ui-table-heading "Type")
              (ui/ui-table-heading "EDSS")
              (ui/ui-table-heading "Disease course")
              (ui/ui-table-heading "In relapse?")
              (ui/ui-table-heading "Weight")]))
         (ui/ui-table-body (map ui-encounter-list-item encounters))]))))

(def ui-patient-encounters (comp/factory PatientEncounters))

(defsc PatientPage
  [this {:t_patient/keys [id patient_identifier first_names last_name date_birth sex date_death nhs_number]
         current-project :ui/current-project
         banner          :>/banner
         demographics    :>/demographics
         encounters      :>/encounters}]
  {:ident               :t_patient/patient_identifier
   :route-segment       ["patient" :t_patient/patient_identifier]
   :query               [:t_patient/id :t_patient/patient_identifier :t_patient/first_names :t_patient/last_name
                         :t_patient/date_birth :t_patient/sex :t_patient/date_death :t_patient/nhs_number :t_patient/status
                         {:t_patient/encounters (comp/get-query EncounterListItem)}
                         {[:ui/current-project '_] [:t_project/id]}
                         {:>/banner (comp/get-query PatientBanner)}
                         {:>/demographics (comp/get-query PatientDemographics)}
                         {:>/encounters (comp/get-query PatientEncounters)}]
   :initial-state       {}
   :will-enter          (fn [app {:t_patient/keys [patient_identifier]}]
                          (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                            (println "entering patient demographics page; patient-identifier:" patient-identifier " : " PatientPage)
                            (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                               (fn []
                                                 (df/load! app [:t_patient/patient_identifier patient-identifier] PatientPage
                                                           {:target               [:ui/current-patient]
                                                            :without              #{:t_patient/encounters}
                                                            :post-mutation        `dr/target-ready
                                                            :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))
   :allow-route-change? (constantly true)
   :will-leave          (fn [this props]
                          (log/info "leaving patient page; patient identifier: " (:t_patient/patient_identifier props))
                          (comp/transact! this [(pc4.users/close-patient nil)]))}
  (let [selected-page (or (comp/get-state this :selected-page) :home)]
    (comp/fragment
      (ui-patient-banner banner {:onClose #(dr/change-route! this ["project" (:t_project/id current-project)])})
      (dom/h1 "Patient page")
      (dom/ul
        (dom/li "id:" patient_identifier)
        (dom/li "Name: " first_names " " last_name))
      (dom/ul
        (dom/li (dom/button {:onClick #(comp/set-state! this {:selected-page :home})} "Demographics"))
        (dom/li (dom/button {:onClick #(do (df/load-field! this [:t_patient/patient_identifier :t_patient/encounters] {:marker :patient-encounters})
                                           (comp/set-state! this {:selected-page :encounters}))} "Encounters")))

      (dom/div :.pt-3.border.bg-white.overflow-hidden.shadow-lg.sm:rounded-lg
               (dom/div :.px-4.py-5.sm:p-6
                        (case selected-page
                          :home (ui-patient-demographics demographics)
                          :encounters (ui-patient-encounters encounters)))))))

(def ui-patient-page (comp/factory PatientPage))
