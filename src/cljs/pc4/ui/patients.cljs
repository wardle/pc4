(ns pc4.ui.patients
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.nhsnumber :as nhs-number]
            [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
            [pc4.ui.core :as ui]
            [pc4.ui.snomed :as snomed]
            [pc4.users]
            [taoensso.timbre :as log]))


(defsc PatientBanner*
  [this {:keys [name nhs-number gender born hospital-identifier address deceased]} {:keys [onClose content]}]
  (div :.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.lg:m-2.sm:m-0.border-gray-200.relative
    (when onClose
      (div :.absolute.top-0.5.sm:-top-2.5.right-0.sm:-right-2.5
        (dom/button :.rounded.bg-white.border.hover:bg-gray-300.bg-gray-50.px-1.py-1
          {:onClick onClose :title "Close patient record"}
          (dom/svg {:xmlns "http://www.w3.org/2000/svg" :width "20" :height "20" :viewBox "0 0 18 18"}
            (dom/path {:d "M14.53 4.53l-1.06-1.06L9 7.94 4.53 3.47 3.47 4.53 7.94 9l-4.47 4.47 1.06 1.06L9 10.06l4.47 4.47 1.06-1.06L10.06 9z"})))))
    (when deceased
      (div :.grid.grid-cols-1.pb-2.mr-4
        (ui/ui-badge {:label (cond (instance? goog.date.Date deceased) (str "Died " (ui/format-date deceased))
                                   (string? deceased) (str "Died " deceased)
                                   :else "Deceased")})))
    (div :.grid.grid-cols-2.lg:grid-cols-5.pt-1
      (when name (div :.font-bold.text-lg.min-w-min name))
      (div :.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
        (when gender (dom/span :.text-sm.font-thin.hidden.sm:inline "Gender ")
                     (dom/span :.font-bold gender)))
      (div :.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
        (dom/span :.text-sm.font-thin "Born ") (dom/span :.font-bold born))
      (div :.lg:hidden.text-right.mr-8.md:mr-0 gender " " (dom/span :.font-bold born))
      (when nhs-number
        (div :.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
          (dom/span :.text-sm.font-thin.mr-2 "NHS No ")
          (dom/span :.font-bold (nhs-number/format-nnn nhs-number))))
      (when hospital-identifier
        (div :.text-right.min-w-min (dom/span :.text-sm.font-thin "CRN ") (dom/span :.font-bold hospital-identifier))))
    (div :.grid.grid-cols-1 {:className (if-not deceased "bg-gray-100" "bg-red-100")}
      (div :.font-light.text-sm.tracking-tighter.text-gray-500.truncate address))
    (when content
      (div content))))

(def ui-patient-banner* (comp/computed-factory PatientBanner*))

(defsc PatientEpisode [this props]
  {:ident :t_episode/id
   :query [:t_episode/id :t_episode/project_fk :t_episode/stored_pseudonym]})

