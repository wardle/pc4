(ns pc4.ui.patients
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.nhsnumber :as nhs-number]
            [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
            [pc4.ui.core :as ui]
            [pc4.ui.snomed :as snomed]
            [pc4.users]
            [taoensso.timbre :as log]))

(declare PatientDemographics)

(defmutation edit-demographics
  [{:keys [patient-identifier]}]
  (action
    [{:keys [state]}]
    (swap! state (fn [st]
                   (-> st
                       (fs/add-form-config* PatientDemographics [:t_patient/patient_identifier patient-identifier] {:destructive? true})
                       (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-demographics] true))))))
(defmutation cancel-edit-demographics
  [{:keys [patient-identifier]}]
  (action
    [{:keys [state]}]
    (swap! state (fn [st]
                   (-> st
                       (fs/pristine->entity* [:t_patient/patient_identifier patient-identifier])
                       (update-in [:t_patient/patient_identifier patient-identifier :ui/editing-demographics] not))))))

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

(defsc PatientMenu
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
                   {:id      :results
                    :content "Investigations"
                    :onClick #(dr/change-route! this ["pt" patient_identifier "results"])}
                   {:id      :admissions
                    :content "Admissions"
                    :onClick #(dr/change-route! this ["pt" patient_identifier "admissions"])}]
     :sub-menu    sub-menu}))

(def ui-patient-menu (comp/computed-factory PatientMenu))

(defsc Layout [this {:keys [banner menu]}]
  (comp/fragment
    banner
    (div :.grid.grid-cols-1.md:grid-cols-6.gap-x-4.relative.pr-2
      (div :.col-span-1.p-2 menu)
      (div :.col-span-1.md:col-span-5.pt-2
        (comp/children this)))))

(def ui-layout (comp/factory Layout))

(defsc EditDeathCertificate
  [this params]
  {:ident       :t_death_certificate/id
   :query       [:t_death_certificate/id
                 :t_death_certificate/part1a
                 :t_death_certificate/part1b
                 :t_death_certificate/part1c
                 :t_death_certificate/part2]
   :form-fields #{:t_death_certificate/part1a :t_death_certificate/part1b
                  :t_death_certificate/part1c :t_death_certificate/part2}}
  (ui/ui-modal {}
    (dom/h1 "Edit death certificate")))

(def ui-edit-death-certificate (comp/factory EditDeathCertificate))


(defsc InspectEditLsoa
  [this {:t_patient/keys [patient_identifier lsoa11]}]
  (let [editing (comp/get-state this :ui/editing)
        postcode (comp/get-state this :ui/postcode)]
    (if-not editing
      (ui/ui-link-button {:onClick #(do (comp/set-state! this {:ui/editing true :ui/postcode ""}))}
                         (or lsoa11 "Not yet set"))
      (div :.space-y-6
        (ui/ui-textfield {:label    "Enter postal code" :value postcode
                          :onChange #(comp/set-state! this {:ui/postcode %})})
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


(defsc PatientDemographics
  [this {:t_patient/keys [id patient_identifier status sex title first_names last_name nhs_number date_birth date_death current_age address] :as patient
         :>/keys         [banner] :ui/keys [editing-demographics editing-death-certificate]}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/id
                   :t_patient/patient_identifier :t_patient/status
                   :t_patient/title :t_patient/first_names :t_patient/last_name :t_patient/sex
                   :t_patient/nhs_number :t_patient/date_birth :t_patient/date_death :t_patient/current_age
                   {:t_patient/death_certificate (comp/get-query EditDeathCertificate)}
                   :t_patient/lsoa11
                   {:t_patient/address [:t_address/address1 :t_address/address2 :t_address/address3 :t_address/address4 :t_address/postcode]}
                   {:>/banner (comp/get-query PatientBanner)}
                   :ui/editing-demographics
                   :ui/editing-death-certificate
                   fs/form-config-join]
   :route-segment ["pt" :t_patient/patient_identifier "home"]
   :form-fields   #{:t_patient/title
                    :t_patient/first_names
                    :t_patient/last_name
                    :t_patient/date_birth
                    :t_patient/date_death}
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientDemographics
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))}

  (let [do-edit #(comp/transact! this [(edit-demographics {:patient-identifier patient_identifier})])
        do-cancel-edit #(comp/transact! this [(cancel-edit-demographics {:patient-identifier patient_identifier})])
        do-save #(comp/transact! this [(list 'pc4.rsdb/set-date-death {:t_patient/patient_identifier patient_identifier
                                                                       :t_patient/date_death         date_death})])]
    (when (and id patient_identifier)
      (ui-layout
        {:banner (ui-patient-banner banner)
         :menu   (ui-patient-menu patient
                   {:selected-id :home
                    :sub-menu    {:items [{:id      ::edit
                                           :content (ui/ui-menu-button {:onClick do-edit} "Edit demographics")}
                                          (when-not (:t_death_certificate/id editing-death-certificate)
                                            {:id      ::add-death-certificate
                                             :content (ui/ui-menu-button {:onClick #(println "death certificate")}
                                                                         "Add death certificate")})]}})}
        (when editing-demographics
          ;; at the moment, this only supports pseudonymous patients
          (ui/ui-modal
            {:actions [{:id ::save :title "Save" :role :primary :onClick do-save}
                       {:id ::cancel :title "Cancel" :onClick do-cancel-edit}]
             :onClose do-cancel-edit}
            (ui/ui-simple-form {}
              (ui/ui-simple-form-item {:label "Gender"}
                (div :.pt-2 (name sex)))
              (ui/ui-simple-form-item {:label "Date of birth"}
                (div :.pt-2 (ui/format-month-year date_birth)))
              (ui/ui-simple-form-item {:label "Date of death"}
                (ui/ui-local-date
                  {:value    date_death
                   :min-date date_birth
                   :max-date (goog.date.Date.)
                   :onChange #(m/set-value! this :t_patient/date_death %)})))))
        (when editing-death-certificate
          (ui-edit-death-certificate editing-death-certificate))
        (ui/ui-two-column-card
          {:title "Demographics"
           :items [{:title "First names" :content first_names}
                   {:title "Last name" :content last_name}
                   {:title "Title" :content title}
                   {:title "NHS number" :content (nhs-number/format-nnn nhs_number)}
                   {:title "Date of birth" :content (ui/format-date date_birth)}
                   (if date_death {:title "Date of death" :content (ui/format-date date_death)}
                                  {:title "Current age" :content current_age})]})
        (ui/ui-two-column-card
          {:title "Current address"
           :items
           (if (= :PSEUDONYMOUS status)
             [{:title "LSOA code" :content (ui-inspect-edit-lsoa patient)}]
             [{:title "Address1" :content (:t_address/address1 address)}
              {:title "Address2" :content (:t_address/address2 address)}
              {:title "Address3" :content (:t_address/address3 address)}
              {:title "Address4" :content (:t_address/address4 address)}
              {:title "Postal code" :content (:t_address/postcode address)}])})))))














