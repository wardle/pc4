(ns pc4.users
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div span li p]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [taoensso.timbre :as log]
    [pc4.app :refer [SPA]]
    [pc4.session :as session]
    [pc4.ui.ui :as ui]))


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
    (div :.px-4.py-4.sm:px-6
         (div :.flex.items-center.justify-between
              (p :.text-lg.font-medium.text-indigo-600.truncate title)
              (div :.ml-2.flex-shrink-0.flex
                   (p :.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-blue-100.text-green-800
                      (dom/time {:date-time date_time} (ui/format-date date_time)))))
         (div :.sm:flex.sm:justify-between
              (div :.mb-2.flex.items-center.text-sm.text-gray-500.sm:mt-0
                   (p "by " (ui-news-author author))))
         (p :.text-sm {:dangerouslySetInnerHTML {:__html body}})))

(def ui-news-item (comp/factory NewsItem {:keyfn :t_news/id}))

(defsc ProjectButton [this {:t_project/keys [id name type title]}]
  {:query [:t_project/id :t_project/name :t_project/title :t_project/type]
   :ident :t_project/id}
  (dom/a :.cursor-default
         {:onClick #(js/console.log "selecting project " id name title)}
         (dom/div :.px-3.py-1.text-sm.border
                  {:classes [(if (= :RESEARCH type) "bg-pink-50 hover:bg-pink-100" "bg-yellow-50 hover:bg-yellow-100")]}
                  title)))

(def ui-project-button (comp/factory ProjectButton {:keyfn :t_project/id}))

(defsc ListProjects [this {:keys [projects]}]
  {:query [{:projects (comp/get-query ProjectButton)}]}
  (let [grouped (group-by :t_project/type projects)
        has-clinical? (seq (:NHS grouped))
        has-research? (seq (:RESEARCH grouped))]
    (div :.border-solid.border-gray-800.bg-gray-50.border.rounded.shadow-lg
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

(def ui-list-projects (comp/factory ListProjects))

(defsc User
  [this {:t_user/keys [id title first_names last_name active_projects latest_news] :as user token :io.jwt/token}]
  {:query [:t_user/id :io.jwt/token :t_user/title :t_user/first_names :t_user/last_name
           {:t_user/active_projects (comp/get-query ProjectButton)}
           {:t_user/latest_news (comp/get-query NewsItem)}]
   :ident :t_user/id}
  (when user
    (dom/div
      (dom/p "User " id " " title " " first_names " " last_name)
      (map ui-news-item latest_news)
      (ui-list-projects {:projects active_projects})
      (dom/button :.border.border-black.bg-blue-400.hover:bg-blue-200.shadow.mb-2 {:onClick #(comp/transact! @SPA [(list 'pc4.users/logout)])} "Logout"))))

(def ui-user (comp/factory User))


(defsc UserHomePage
  [this {:t_user/keys [id title first_names last_name active_projects latest_news] :as user}]
  {:query [:t_user/id :io.jwt/token :t_user/title :t_user/first_names :t_user/last_name
           {:t_user/active_projects (comp/get-query ProjectButton)}
           {:t_user/latest_news (comp/get-query NewsItem)}]
   :ident :t_user/id}
  (div :.grid.grid-cols-1.md:grid-cols-4.md:gap-4.m-4
       (div :.md:mr-2 (ui-list-projects {:projects active_projects}))
       (div :.col-span-3
            (map ui-news-item latest_news))))

(def ui-user-home-page (comp/factory UserHomePage))

(defmutation login
  "Performs a login action. If the server responds successfully (200) but there
  is no token, then we have invalid credentials."
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing login action" params))
  (login [env]
         (js/console.log "Sending login action to remote" env)
         (-> env
             (m/returning UserHomePage)
             (m/with-target [:session/authenticated-user])))
  (ok-action [{:keys [result] :as env}]
             (let [token (get-in result [:body 'pc4.users/login :io.jwt/token])]
               (js/console.log "response from remote: " result)
               (if token (swap! (:state env) dissoc :session/error)
                         (swap! (:state env) assoc :session/error "Invalid username or password"))
               (reset! session/authentication-token token))))

(defmutation logout
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing logout action" params)
          (swap! state dissoc :session/authenticated-user :session/error)
          (reset! session/authentication-token nil)))

(defmutation refresh-token
  [params]
  (action [{:keys [state]}]
          (js/console.log "Performing refresh token"))
  (remote [env] true)
  (ok-action [{:keys [result] :as env}]
             (let [token (get-in result [:body 'pc4.users/refresh-token :io.jwt/token])]
               (reset! session/authentication-token token))))


(comment
  (com.fulcrologic.fulcro.algorithms.merge/merge-component!
    @SPA
    User
    {:t_user/id              2
     :t_user/last_name       "Smith"
     :t_user/first_names     "John"
     :t_user/active_projects '({:t_project/id   1
                                :t_project/name "Wibble"})})
  (com.fulcrologic.fulcro.algorithms.merge/merge-component!
    @SPA
    User
    {:t_user/id              2
     :t_user/last_name       "Smith"
     :t_user/first_names     "John"
     :t_user/active_projects [{:t_project/id   1
                               :t_project/name "Wibble"}]})
  )