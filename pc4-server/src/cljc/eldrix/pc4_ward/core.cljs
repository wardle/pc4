(ns eldrix.pc4-ward.core
  (:require [cljs.spec.alpha :as s]
            [clojure.pprint]
            [edn-query-language.core :as eql]
            [reagent.dom :as rdom]
            [reitit.coercion.spec :as rss]
            [reitit.frontend :as r]
            [reitit.frontend.controllers :as rfc]
            [reitit.frontend.easy :as rfe]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.subs :as subs]
            [eldrix.pc4-ward.events :as events]
            [eldrix.pc4-ward.views :as views]
            [eldrix.pc4-ward.patient.events :as patient-events]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.user.views :as user-views]
            [eldrix.pc4-ward.refer.views :as refer]
            [eldrix.pc4-ward.project.events :as project-events]
            [eldrix.pc4-ward.project.views :as project]
            [eldrix.pc4-ward.config :as config]
            [eldrix.pc4-ward.ui :as ui]
            [pc4.patients :as patients]
            [pc4.projects :as projects]))

(defn href
  "Return relative url for given route. Url can be used in HTML links."
  ([k]
   (href k nil nil))
  ([k params]
   (href k params nil))
  ([k params query]
   (rfe/href k params query)))

(def pathom-controller
  "A reitit controller that dispatches a pathom load event with data from route.
  - query  - EQL"
  {:identity (fn [{:keys [data parameters] :as _match}]
               (tap> {:pathom-controller _match})
               (let [route-name (:name data)
                     query (get-in data [:component :query])
                     targets (get-in data [:component :targets])]
                 ;; return controller identity as a query based on parameters and any targets
                 {:id      route-name
                  :query   (if (fn? query) (query parameters) query)
                  :targets (if (fn? targets) (targets parameters) targets)}))
   :start    (fn [{:keys [query] :as m}]
               ;; normalise and merge data into app database
               (rf/dispatch [:eldrix.pc4-ward.server/load m]))})
(defn view
  "Returns a view for the given route, automatically subscribing to any data
  loaded as a consequence of the route controller.
  A route may have a :view or :component key.
  - :view - a function that takes a route & returns data (no data subscription)
  - :component - a map declaring a 'component'

  A component is defined by following keys
  - :query   - a function to return a query given route parameters
  - :view    - a 2-arity function taking ctx and data from query
  - :targets - (optional) - a path to which data will be `assoc-in`-ed

  If there is no target for a given result, data will be normalised."
  [route]
  (let [parameters (:parameters route)
        route-name (-> route :data :name)
        {:keys [query targets view] :as component} (-> route :data :component)]
    (if component
      (let [query' (query parameters)
            results @(rf/subscribe [:eldrix.pc4-ward.server/pull query' targets])
            loading @(rf/subscribe [:eldrix.pc4-ward.server/loading route-name])]
        [view (assoc route :loading loading) results])
      (let [view (-> route :data :view)]
        [view route]))))

(defn routes
  "Routes define the high-level URLs for the application.
  A route contains additional configuration including:

  - auth  - a function that will be given the current authenticated user"
  []
  [["/"
    {:name      :home
     :view      views/main-page
     :link-text "Home"
     :auth      identity
     :controllers
     [{;; Do whatever initialization needed for home page
       ;; I.e (re-frame/dispatch [::events/load-something-with-ajax])
       :start (fn [& params] (js/console.log "Entering home page"))
       ;; Teardown can be done here.
       :stop  (fn [& params] (js/console.log "Leaving home page"))}]}]

   ["/login"
    {:name :login
     :view views/login-page}]

   ["/refer"
    {:name      :refer
     :title     "Refer"
     :view      refer/refer-page
     :auth      (constantly false)
     :link-text "Refer"}]

   ["/change-password"
    {:name      :change-password
     :title     "Change password"
     :auth      identity                                    ;; we need a logged in user to change password
     :view      user-views/change-password
     :link-text "Change password"}]

   ["/projects/:project-id/home"
    {:name        :project/home
     :title       "Project"
     :component   projects/home-page
     :auth        identity
     :parameters  {:path {:project-id int?}}
     :controllers [pathom-controller]}]

   ["/projects/:project-id/find-pseudonymous-patient"
    {:name        :project/find-pseudonymous-patient
     :component   projects/find-pseudonymous-patient
     :auth        identity
     :parameters  {:path {:project-id int?}}
     :controllers [{:start (fn [_] (rf/dispatch [::patient-events/clear-search-legacy-pseudonym-results]))}
                   pathom-controller]}]

   ["/projects/:project-id/register-pseudonymous-patient"
    {:name        :project/register-pseudonymous-patient
     :component   projects/register-pseudonymous-patient
     :auth        identity
     :parameters  {:path {:project-id int?}}
     :controllers [pathom-controller]}]

   ["/projects/:project-id/team"
    {:name        :project/team
     :component   projects/team-page
     :auth        identity
     :parameters  {:path {:project-id int?}}
     :controllers [pathom-controller]}]

   ["/projects/:project-id/downloads"
    {:name        :project/downloads
     :component   projects/downloads
     :auth        identity
     :parameters  {:path {:project-id int?}}
     :controllers [pathom-controller]}]

   ["/projects/:project-id/patients/pseudonym/:pseudonym/home"
    {:name        :pseudonymous-patient/home
     :component   pc4.patients/neuroinflamm-page
     :auth        identity
     :parameters  {:path {:project-id int? :pseudonym string?}}
     :controllers [pathom-controller]}]

   ["/projects/:project-id/patients/pseudonym/:pseudonym/diagnoses"
    {:name        :pseudonymous-patient/diagnoses
     :component   pc4.patients/diagnoses-page
     :auth        identity
     :parameters  {:path  {:project-id int? :pseudonym string?}
                   :query (s/keys :opt-un [::filter])}
     :controllers [pathom-controller]}]

   ["/projects/:project-id/patients/pseudonym/:pseudonym/medication"
    {:name        :pseudonymous-patient/medication
     :component   pc4.patients/medication-page
     :auth        identity
     :parameters  {:path {:project-id int? :pseudonym string?}
                   :query (s/keys :opt-un [::filter])}
     :controllers [pathom-controller]}]

   #_["/projects/:project-id/patients/pseudonym/:pseudonym"
      {:name        :patient-by-project-pseudonym
       :view        project/view-pseudonymous-patient
       :auth        identity                                ;; we need a logged in user to view a patient
       :parameters  {:path {:project-id int? :pseudonym string?}}
       :controllers [{:parameters {:path [:project-id :pseudonym]}
                      :start      (fn [{:keys [path]}]
                                    (println "viewing patient by pseudonym page" (:project-id path) (:pseudonym path))
                                    (rf/dispatch [::project-events/open-project (:project-id path)])
                                    (rf/dispatch [::patient-events/open-pseudonymous-patient (:project-id path) (:pseudonym path)]))
                      :stop       (fn [{:keys [path]}]
                                    (println "leaving pseudonymous patient page" (:pseudonym path))
                                    (rf/dispatch [::patient-events/close-current-patient])
                                    (rf/dispatch [::project-events/close-current-project]))}]}]

   ["/patients/id/:patient-identifier/home"
    {:name        :patient/home
     :component   pc4.patients/neuroinflamm-page
     :auth        identity                                  ;; we need a logged in user to view a patient
     :parameters  {:path {:patient-identifier int?}}
     :controllers [pathom-controller]}]

   ["/patients/id/:patient-identifier/diagnoses"
    {:name        :patient/diagnoses
     :component   pc4.patients/diagnoses-page
     :auth        identity                                  ;; we need a logged in user to view a patient
     :parameters  {:path  {:patient-identifier int?}
                   :query (s/keys :opt-un [::filter])}
     :controllers [pathom-controller]}]

   ["/patients/id/:patient-identifier/medication"
    {:name        :patient/medication
     :component   pc4.patients/medication-page
     :auth        identity                                  ;; we need a logged in user to view a patient
     :parameters  {:path {:patient-identifier int?}
                   :query (s/keys :opt-un [::filter])}
     :controllers [pathom-controller]}]

   #_["/projects/:project-id/patients/id/:patient-identifier"
      {:name        :patient-by-project-and-patient-identifier
       :title       "Patient"
       :view        project/view-pseudonymous-patient
       :auth        identity                                ;; we need a logged in user to view a patient
       :parameters  {:path {:project-id int? :patient-identifier int?}}
       :controllers [{:parameters {:path [:project-id :patient-identifier]}
                      :start      (fn [{:keys [path]}]
                                    (println "viewing patient by project page" (:project-id path) (:patient-identifier path))
                                    (rf/dispatch [::project-events/open-project (:project-id path)])
                                    (rf/dispatch [::patient-events/open-patient (:project-id path) (:patient-identifier path)]))
                      :stop       (fn [{:keys [path]}]
                                    (println "leaving patient page" (:patient-identifier path))
                                    (rf/dispatch [::patient-events/close-current-patient])
                                    (rf/dispatch [::project-events/close-current-project]))}]}]])

