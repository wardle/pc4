(ns eldrix.pc4-ward.core
  (:require [reagent.dom :as rdom]
            [reitit.core :as r]
            [reitit.coercion.spec :as rss]
            [reitit.frontend :as rf]
            [reitit.frontend.controllers :as rfc]
            [reitit.frontend.easy :as rfe]
            [re-frame.core :as re-frame]
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
            [eldrix.pc4-ward.ui :as ui]))

(defn href
  "Return relative url for given route. Url can be used in HTML links."
  ([k]
   (href k nil nil))
  ([k params]
   (href k params nil))
  ([k params query]
   (rfe/href k params query)))

(defn routes
  "Routes define the high-level URLs for the application.
  A route contains additional configuration including:

  - auth  - a function that will be given the current authenticated user"
  []
  [["/"
    {:name      :home
     :view      views/main-page
     :link-text "Home"
     :controllers
     [{;; Do whatever initialization needed for home page
       ;; I.e (re-frame/dispatch [::events/load-something-with-ajax])
       :start (fn [& params] (js/console.log "Entering home page"))
       ;; Teardown can be done here.
       :stop  (fn [& params] (js/console.log "Leaving home page"))}]}]
   ["/refer"
    {:name      :refer
     :title     "Refer"
     :view      refer/refer-page
     :link-text "Refer"}]

   ["/change-password"
    {:name      :change-password
     :title     "Change password"
     :auth        identity                                  ;; we need a logged in user to change password
     :view      user-views/change-password
     :link-text "Change password"}]

   ["/projects/:project-id/:slug"
    {:name        :projects
     :title       "Projects"
     :view        project/project-home-page
     :auth        identity                                  ;; we need a logged in user to view a project
     :parameters  {:path {:project-id int? :slug string?}}
     :controllers [{:parameters {:path [:project-id :slug]}
                    :start      (fn [{:keys [path]}]
                                  (println "entering project page" (:project-id path))
                                  (re-frame/dispatch [::project-events/set-current-project (:project-id path)]))
                    :stop       (fn [{:keys [path]}]
                                  (println "leaving project page" (:project-id path))
                                  (re-frame/dispatch [::project-events/close-current-project]))}]}]

   ["/projects/:project-id/patients/pseudonym/:pseudonym"
    {:name        :patient-by-project-pseudonym
     :title       "Patient"
     :view        project/view-pseudonymous-patient
     :auth        identity                                  ;; we need a logged in user to view a patient
     :parameters  {:path {:project-id int? :pseudonym string?}}
     :controllers [{:parameters {:path [:project-id :pseudonym]}
                    :start      (fn [{:keys [path]}]
                                  (println "viewing patient by pseudonym page" (:project-id path) (:pseudonym path))
                                  (re-frame/dispatch [::project-events/set-current-project (:project-id path)])
                                  (re-frame/dispatch [::patient-events/open-pseudonymous-patient (:project-id path) (:pseudonym path)]))
                    :stop       (fn [{:keys [path]}]
                                  (println "leaving pseudonymous patient page" (:pseudonym path))
                                  (re-frame/dispatch [::patient-events/close-current-patient])
                                  (re-frame/dispatch [::project-events/close-current-project]))}]}]
   ["/projects/:project-id/patients/id/:patient-identifier"
    {:name        :patient-by-project-and-patient-identifier
     :title       "Patient"
     :view        project/view-pseudonymous-patient
     :auth        identity                                  ;; we need a logged in user to view a patient
     :parameters  {:path {:project-id int? :patient-identifier int?}}
     :controllers [{:parameters {:path [:project-id :patient-identifier]}
                    :start      (fn [{:keys [path]}]
                                  (println "viewing patient by project page" (:project-id path) (:patient-identifier path))
                                  (re-frame/dispatch [::project-events/set-current-project (:project-id path)])
                                  (re-frame/dispatch [::patient-events/open-patient (:project-id path) (:patient-identifier path)]))
                    :stop       (fn [{:keys [path]}]
                                  (println "leaving patient page" (:patient-identifier path))
                                  (re-frame/dispatch [::patient-events/close-current-patient])
                                  (re-frame/dispatch [::project-events/close-current-project]))}]}]])

(defn on-navigate [new-match]
  (when new-match
    (re-frame/dispatch [::events/navigate new-match])))

(def router
  (rf/router
    (routes)
    {:data {:coercion rss/coercion}}))

(defn init-routes! []
  (js/console.log "initializing routes")
  (rfe/start!
    router
    on-navigate
    {:use-fragment true}))




(defn router-component
  [{:keys [router]}]
  (let [authenticated-user @(re-frame/subscribe [::user-subs/authenticated-user])
        current-route (or @(re-frame/subscribe [::subs/current-route]) (r/match-by-path router "/"))
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
                  {:id :logout :title "Sign out" :on-click #(re-frame/dispatch [::user-events/do-logout])}]]
     (if (or (nil? auth) (auth authenticated-user))
       [(-> current-route :data :view) current-route]
       [:div.flex.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
        [:div.max-w-md.w-full.space-y-8
         [ui/box-error-message :title "Not authorized" :message (str "You are not authorized to view this page. " (when-not authenticated-user "Please login."))]]])]))

(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (init-routes!)
  (rdom/render [router-component {:router router}] (.getElementById js/document "app")))
  ;;(rdom/render [refer3/refer-page] (.getElementById js/document "app"))


(defn init []
  (re-frame/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (init-routes!)                                            ;; Reset routes on reload
  (mount-root))

(comment
  (init-routes!)
  (rfe/href :projects {:project-name "NINFLAMMCARDIFF"}))
