(ns pc4.ui.patients
  (:require [clojure.string :as str]
            [com.eldrix.nhsnumber :as nnn]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.dom :as dom :refer [div]]
            [com.fulcrologic.fulcro.algorithms.merge :as merge]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.ui.core :as ui]
            [pc4.users]
            [pc4.route :as route]))

(defn unload-related*
  [state patient-identifier k table-name]
  (let [path (conj [:t_patient/patient_identifier patient-identifier k])
        idents (get-in state path)]
    (reduce (fn [state ident]
              (let [id (second ident)]
                (-> state
                    (update table-name dissoc id)
                    (merge/remove-ident* ident path)))) state idents)))

(defn close-patient-record*
  [state patient-identifier]
  (-> state
      (unload-related* patient-identifier :t_patient/encounters :t_encounter/id)
      (unload-related* patient-identifier :t_patient/episodes :t_episode/id)
      (unload-related* patient-identifier :t_patient/diagnoses :t_diagnosis/id)
      (unload-related* patient-identifier :t_patient/medications :t_medication/id)))

(defmutation close-patient-record
  "Close a patient record. Unloads data from most important relationships such
  as encounters, episodes, diagnoses and medications for the patient specified."
  [{:keys [patient-identifier]}]
  (action
   [{:keys [state]}]
   (swap! state close-patient-record* patient-identifier)))

(declare PatientDemographics)
(declare PatientDeathCertificate)

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
                      (update-in [:t_patient/patient_identifier patient-identifier :ui/editing-demographics] not)
                      (update-in [:t_patient/patient_identifier patient-identifier :ui/editing-death-certificate] not)
                      (assoc-in [:t_patient/patient_identifier patient-identifier :ui/change-registration-data] false))))))

(defmutation edit-death-certificate
  [{:keys [patient-identifier]}]
  (action
   [{:keys [state]}]
   (swap! state (fn [st]
                  (-> st
                      (fs/add-form-config* PatientDeathCertificate [:t_patient/patient_identifier patient-identifier] {:destructive? true})
                      (assoc-in [:t_patient/patient_identifier patient-identifier :ui/editing-death-certificate] true))))))

(defsc PatientBanner*
  [this {:keys [name nhs-number gender born hospital-identifier address deceased]} {:keys [onClose]}]
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
                   (dom/span :.font-bold (nnn/format-nnn nhs-number))))
            (when hospital-identifier
              (div :.text-right.min-w-min (dom/span :.text-sm.font-thin "CRN ") (dom/span :.font-bold hospital-identifier))))
       (div :.grid.grid-cols-1 {:className (if-not deceased "bg-gray-100" "bg-red-100")}
            (div :.font-light.text-sm.tracking-tighter.text-gray-500.truncate address))
       (comp/children this)))

(def ui-patient-banner* (comp/computed-factory PatientBanner*))

(defsc PatientEpisode
  "Component only for data fetch purposes in order to return appropriate project-specific pseudonym."
  [this props]
  {:ident :t_episode/id
   :query [:t_episode/id :t_episode/project_fk :t_episode/stored_pseudonym]})