(defsc PatientBanner [this {:t_patient/keys [patient_identifier status nhs_number date_birth sex date_death
                                             title first_names last_name address episodes]
                            current-project :ui/current-project}
                      {:keys [onClose] :as computed-props}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/patient_identifier :t_patient/status :t_patient/nhs_number :t_patient/sex
                   :t_patient/title :t_patient/first_names :t_patient/last_name :t_patient/date_birth :t_patient/date_death
                   {:t_patient/address [:t_address/address1 :t_address/address2 :t_address/address3 :t_address/address4 :t_address/address5 :t_address/postcode]}
                   {:t_patient/episodes (comp/get-query PatientEpisode)}
                   {[:ui/current-project '_] [:t_project/id]}]
   :initial-state (fn [params]
                    {:t_patient/patient_identifier (:t_patient/patient_identifier params)
                     :t_patient/episodes           []})}
  (let [project-id (:t_project/id current-project)
        pseudonym (when project-id (:t_episode/stored_pseudonym (first (filter #(= (:t_episode/project_fk %) project-id) episodes))))]
    (if (= :PSEUDONYMOUS status)                            ;; could use polymorphism to choose component here?
      (ui-patient-banner* {:name     (when sex (name sex))
                           :born     (ui/format-month-year date_birth)
                           :address  pseudonym
                           :deceased (ui/format-month-year date_death)} computed-props)
      (let [{:t_address/keys [address1 address2 address3 address4 address5 postcode]} address]
        (ui-patient-banner* {:name       (str (str/join ", " [(when last_name (str/upper-case last_name)) first_names]) (when title (str " (" title ")")))
                             :born       (ui/format-date date_birth)
                             :nhs-number nhs_number
                             :address    (str/join ", " (remove str/blank? [address1 address2 address3 address4 address5 postcode]))
                             :deceased   date_death} computed-props)))))


(def ui-patient-banner (comp/computed-factory PatientBanner))

(defsc PseudonymousMenu
  "Patient menu. At the moment, we have a different menu for pseudonymous
  patients but this will become increasingly unnecessary."
  [this {:t_patient/keys [patient_identifier]
         pseudonym       :t_episode/stored_pseudonym}
   {:keys [selected-id sub-menu]}]
  (ui/ui-vertical-navigation
    {:selected-id selected-id
     :items       [{:id      :home
                    :content "Home"
                    :onClick #(dr/change-route! this ["pt" patient_identifier "home"])}
                   {:id      :diagnoses
                    :content "Diagnoses"
                    :onClick #(dr/change-route! this ["pt" patient_identifier "diagnoses"])}
                   {:id      :medications
                    :content "Medication"
                    :onClick #(dr/change-route! this ["pt" patient_identifier "medications"])}
                   {:id      :relapses
                    :content "Relapses"
                    :onClick #(dr/change-route! this ["pt" patient_identifier "neuroinflammatory"])}
                   {:id      :encounters
                    :content "Encounters"
                    :onClick #(dr/change-route! this ["pt" patient_identifier "encounters"])}
                   {:id      :investigations
                    :content "Investigations"}
                   {:id      :admissions
                    :content "Admissions"
                    :onClick #(dr/change-route! this ["pt" patient_identifier "admissions"])}]
     :sub-menu    sub-menu}))

(def ui-pseudonymous-menu (comp/computed-factory PseudonymousMenu))

(defsc Layout [this {:keys [banner menu content]}]
  (comp/fragment
    banner
    (div :.grid.grid-cols-1.md:grid-cols-6.gap-x-4.relative.pr-2
      (div :.col-span-1.p-2 menu)
      (div :.col-span-1.md:col-span-5.pt-2 content))))

(def ui-layout (comp/factory Layout))

(defsc NewPatientDemographics
  [this {:t_patient/keys [id patient_identifier status title first_names last_name nhs_number date_birth date_death current_age address] :as patient :>/keys [banner]}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/id
                   :t_patient/patient_identifier :t_patient/status
                   :t_patient/title :t_patient/first_names :t_patient/last_name
                   :t_patient/nhs_number :t_patient/date_birth :t_patient/date_death :t_patient/current_age
                   {:t_patient/address [:t_address/address1 :t_address/address2 :t_address/address3 :t_address/address4 :t_address/postcode]}
                   {:>/banner (comp/get-query PatientBanner)}]
   :route-segment ["pt" :t_patient/patient_identifier "home"]
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (log/debug "on-enter patient demographics" route-params)
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (println "entering patient demographics page; patient-identifier:" patient-identifier " : " NewPatientDemographics)
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] NewPatientDemographics
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))}
  (when (and id patient_identifier)
    (ui-layout
      {:banner  (ui-patient-banner banner)
       :menu    (ui-pseudonymous-menu patient {:selected-id :home})
       :content (ui/ui-two-column-card
                  {:title "Demographics"
                   :items [{:title "First names" :content first_names}
                           {:title "Last name" :content last_name}
                           {:title "Title" :content title}
                           {:title "NHS number" :content (nhs-number/format-nnn nhs_number)}
                           {:title "Date of birth" :content (ui/format-date date_birth)}
                           (if date_death {:title "Date of death" :content (ui/format-date date_death)}
                                          {:title "Current age" :content current_age})
                           {:title "Address1" :content (:t_address/address1 address)}
                           {:title "Address2" :content (:t_address/address2 address)}
                           {:title "Address3" :content (:t_address/address3 address)}
                           {:title "Address4" :content (:t_address/address4 address)}
                           {:title "Postal code" :content (:t_address/postcode address)}]})})))












(defsc NeuroinflammatoryDiagnosis
  [this {:t_ms_diagnosis/keys [id name]}]
  {:ident :t_ms_diagnosis/id
   :query [:t_ms_diagnosis/id :t_ms_diagnosis/name]})

(defsc ChooseNeuroinflammatoryDiagnosis
  [this {sms-id           :t_summary_multiple_sclerosis/id
         patient          :t_summary_multiple_sclerosis/patient
         ms-diagnosis     :t_summary_multiple_sclerosis/ms_diagnosis
         all-ms-diagnoses :com.eldrix.rsdb/all-ms-diagnoses
         :as              params}]
  {:ident :t_summary_multiple_sclerosis/id
   :query [:t_summary_multiple_sclerosis/id
           {:t_summary_multiple_sclerosis/patient [:t_patient/patient_identifier]}
           {:t_summary_multiple_sclerosis/ms_diagnosis (comp/get-query NeuroinflammatoryDiagnosis)}
           {:com.eldrix.rsdb/all-ms-diagnoses (comp/get-query NeuroinflammatoryDiagnosis)}]}
  (println params)
  (ui/ui-select-popup-button {:value       ms-diagnosis
                              :options     all-ms-diagnoses
                              :id-key      :t_ms_diagnosis/id
                              :display-key :t_ms_diagnosis/name
                              :onChange    #(comp/transact! this [(list 'pc4.rsdb/save-ms-diagnosis (merge patient %))])}))

(def ui-choose-neuroinflammatory-diagnosis (comp/factory ChooseNeuroinflammatoryDiagnosis))

(defsc InspectEditLsoa
  [this {:t_patient/keys [patient_identifier lsoa11]}]
  (let [editing (comp/get-state this :ui/editing)
        postcode (comp/get-state this :ui/postcode)]
    (if-not editing
      (ui/ui-link-button {:onClick #(do (comp/set-state! this {:ui/editing true :ui/postcode ""}))}
                         (or lsoa11 "Not yet set"))
      (div :.space-y-6
        (ui/ui-textfield {:label "Enter postal code" :value postcode}
                         {:onChange #(comp/set-state! this {:ui/postcode %})})
        (ui/ui-button {:role      :primary
                       :onClick   #(do (println "Save address" patient_identifier postcode)
                                       (comp/transact! (comp/get-parent this)
                                                       [(list 'pc4.rsdb/save-pseudonymous-patient-postal-code
                                                          {:t_patient/patient_identifier patient_identifier
                                                           :uk.gov.ons.nhspd/PCD2        postcode})])
                                       (comp/set-state! this {:ui/editing false :ui/postcode ""}))
                       :disabled? (str/blank? postcode)} "Save")
        (ui/ui-button {:onClick #(comp/set-state! this {:ui/editing false :ui/postcode ""})} "Cancel")))))

(def ui-inspect-edit-lsoa (comp/factory InspectEditLsoa))


(defsc PatientDeathCertificate
  [this {:t_patient/keys           [date_death]
         :t_death_certificate/keys [part1a part1b part1c part2]
         banner                    :>/banner}]
  {:ident          :t_patient/patient_identifier
   :query          [{:>/banner (comp/get-query PatientBanner)}
                    :t_patient/patient_identifier
                    :t_patient/date_death
                    :t_death_certificate/part1a
                    :t_death_certificate/part1b
                    :t_death_certificate/part1c
                    :t_death_certificate/part2]
   :initLocalState (fn [this props]
                     (select-keys props [:t_patient/date_death :t_death_certificate/part1a
                                         :t_death_certificate/part1b :t_death_certificate/part1b
                                         :t_death_certificate/part1c
                                         :t_death_certificate/part2]))}
  (let [state (comp/get-state this)
        disabled (nil? (:t_patient/date_death state))]
    (println "state: " state)
    (if-not (:ui/editing state)
      (div
        (if-not date_death "Alive" (str "Died on " (ui/format-date date_death)))
        (ui/ui-button {:onClick #(comp/set-state! this (assoc state :ui/editing true))} "Edit"))
      (ui/ui-modal {:title   (ui-patient-banner banner)
                    :actions [{:id :save, :role :primary :title "Save"}
                              {:id      :cancel, :title "Cancel"
                               :onClick #(comp/set-state! this {:ui/editing false})}]}
        (ui/ui-simple-form {:title "Death certificate"}
          (ui/ui-simple-form-item {:label "Date of death"}
            (ui/ui-local-date {:value (:t_patient/date_death state)}
                              {:onChange #(comp/set-state! this (assoc state :t_patient/date_death %))}))
          (ui/ui-simple-form-item {:label "Certificate"}
            (ui/ui-textfield {:label    "Part 1a" :value (:t_death_certificate/part1a state)
                              :disabled disabled}
                             {:onChange #(comp/set-state! this (assoc state :t_death_certificate/part1a %))})
            (ui/ui-textfield {:label    "Part 1b" :value (:t_death_certificate/part1b state)
                              :disabled disabled}
                             {:onChange #(comp/set-state! this (assoc state :t_death_certificate/part1b %))})
            (ui/ui-textfield {:label    "Part 1c" :value (:t_death_certificate/part1c state)
                              :disabled disabled}
                             {:onChange #(comp/set-state! this (assoc state :t_death_certificate/part1c %))})
            (ui/ui-textfield {:label    "Part 2" :value (:t_death_certificate/part2 state)
                              :disabled disabled}
                             {:onChange #(comp/set-state! this (assoc state :t_death_certificate/part2 %))})))))))


(def ui-patient-death-certificate (comp/factory PatientDeathCertificate))


(defsc EditDeathCertificate
  [this params]
  {:ident         (fn [] [:component/id :edit-death-certificate])
   :query         [:t_patient/id
                   :t_patient/patient_identifier
                   :t_patient/date_death
                   :t_death_certificate/part1a
                   :t_death_certificate/part1b
                   :t_death_certificate/part1c
                   :t_death_certificate/part2]
   :initial-state {}
   :form-fields   #{:t_patient/date_death
                    :t_death_certificate/part1a :t_death_certificate/part1b
                    :t_death_certificate/part1c :t_death_certificate/part2}}
  (dom/h1 "Edit death certificate"))

(def ui-edit-death-certificate (comp/factory EditDeathCertificate))

(defmutation edit-death-certificate
  [{:t_patient/keys [patient_identifier]}]
  (action
    [{:keys [state]}]
    (swap! state (fn [state]
                   (-> state
                       #_(fs/add-form-config* EditDeathCertificate [:t_patient/id id])
                       (targeting/integrate-ident* [:t_patient/patient_identifier patient_identifier] :replace [:component/id :edit-death-certificate :patient]))))))

(defmutation cancel-edit-death-certificate
  [_]
  (action [{:keys [state]}]
          (swap! state (fn [state]
                         (update-in state [:component/id :edit-death-certificate] dissoc :patient)))))


(defsc PatientDeathCertificate2
  [this {:t_patient/keys [id patient_identifier date_death]
         banner          :>/banner
         :ui/keys        [editing-death-certificate] :as params}]
  {:ident         :t_patient/patient_identifier
   :query         [{:>/banner (comp/get-query PatientBanner)}
                   :t_patient/id
                   :t_patient/patient_identifier
                   :t_patient/date_death
                   {:ui/editing-death-certificate (comp/get-query EditDeathCertificate)}]
   :initial-state {:ui/editing-death-certificate {}}}
  (println params)
  (let [editing (:patient editing-death-certificate)
        cancel-edit-fn #(comp/transact! this [(cancel-edit-death-certificate nil)])]
    (if-not editing
      (div
        (if-not date_death "Alive" (str "Died on " (ui/format-date date_death)))
        (ui/ui-button {:onClick #(do (println "edit clicked")
                                     (comp/transact! this [(edit-death-certificate {:t_patient/id id})]))} "Edit"))
      (ui/ui-modal
        {:title   (ui-patient-banner banner)
         :actions [{:id :save, :role :primary :title "Save"}
                   {:id :cancel, :title "Cancel" :onClick cancel-edit-fn}]
         :onClose cancel-edit-fn}
        (ui-edit-death-certificate editing-death-certificate)))))

(def ui-patient-death-certificate2 (comp/factory PatientDeathCertificate2))












(defsc ResultListItem
  [this {:t_result/keys [id date summary]
         entity-name    :t_result_type/result_entity_name
         result-name    :t_result_type/name
         result-desc    :t_result_type/description}]
  {:ident :t_result/id
   :query [:t_result/id :t_result/date :t_result/summary
           :t_result_type/result_entity_name :t_result-type/id
           :t_result_type/name :t_result_type/description]}
  (ui/ui-table-row {}
    (ui/ui-table-cell {} (ui/format-date date))
    (ui/ui-table-cell {} result-name)
    (ui/ui-table-cell {} (div :.overflow-hidden (ui/truncate summary 120)))
    (ui/ui-table-cell {} "")))

(def ui-result-list-item (comp/factory ResultListItem {:keyfn :t_result/id}))

(defsc PatientResults [this {:t_patient/keys [results]}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           {:t_patient/results (comp/get-query ResultListItem)}]}
  (comp/fragment
    (ui/ui-title {:title "Investigations"}
      (ui/ui-title-button {:title "Add result"} {:onClick #(println "Action: add result")}))
    (ui/ui-table {}
      (ui/ui-table-head {}
        (ui/ui-table-row {}
          (map #(ui/ui-table-heading {:react-key %} %) ["Date" "Investigation" "Result" ""])))
      (ui/ui-table-body {}
        (->> results
             (sort-by #(some-> % :t_result/date .getTime))
             (map ui-result-list-item))))))

(def ui-patient-results (comp/factory PatientResults))

