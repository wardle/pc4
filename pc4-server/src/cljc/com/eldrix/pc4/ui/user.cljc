(ns com.eldrix.pc4.ui.user
  (:require [clojure.string :as str]
            [com.eldrix.pc4.ui.misc :as misc]
            [rum.core :as rum]))


(rum/defc nav-bar
  "A navigation bar.
    Parameters:
    - title      : a map with
      - s      : the title to show
      - attrs  : attributes to be merged
    - user       : a map with
      - full-name  : full name of user
      - initials   : initials of user (for display on small devices)
      - attrs      : attributes for open user menu
      - photo      : attributes for photo, e.g {:src \"https://...\"}
      - menu       : a sequence of menu items each with keys :id :title :attrs
      - menu-open? : is user menu displayed?
    - notify     : attrs for notify button or nil if no notification"
  [{:keys [id title user notify] :as params}]
  (println "params: " params)
  [:nav.bg-gray-800
   {:id (or id "navbar")}
   [:div.mx-auto.px-2.sm:px-6.lg:px-8
    [:div.relative.flex.items-center.justify-between.h-16
     [:div.flex-1.flex.items-center.justify-center.sm:items-stretch.sm:justify-start
      [:div.flex-shrink-0.flex.items-center
       [:span.text-white.rounded-md.text-lg.font-large.font-bold [:a (:attrs title) (:s title)]]]]
     [:div.absolute.inset-y-0.right-0.flex.items-center.pr-2.sm:static.sm:inset-auto.sm:ml-6.sm:pr-0
      (when (seq user)
        [:div.ml-3.relative
         [:div
          [:button#user-menu-button.bg-gray-800.flex.text-sm.rounded-full
           (merge {:type "button" :aria-expanded "false" :aria-haspopup "true"} (:attrs user))
           [:span.sr-only "Open user menu"]
           [:span.hidden.sm:block.text-white [:span.flex (or (:full-name user) "User") (misc/icon-chevron-down)]]
           [:span.sm:hidden.text-white [:div.flex (when (:initials user) (:initials user)) (misc/icon-chevron-down)]]]]

         (when (and (:menu-open? user) (seq (:menu user)))
           [:div.origin-top-right.absolute.z-50.right-0.mt-2.w-48.rounded-md.shadow-lg.py-1.bg-white.ring-1.ring-black.ring-opacity-5.focus:outline-none
            {:role "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabIndex "-1"}
            (for [item (:menu user)]
              (if (:attrs item)
                [:a.block.px-4.py-2.text-sm.text-gray-700.hover:bg-gray-700.hover:text-white
                 (merge {:role "menuitem" :tabIndex "-1"} (:attrs item)) (:title item)]
                [:span.block.px-4.py-2.text-sm.italic.text-gray-600 (:title item)]))])])]]]])


(rum/defc login-panel
  "Login panel with hero title, and parameters:
  - form      : properties for HTML form
  - username  :
  - password  :
  - disabled  : if login form should be disabled
  - error     : an error message to be shown"
  [{:keys [form username password hidden disabled error]}]
  [:div.flex.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
   [:div.max-w-md.w-full.space-y-8
    [:form form
     (for [[k v] hidden
           :when v]
       [:input {:type "hidden" :name (name k) :value (str v)}])
     [:div
      [:h1.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter "PatientCare " [:span.font-bold "v4"]]]
     [:div.rounded-md.shadow-sm
      [:div.mt-8
       [:label.sr-only {:for "username"} "username"]
       [:input#email-address.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
        (merge {:name "username" :type "text" :autoComplete "username" :required true :placeholder "Username" :autofocus true :disabled disabled}
               username)]]
      [:div.mt-2.mb-4
       [:label.sr-only {:for "password"} "Password"]
       [:input#password.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
        (merge {:name "password" :type "password" :autoComplete "current-password" :required true :placeholder "Password" :disabled disabled}
               password)]]]
     (when error
       (misc/box-error-message {:message error}))
     [:div.mt-2
      [:button.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500
       {:type "" :disabled disabled :class (if disabled "bg-gray-400 animate-pulse" "bg-indigo-600 hover:bg-indigo-700")}
       [:span.absolute.left-0.inset-y-0.flex.items-center.pl-3
        [:svg.h-5.w-5.text-indigo-500.group-hover:text-indigo-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true" :class (when disabled "animate-bounce")}
         [:path {:fill-rule "evenodd" :d "M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" :clip-rule "evenodd"}]]] (if disabled "Signing in" "Sign in")]]]]])


(rum/defc project-panel
  "A panel to show the user's own projects."
  [{:keys [projects make-attrs]}]
  (let [grouped (group-by :t_project/type projects)
        has-clinical (seq (:NHS grouped))
        has-research (seq (:RESEARCH grouped))]
    [:div.border-solid.border-gray-800.bg-gray-50.border.rounded.shadow-lg
     [:div.bg-gray-800.text-white.px-2.py-2.border-solid.border-grey-800 "My projects / services"]
     (when has-clinical
       [:<>
        [:div
         [:span.mt-2.text-xs.inline-block.py-1.px-2.uppercase.bg-yellow-200.uppercase.last:mr-0.mr-1 "clinical"]]
        (for [project (sort-by :t_project/title (:NHS grouped))]
          [:a.cursor-default (merge {:key (:t_project/id project)} (make-attrs project))
           [:div.px-3.py-1.text-sm.bg-yellow-50.hover:bg-yellow-100.border
            (:t_project/title project)]])])
     (when (and has-clinical has-research)
       [:hr])
     (when has-research
       [:<>
        [:div
         [:span.mt-2.text-xs.inline-block.py-1.px-2.uppercase.bg-pink-200.uppercase.last:mr-0.mr-1 "research"]]
        (for [project (sort-by :t_project/title (:RESEARCH grouped))]
          [:a.cursor-default (merge {:key (:t_project/id project)} (make-attrs project))
           [:div.px-3.py-1.text-sm.bg-pink-50.hover:bg-pink-100.border
            (:t_project/title project)]])])]))

;; TODO: make compatible with cljs
(def dtf (java.time.format.DateTimeFormatter/ofPattern "dd MMM yyyy"))

(rum/defc list-news [{:keys [news-items]}]
  [:div.mb-4.bg-white.shadow-lg.overflow-hidden.sm:rounded-md
   [:ul.divide-y.divide-gray-200 {:role "list"}
    (for [article news-items]
      [:li {:key (:t_news/id article)}
       [:div.px-4.py-4.sm:px-6
        [:div.flex.items-center.justify-between
         [:p.text-lg.font-medium.text-indigo-600.truncate (:t_news/title article)]
         [:div.ml-2.flex-shrink-0.flex
          [:p.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-blue-100.text-green-800 [:time {:date-time (:t_news/date_time article)} (.format dtf (:t_news/date_time article))]]]]
        [:div.sm:flex.sm:justify-between
         [:div.mb-2.flex.items-center.text-sm.text-gray-500.sm:mt-0
          [:p "by " (:t_user/first_names article) " " (:t_user/last_name article)]]]
        [:article.prose.lg:prose-xl.pb-4 {:dangerouslySetInnerHTML {:__html (:t_news/body article)}}]]])]])



(def role->badge-class
  {:INACTIVE              "bg-black text-white"
   :NORMAL_USER           "bg-green-100 text-green-800"
   :POWER_USER            "bg-red-200 text-red-800"
   :PID_DATA              "bg-yellow-200 text-black"
   :LIMITED_USER          "bg-teal-600 text-teal-600"
   :BIOBANK_ADMINISTRATOR "bg-blue-600 text-blue-600"})

(rum/defc role-badge [role]
  [:span.inline-block.flex-shrink-0.rounded-full.px-2.py-0.5.text-xs.font-medium
   {:class (role->badge-class role)}
   (str/replace (name role) #"_" " ")])


(rum/defc list-role [{:t_project/keys [title] :t_project_user/keys [date_from date_to role]}]
  [:tr
   [:td.whitespace-nowrap.py-4.pl-4.pr-3.text-sm.font-medium.text-gray-900.sm:pl-0 title]
   [:td.whitespace-nowrap.py-4.px-3.text-sm.text-gray-500 date_from]
   [:td.whitespace-nowrap.py-4.px-3.text-sm.text-gray-500 date_to]
   [:td.whitespace-nowrap.py-4.px-3.text-sm.text-gray-500 (role-badge role)]])

(rum/defc list-roles [roles]
  [:div.mt-8.flow-root
   [:div.-my-2.-mx-4.overflow-x-auto.sm:-mx-6.lg:-mx-8
    [:div.inline-block.min-w-full.py-2.align-middle.sm:px-6.lg:px-8
     [:table.min-w-full.divide-y.divide-gray-300
      [:thead
       [:tr
        [:th.py-3.5.pl-4.pr-3.text-left.text-sm.font-semibold.text-gray-900.sm:pl-0 {:scope "col"} "Project"]
        [:th.py-3.5.px-3.text-left.text-sm.font-semibold.text-gray-900 {:scope "col"} "Date from"]
        [:th.py-3.5.px-3.text-left.text-sm.font-semibold.text-gray-900 {:scope "col"} "Date to"]
        [:th.py-3.5.px-3.text-left.text-sm.font-semibold.text-gray-900 {:scope "col"} "Role"]
        #_[:th.relative.py-3.5.pl-3.pr-4.sm:pr-0 {:scope "col"}
           [:span.sr-only "Edit"]]]]
      [:tbody.divide-y.divide-gray-200
       (for [role roles]
         (list-role role))]]]]])

(rum/defc view-user
  "View a user with optional context. If context is provided, the view will be
  modified accordingly.
  Context options
  :project-id : if provided, roles will be shown for the project specified"
  ([user] (view-user {} user))
  ([{project-id :t_project/id project-title :t_project/title :as ctx}
    {:t_user/keys [full_name first_names last_name postnomial title authentication_method job_title roles] :as user}]
   (tap> {:ctx ctx :user user})
   (misc/description-list {:title    (str full_name " " postnomial)
                           :subtitle job_title}
                          [:<>
                           (misc/description-list-item {:label "Title"
                                                        :content title})
                           (misc/description-list-item {:label   (if (> (count (str/split first_names #"\s")) 1) "First names:" "First name:")
                                                        :content first_names})
                           (misc/description-list-item {:label   "Last name:"
                                                        :content last_name})
                           (misc/description-list-item {:label   "Authentication method:"
                                                        :content (name authentication_method)})
                           (misc/description-list-item {:label   "Professional registration:"
                                                        :content (let [s (str (:t_professional_registration_authority/abbreviation user) " "
                                                                              (:t_user/professional_registration user))
                                                                       href (:t_user/professional_registration_url user)]
                                                                   (if href [:a.underline.text-blue-600.hover:text-blue-800 {:href href} s] s))})

                           (misc/description-list-item {:label   "Active roles"
                                                        :content (list-roles (->> roles
                                                                                  (filter #(and (:t_project/active? %) (:t_project_user/active? %)))
                                                                                  (sort-by :t_project/title)))})
                           (misc/description-list-item {:label "Inactive roles"
                                                        :content (list-roles (->> roles
                                                                                  (remove #(or (:t_project/active %) (:t_project_user/active? %)))
                                                                                  (sort-by :t_project/title)))})])))












