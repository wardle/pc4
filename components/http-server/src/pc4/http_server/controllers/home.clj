(ns pc4.http-server.controllers.home
  (:require
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.http-server.html :as html]
    [pc4.http-server.ui :as ui]
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
           [:span.hidden.sm:block.text-white [:span.flex (or (:full-name user) "User") (ui/icon-chevron-down)]]
           [:span.sm:hidden.text-white [:div.flex (when (:initials user) (:initials user)) (ui/icon-chevron-down)]]]]

         (when (and (:menu-open? user) (seq (:menu user)))
           [:div.origin-top-right.absolute.z-50.right-0.mt-2.w-48.rounded-md.shadow-lg.py-1.bg-white.ring-1.ring-black.ring-opacity-5.focus:outline-none
            {:role "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabIndex "-1"}
            (for [item (:menu user)]
              (if (:attrs item)
                [:a.block.px-4.py-2.text-sm.text-gray-700.hover:bg-gray-700.hover:text-white
                 (merge {:role "menuitem" :tabIndex "-1"} (:attrs item)) (:title item)]
                [:span.block.px-4.py-2.text-sm.italic.text-gray-600 (:title item)]))])])]]]])

#_(defn navigation-bar
    [ctx]
    (let [authenticated-user (get-in ctx [:request :session :authenticated-user])
          show-user-menu? (some-> (get-in ctx [:request :params :show-user-menu]) parse-boolean)]
      (nav-bar
        (cond-> {:id    "nav-bar"
                 :title {:s "PatientCare v4" :attrs {:href (route/url-for :home)}}}
          authenticated-user
          (assoc :user {:full-name  (:t_user/full_name authenticated-user)
                        :initials   (:t_user/initials authenticated-user)
                        :attrs      {:hx-get    (r/match->path (r/match-by-name! router :nav-bar) {:show-user-menu (not show-user-menu?)})
                                     :hx-target "#nav-bar" :hx-swap "outerHTML"}
                        :photo      (when (:t_user/has_photo authenticated-user) {:src (r/match->path (r/match-by-name! router :get-user-photo {:system "patientcare.app" :value (:t_user/username authenticated-user)}))})
                        :menu-open? show-user-menu?
                        :menu       [{:id    :about
                                      :title (:t_user/job_title authenticated-user)}
                                     {:id    :view-profile
                                      :title "My profile"
                                      :attrs {:href (r/match->path (r/match-by-name! router :get-user {:user-id (:t_user/id authenticated-user)}))}}
                                     {:id    :logout :title "Logout"
                                      :attrs {:hx-post     (r/match->path (r/match-by-name router :logout))
                                              :hx-push-url "true"
                                              :hx-target   "body"
                                              :hx-vals     (str "{\"__anti-forgery-token\" : \"" (get-in ctx [:request ::csrf/anti-forgery-token]) "\"}")}}]})))))

(def home
  {:name :home
   :enter
   (fn [{:keys [request] :as ctx}]
     (assoc ctx :response
                (html/html5 {:title "pc4/home"}
                            [:<>
                               [:form {:method "post" :action (route/url-for :logout)}
                                [:input {:type "hidden" :name "__anti-forgery-token" :value (csrf/existing-token request)}]
                                (ui/button {:s "Logout"})]
                               [:p "Hello there" (get-in request [:session :authenticated-user])]])))})
