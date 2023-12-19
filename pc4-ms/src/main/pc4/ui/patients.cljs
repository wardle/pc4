(ns pc4.ui.patients
  (:require [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.dom.events :as evt]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [pc4.app :refer [SPA]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
            [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [clojure.string :as str]
            [pc4.ui.core :as ui]
            [com.eldrix.nhsnumber :as nhs-number]
            [pc4.rsdb]
            [pc4.ui.snomed :as snomed]
            [pc4.users]
            [taoensso.timbre :as log]
            [cljs.spec.alpha :as s])
  (:import [goog.date Date]))


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
                    :content "Relapses"}
                   {:id      :encounters
                    :content "Encounters"}
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
  (when patient_identifier
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







(defn most-recent-edss-encounter
  "From a collection of encounters, return the most recent containing an EDSS result."
  [encounters]
  (->> encounters
       (filter :t_encounter/active)
       (sort-by :t_encounter/date_time)
       (filter #(or (:t_encounter/form_edss %) (:t_encounter/form_edss_fs %)))
       reverse
       first))

(def ms-event-types
  [{:abbreviation "UK" :label "Unknown" :key :t_ms_event/site_unknown}
   {:abbreviation "UE" :label "Upper limb motor" :key :t_ms_event/site_arm_motor}
   {:abbreviation "LE" :label "Lower limb motor" :key :t_ms_event/site_leg_motor}
   {:abbreviation "SS" :label "Limb sensory" :key :t_ms_event/site_limb_sensory}
   {:abbreviation "SP" :label "Sphincter" :key :t_ms_event/site_sphincter}
   {:abbreviation "SX" :label "Sexual" :key :t_ms_event/site_sexual}
   {:abbreviation "FM" :label "Face motor" :key :t_ms_event/site_face_motor}
   {:abbreviation "FS" :label "Face sensory" :key :t_ms_event/site_face_sensory}
   {:abbreviation "OM" :label "Oculomotor" :key :t_ms_event/site_diplopia}
   {:abbreviation "VE" :label "Vestibular" :key :t_ms_event/site_vestibular}
   {:abbreviation "BB" :label "Bulbar" :key :t_ms_event/site_bulbar}
   {:abbreviation "CB" :label "Cerebellar" :key :t_ms_event/site_cerebellar}
   {:abbreviation "ON" :label "Optic neuritis" :key :t_ms_event/site_optic_nerve}
   {:abbreviation "PS" :label "Psychiatric" :key :t_ms_event/site_psychiatric}
   {:abbreviation "OT" :label "Other" :key :t_ms_event/site_other}
   {:abbreviation "MT" :label "Cognitive" :key :t_ms_event/site_cognitive}])

(def ms-event-type-help-text
  "A lookup from abbreviation to label"
  (reduce (fn [acc {:keys [abbreviation label]}] (assoc acc abbreviation label)) {} ms-event-types))

(defn make-table-cells
  [relapse]
  (reduce (fn [acc {:keys [abbreviation label key]}]
            (conj acc (if (get relapse key)
                        (ui/ui-table-cell {:react-key abbreviation :title label} abbreviation)
                        (ui/ui-table-cell {:react-key abbreviation} "")))) [] ms-event-types))

(defsc EncounterListItem
  [this {:t_encounter/keys [id date_time encounter_template form_edss form_edss_fs form_ms_relapse form_weight_height]}]
  {:ident :t_encounter/id
   :query [:t_encounter/id :t_encounter/date_time :t_encounter/active
           {:t_encounter/encounter_template [:t_encounter_template/title]}
           :t_encounter/notes
           {:t_encounter/form_edss [:t_form_edss/score]}
           {:t_encounter/form_edss_fs [:t_form_edss_fs/score]}
           {:t_encounter/form_ms_relapse [:t_form_ms_relapse/in_relapse :t_ms_disease_course/name]}
           {:t_encounter/form_weight_height [:t_form_weight_height/weight_kilogram]}]}
  (ui/ui-table-row {}
    (ui/ui-table-cell {} (ui/format-date date_time))
    (ui/ui-table-cell {} (:t_encounter_template/title encounter_template))
    (ui/ui-table-cell {} (or (:t_form_edss/score form_edss) (:t_form_edss_fs/score form_edss_fs)))
    (ui/ui-table-cell {} (:t_ms_disease_course/name form_ms_relapse))
    (ui/ui-table-cell {} (case (:t_form_ms_relapse/in_relapse form_ms_relapse) true "Yes" false "No" ""))
    (ui/ui-table-cell {} (when-let [wt (:t_form_weight_height/weight_kilogram form_weight_height)] (str wt "kg")))))

(def ui-encounter-list-item (comp/factory EncounterListItem {:keyfn :t_encounter/id}))

(defsc MostRecentEDSS [this props]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           {:t_patient/encounters [:t_encounter/active
                                   :t_encounter/date_time
                                   :t_encounter/form_edss
                                   :t_encounter/form_edss_fs]}]})

(defsc RelapseListItem
  [this {:t_ms_event/keys  [id date is_relapse site_arm_motor site_ataxia site_bulbar
                            site_cognitive site_diplopia site_face_motor
                            site_face_sensory site_leg_motor site_limb_sensory
                            site_optic_nerve site_other site_psychiatric
                            site_sexual site_sphincter site_unknown site_vestibular source impact]
         event-type-abbrev :t_ms_event_type/abbreviation
         event-type-name   :t_ms_event_type/name :as props}]
  {:ident :t_ms_event/id
   :query [:t_ms_event/id
           :t_ms_event/date :t_ms_event/date_accuracy
           :t_ms_event/is_relapse
           :t_ms_event_type/abbreviation :t_ms_event_type/name
           :t_ms_event/site_arm_motor :t_ms_event/site_ataxia :t_ms_event/site_bulbar
           :t_ms_event/site_cognitive :t_ms_event/site_diplopia :t_ms_event/site_face_motor
           :t_ms_event/site_face_sensory :t_ms_event/site_leg_motor :t_ms_event/site_limb_sensory
           :t_ms_event/site_optic_nerve :t_ms_event/site_other :t_ms_event/site_psychiatric
           :t_ms_event/site_sexual :t_ms_event/site_sphincter :t_ms_event/site_unknown
           :t_ms_event/site_vestibular
           :t_ms_event/source
           :t_ms_event/impact]}
  (ui/ui-table-row
    {}
    (ui/ui-table-cell {} (ui/format-date date))
    (ui/ui-table-cell {:title event-type-name} event-type-abbrev)
    (ui/ui-table-cell {} (str impact))
    (make-table-cells props)))





(def ui-relapse-list-item (comp/factory RelapseListItem {:keyfn :t_ms_event/id}))

(defsc SummaryMultipleSclerosis [this {:t_summary_multiple_sclerosis/keys [events]}]
  {:ident :t_summary_multiple_sclerosis/id
   :query [:t_summary_multiple_sclerosis/id
           {:t_summary_multiple_sclerosis/events (comp/get-query RelapseListItem)}]}
  (comp/fragment
    (ui/ui-title {:title "Relapses"})
    (ui/ui-table {}
      (ui/ui-table-head {}
        (ui/ui-table-row {}
          (map #(let [help-text (get ms-event-type-help-text %)]
                  (ui/ui-table-heading (cond-> {:react-key %}
                                               help-text (assoc :title (str "Site of event: " help-text))) %))
               ["Date" "Type" "Impact" "UK" "UE" "LE" "SS" "SP" "SX" "FM" "FS"
                "OM" "VE" "BB" "CB" "ON" "PS" "OT" "MT"])))
      (ui/ui-table-body {}
        (map ui-relapse-list-item
             (->> events
                  (filter :t_ms_event/is_relapse)
                  (sort-by #(some-> % :t_ms_event/date .getTime))
                  reverse))))))

(def ui-summary-multiple-sclerosis (comp/factory SummaryMultipleSclerosis))

(defsc PatientRelapses [this {:t_patient/keys [summary_multiple_sclerosis]}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           {:t_patient/summary_multiple_sclerosis (comp/get-query SummaryMultipleSclerosis)}
           #_{:>/most-recent-edss (comp/get-query MostRecentEDSS)}]}
  (ui-summary-multiple-sclerosis summary_multiple_sclerosis))

(def ui-patient-relapses (comp/factory PatientRelapses))


(defsc MedicationReasonForStopping
  [this {:t_medication_reason_for_stopping/keys [id name]}]
  {:ident :t_medication_reason_for_stopping/id
   :query [:t_medication_reason_for_stopping/id
           :t_medication_reason_for_stopping/name]})

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
                              :display-key :t_ms_diagnosis/name}
                             {:onChange #(comp/transact! this [(pc4.rsdb/save-ms-diagnosis (merge patient %))])}))

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
                                                       [(pc4.rsdb/save-pseudonymous-patient-postal-code
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
                   {:id :cancel, :title "Cancel" :onClick cancel-edit-fn}]}
        {:onClose cancel-edit-fn}
        (ui-edit-death-certificate editing-death-certificate)))))