(defsc PatientBanner
  [this {:t_patient/keys [patient_identifier status nhs_number date_birth current_age sex date_death
                          title first_names last_name address episodes]
         w-lsoa-name     :wales-imd-2019-ranks/lsoa_name e-lsoa-name :england-imd-2019-ranks/lsoa_name
         current-project :ui/current-project}
   {:keys [onClose hospital-identifier] :as computed-props}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/patient_identifier :t_patient/status :t_patient/nhs_number :t_patient/sex :t_patient/current_age
                   :t_patient/title :t_patient/first_names :t_patient/last_name :t_patient/date_birth :t_patient/date_death
                   :wales-imd-2019-ranks/lsoa_name :england-imd-2019-ranks/lsoa_name
                   {:t_patient/address [:t_address/address1 :t_address/address2 :t_address/address3 :t_address/address4 :t_address/address5 :t_address/postcode]}
                   {:t_patient/episodes (comp/get-query PatientEpisode)}
                   {[:ui/current-project '_] [:t_project/id]}]
   :initial-state (fn [params]
                    {:t_patient/patient_identifier (:t_patient/patient_identifier params)
                     :t_patient/episodes           []})}
  (let [lsoa-name (or w-lsoa-name e-lsoa-name)
        project-id (:t_project/id current-project)
        pseudonym (when project-id (:t_episode/stored_pseudonym (first (filter #(= (:t_episode/project_fk %) project-id) episodes))))]
    (if (= :PSEUDONYMOUS status)                            ;; could use polymorphism to choose component here?
      (ui-patient-banner* {:name     (when sex (name sex))
                           :born     (str (ui/format-month-year date_birth) (when current_age (str " (~" current_age ")")))
                           :address  (div {} (dom/span :.mr-4 (when lsoa-name (str lsoa-name " "))) (dom/span {} pseudonym))
                           :deceased (ui/format-month-year date_death)} computed-props
                          (comp/children this))
      (let [{:t_address/keys [address1 address2 address3 address4 address5 postcode]} address]
        (ui-patient-banner* {:name       (str (str/join ", " [(when last_name (str/upper-case last_name)) first_names]) (when title (str " (" title ")")))
                             :born       (str (ui/format-date date_birth) (when current_age (str " (" current_age ")")))
                             :nhs-number nhs_number
                             :hospital-identifier hospital-identifier
                             :address    (str/join ", " (remove str/blank? [address1 address2 address3 address4 address5 postcode]))
                             :deceased   date_death} computed-props
                            (comp/children this))))))

(def ui-patient-banner (comp/computed-factory PatientBanner))

(defsc PatientMenu
  [this {:t_patient/keys [patient_identifier permissions]
         :ui/keys [current-project]}
   {:keys [selected-id sub-menu]}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier :t_patient/permissions
           {[:ui/current-project '_] [:t_project/id]}]}
  (let [{project-id :t_project/id} current-project]
    (cond
      (permissions :PATIENT_VIEW)
      (ui/ui-vertical-navigation2
       {:selected-id selected-id
        :items
        [{:id      :home
          :content "Home"
          :onClick #(route/route-to! ::route/patient-home {:patient-identifier patient_identifier})}
         {:id      :diagnoses
          :content "Diagnoses"
          :onClick #(route/route-to! ::route/patient-diagnoses {:patient-identifier patient_identifier})}
         {:id      :medications
          :content "Medication"
          :onClick #(route/route-to! ::route/patient-medications {:patient-identifier patient_identifier})}
         {:id      :relapses
          :content "Relapses"
          :onClick #(route/route-to! ::route/patient-neuroinflammatory {:patient-identifier patient_identifier})}
         {:id      :encounters
          :content "Encounters"
          :onClick #(route/route-to! ::route/patient-encounters {:patient-identifier patient_identifier})}
         {:id      :results
          :content "Results"
          :onClick #(route/route-to! ::route/patient-results {:patient-identifier patient_identifier})}
         {:id      :admissions
          :content "Admissions"
          :onClick #(route/route-to! ::route/patient-admissions {:patient-identifier patient_identifier})}]}
       (ui/ui-vertical-navigation-title {:title ""})
       (ui/ui-vertical-navigation-submenu {:items sub-menu}))
    ;; No permission -> show break glass and a limited menu
      :else
      (ui/ui-vertical-navigation2
       {:selected-id :break-glass
        :items       [{:id      :break-glass
                       :content "No access"}]}))))

(def ui-patient-menu (comp/computed-factory PatientMenu))

(defsc SuggestedRegistration [this params]
  {:ident :t_project/id
   :query [:t_project/id :t_project/title]})

(def ui-suggested-registration (comp/factory SuggestedRegistration {:keyfn :t_project/id}))

(defsc AdministratorUser [this params]
  {:ident :t_user/id
   :query [:t_user/id :t_user/full_name]})

(defsc PatientBreakGlass
  [this {:t_patient/keys [patient_identifier suggested_registrations administrators] :as patient
         :ui/keys        [administrator project explanation]}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/id :t_patient/patient_identifier :t_patient/permissions
           {:t_patient/suggested_registrations (comp/get-query SuggestedRegistration)}
           {:t_patient/administrators (comp/get-query AdministratorUser)}
           :ui/administrator :ui/project :ui/explanation]}
  (div :.pl-2.pr-2
       (ui/ui-panel {:classes ["bg-red-50" "text-red-800"]}
                    (dom/p :.font-bold.text-lg.min-w-min "You do not have permission to view this patient record.")
                    (dom/p "This patient is not registered to any of your registered projects.")
                    (dom/p "You may only view patient records if you are registered to one of this patient's projects or you
               obtain emergency 'break-glass' access for clinical reasons."))
       (div :.grid.grid-cols-1.md:grid-cols-2.md:gap-4.m-4
            (when (seq suggested_registrations)
              (ui/ui-active-panel
               {:title    "Register a patient to one of your projects"
                :subtitle "This is most suitable when this patient is under the care of a specific service
                           or part of a specific project given a relationship between you, the project or service, and
                           the patient. Once you register a patient to a service, you may subsequently
                           discharge that patient should ongoing registration not be required."
                :classes  (when administrator ["opacity-50"])}
               (div :.space-y-2
                    (ui/ui-select-popup-button
                     {:options             suggested_registrations
                      :no-selection-string "« choose a project to which patient should be registered »"
                      :display-key         :t_project/title
                      :id-key              :t_project/id
                      :value               project
                      :onChange            #(do (m/set-value! this :ui/project %)
                                                (when % (m/set-value! this :ui/administrator nil)
                                                      (m/set-value! this :ui/explanation nil)))})
                    (ui/ui-active-panel-button {:title    "Register »"
                                                :disabled (not project)
                                                :onClick  #(do
                                                             (println "register" {:patient (select-keys patient [:t_patient/id]) :project-id (:t_project/id project)})
                                                             (comp/transact! this [(list 'pc4.rsdb/register-patient-to-project {:patient    (select-keys patient [:t_patient/id :t_patient/patient_identifier])
                                                                                                                                :project-id (:t_project/id project)})]))}))))
            (ui/ui-active-panel
             {:title    "Get emergency access via 'break-glass'"
              :subtitle "This is most suitable when you are a clinician and need access in an emergency for
                           clinical reasons, you have a direct care relationship with the patient, but you only need temporary access.
                           Break-glass events are logged and checked, and last only for the session."
              :classes  (when project ["opacity-50"])}
             (div :.space-y-2
                  (ui/ui-select-popup-button
                   {:no-selection-string "« choose administrator to be notified »"
                    :options             administrators
                    :display-key         :t_user/full_name
                    :id-key              :t_user/id
                    :value               administrator
                    :onChange            #(do (m/set-value! this :ui/administrator %)
                                              (when % (m/set-value! this :ui/project nil)))})
                  (ui/ui-textarea {:label    "Explain why break-glass access is needed. "
                                   :value    explanation
                                   :onChange #(m/set-value! this :ui/explanation %)})
                  (ui/ui-active-panel-button {:title    "Break-glass »"
                                              :disabled (or (str/blank? explanation) (not administrator))
                                              :onClick  #(comp/transact! this [(list 'pc4.rsdb/break-glass {:patient-identifier patient_identifier})])}))))))

(def ui-patient-break-glass (comp/factory PatientBreakGlass))

(defsc Layout
  [this {:t_patient/keys [id patient_identifier permissions] :as props
         is-break-glass  :t_patient/break_glass :>/keys [banner menu break-glass]}
   {:keys [selected-id sub-menu]}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/id :t_patient/patient_identifier :t_patient/permissions
           :t_patient/break_glass
           {:>/banner (comp/get-query PatientBanner)}
           {:>/menu (comp/get-query PatientMenu)}
           {:>/break-glass (comp/get-query PatientBreakGlass)}
           [df/marker-table :patient]]}
  (when (and id patient_identifier)
    (comp/fragment
     (ui-patient-banner banner {}                          ;; always show the banner
                        (when is-break-glass
                          (ui/box-error-message :message "You have temporary access to this record.")))
     (if (permissions :PATIENT_VIEW)
       (div :.grid.grid-cols-1.md:grid-cols-6.gap-x-4.relative.pr-2
            (div :.col-span-1.p-2
                 (ui-patient-menu menu {:selected-id selected-id :sub-menu sub-menu}))
            (div :.col-span-1.md:col-span-5.pt-2
                 (comp/children this)
                 (let [marker (get props [df/marker-table :patient])]
                   (cond
                     (df/loading? marker) (ui/ui-loading-screen {:dim? false})
                     (df/failed? marker)  "Loading failed. Please retry"))))
       (ui-patient-break-glass break-glass)))))

(def ui-layout (comp/computed-factory Layout))

(defsc EditDeathCertificate
  [_ _]
  {:ident       :t_death_certificate/id
   :query       [:t_death_certificate/id
                 :t_death_certificate/part1a
                 :t_death_certificate/part1b
                 :t_death_certificate/part1c
                 :t_death_certificate/part2]
   :form-fields #{:t_death_certificate/part1a :t_death_certificate/part1b
                  :t_death_certificate/part1c :t_death_certificate/part2}}
  (comp/fragment
   (ui/ui-simple-form-title {:title "Death certificate"})
   (ui/ui-simple-form-item {:label "Part 1a"})
   (ui/ui-simple-form-item {:label "Part 1b"})
   (ui/ui-simple-form-item {:label "Part 1c"})
   (ui/ui-simple-form-item {:label "Part 2"})))

(def ui-edit-death-certificate (comp/factory EditDeathCertificate))

(defsc EditPatientDeathCertificate
  [this {:t_patient/keys [patient_identifier date_birth date_death death_certificate]}]
  {:ident       :t_patient/patient_identifier
   :query       [:t_patient/patient_identifier
                 :t_patient/date_birth
                 :t_patient/date_death
                 {:t_patient/death_certificate (comp/get-query EditDeathCertificate)}]
   :form-fields #{:t_patient/date_death}}
  (ui/ui-modal {}
               (ui/ui-simple-form {}
                                  (ui/ui-simple-form-item {:label "Date of death"}
                                                          (ui/ui-local-date
                                                           {:value    date_death
                                                            :min-date date_birth
                                                            :max-date (goog.date.Date.)
                                                            :onChange #(m/set-value! this :t_patient/date_death %)}))
                                  (ui-edit-death-certificate death_certificate))))

(def ui-edit-patient-death-certificate (comp/factory EditPatientDeathCertificate))

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

(defsc EditPseudonymousPatientDemographics
  [this {:t_patient/keys           [id patient_identifier sex date_birth date_death]
         :t_death_certificate/keys [part1a part1b part1c part2]}]
  (let [parent (comp/get-parent this)]
    (ui/ui-simple-form
     {}
     (ui/ui-simple-form-title
      {:title "Edit patient demographics (pseudonymous registration)"})
     (div :.text-sm.font-medium.text-gray-400
          "This patient was registered using NHS number, date of birth and gender, with those details used
to generate a pseudonym. If these registration details were entered incorrectly, some users have
permission to edit that registration information. Most users can only enter information about death here
and cannot change registration data.")
     (ui/ui-simple-form-item
      {:label "Gender"} (div :.pt-2 (name sex)))
     (ui/ui-simple-form-item
      {:label "Date of birth"} (div :.pt-2 (ui/format-month-year date_birth)))
     (ui/ui-simple-form-item
      {:label "Date of death"}
      (ui/ui-local-date {:value date_death, :min-date date_birth, :max-date (goog.date.Date.)
                         :onChange #(m/set-value! parent :t_patient/date_death %)}))
     (when date_death
       (comp/fragment
        (ui/ui-simple-form-title
         {:title "Death certificate"})
        (ui/ui-simple-form-item
         {:label "Part 1a"} (ui/ui-textfield {:value part1a, :onChange #(m/set-value! parent :t_death_certificate/part1a %)}))
        (ui/ui-simple-form-item
         {:label "Part 1b"} (ui/ui-textfield {:value part1b, :onChange #(m/set-value! parent :t_death_certificate/part1b %)}))
        (ui/ui-simple-form-item
         {:label "Part 1c"} (ui/ui-textfield {:value part1c, :onChange #(m/set-value! parent :t_death_certificate/part1c %)}))
        (ui/ui-simple-form-item
         {:label "Part 2"} (ui/ui-textfield {:value part2, :onChange #(m/set-value! parent :t_death_certificate/part2 %)})))))))

(def ui-edit-pseudonymous-patient-demographics (comp/factory EditPseudonymousPatientDemographics))

(defsc EditLocalPatientDemographics
  [this props]
  (ui/ui-simple-form
   {}
   (ui/ui-simple-form-title
    {:title "Edit patient demographics"})
   (ui/box-error-message
    {:title   "Not yet implemented:"
     :message "Editing non-pseudonymous patient demographic data is not currently supported."})))

(def ui-edit-local-patient-demographics (comp/factory EditLocalPatientDemographics))

(defsc EditPseudonymousPatientRegistration
  [this {:t_patient/keys [sex date_birth nhs_number]}]
  (ui/ui-simple-form
   {}
   (ui/ui-simple-form-title
    {:title "Edit patient demographics (pseudonymous registration)"})
   (div :.text-red-400.space-y-2
        (dom/p :.font-bold.italic
               "Danger: You are changing the registration data used for this patient record. ")
        (dom/p :.text-sm.font-medium
               "This changes the date of birth and gender for this record, and will therefore
                change the project-specific pseudonym used to access the record. This is only
                possible for privileged users."))
   (ui/ui-simple-form-item {:label "NHS number"}
                           (div :.pt-2.text-gray-500.italic (nnn/format-nnn nhs_number)))
   (ui/ui-simple-form-item {:label "Gender"}
                           (ui/ui-select-popup-button
                            {:id       "sex" :value sex
                             :options  [:MALE :FEMALE] :display-key name
                             :onChange #(m/set-value! (comp/get-parent this) :t_patient/sex %)}))
   (ui/ui-simple-form-item {:label "Date of birth"}
                           (ui/ui-local-date
                            {:value    date_birth
                             :min-date (goog.date.Date. 1900 1 1)
                             :max-date (goog.date.Date.)
                             :onChange #(m/set-value! (comp/get-parent this) :t_patient/date_birth %)}))))

(def ui-edit-pseudonymous-patient-registration (comp/factory EditPseudonymousPatientRegistration))

(defsc PatientDemographics
  [this {:t_patient/keys [id patient_identifier status sex title first_names
                          last_name nhs_number date_birth date_death current_age
                          authoritative_demographics address permissions] :as patient
         :t_death_certificate/keys [part1a part1b part1c part2]
         :>/keys [layout]
         :ui/keys [editing-demographics change-registration-data]}]
  {:ident         :t_patient/patient_identifier
   :query         [:t_patient/id
                   :t_patient/patient_identifier :t_patient/status
                   :t_patient/authoritative_demographics
                   :t_patient/title :t_patient/first_names :t_patient/last_name :t_patient/sex
                   :t_patient/nhs_number :t_patient/date_birth :t_patient/date_death :t_patient/current_age
                   :t_death_certificate/part1a :t_death_certificate/part1b
                   :t_death_certificate/part1c :t_death_certificate/part2
                   :t_patient/lsoa11 :t_patient/permissions
                   {:t_patient/address [:t_address/address1 :t_address/address2 :t_address/address3 :t_address/address4 :t_address/postcode]}
                   {:>/layout (comp/get-query Layout)}
                   :ui/editing-demographics
                   :ui/change-registration-data
                   :ui/editing-death-certificate
                   fs/form-config-join]
   :route-segment ["pt" :t_patient/patient_identifier "home"]
   :form-fields   #{:t_patient/title
                    :t_patient/sex
                    :t_patient/first_names
                    :t_patient/last_name
                    :t_patient/date_birth
                    :t_patient/date_death
                    :t_death_certificate/part1a :t_death_certificate/part1b
                    :t_death_certificate/part1c :t_death_certificate/part2}
   :will-enter    (fn [app {:t_patient/keys [patient_identifier] :as route-params}]
                    (when-let [patient-identifier (some-> patient_identifier (js/parseInt))]
                      (dr/route-deferred [:t_patient/patient_identifier patient-identifier]
                                         (fn []
                                           (df/load! app [:t_patient/patient_identifier patient-identifier] PatientDemographics
                                                     {:target               [:ui/current-patient]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_patient/patient_identifier patient-identifier]}})))))}

  (let [;; start editing by opening modal dialog
        do-edit
        #(comp/transact! this [(edit-demographics {:patient-identifier patient_identifier})])
        ;; save patient demographic data - TODO: rename pc4.rsdb/notify-death to save-demographics instead
        do-save-dod
        #(do (m/set-value! this :ui/change-registration-data false)
             (comp/transact! this [(list 'pc4.rsdb/notify-death
                                         {:t_patient/patient_identifier patient_identifier
                                          :t_patient/date_death         date_death
                                          :t_death_certificate/part1a   part1a
                                          :t_death_certificate/part1b   part1b
                                          :t_death_certificate/part1c   part1c
                                          :t_death_certificate/part2    part2})]))
        ;; save pseudonymous patient registration data
        do-save-reg
        #(comp/transact! this [(list 'pc4.rsdb/change-pseudonymous-registration
                                     (select-keys patient [:t_patient/id :t_patient/patient_identifier :t_patient/date_birth
                                                           :t_patient/date_death :t_patient/sex :t_patient/nhs_number]))])
        ;; start editing pseudonymous patient registration
        do-change-reg
        #(do (m/set-value! this :ui/change-registration-data true)
             (m/set-value! this :t_patient/date_birth nil))
        ;; cancel editing and close modal dialog
        do-cancel-edit
        #(do (m/set-value! this :ui/change-registration-data false)
             (comp/transact! this [(cancel-edit-demographics {:patient-identifier patient_identifier})]))]
    (ui-layout
     layout
     {:selected-id :home
      :sub-menu    [#_{:id      ::view-episodes
                       :onClick #(println "view episodes")
                       :content "View episodes"}
                    (when (permissions :PATIENT_EDIT)
                      {:id      ::edit
                       :onClick do-edit
                       :content "Edit demographics..."})]}
     (when editing-demographics
       (ui/ui-modal
        {:actions
         [(when (and (= :LOCAL authoritative_demographics) (not change-registration-data))
            {:id ::save :title "Save" :role :primary :onClick do-save-dod})
          (when (and (= :LOCAL authoritative_demographics) change-registration-data)
            {:id ::save-reg :title "Save" :role :primary :onClick do-save-reg :disabled? (not date_birth)})
          (when (and (= :PSEUDONYMOUS status) (not change-registration-data))
            {:id ::change :title "Change registration details..." :onClick do-change-reg :disabled? (not (permissions :PATIENT_CHANGE_PSEUDONYMOUS_DATA))})
          {:id ::cancel :title "Cancel" :onClick do-cancel-edit}]
         :onClose do-cancel-edit}
        (cond
          (and (= :PSEUDONYMOUS status) (not change-registration-data))
          (ui-edit-pseudonymous-patient-demographics patient)
          (and (= :PSEUDONYMOUS status) change-registration-data)
          (ui-edit-pseudonymous-patient-registration patient)
          (= :LOCAL authoritative_demographics)
          (ui-edit-local-patient-demographics patient)
          (= :CAVUHB authoritative_demographics)
          (ui/ui-simple-form
           {}
           (ui/ui-simple-form-title {:title "Patient demographics managed by CAV PMS"})
           (div :.text-sm.font-medium.text-gray-400
                "This patient is managed by Cardiff and Vale patient management system (PMS) and so you cannot
                   change demographics from here."))
          (= :EMPI authoritative_demographics)
          (ui/ui-simple-form
           {}
           (ui/ui-simple-form-title {:title "Patient demographics managed by NHS Wales' eMPI"})
           (div :.text-sm.font-medium.text-gray-400
                "This patient is managed by the NHS Wales enterprise master patient index (eMPI) and so you cannot
                   change demographics from here. You will need to update using another application such as your local 
                   patient administration system (PAS).")))))
     (ui/ui-two-column-card
      {:title "Demographics"
       :items [{:title "First names" :content first_names}
               {:title "Last name" :content last_name}
               {:title "Title" :content title}
               {:title "NHS number" :content (nnn/format-nnn nhs_number)}
               {:title "Date of birth" :content (if (= :PSEUDONYMOUS status) (ui/format-month-year date_birth) (ui/format-date date_birth))}
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
          {:title "Postal code" :content (:t_address/postcode address)}])}))))














