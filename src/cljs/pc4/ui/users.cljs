(ns pc4.ui.users
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div span li p]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [pc4.app :refer [SPA]]
    [pc4.ui.core :as ui]
    [pc4.ui.nav]
    [pc4.ui.projects]
    [pc4.users :as users]))

(defsc NewsAuthor [this {:t_user/keys [id title first_names last_name]}]
  {:ident :t_user/id
   :query [:t_user/id :t_user/title :t_user/first_names :t_user/last_name]}
  (span title " " first_names " " last_name))

(def ui-news-author (comp/factory NewsAuthor {:keyfn :t_user/id}))

(defsc NewsItem
  [this {:t_news/keys [id date_time title body author] :as news-item}]
  {:ident :t_news/id
   :query [:t_news/id :t_news/date_time :t_news/title :t_news/body
           {:t_news/author (comp/get-query NewsAuthor)}]}
  (div :.px-4.py-4.sm:px-6.bg-gray-50.mb-4.border.rounded.shadow
    (div
      (div :.flex.items-center.justify-between
        (p :.text-lg.font-medium.text-indigo-600.truncate title)
        (div :.ml-2.flex-shrink-0.flex
          (p :.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-blue-100.text-green-800
             (dom/time {:date-time date_time} (ui/format-date date_time)))))
      (div :.sm:flex.sm:justify-between
        (div :.mb-2.flex.items-center.text-sm.text-gray-500.sm:mt-0
          (p "by " (ui-news-author author)))))
    (dom/article :.text-sm.prose.p-4 {:dangerouslySetInnerHTML {:__html body}})))

(def ui-news-item (comp/factory NewsItem {:keyfn :t_news/id}))

(defsc ProjectButton [this {:t_project/keys [id name type title] :as project}]
  {:query [:t_project/id :t_project/name :t_project/title :t_project/type]
   :ident :t_project/id}
  (dom/a :.cursor-pointer
    {:onClick #(do (js/console.log "selecting project " id name title)
                   (dr/change-route! this (dr/path-to pc4.ui.projects/ProjectPage id)))}
    (dom/div :.px-3.py-1.text-sm.border
      {:classes [(if (= :RESEARCH type) "bg-pink-50 hover:bg-pink-100" "bg-yellow-50 hover:bg-yellow-100")]}
      title)))

(def ui-project-button (comp/factory ProjectButton {:keyfn :t_project/id}))


(defsc PatientById [this props]
  (dom/input :.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md.pl-5.py-2
    {:type      "text" :placeholder "Patient identifier"
     :autoFocus true
     :value     (or (comp/get-state this :s) "")
     :onKeyDown #(when (evt/enter-key? %)
                   (dr/change-route! this ["pt" (js/parseInt (comp/get-state this :s)) "home"]))
     :onChange  #(let [s (evt/target-value %)]
                   (comp/set-state! this {:s s}))}))

(def ui-patient-by-id (comp/factory PatientById))

(defsc ListUserProjects [this {:t_user/keys [active_projects]}]
  {:query [:t_user/id {:t_user/active_projects (comp/get-query ProjectButton)}]
   :ident :t_user/id}
  (let [grouped (group-by :t_project/type active_projects)
        has-clinical? (seq (:NHS grouped))
        has-research? (seq (:RESEARCH grouped))]
    (div :.border-solid.border-gray-800.bg-gray-50.border.rounded.shadow-lg
      (ui-patient-by-id {})
      (div :.bg-gray-800.text-white.px-2.py-2.border-solid.border-grey-800 "My projects / services")
      (when has-clinical?
        (comp/fragment
          (div
            (span :.mt-2.text-xs.inline-block.py-1.px-2.uppercase.bg-yellow-200.uppercase.last:mr-0.mr-1 "clinical"))
          (map ui-project-button (sort-by :t_project/title (:NHS grouped)))))
      (when (and has-clinical? has-research?)
        (dom/hr))
      (when has-research?
        (comp/fragment
          (div
            (span :.mt-2.text-xs.inline-block.py-1.px-2.uppercase.bg-pink-200.uppercase.last:mr-0.mr-1 "research"))
          (map ui-project-button (sort-by :t_project/title (:RESEARCH grouped))))))))

(def ui-list-user-projects (comp/factory ListUserProjects))