(def ui-patient-death-certificate2 (comp/factory PatientDeathCertificate2))

(defsc PatientDemographics
  [this {:t_patient/keys   [patient_identifier date_death status encounters]
         sms               :t_patient/summary_multiple_sclerosis
         death-certificate :>/death_certificate
         :as               patient}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/patient_identifier :t_patient/id :t_patient/status
                   :t_patient/first_names :t_patient/last_name :t_patient/lsoa11
                   :t_patient/sex :t_patient/date_birth :t_patient/date_death
                   {:>/death_certificate (comp/get-query PatientDeathCertificate2)}
                   {:t_patient/encounters [:t_encounter/date_time {:t_encounter/form_edss [:t_form_edss/score]}]}
                   {:t_patient/summary_multiple_sclerosis (comp/get-query ChooseNeuroinflammatoryDiagnosis)}]
   :initial-state {:>/death_certificate {}}}
  (let [sorted-encounters (sort-by #(some-> % :t_encounter/date_time % .getTime) encounters)
        last-encounter-date (or date_death (:t_encounter/date_time (first sorted-encounters)))
        edss-encounter (->> sorted-encounters
                            (filter #(or (seq (:t_encounter/form_edss %)) (seq (:t_encounter/form_edss_fs %))))
                            first)
        most-recent-edss (or (get-in edss-encounter [:t_encounter/form_edss :t_form_edss/score])
                             (get-in edss-encounter [:t_encounter/form_edss_fs :t_form_edss_fs/score]))
        last-edss-date (:t_encounter/date_time edss-encounter)]
    (case status
      :PSEUDONYMOUS
      (dom/div :.m-4
        (ui/ui-simple-form {}
          (ui/ui-simple-form-title {:title "Neuroinflammatory disease"})
          (ui/ui-simple-form-item {:htmlFor "date-from" :label "Diagnostic criteria"}
            (ui-choose-neuroinflammatory-diagnosis sms))
          (ui/ui-simple-form-item {:htmlFor "date-to" :label (div "Most recent EDSS" (when last-edss-date (dom/span :.font-light.text-gray-500 (str " (on " (ui/format-date last-edss-date) ")"))))}
            (if most-recent-edss most-recent-edss "None recorded"))
          (ui/ui-simple-form-item {:label "LSOA (geography)"}
            (ui-inspect-edit-lsoa (select-keys patient [:t_patient/patient_identifier :t_patient/lsoa11])))
          (ui/ui-simple-form-item {:label (div "Vital status" (when last-encounter-date (dom/span :.font-light.text-gray-500 (str " (as of " (ui/format-date last-encounter-date)) ")")))}
            (ui-patient-death-certificate2 death-certificate))))
      (ui/box-error-message {:title   "Warning: patient type not yet supported"
                             :message "This form supports only pseudonymous patients."}))))

(def ui-patient-demographics (comp/factory PatientDemographics))

(defsc PatientEncounters
  [this {:t_patient/keys [patient_identifier encounters] :as props}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier
           [df/marker-table :patient-encounters]
           {:t_patient/encounters (comp/get-query EncounterListItem)}]}
  (let [load-marker (get props [df/marker-table :patient-encounters])]
    (comp/fragment
      (when (df/loading? load-marker) (ui/ui-loading-screen {:dim? false}))
      (ui/ui-title
        {:title "Encounters"})
      (ui/ui-table {}
        (ui/ui-table-head {}
          (ui/ui-table-row {}
            (ui/ui-table-heading {} "Date")
            (ui/ui-table-heading {} "Type")
            (ui/ui-table-heading {} "EDSS")
            (ui/ui-table-heading {} "Disease course")
            (ui/ui-table-heading {} "In relapse?")
            (ui/ui-table-heading {} "Weight")))
        (ui/ui-table-body {}
          (->> encounters
               (filter :t_encounter/active)
               (map ui-encounter-list-item)))))))