(defn on-navigate [new-match]
  (when new-match
    (rf/dispatch [::events/navigate new-match])))

(def router
  (r/router
    (routes)
    {:data {:coercion rss/coercion}}))

(defn init-routes! []
  (js/console.log "initializing routes")
  (rfe/start!
    router
    on-navigate
    {:use-fragment true}))


(defn router-component
  [router]
  (let [authenticated-user @(rf/subscribe [::user-subs/authenticated-user])
        current-route (or @(rf/subscribe [::subs/current-route]) (r/match-by-path router "/"))
        auth (get-in current-route [:data :auth])]
    [:div
     [views/nav-bar
      :route current-route
      :title "PatientCare v4"
      :menu []                                              ;[{:id :home :title "Home" :href (href :home)}  {:id :refer :title "Refer" :href (href :refer)}]
      :selected (when current-route (-> current-route :data :name))
      :show-user? authenticated-user
      :full-name (:urn:oid:2.5.4/commonName authenticated-user)
      :initials (:urn:oid:2.5.4/initials authenticated-user)
      :user-menu [{:id :change-password :title "Change password" :on-click #(rfe/push-state :change-password)}
                  {:id :logout :title "Sign out" :on-click #(rf/dispatch [::user-events/do-logout])}]]
     (if (or (nil? auth) (auth authenticated-user))
       (view current-route)
       (if authenticated-user
         [:div.flex.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
          [:div.max-w-md.w-full.space-y-8
           [ui/box-error-message
            :title "Not authorized"
            :message "You are not authorized to view this page."]]]
         (rf/dispatch [::events/push-state :login])))]))



(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (rf/clear-subscription-cache!)
  (init-routes!)
  ;;(rdom/render [refer3/refer-page] (.getElementById js/document "app")
  (rdom/render [router-component router]
               (.getElementById js/document "app")))



(defn init []
  (rf/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (init-routes!)                                            ;; Reset routes on reload
  (mount-root))

(comment
  (init-routes!)
  (rfe/href :projects {:project-name "NINFLAMMCARDIFF"}))
