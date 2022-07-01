(ns pc4.projects
  (:require [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.dom.events :as evt]
            [com.fulcrologic.fulcro.mutations :refer [defmutation returning]]
            [pc4.app :refer [SPA]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
            [com.fulcrologic.fulcro.data-fetch :as df]

            [clojure.string :as str]
            [pc4.ui :as ui]
            [pc4.rsdb]
            [taoensso.timbre :as log]
            [com.fulcrologic.fulcro.mutations :as m])
  (:import [goog.date Date]))

(defsc PatientSearchByPseudonym
  [this {project-id :t_project/id
         patient    :ui/search-patient-pseudonymous}]
  {:query         [:t_project/id
                   {[:ui/search-patient-pseudonymous '_] (comp/get-query pc4.rsdb/PseudonymousPatient)}]
   :initial-state {:t_project/id :param/id}}
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
                    (dom/label :.sr-only {:for "pseudonym"} "Pseudonym")
                    (dom/input :.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md.pl-5.py-2
                               {:type      "text" :name "pseudonym" :placeholder "Start typing pseudonym" :auto-focus true
                                :onKeyDown #(when (and patient (evt/enter-key? %))
                                              (println "Will go to patient!" patient))
                                :onChange  #(let [s (evt/target-value %)]
                                              (println "Patient search " s)
                                              (comp/transact! this [(pc4.rsdb/search-patient-by-pseudonym {:project-id project-id :pseudonym s})]))}))
               (when patient
                 (pc4.rsdb/ui-pseudonymous-patient patient))))))))

(def ui-patient-search-by-pseudonym (comp/factory PatientSearchByPseudonym))



(defn clear-register-pseudonymous-form*
  [state-map]
  (-> state-map
      (assoc-in [:component-id :register-pseudonymous-patient]
        {:ui/nhs-number ""
         :ui/gender     nil
         :ui/date-birth nil})
      (fs/add-form-config* (comp/registry-key->class ::RegisterByPseudonym) [:component-id :register-pseudonymous-patient])))

(defmutation clear-register-pseudonymous-form [_]
  (action [{:keys [state]}]
          (swap! state clear-register-pseudonymous-form*)))