(def ui-patient-encounters (comp/factory PatientEncounters))


(declare EditMedication)

(defmutation edit-medication
  [{:t_medication/keys [id] :as params}]
  (action
    [{:keys [app state]}]
    (tap> {:merging-edit-medication params})
    (swap! state update-in [:component/id :edit-medication] merge params)
    (when-not (get-in @state [:component/id :edit-medication :com.eldrix.rsdb/all-medication-reasons-for-stopping])
      (df/load! app :com.eldrix.rsdb/all-medication-reasons-for-stopping EditMedication
                {:target [:component/id :edit-medication :com.eldrix.rsdb/all-medication-reasons-for-stopping]}))))

(defmutation cancel-medication-edit
  [params]
  (action
    [{:keys [state]}]
    (swap! state update-in [:component/id :edit-medication]
           dissoc :t_medication/date_to :t_medication/more_information :t_medication/medication :t_medication/date_from :t_medication/patient_fk)))

(defsc MedicationListItem
  [this {:t_medication/keys [id date_from date_to medication] :as params} {:keys [actions]}]
  {:ident :t_medication/id
   :query [:t_medication/id :t_medication/date_from :t_medication/date_to :t_medication/patient_fk :t_medication/more_information
           {:t_medication/medication [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}
  (ui/ui-table-row {}
    (ui/ui-table-cell {}
      (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
    (ui/ui-table-cell {} (ui/format-date date_from))
    (ui/ui-table-cell {} (ui/format-date date_to))
    (ui/ui-table-cell {} "")
    (ui/ui-table-cell {}
      (for [action actions, :when action]
        (ui/ui-button {:key       (:id action)
                       :disabled? (:disabled? action)
                       :onClick   #(when-let [f (:onClick action)] (f))}
                      (:label action))))))

(def ui-medication-list-item (comp/computed-factory MedicationListItem {:keyfn :t_medication/id}))


(s/def :t_medication/date_from (s/nilable #(instance? goog.date.Date %)))
(s/def :t_medication/date_to (s/nilable #(instance? goog.date.Date %)))
(s/def :t_medication/medication (s/keys :req [:info.snomed.Concept/id]))
(s/def :t_medication/more_information (s/nilable string?))
(s/def ::save-medication
  (s/keys :req [:t_medication/date_from :t_medication/date_to
                :t_medication/medication :t_medication/more_information]))

(defsc MedicationEdit
  [this {:t_medication/keys [id date_from date_to medication more_information]
         :ui/keys           [choose-medication] :as params}
   {:keys [onClose onSave onDelete]}]
  {:ident         (fn [] [:component/id :edit-medication])  ;; singleton component
   :query         [:t_medication/id :t_medication/date_from :t_medication/date_to
                   :t_medication/more_information :t_medication/patient_fk
                   {:t_medication/medication [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                   {:ui/choose-medication (comp/get-query snomed/Autocomplete)}
                   {:com.eldrix.rsdb/all-medication-reasons-for-stopping (comp/get-query MedicationReasonForStopping)}]
   :initial-state (fn [params] {:ui/choose-medication (comp/get-initial-state snomed/Autocomplete {:id :choose-medication})})}
  (tap> {:component  :edit-medication
         :params     params
         :valid?     (s/valid? ::save-medication params)
         :validation (s/explain-data ::save-medication params)})
  (ui/ui-modal
    {:actions [(when onSave {:id        ::save-action :title "Save" :role :primary
                             :disabled? (not (s/valid? ::save-medication params))
                             :onClick   onSave})
               (when onDelete {:id ::delete-action :title "Delete" :onClick onDelete :disabled? (not id)})
               (when onClose {:id ::cancel-action :title "Cancel" :onClick onClose})]}
    {:onClose onClose}
    (ui/ui-simple-form {}
      (ui/ui-simple-form-title {:title (if id "Edit medication" "Add medication")})
      (ui/ui-simple-form-item {:htmlFor "medication" :label "Medication"}
        (tap> {:choose-medication choose-medication})
        (if id
          (dom/div :.mt-2 (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
          (if (:info.snomed.Concept/id medication)
            (dom/div :.mt-2 (ui/ui-link-button {:onClick #(m/set-value! this :t_medication/medication nil)}
                                               (get-in medication [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
            (snomed/ui-autocomplete choose-medication {:autoFocus true, :constraint "<10363601000001109"
                                                       :onSave    #(m/set-value! this :t_medication/medication %)}))))
      (ui/ui-simple-form-item {:htmlFor "date-from" :label "Date from"}
        (ui/ui-local-date {:value date_from}
                          {:onChange #(m/set-value! this :t_medication/date_from %)}))
      (ui/ui-simple-form-item {:htmlFor "date-to" :label "Date to"}
        (ui/ui-local-date {:value date_to}
                          {:onChange #(m/set-value! this :t_medication/date_to %)}))
      (ui/ui-simple-form-item {:htmlFor "notes" :label "Notes"}
        (ui/ui-textarea {:id "notes" :value more_information}
                        {:onChange #(m/set-value! this :t_medication/more_information %)})))))

(def ui-medication-edit (comp/computed-factory MedicationEdit))

(def empty-medication
  {:t_medication/id               nil
   :t_medication/patient_fk       nil
   :t_medication/date_from        nil
   :t_medication/date_to          nil
   :t_medication/more_information ""})


(defsc PatientMedication
  [this {:t_patient/keys [id medications] :ui/keys [editing-medication] :as props}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/id :t_patient/patient_identifier
           {:t_patient/medications (comp/get-query MedicationListItem)}
           {[:ui/editing-medication '_] (comp/get-query MedicationEdit)}]}
  (tap> {:patient-medication medications})
  (comp/fragment
    (when (:t_medication/patient_fk editing-medication)
      (ui-medication-edit editing-medication
        {:onSave   #(let [m (select-keys editing-medication [:t_medication/patient_fk :t_medication/medication :t_medication/date_from :t_medication/date_to :t_medication/more_information])
                          m' (if-let [med-id (:t_medication/id editing-medication)] (assoc m :t_medication/id med-id) m)]
                      (println "Saving medication" m')
                      (comp/transact! this [(pc4.rsdb/save-medication m')
                                            (cancel-medication-edit nil)])
                      (df/load-field! this :t_patient/medications {}))
         :onDelete #(comp/transact! this [(pc4.rsdb/delete-medication editing-medication)
                                          (cancel-medication-edit nil)])
         :onClose  #(comp/transact! this [(cancel-medication-edit nil)])}))
    (ui/ui-title {:title "Medication"}
      (ui/ui-title-button
        {:title "Add medication"}
        {:onClick #(comp/transact! this [(pc4.ui.snomed/reset-autocomplete {:id :choose-medication})
                                         (edit-medication (assoc empty-medication :t_medication/patient_fk id))])}))
    (ui/ui-table {}
      (ui/ui-table-head {}
        (ui/ui-table-row {}
          (map #(ui/ui-table-heading {:react-key %} %) ["Treatment" "Date from" "Date to" "Reason for stopping" ""])))
      (ui/ui-table-body {}
        (->> medications
             (sort-by (juxt #(some-> % :t_medication/date_from .getTime)
                            #(get-in % [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
             reverse
             (map #(ui-medication-list-item
                     % {:actions [{:id      :edit
                                   :label   "Edit"
                                   :onClick (fn [_] (comp/transact! this [(pc4.ui.snomed/reset-autocomplete {:id :choose-medication})
                                                                          (edit-medication (merge empty-medication %))]))}]})))))))

(def ui-patient-medication (comp/factory PatientMedication))

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


(defsc AdmissionListItem
  [this {:t_episode/keys [id date_registration date_discharge]} {:keys [actions]}]
  {:ident :t_episode/id
   :query [:t_episode/id
           :t_episode/date_registration
           :t_episode/date_discharge
           {:t_episode/project [:t_project/is_admission]}]}
  (ui/ui-table-row {}
    (ui/ui-table-cell {} (ui/format-date date_registration))
    (ui/ui-table-cell {} (ui/format-date date_discharge))
    (ui/ui-table-cell {}
      (for [action actions
            :when action]
        (ui/ui-button {:key       (:id action)
                       :disabled? (:disabled? action)
                       :onClick   #(when-let [f (:onClick action)] (f))}
                      (:label action))))))


(def ui-admission-list-item (comp/computed-factory AdmissionListItem {:keyfn :t_episode/id}))


(s/def :t_episode/date_registration #(instance? goog.date.Date %))
(s/def :t_episode/date_discharge #(instance? goog.date.Date %))
(s/def ::save-admission (s/keys :req [:t_episode/date_registration :t_episode/date_discharge]
                                :opt [:t_episode/id]))

(defsc AdmissionEdit
  [this {:t_episode/keys [id date_registration date_discharge patient_fk] :as params}
   {:keys [onChange onClose onSave onDelete]}]
  (ui/ui-modal {:actions [{:id        ::save-action :title "Save" :role :primary
                           :disabled? (not (s/valid? ::save-admission params))
                           :onClick   onSave}
                          {:id ::delete-action :title "Delete" :onClick onDelete :disabled? (not id)}
                          {:id ::cancel-action :title "Cancel" :onClick onClose}]
                :onClose onClose}
    (ui/ui-simple-form {}
      (ui/ui-simple-form-title {:title (if id "Edit admission" "Add admission")})
      (ui/ui-simple-form-item {:htmlFor "date-from" :label "Date from"}
        (ui/ui-local-date {:value date_registration}
                          {:onChange #(when onChange (onChange (assoc params :t_episode/date_registration %)))}))
      (ui/ui-simple-form-item {:htmlFor "date-to" :label "Date to"}
        (ui/ui-local-date {:value date_discharge}
                          {:onChange #(when onChange (onChange (assoc params :t_episode/date_discharge %)))})))))


(def ui-admission-edit (comp/computed-factory AdmissionEdit))

(defsc PatientAdmissions
  [this {patient-pk :t_patient/id :t_patient/keys [patient_identifier episodes] :ui/keys [editing-admission]}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier :t_patient/id
           :ui/editing-admission
           {:t_patient/episodes (comp/get-query AdmissionListItem)}]}
  (if editing-admission
    (ui-admission-edit editing-admission
                       {:onChange #(m/set-value! this :ui/editing-admission %)
                        :onClose  #(m/set-value! this :ui/editing-admission nil)
                        :onDelete #(do (comp/transact! this [(pc4.rsdb/delete-admission (select-keys editing-admission [:t_episode/id :t_episode/patient_fk]))])
                                       (df/refresh! this))
                        :onSave   #(do (comp/transact! this [(pc4.rsdb/save-admission (select-keys editing-admission [:t_episode/id :t_episode/patient_fk :t_episode/date_registration :t_episode/date_discharge]))])
                                       (df/refresh! this))})

    (comp/fragment
      (ui/ui-title {:title "Admissions"}
        (ui/ui-title-button
          {:title "Add admission"}
          {:onClick #(comp/transact! this [(pc4.rsdb/create-admission {:t_episode/patient_fk patient-pk})])}))
      (ui/ui-table {}
        (ui/ui-table-head {}
          (ui/ui-table-row {}
            (map #(ui/ui-table-heading {:react-key %} %) ["Date of admission" "Date of discharge" ""])))
        (ui/ui-table-body {}
          (->> episodes
               (filter #(-> % :t_episode/project :t_project/is_admission))
               (sort-by #(some-> % :t_episode/date_registration .getTime))
               (reverse)
               (map #(ui-admission-list-item % {:actions [{:id      :edit :label "Edit"
                                                           :onClick (fn [_] (m/set-value! this :ui/editing-admission (assoc % :t_episode/patient_fk patient-pk)))}]}))))))))

(def ui-patient-admissions (comp/factory PatientAdmissions))

(defsc PatientPage
  [this {:t_patient/keys [id patient_identifier status first_names last_name date_birth sex date_death nhs_number] :as props
         current-project :ui/current-project
         banner          :>/banner
         demographics    :>/demographics
         medication      :>/medication
         relapses        :>/relapses
         encounters      :>/encounters
         results         :>/results
         admissions      :>/admissions}]
  {:ident               :t_patient/patient_identifier
   :route-segment       ["patient" :t_patient/patient_identifier]
   :query               [:t_patient/id :t_patient/patient_identifier :t_patient/status
                         :t_patient/first_names :t_patient/last_name
                         :t_patient/date_birth :t_patient/sex :t_patient/date_death :t_patient/nhs_number
                         {[:ui/current-project '_] [:t_project/id]}
                         {:>/banner (comp/get-query PatientBanner)}
                         {:>/demographics (comp/get-query PatientDemographics)}
                         {:>/medication (comp/get-query PatientMedication)}
                         {:>/relapses (comp/get-query PatientRelapses)}
                         {:>/encounters (comp/get-query PatientEncounters)}
                         {:>/results (comp/get-query PatientResults)}
                         {:>/admissions (comp/get-query PatientAdmissions)}]
   :initial-state       {:>/demographics {}}
   :will-enter          (fn [app {:t_patient/keys [patient_identifier]}]
                          (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                            (println "entering patient demographics page; patient-identifier:" patient-identifier " : " PatientPage)
                            (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                               (fn []
                                                 (df/load! app [:t_patient/patient_identifier patient-identifier] PatientPage
                                                           {:target               [:ui/current-patient]
                                                            ;:without              #{:t_patient/encounters}
                                                            :post-mutation        `dr/target-ready
                                                            :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))
   :allow-route-change? (constantly true)
   :will-leave          (fn [this props]
                          (log/info "leaving patient page; patient identifier: " (:t_patient/patient_identifier props))
                          (comp/transact! this [(pc4.users/close-patient nil)]))}
  (tap> props)
  (let [pseudonymous (= :PSEUDONYMOUS status)
        selected-page (or (comp/get-state this :selected-page) (if pseudonymous :home :medication))]
    (comp/fragment
      (ui-patient-banner
        banner
        {:onClose #(dr/change-route! this ["project" (:t_project/id current-project)])
         :content (div :.mt-4
                    (ui/flat-menu [{:id :home :title "Home" :disabled (not pseudonymous)}
                                   {:id :diagnoses :title "Diagnoses" :disabled (not pseudonymous)}
                                   {:id :medication :title "Treatment"}
                                   {:id :relapses :title "Relapses" :disabled (not pseudonymous)}
                                   {:id         :encounters
                                    :title      "Encounters"
                                    :load-field [:t_patient/patient_identifier :>/encounters] :load-marker :patient-encounters
                                    :disabled   (not pseudonymous)}
                                   {:id :results :title "Investigations" :disabled (not pseudonymous)}
                                   {:id :admissions :title "Admissions" :disabled (not pseudonymous)}]
                                  :selected-id selected-page
                                  :select-fn (fn [{:keys [id load-field load-marker]}]
                                               (when load-field (df/load-field! this load-field {:marker load-marker}))
                                               (comp/set-state! this {:selected-page id}))))})

      (dom/div :.lg:m-4.sm:m-0.border.bg-white.overflow-hidden.shadow-lg.sm:rounded-lg
        (case selected-page
          :home (ui-patient-demographics demographics)
          :diagnoses nil
          :medication (ui-patient-medication medication)
          :relapses (ui-patient-relapses relapses)
          :encounters (ui-patient-encounters encounters)
          :results (ui-patient-results results)
          :admissions (ui-patient-admissions admissions)
          (div "Page not found"))))))

(def ui-patient-page (comp/factory PatientPage))
