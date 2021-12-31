(ns pc4.projects
  (:require [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [pc4.app :refer [SPA]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [clojure.string :as str]
            [pc4.ui.ui :as ui]))

(defsc ProjectUsers
  [this {:t_project/keys [users] :as props}]
  {:ident         :t_project/id
   :query         [:t_project/id
                   {:t_project/users [:t_user/first_names
                                      :t_user/last_name :t_user/title
                                      :t_user/custom_job_title :t_user/job_title_name]}]
   :initial-state {}}
  (js/console.log "users: " props)
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
                                      (tr {:key id}
                                          (td :.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900 (str/join " " [(:t_user/title user) (:t_user/first_names user) (:t_user/last_name user)]))
                                          (td :.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (or (:t_user/custom_job_title user) (:t_job_title/name user)))
                                          (td :.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (:t_user/email user)))))))))))

(def ui-project-users (comp/factory ProjectUsers))

(defsc ProjectHome
  [this {:t_project/keys [id active? title date_from date_to virtual long_description
                          inclusion_criteria exclusion_criteria type
                          count_registered_patients count_discharged_episodes]
         :as             project}]
  {:ident         (fn [] [:component/id :project-home])
   :query         [:t_project/id :t_project/active? :t_project/title :t_project/date_from :t_project/date_to :t_project/type
                   :t_project/virtual :t_project/long_description :t_project/inclusion_criteria :t_project/exclusion_criteria
                   :t_project/count_registered_patients :t_project/count_discharged_episodes]
   :route-segment ["home"]
   :initial-state {}}
  (if-not project
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
                           (dd :.mt-1.text-sm.text-gray-900 count_discharged_episodes))))

         (when inclusion_criteria
           (div :.sm:col-span-2
                (dt :.text-sm.font-medium.text-gray-500 "Inclusion criteria")
                (dd :.mt-1.text-sm.text-gray-900 {:dangerouslySetInnerHTML {:__html inclusion_criteria}})))
         (when exclusion_criteria
           (div :.sm:col-span-2
                (dt :.text-sm.font-medium.text-gray-500 "Exclusion criteria")
                (dd :.mt-1.text-sm.text-gray-900 {:dangerouslySetInnerHTML {:__html exclusion_criteria}}))))))

(def ui-project-home (comp/factory ProjectHome))



(defsc ProjectPage [this {current-project    :session/current-project
                          home               :>/home        ;; TODO: this should use routing, rather than manually handling the menu
                          users              :>/users
                          authenticated-user :session/authenticated-user
                          :as                props}]
  {:ident         :t_project/id
   :route-segment ["project" :t_project/id]
   :query         [:t_project/id
                   :session/current-project
                   {[:session/authenticated-user '_] [:t_user/first_names :t_user/last_name]}
                   {:>/home (comp/get-query ProjectHome)}
                   {:>/users (comp/get-query ProjectUsers)}]
   :initial-state {:>/home  {}
                   :>/users {}}
   :will-enter    (fn [app {:t_project/keys [id] :as route-params}]
                    (when-let [project-id (some-> id (js/parseInt))]
                      (println "entering project page: project-id" project-id)
                      (dr/route-deferred [:t_project/id project-id]
                                         (fn [] (df/load! app [:t_project/id project-id] ProjectPage
                                                          {:target               [:session/current-project]
                                                           :post-mutation        `dr/target-ready
                                                           :post-mutation-params {:target [:t_project/id project-id]}})))))
   :will-leave    (fn [this props]
                    (comp/transact! this [(list 'pc4.users/close-project)]))}
  (let [selected-page (or (comp/get-state this :selected-page) :home)]
    (comp/fragment
      (div :.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200
           (dom/ul :.flex
                   (div :.font-bold.text-lg.min-w-min.mr-6.py-1 (:t_project/title home))
                   (ui/flat-menu [{:title "Home" :id :home}
                                  {:title "Register" :id :register}
                                  {:title "Search" :id :search}
                                  {:title "Users" :id :users}]
                                 :selected-id selected-page
                                 :select-fn #(do
                                               (println "selected " %)
                                               (comp/set-state! this {:selected-page %})))))
      (case selected-page
        :home (ui-project-home home)
        :users (ui-project-users users)
        (ui/box-error-message :message "Page not found")))))

(def ui-project-page (comp/factory ProjectPage))


(defsc ProjectPage2 [this {:t_project/keys [id name title long_description]}]
  {:query         [:t_project/id :t_project/name :t_project/title :t_project/long_description]
   :ident         :t_project/id
   :route-segment ["project" :t_project/id]
   :will-enter      (fn [app {:project/keys [id] :as route-params}]
                      (log/info "Will enter patient with route params " route-params)
                      ;; be sure to convert strings to int for this case
                      (let [id (if (string? id) (js/parseInt id) id)]
                        (dr/route-deferred [:project/id id]
                                           #(df/load app [:project/id id] ProjectHome
                                                     {:post-mutation `dr/target-ready
                                                      :post-mutation-params
                                                      {:target [:project/id id]}}))))
   }
  (dom/div
    (dom/h3 (str "Project " id))
    (dom/div (str name " title: " title ": " long_description)))

(def ui-project-page2 (comp/factory ProjectPage2))