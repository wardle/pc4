(ns eldrix.pc4-ward.core
  (:require [reagent.dom :as rdom]
            [reitit.core :as r]
            [reitit.coercion.spec :as rss]
            [reitit.frontend :as rf]
            [reitit.frontend.controllers :as rfc]
            [reitit.frontend.easy :as rfe]
            [re-frame.core :as re-frame]
            [eldrix.pc4-ward.events :as events]
            [eldrix.pc4-ward.views :as views]
   ;;         [eldrix.pc4-ward.refer :as refer]
   ;;         [eldrix.pc4-ward.refer2 :as refer2]
            [eldrix.pc4-ward.refer.views :as refer3]
            [eldrix.pc4-ward.config :as config]))

(re-frame/reg-fx :push-state
  (fn [route]
    (apply rfe/push-state route)))

(re-frame/reg-event-fx ::push-state
  (fn [db [_ & route]]
    {:push-state route}))

(re-frame/reg-event-db ::navigated
  (fn [db [_ new-match]]
    (let [old-match (:current-route db)
          controllers (rfc/apply-controllers (:controllers old-match) new-match)]
      (assoc db :current-route (assoc new-match :controllers controllers)))))

(re-frame/reg-sub ::current-route
  (fn [db]
    (:current-route db)))

(defn href
  "Return relative url for given route. Url can be used in HTML links."
  ([k]
   (href k nil nil))
  ([k params]
   (href k params nil))
  ([k params query]
   (rfe/href k params query)))


(defn test-parameters-page
  [route]
  (js/console.log "test parameters page : " route)
  (let [{:keys [system-id value-id patient-id]} (:path-params route)]
    [:<>
     [:h1 "Hello Mark"]
     [:ul [:li "system id:" system-id]
      [:li "value id:" value-id]
      [:li "patient id " patient-id]]]))

(defn routes []
  [["/"
    {:name      ::home
     :view      views/main-page
     :link-text "Home"
     :controllers
                [{;; Do whatever initialization needed for home page
                  ;; I.e (re-frame/dispatch [::events/load-something-with-ajax])
                  :start (fn [& params] (js/console.log "Entering home page"))
                  ;; Teardown can be done here.
                  :stop  (fn [& params] (js/console.log "Leaving home page"))}]}]
   ["/{system-id}/{value-id}/{patient-id}/test-params"
    {:name        ::refer
     :view        test-parameters-page
     :controllers [{:start      (fn [& params] (js/console.log "entering refer page: " params))
                    :parameters {:path [:namespace-id :patient-id]}}]}]])

(defn on-navigate [new-match]
  (when new-match
    (re-frame/dispatch [::navigated new-match])))

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
  (let [current-route @(re-frame/subscribe [::current-route])]
    [:div
     (when current-route
       [(-> current-route :data :view) current-route])]))


(defn dev-setup []
  (when config/debug?
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  ;;(rdom/render [router-component {:router router}] (.getElementById js/document "app"))
  (rdom/render [refer3/refer-page] (.getElementById js/document "app"))
  )

(defn init []
  (re-frame/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (init-routes!)                                            ;; Reset routes on reload
  (mount-root))

(comment
  )