(defsc UserHomePage
  [this {:>/keys      [projects]
         :t_user/keys [latest_news]
         :as          user}]
  {:ident         :t_user/id
   :query         [:t_user/id :t_user/username :t_user/title
                   :t_user/first_names :t_user/last_name
                   :t_user/active_roles
                   {:>/projects (comp/get-query ListUserProjects)}
                   {:t_user/latest_news (comp/get-query NewsItem)}]
   :initial-state {:>/projects         []
                   :t_user/latest_news []}}

  (tap> user)
  (div
    #_(pc4.ui.ui/ui-nav-bar {:title     "PatientCare v4" :show-user? true
                             :full-name (str first_names " " last_name) :initials initials
                             :user-menu [{:id :logout :title "Sign out" :onClick #(comp/transact! @SPA [(list 'pc4.users/logout)])}]})
    (div :.grid.grid-cols-1.md:grid-cols-4.md:gap-4.m-4
      (div :.md:mr-2 (ui-list-user-projects projects))
      (div :.col-span-3
        (map ui-news-item latest_news)))))

(def ui-user-home-page (comp/factory UserHomePage))

(defsc UserPhoto
  [this _]
  {:query                [:t_photo/data :t_photo/mime_type]
   :componentDidMount    #(let [{:t_photo/keys [data mime_type]} (comp/props %)]
                            (comp/set-state! % {:url (js/URL.createObjectURL (js/Blob. (clj->js [data]) #js {:type mime_type}))}))
   :componentWillUnmount #(when-let [url (comp/get-state % :url)] (js/URL.revokeObjectURL url))}
  (dom/img {:src (comp/get-state this :url)}))

(def ui-user-photo (comp/factory UserPhoto))

(defsc GlobalNetworkActivity [this {active-remotes :com.fulcrologic.fulcro.application/active-remotes}]
  {:query         [[:com.fulcrologic.fulcro.application/active-remotes '_]]
   :ident         (fn [] [:component/id :global-activity])
   :initial-state {}}
  (when (seq active-remotes)
    (ui/ui-loading {})))


(defsc NavBar
  [this {:t_user/keys [id username title first_names last_name initials has_photo] :as params}]
  {:ident         :t_user/id
   :query         [:t_user/id :t_user/username :t_user/title :t_user/first_names :t_user/last_name :t_user/initials
                   :t_user/has_photo]
   :initial-state {}}
  (pc4.ui.nav/ui-nav-bar {:title     "PatientCare v4" :show-user? true
                          :full-name (str (when-not (str/blank? title) (str title " ")) first_names " " last_name)
                          :initials  initials
                          :photo     (when has_photo (str "http://localhost:8080/users/cymru.nhs.uk/" username "/photo"))
                          :user-menu [{:id :logout :title "Sign out" :onClick #(comp/transact! @SPA [(list 'pc4.users/logout {})])}]}
                         {:on-home #(dr/change-route! this ["home"])}))

(def ui-nav-bar (comp/factory NavBar))

(defsc Login
  "Login component that keeps user credentials in local state."
  [this {:ui/keys [loading? error]}]
  {:ident         (fn [] [:component/id :login])
   :query         [:ui/loading? :ui/error]
   :initial-state {:ui/loading? false, :ui/error nil}
   :route-segment ["login"]}
  (let [username (or (comp/get-state this :username) "")
        password (or (comp/get-state this :password) "")
        disabled? (or loading? (str/blank? username) (str/blank? password))
        do-login #(when-not disabled? (comp/transact! @SPA [(pc4.users/perform-login {:system "cymru.nhs.uk" :value username :password password})]))]
    (div :.flex.h-screen.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
      (div :.max-w-md.w-full.space-y-8.m-auto
        (dom/form
          {:method "POST" :onSubmit (fn [evt] (evt/prevent-default! evt) (do-login))}
          (dom/h1 :.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter.mb-8 "PatientCare " (dom/span :.font-bold "v4"))
          (div :.rounded-md.shadow-sm.-space-y-px
            (div
              (dom/label :.sr-only {:htmlFor "username"} "username")
              (dom/input :.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
                {:id           "username"
                 :name         "username" :type "text"
                 :autoComplete "username" :required true :placeholder "Username"
                 :autoFocus    true :disabled false
                 :value        username
                 :onChange     (fn [evt] (comp/set-state! this {:username (evt/target-value evt)}))
                 :onKeyDown    (fn [evt] (when (evt/enter? evt) (.focus (.getElementById js/document "password"))))}))
            (div
              (dom/label :.sr-only {:htmlFor "password"} "Password")
              (dom/input :.password.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
                {:id           "password"
                 :type         "password"
                 :autoComplete "current-password"
                 :value        password
                 :onChange     #(comp/set-state! this {:password (evt/target-value %)})
                 :required     true
                 :placeholder  "Password"})))
          (when error                                       ;; error
            (ui/box-error-message :message error))
          (div
            (when loading?
              (ui/ui-loading-screen {}))
            (dom/button :.mt-4.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500.bg-indigo-600.hover:bg-indigo-700
              {:type     "submit"
               :classes  (when disabled? ["opacity-50"])
               :disabled disabled?}
              "Sign in")))))))

(def ui-login (comp/factory Login))



