(ns com.eldrix.pc4.server.home
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.pc4.server.ui :as ui]))

(s/def ::project (s/keys :req [:t_project/id :t_project/title :t_project/name :t_project/url]))
(s/def ::article (s/keys :req [:t_news/id :t_news/author :t_news/date_time :t_news/title :t_news/body]))


(s/fdef project-panel
  :args (s/cat :projects (s/coll-of ::project)))
(defn project-panel
  "A simple panel to show the user's own projects."
  [projects]
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
          [:a.cursor-default {:href (:t_project/url project)}
           [:div.px-3.py-1.text-sm.bg-yellow-50.hover:bg-yellow-100.border
            (:t_project/title project)]])])
     (when (and has-clinical has-research)
       [:hr])
     (when has-research
       [:<>
        [:div
         [:span.mt-2.text-xs.inline-block.py-1.px-2.uppercase.bg-pink-200.uppercase.last:mr-0.mr-1 "research"]]
        (for [project (sort-by :t_project/title (:RESEARCH grouped))]
          [:a.cursor-default {:href (:t_project/url project)}
           [:div.px-3.py-1.text-sm.bg-pink-50.hover:bg-pink-100.border
            (:t_project/title project)]])])]))

(defn home-panel
  [{:keys [projects latest-news]}]
  [:div.grid.grid-cols-1.md:grid-cols-4.md:gap-4.m-4
   [:div.md:mr-2
    (project-panel projects)]
   [:div.col-span-3
    [:div.mb-4.bg-white.shadow-lg.overflow-hidden.sm:rounded-md
     [:ul.divide-y.divide-gray-200 {:role "list"}
      (for [article latest-news]
        [:li {:key (:t_news/id article)}
         [:div.px-4.py-4.sm:px-6
          [:div.flex.items-center.justify-between
           [:p.text-lg.font-medium.text-indigo-600.truncate (:t_news/title article)]
           [:div.ml-2.flex-shrink-0.flex
            [:p.px-2.inline-flex.text-xs.leading-5.font-semibold.rounded-full.bg-blue-100.text-green-800
             [:time {:date-time (:t_news/date_time article)} (ui/format-date (:t_news/date_time article))]]]]
          [:div.sm:flex.sm:justify-between
           [:div.mb-2.flex.items-center.text-sm.text-gray-500.sm:mt-0
            [:p "by " (:t_user/first_names article) " " (:t_user/last_name article)]]]
          [:p.text-sm {:dangerouslySetInnerHTML {:__html (:t_news/body article)}}]]])]]]])

(defn main-login-panel
  "PatientCare main login panel with hero title, and parameters:
  - error      - an error message to show."
  [{:keys [username error url disabled]}]
  [:div.flex.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
   [:div.max-w-md.w-full.space-y-8
    [:form {:method "post" :action "/login"}
     (when url [:input {:name "url" :type "hidden" :value url}])
     [:div
      [:h1.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter "PatientCare " [:span.font-bold "v4"]]]
     [:div.rounded-md.shadow-sm
      [:div.mt-8
       [:label.sr-only {:for "username"} "username"]
       [:input#email-address.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
        {:name "username" :type "text" :value username :autoComplete "username" :required true :placeholder "Username" :auto-focus true :disabled disabled}]]
      [:div.mt-2.mb-4
       [:label.sr-only {:for "password"} "Password"]
       [:input#password.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
        {:name "password" :type "password" :autoComplete "current-password" :required true :placeholder "Password" :disabled disabled}]]]
     (when error
       (ui/box-error-message {:message error}))
     [:div.mt-2
      [:button.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500
       {:type "submit" :disabled disabled :class (if disabled "bg-gray-400 animate-pulse" "bg-indigo-600 hover:bg-indigo-700")}
       [:span.absolute.left-0.inset-y-0.flex.items-center.pl-3
        [:svg.h-5.w-5.text-indigo-500.group-hover:text-indigo-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true" :class (when disabled "animate-bounce")}
         [:path {:fill-rule "evenodd" :d "M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" :clip-rule "evenodd"}]]] (if disabled "Signing in" "Sign in")]]]]])