(defsc RegisterByPseudonym
  [this {project-id :t_project/id
         :ui/keys   [nhs-number date-birth gender] :as params}]
  {:ident             (fn [] [:component-id :register-pseudonymous-patient])
   :query             [:t_project/id
                       :ui/nhs-number :ui/date-birth :ui/gender
                       :ui/error fs/form-config-join]
   :initial-state     (fn [_]
                        (fs/add-form-config RegisterByPseudonym
                                            {:ui/nhs-number ""
                                             :ui/gender     nil
                                             :ui/date-birth nil}))
   :form-fields       #{:ui/nhs-number :ui/date-birth :ui/gender}
   :componentDidMount (fn [this] (comp/transact! this [(list 'pc4.projects/clear-register-pseudonymous-form)]))}
  (let [valid? true
        do-register (fn [] (do (println "Attempting to register" params)
                               (comp/transact! @SPA [(pc4.rsdb/register-patient-by-pseudonym {:project-id project-id
                                                                                              :nhs-number nhs-number
                                                                                              :date-birth date-birth
                                                                                              :gender     gender})])))]
    (div :.space-y-6
         (div :.bg-white.shadow.px-4.py-5.sm:rounded-lg.sm:p-6
              (div :.md:grid.md:grid-cols-3.md:gap-6
                   (div :.md:col-span-1.pr-6
                        (dom/h3 :.text-lg.font-medium.leading-6.text-gray-900 "Register a patient")
                        (div :.mt-1.mr-12.text-sm.text-gray-500)
                        (p "Enter patient details.")
                        (p :.mt-4 "This is safe even if patient already registered")
                        (p :.mt-4 "Patient identifiable information is not stored but simply used to generate a pseudonym."))
                   (div :.mt-5.md:mt-0.md:col-span-2.space-y-4
                        (dom/form {:onSubmit #(do (evt/prevent-default! %) (do-register))})
                        (ui/ui-textfield {:id "nnn" :value nhs-number :label "NHS Number:" :placeholder "Enter NHS number" :auto-focus true}
                                         {:onChange #(m/set-string! this :ui/nhs-number :value %)})
                        (ui/ui-local-date {:id "date-birth" :value date-birth :label "Date of birth:" :min-date (Date. 1900 1 1) :max-date (Date.)}
                                          {:onChange #(m/set-value! this :ui/date-birth %)}))))
         (div :.flex.justify-end.mr-8
              (dom/button :.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600
                          {:type      "submit"
                           :className (if-not valid? "opacity-50 pointer-events-none" "hover:bg-blue-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-blue-500")
                           :onClick   #(when valid? (do-register))}
                          "Search or register patient »")))))


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
   :initial-state {}}
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

(defsc ProjectHome
  [this {:t_project/keys [id active? title date_from date_to virtual long_description
                          inclusion_criteria exclusion_criteria type
                          count_registered_patients count_discharged_episodes]
         :as             project}]
  {:ident         :t_project/id
   :query         [:t_project/id :t_project/active? :t_project/title :t_project/date_from :t_project/date_to :t_project/type
                   :t_project/virtual :t_project/long_description :t_project/inclusion_criteria :t_project/exclusion_criteria
                   :t_project/count_registered_patients :t_project/count_discharged_episodes]
   :initial-state {}
   :route-segment ["home"]}
  (if-not (seq project)
    (div (ui/box-error-message :message "No project information available"))
    (div :.bg-white.shadow.overflow-hidden.sm:rounded-lg
         (div :.px-4.py-5.sm:px-6
              (p :.mt-1.max-w-2xl.text-sm.text-gray-500 {:dangerouslySetInnerHTML {:__html long_description}}))
         (div :.border-t.border-gray-200.px-4.py-5.sm:px-6
              (dom/dl :.grid.grid-cols-1.gap-x-4.gap-y-8.sm:grid-cols-2
                      (div :.sm:col-span-1
                           (dom/dt :.text-sm.font-medium.text-gray-500 "Status")
                           (dom/dd :.mt-1.text-sm.text-gray-900 (if active? "Active" "Inactive")))
                      (div :.sm:col-span-1
                           (dom/dt :.text-sm.font-medium.text-gray-500 "Type")
                           (dom/dd :.mt-1.text-sm.text-gray-900 (str/upper-case (if type (name type) "")) " " (when virtual "VIRTUAL")))
                      (div :.sm:col-span-1
                           (dt :.text-sm.font-medium.text-gray-500 "Date from")
                           (dd :.mt-1.text-sm.text-gray-900 (ui/format-date date_from)))
                      (div :.sm:col-span-1
                           (dt :.text-sm.font-medium.text-gray-500 "Date to")
                           (dd :.mt-1.text-sm.text-gray-900 (ui/format-date date_to)))
                      (div :.sm:col-span-1
                           (dt :.text-sm.font-medium.text-gray-500 "Registered patients")
                           (dd :.mt-1.text-sm.text-gray-900 count_registered_patients))
                      (div :.sm:col-span-1
                           (dt :.text-sm.font-medium.text-gray-500 "Discharged episodes")
                           (dd :.mt-1.text-sm.text-gray-900 count_discharged_episodes))
                      (when inclusion_criteria
                        (div :.sm:col-span-2
                             (dt :.text-sm.font-medium.text-gray-500 "Inclusion criteria")
                             (dd :.mt-1.text-sm.text-gray-900 {:dangerouslySetInnerHTML {:__html inclusion_criteria}})))
                      (when exclusion_criteria
                        (div :.sm:col-span-2
                             (dt :.text-sm.font-medium.text-gray-500 "Exclusion criteria")
                             (dd :.mt-1.text-sm.text-gray-900 {:dangerouslySetInnerHTML {:__html exclusion_criteria}}))))))))

(def ui-project-home (comp/factory ProjectHome))

(defsc ProjectPage
  [this {:t_project/keys [id title]
         home            :>/home
         search          :>/search
         register        :>/register
         users           :>/users}]

  {:ident               :t_project/id
   :route-segment       ["project" :t_project/id]
   :query               [:t_project/id :t_project/title
                         {[:session/authenticated-user '_] [:t_user/first_names :t_user/last_name]}
                         {:>/home (comp/get-query ProjectHome)}
                         {:>/users (comp/get-query ProjectUsers)}
                         {:>/register (comp/get-query RegisterByPseudonym)}
                         {:>/search (comp/get-query PatientSearchByPseudonym)}]
   :will-enter          (fn [app {:t_project/keys [id] :as route-params}]
                          (when-let [project-id (some-> id (js/parseInt))]
                            (println "entering project page: project-id" project-id)
                            (dr/route-deferred [:t_project/id project-id]
                                               (fn [] (df/load! app [:t_project/id project-id] ProjectPage
                                                                {:target               [:session/current-project]
                                                                 :post-mutation        `dr/target-ready
                                                                 :post-mutation-params {:target [:t_project/id project-id]}})))))
   :allow-route-change? (constantly true)
   :will-leave          (fn [this props]
                          (comp/transact! this [(list 'pc4.users/close-project)]))}
  (let [selected-page (or (comp/get-state this :selected-page) :home)]
    (comp/fragment
      (div :.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200
           (dom/ul :.flex
                   (div :.font-bold.text-lg.min-w-min.mr-6.py-1 title)
                   (ui/flat-menu [{:title "Home" :id :home}
                                  {:title "Search" :id :search}
                                  {:title "Register" :id :register}
                                  {:title "Users" :id :users}]
                                 :selected-id selected-page
                                 :select-fn #(do
                                               (println "selected " %)
                                               (comp/set-state! this {:selected-page %})))))
      (case selected-page
        :home (ui-project-home home)
        :search (ui-patient-search-by-pseudonym search)
        :register (ui-register-by-pseudonym register)
        :users (ui-project-users users)
        (ui/box-error-message :message "Page not found")))))


(def ui-project-page (comp/factory ProjectPage))


(defsc AboutPage [this params]
  {:route-segment ["about"]}
  (dom/div (dom/h3 "About")))

