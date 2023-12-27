(ns pc4.ui.projects
  (:require [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [com.eldrix.nhsnumber :as nnn]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.dom.events :as evt]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [pc4.app :refer [SPA]]
            [pc4.ui.core :as ui]
            [pc4.ui.patients]
            [pc4.rsdb]
            [taoensso.timbre :as log])

  (:import [goog.date Date]))

(defsc PatientSearchByPseudonym
  [this {project-id :t_project/id
         patient    :ui/search-patient-pseudonymous}]
  {:ident                (fn [] [:component/id :patient-search-by-pseudonym])
   :query                [:t_project/id
                          {[:ui/search-patient-pseudonymous '_] (comp/get-query pc4.ui.patients/PatientBanner)}]
   :initial-state        {:t_project/id :param/id}
   :componentWillUnmount #(comp/transact! @SPA [(pc4.rsdb/search-patient-by-pseudonym {})])}
  (div
    :.bg-white.overflow-hidden.shadow.sm:rounded-lg
    (div
      :.px-4.py-6.sm:p-6
      (dom/form
        :.divide-y.divide-gray-200 {:onSubmit evt/prevent-default!}
        (div :.divide-y.divide-gray-200.sm:space-y-5
          (div
            (dom/h3 :.text-lg.leading-6.font-medium.text-gray-900 "Search by pseudonymous identifier"
                    (p :.max-w-2xl.text-sm.text-gray-500 "Enter a project-specific pseudonym, or choose register to search by patient identifiable information."))
            (div :.mt-4
              (dom/label :.sr-only {:htmlFor "pseudonym"} "Pseudonym")
              (dom/input :.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md.pl-5.py-2
                {:type      "text" :placeholder "Start typing pseudonym"
                 :autoFocus true
                 :value     (or (comp/get-state this :s) "")
                 :onKeyDown #(when (and patient (evt/enter-key? %))
                               (dr/change-route! this ["pt" (:t_patient/patient_identifier patient) "home"]))
                 :onChange  #(let [s (evt/target-value %)]
                               (comp/set-state! this {:s s})
                               (when (>= (count s) 3)
                                 (comp/transact! this [(pc4.rsdb/search-patient-by-pseudonym {:project-id project-id :pseudonym s})])))}))
            (when (:t_patient/patient_identifier patient)
              (div
                (pc4.ui.patients/ui-patient-banner patient)
                (ui/ui-submit-button {:label   "View patient record »"
                                      :onClick #(dr/change-route! this ["pt" (:t_patient/patient_identifier patient) "home"])})))))))))

(def ui-patient-search-by-pseudonym (comp/factory PatientSearchByPseudonym))


(defn clear-register-pseudonymous-form*
  [state-map]
  (-> state-map
      (fs/add-form-config* (comp/registry-key->class ::RegisterByPseudonym)
                           [:component/id :register-pseudonymous-patient]
                           {:destructive? true})
      (update-in [:component/id :register-pseudonymous-patient]
                 #(-> %
                      (dissoc :ui/error)
                      (assoc :ui/nhs-number "" :ui/sex nil :ui/date-birth nil)))))


(defmutation clear-register-pseudonymous-form [_]
  (action [{:keys [state]}]
          (swap! state clear-register-pseudonymous-form*)))


(defn clear-register-patient-form*
  [state-map]
  (-> state-map
      (fs/add-form-config* (comp/registry-key->class ::RegisterByNnn)
                           [:component/id :register-by-nnn]
                           {:destructive? true})
      (update-in [:component/id :register-by-nnn]
                 #(-> %
                      (dissoc :ui/error)
                      (assoc :ui/nhs-number "")))))


(defmutation clear-register-patient-form [_]
  (action [{:keys [state]}]
          (swap! state clear-register-patient-form*)))




(s/def :ui/nhs-number #(nnn/valid? (nnn/normalise %)))
(s/def :ui/date-birth (s/and #(instance? Date %)
                             #(pos-int? (Date/compare % (Date. 1900 1 1)))
                             #(nat-int? (Date/compare (Date.) %))))
(s/def :ui/sex #{:MALE :FEMALE})



(defsc RegisterByNnn
  [this {project-id :t_project/id
         :ui/keys   [nhs-number error] :as props}]
  {:ident                (fn [] [:component/id :register-by-nnn])
   :query                [:t_project/id
                          :ui/nhs-number :ui/date-birth :ui/sex :ui/error
                          fs/form-config-join]
   :initial-state        {}
   :form-fields          #{:ui/nhs-number}
   :componentDidMount    (fn [this] (comp/transact! this [(clear-register-patient-form nil)]))
   :componentWillUnmount (fn [this] (comp/transact! this [(clear-register-patient-form nil)]))}
  (let [do-register (fn [] (do (println "Attempting to register" props)
                               (comp/transact! this [(pc4.rsdb/register-patient
                                                       {:project-id project-id, :nhs-number nhs-number})])))]
    (div :.space-y-6
      (div :.bg-white.shadow.px-4.py-5.sm:rounded-lg.sm:p-6
        (div :.md:grid.md:grid-cols-3.md:gap-6
          (div :.md:col-span-1.pr-6
            (dom/h3 :.text-lg.font-medium.leading-6.text-gray-900 "Register a patient")
            (div :.mt-1.mr-12.text-sm.text-gray-500)
            (p "Please enter patient details.")
            (p :.mt-4 "This is safe to use even if patient already registered."))
          (div :.mt-5.md:mt-0.md:col-span-2.space-y-4
            (dom/form {:onSubmit #(do (evt/prevent-default! %) (do-register))})
            (ui/ui-textfield {:id "nnn" :value nhs-number :label "NHS Number:" :placeholder "Enter NHS number" :auto-focus true}
                             {:onChange   (fn [nnn]
                                            (when (= 10 (count (nnn/normalise nnn)))
                                              (comp/transact! this [(fs/mark-complete! {:field :ui/nhs-number})]))
                                            (m/set-string!! this :ui/nhs-number :value nnn))
                              :onBlur     #(comp/transact! this [(fs/mark-complete! {:field :ui/nhs-number})])
                              :onEnterKey do-register})
            (when (fs/invalid-spec? props :ui/nhs-number)
              (ui/box-error-message {:message "Invalid NHS number"}))
            (when error
              (div (ui/box-error-message {:message error}))))))

      (div :.flex.justify-end.mr-8
        (ui/ui-submit-button {:label   "Search or register patient »" :disabled? (not (fs/valid-spec? props))
                              :onClick do-register})))))


(def ui-register-by-nnn (comp/factory RegisterByNnn))

(defsc RegisterByPseudonym
  [this {project-id :t_project/id
         :ui/keys   [nhs-number date-birth sex error] :as props}]
  {:ident                (fn [] [:component/id :register-pseudonymous-patient])
   :query                [:t_project/id
                          :ui/nhs-number :ui/date-birth :ui/sex :ui/error
                          fs/form-config-join]
   :initial-state        {}
   :form-fields          #{:ui/nhs-number :ui/date-birth :ui/sex}
   :componentDidMount    (fn [this] (comp/transact! this [(clear-register-pseudonymous-form nil)]))
   :componentWillUnmount (fn [this] (comp/transact! this [(clear-register-pseudonymous-form nil)]))}
  (let [do-register (fn [] (do (println "Attempting to register" props)
                               (comp/transact! this [(pc4.rsdb/register-patient-by-pseudonym {:project-id project-id
                                                                                              :nhs-number nhs-number
                                                                                              :date-birth date-birth
                                                                                              :sex        sex})])))]
    (div :.space-y-6
      (div :.bg-white.shadow.px-4.py-5.sm:rounded-lg.sm:p-6
        (div :.md:grid.md:grid-cols-3.md:gap-6
          (div :.md:col-span-1.pr-6
            (dom/h3 :.text-lg.font-medium.leading-6.text-gray-900 "Register a patient")
            (div :.mt-1.mr-12.text-sm.text-gray-500)
            (p "Please enter patient details.")
            (p :.mt-4 "This is safe to use even if patient already registered.")
            (p :.mt-4 "Patient identifiable information is not stored but simply used to generate a pseudonym."))
          (div :.mt-5.md:mt-0.md:col-span-2.space-y-4
            (dom/form {:onSubmit #(do (evt/prevent-default! %) (do-register))})
            (ui/ui-textfield {:id "nnn" :value nhs-number :label "NHS Number:" :placeholder "Enter NHS number" :auto-focus true}
                             {:onChange   #(m/set-string!! this :ui/nhs-number :value %)
                              :onBlur     #(comp/transact! this [(fs/mark-complete! {:field :ui/nhs-number})])
                              :onEnterKey do-register})
            (when (fs/invalid-spec? props :ui/nhs-number)
              (ui/box-error-message {:message "Invalid NHS number"}))
            (ui/ui-local-date {:id       "date-birth" :value date-birth :label "Date of birth:"
                               :min-date (Date. 1900 1 1) :max-date (Date.)}
                              {:onChange   #(m/set-value!! this :ui/date-birth %)
                               :onBlur     #(comp/transact! this [(fs/mark-complete! {:field :ui/date-birth})])
                               :onEnterKey do-register})
            (when (fs/invalid-spec? props :ui/date-birth)
              (ui/box-error-message {:message "Invalid date of birth"}))
            (ui/ui-select-popup-button {:id         "sex" :value sex :label "Sex" :no-selection-string "- Choose -"
                                        :options    [:MALE :FEMALE] :display-key name
                                        :onChange   #(do (m/set-value!! this :ui/sex %)
                                                         (comp/transact! this [(fs/mark-complete! {:field :ui/sex})]))
                                        :onEnterKey do-register})
            (when (fs/invalid-spec? props :ui/sex)
              (ui/box-error-message {:message "Invalid sex"}))
            (when error
              (div (ui/box-error-message {:message error}))))))
      (div :.flex.justify-end.mr-8
        (ui/ui-submit-button {:label   "Search or register patient »" :disabled? (not (fs/valid-spec? props))
                              :onClick do-register})))))


(def ui-register-by-pseudonym (comp/factory RegisterByPseudonym))


(defsc ProjectUser
  [this {:t_user/keys [id first_names last_name title custom_job_title job_title_name email]}]
  {:ident         :t_user/id
   :query         [:t_user/id
                   :t_user/first_names :t_user/last_name
                   :t_user/title
                   :t_user/custom_job_title
                   :t_user/job_title_name :t_user/email]
   :initial-state {}}
  (tr
    (td :.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900 (str/join " " [title first_names last_name]))
    (td :.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (or custom_job_title job_title_name))
    (td :.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 email)))

(def ui-project-user (comp/factory ProjectUser {:keyfn :t_user/id}))

(defsc ProjectUsers
  [this {:t_project/keys [users] :as props}]
  {:ident         :t_project/id
   :query         [:t_project/id
                   {:t_project/users (comp/get-query ProjectUser)}]
   :route-segment ["users"]
   :initial-state {:t_project/users {}}}
  (div :.flex.flex-col
    (div :.-my-2.overflow-x-auto.sm:-mx-6.lg:-mx-8
      (div :.py-2.align-middle.inline-block.min-w-full.sm:px-6.lg:px-8
        (div :.shadow.overflow-hidden.border-b.border-gray-200.sm:rounded-lg
          (table :.min-w-full.divide-y.divide-gray-200
                 (thead :.bg-gray-50
                        (tr
                          (th :.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Name")
                          (th :.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Title")
                          (th :.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Email")))
                 (tbody :.bg-white.divide-y.divide-gray-200
                        (for [user (sort-by (juxt :t_user/last_name :t_user/first_names) (reduce-kv (fn [acc k v] (conj acc (first v))) [] (group-by :t_user/id users)))
                              :let [id (:t_user/id user)]]
                          (ui-project-user user)))))))))

(def ui-project-users (comp/factory ProjectUsers))

(defsc AboutPage [this params]
  {:route-segment ["about"]}
  (dom/div (dom/h3 "About")))












(defsc Menu
  [this {:keys [project selected-id sub-menu]}]
  (let [{:t_project/keys [id title pseudonymous]} project
        content (fn [s] (dom/span :.truncate s))]
    (comp/fragment
      (div :.px-2.pt-1.pb-8.font-bold title)
      (ui/ui-vertical-navigation
        {:selected-id selected-id
         :items       [{:id      :home
                        :content (content "Home")
                        :onClick #(dr/change-route! this ["projects" id "home"])}
                       (when pseudonymous
                         {:id      :find-pseudonymous-patient
                          :content (content "Find patient")})
                       (if pseudonymous
                         {:id      :register-pseudonymous-patient
                          :content (content "Register patient")}
                         {:id      :register-patient
                          :content (content "Register patient")})
                       {:id      :team
                        :content (content "Team")
                        :onClick #(dr/change-route! this ["projects" id "team"])}
                       {:id      :reports
                        :content (content "Downloads")}]
         :sub-menu    sub-menu}))))

(def ui-menu (comp/factory Menu))

(def role->badge-class
  {:INACTIVE              "bg-black text-white"
   :NORMAL_USER           "bg-green-100 text-green-800"
   :POWER_USER            "bg-red-200 text-red-800"
   :PID_DATA              "bg-yellow-200 text-black"
   :LIMITED_USER          "bg-teal-600 text-teal-600"
   :BIOBANK_ADMINISTRATOR "bg-blue-600 text-blue-600"})

(defsc RoleBadge
  [this {:t_project_user/keys [role]}]
  (dom/span :.inline-block.flex-shrink-0.rounded-full.px-2.py-0.5.text-xs.font-medium
            {:key role :classes [(role->badge-class role)]}
            (str/replace (name role) #"_" " ")))

(def ui-role-badge (comp/factory RoleBadge {:keyfn :t_project_user/role}))

(defsc ProjectTeamMember [this {:t_user/keys [id first_names last_name full_name job_title photo_url custom_job_title roles active?] :as user}]
  {:ident :t_user/id
   :query [:t_user/id :t_user/first_names :t_user/last_name :t_user/full_name :t_user/job_title
           :t_user/custom_job_title :t_user/active? :t_user/photo_url
           {:t_user/roles [:t_project_user/active? :t_project_user/role]}]}
  (ui/ui-grid-list-item
    {:title    (dom/a :.cursor-pointer.underline.text-blue-600.hover:text-blue-800
                 {:onClick #(dr/change-route! this ["users" id "profile"])} full_name)
     :subtitle (or job_title custom_job_title)
     :image    (if photo_url {:url photo_url} {:content (ui/avatar-14)})}
    (div :.flex.w-full.items-center.p-6.space-x-6
      (for [role (distinct (filter :t_project_user/active? roles))]
        (ui-role-badge role)))))

(def ui-project-team-member (comp/factory ProjectTeamMember {:keyfn :t_user/id}))

(defsc ProjectTeam [this {:t_project/keys [id title type users] :as project}]
  {:ident         :t_project/id
   :query         (fn [this]
                    `[:t_project/id :t_project/title :t_project/type
                      ({:t_project/users ~(comp/get-query ProjectTeamMember)} {:group-by :user})])
   :route-segment ["projects" :t_project/id "team"]
   :will-enter    (fn [app {:t_project/keys [id] :as route-params}]
                    (log/debug "on-enter project home" route-params)
                    (when-let [project-id (some-> id (js/parseInt))]
                      (dr/route-deferred [:t_project/id project-id]
                                         (fn []
                                           (df/load! app [:t_project/id project-id] ProjectTeam
                                                     {:target               [:ui/current-project]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_project/id project-id]}})))))}
  (ui/ui-layout
    {:props {:classes [(case (:t_project/type project) :NHS "bg-amber-50" :RESEARCH "bg-purple-50" nil)]}
     :menu  (ui-menu {:project project :selected-id :team})}
    (tap> {:users users
           :sorted-users (sort-by :t_user/last_name users)})
    (ui/ui-grid-list {}
      (for [user (sort-by :t_user/last_name users)]
        (ui-project-team-member user)))))


(defsc AdministrativeUser [this {:t_user/keys [full_name]}]
  {:ident :t_user/id
   :query [:t_user/id :t_user/full_name]}
  (when full_name full_name))

(def ui-administrative-user (comp/factory AdministrativeUser))

(defsc ParentProject [this {:t_project/keys [id title]}]
  {:ident :t_project/id
   :query [:t_project/id :t_project/title]}
  (when id
    (dom/a {:onClick #(dr/change-route! this ["projects" id "home"])} title)))

(def ui-parent-project (comp/factory ParentProject))

(defsc ProjectHome
  [this {:t_project/keys [title type pseudonymous long_description date_from date_to administrator_user
                          parent_project count_registered_patients
                          count_discharged_episodes count_pending_referrals
                          address1 address2 address3 address4 postcode
                          inclusion_criteria exclusion_criteria] :as project}]
  {:ident         :t_project/id
   :query         [:t_project/id :t_project/title :t_project/date_from :t_project/date_to
                   :t_project/count_pending_referrals :t_project/count_registered_patients :t_project/count_discharged_episodes
                   :t_project/type
                   :t_project/pseudonymous :t_project/long_description :t_project/inclusion_criteria :t_project/exclusion_criteria
                   :t_project/address1 :t_project/address2 :t_project/address3 :t_project/address4 :t_project/postcode
                   {:t_project/specialty [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                   {:t_project/administrator_user (comp/get-query AdministrativeUser)}
                   {:t_project/parent_project (comp/get-query ParentProject)}]
   :route-segment ["projects" :t_project/id "home"]
   :will-enter    (fn [app {:t_project/keys [id] :as route-params}]
                    (log/debug "on-enter project home" route-params)
                    (when-let [project-id (some-> id (js/parseInt))]
                      (dr/route-deferred [:t_project/id project-id]
                                         (fn []
                                           (df/load! app [:t_project/id project-id] ProjectHome
                                                     {:target               [:ui/current-project]
                                                      :post-mutation        `dr/target-ready
                                                      :post-mutation-params {:target [:t_project/id project-id]}})))))}
  (tap> project)
  (ui/ui-layout
    {:props {:classes [(case (:t_project/type project) :NHS "bg-amber-50" :RESEARCH "bg-purple-50" nil)]}
     :menu  (ui-menu {:project project :selected-id :home})}
    (ui/ui-two-column-card
      {:title       title
       :title-attrs {:classes (case type :NHS ["bg-yellow-200"] :RESEARCH ["bg-pink-200"] nil)}
       :subtitle    (when long_description (div {:dangerouslySetInnerHTML {:__html long_description}}))
       :items       [{:title "Date from" :content (ui/format-date date_from)}
                     {:title "Date to" :content (ui/format-date date_to)}
                     {:title "Administrator" :content (if (seq administrator_user) (ui-administrative-user administrator_user) "Not known")}
                     {:title "Registered patients" :content count_registered_patients}
                     {:title "Pending referrals" :content count_pending_referrals}
                     {:title "Discharged episodes" :content count_discharged_episodes}
                     {:title "Type" :content (str/join " / " (remove nil? [(when type (name type))
                                                                           (when pseudonymous "PSEUDONYMOUS")]))}
                     {:title "Specialty" :content (get-in project [:t_project/specialty :info.snomed.Concept/preferredDescription :info.snomed.Description/term])}
                     {:title "Parent" :content (when (seq parent_project) (ui-parent-project parent_project))}]
       :long-items  [{:title   "Address"
                      :content (str/join ", " (remove str/blank? [address1 address2 address3 address4 postcode]))}
                     {:title   "Inclusion criteria"
                      :content (when inclusion_criteria (div {:dangerouslySetInnerHTML {:__html inclusion_criteria}}))}
                     {:title   "Exclusion criteria"
                      :content (when exclusion_criteria (div {:dangerouslySetInnerHTML {:__html exclusion_criteria}}))}]})))

(def ui-project-home (comp/factory ProjectHome))