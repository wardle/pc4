(ns eldrix.pc4-ward.core
  (:require [reagent.dom :as rdom]
            [reitit.core :as r]
            [reitit.coercion.spec :as rss]
            [reitit.frontend :as rf]
            [reitit.frontend.controllers :as rfc]
            [reitit.frontend.easy :as rfe]
            [re-frame.core :as re-frame]
            [eldrix.pc4-ward.subs :as subs]
            [eldrix.pc4-ward.user.subs :as user-subs]
            [eldrix.pc4-ward.user.events :as user-events]
            [eldrix.pc4-ward.events :as events]
            [eldrix.pc4-ward.views :as views]
            [eldrix.pc4-ward.refer.views :as refer]
            [eldrix.pc4-ward.config :as config]
            [eldrix.pc4-ward.ui :as ui]))

(re-frame/reg-fx :push-state
  (fn [route]
    (apply rfe/push-state route)))

(re-frame/reg-event-fx ::push-state
  (fn [db [_ & route]]
    {:push-state route}))

(defn href
  "Return relative url for given route. Url can be used in HTML links."
  ([k]
   (href k nil nil))
  ([k params]
   (href k params nil))
  ([k params query]
   (rfe/href k params query)))

(defn routes []
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
     :link-text "Refer"}]])

(defn on-navigate [new-match]
  (when new-match
    (re-frame/dispatch [::events/navigate new-match])))

(def router
  (rf/router
    (routes)
    {:data {:coercion rss/coercion}}
    ))

(defn init-routes! []
  (js/console.log "initializing routes")
  (rfe/start!
    router
    on-navigate
    {:use-fragment false}))

(defn router-component
  [{:keys [router]}]
  (let [authenticated-user @(re-frame/subscribe [::user-subs/authenticated-user])
        current-route (or @(re-frame/subscribe [::subs/current-route]) (r/match-by-path router "/"))]
    [:div
     [views/nav-bar
      :route current-route
      :title "PatientCare v4"
      :menu [{:id :home :title "Home" :href (href :home)}
             {:id :refer :title "Refer" :href (href :refer)}]
      :selected (when current-route (-> current-route :data :name))
      :show-user? authenticated-user
      :full-name (:urn:oid:2.5.4/commonName authenticated-user)
      :initials (:urn:oid:2.5.4/initials authenticated-user)
      :user-menu [{:id :logout :title "Sign out" :on-click #(re-frame/dispatch [::user-events/do-logout])}]]
     (if current-route
       [(-> current-route :data :view) current-route]
       [:p "No current route"])]))

(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (rdom/render [router-component {:router router}] (.getElementById js/document "app"))
  ;;(rdom/render [refer3/refer-page] (.getElementById js/document "app"))
  )

(defn init []
  (re-frame/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (init-routes!)                                            ;; Reset routes on reload
  (mount-root))

(init)

(comment
  )