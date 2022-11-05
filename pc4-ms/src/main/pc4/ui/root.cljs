(ns pc4.ui.root
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :as dom :refer [div ul li p h3 button b]]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [pc4.app :refer [SPA]]
    [pc4.ui.core :as ui]
    [pc4.ui.patients :as patients]
    [pc4.ui.users :as users]
    [pc4.ui.projects :as projects]
    [taoensso.timbre :as log]))


(defn field [{:keys [label valid? error-message] :as props}]
  (let [input-props (-> props (assoc :name label) (dissoc :label :valid? :error-message))]
    (div :.ui.field
         (dom/label {:htmlFor label} label)
         (dom/input input-props)
         (dom/div :.ui.error.message {:classes [(when valid? "hidden")]}
                  error-message))))


(defsc Main [this props]
  {:query         [:main/welcome-message]
   :initial-state {:main/welcome-message "Hi!"}
   :ident         (fn [] [:component/id :main])
   :route-segment ["home"]}
  (div :.ui.container.segment
       (h3 "Main")
       (p (str "Welcome to the Fulcro template. "
               "The Sign up and login functionalities are partially implemented, "
               "but mostly this is just a blank slate waiting "
               "for your project."))))

(defsc Settings [this {:keys [:account/time-zone :account/real-name] :as props}]
  {:query         [:account/time-zone :account/real-name :account/crap]
   :ident         (fn [] [:component/id :settings])
   :route-segment ["settings"]
   :initial-state {}}
  (div :.ui.container.segment
       (h3 "Settings")
       (div "TODO")))


(defsc SnomedDescription [this {:info.snomed.Description/keys [term] :as props}]
  {:query [:info.snomed.Description/id :info.snomed.Description/term]
   :ident (fn [] [:info.snomed.Description/id (:info.snomed.Description/id props)])}
  (dom/span term))

(def ui-snomed-description (comp/factory SnomedDescription))

(defsc SnomedConcept [this {:info.snomed.Concept/keys [id preferredDescription] :as props}]
  {:query [:info.snomed.Concept/id :info.snomed.Concept/preferredDescription]
   :ident (fn [] [:info.snomed.Concept/id (:info.snomed.Concept/id props)])}
  (ui-snomed-description preferredDescription))

(def ui-snomed-concept (comp/factory SnomedConcept))

(defsc HomePage
  [this {:session/keys [authenticated-user]}]
  {:ident         (fn [] [:component/id :home])
   :query         [{[:session/authenticated-user '_] (comp/get-query pc4.ui.users/UserHomePage)}]
   :route-segment ["home"]
   :initial-state {:session/authenticated-user {}}}
  (js/console.log "HomePage: authenticated user = " authenticated-user)
  (when (seq authenticated-user) (pc4.ui.users/ui-user-home-page authenticated-user)))

(def ui-home-page (comp/factory HomePage))


(defrouter MainRouter [this props]
  {:router-targets [HomePage
                    pc4.ui.projects/ProjectPage
                    pc4.ui.patients/PatientPage]})

(def ui-main-router (comp/factory MainRouter))

(defsc Root [this {authenticated-user :session/authenticated-user
                   router             :ui/main-router
                   login              :ui/login
                   :ui/keys           [airport-input]}]
  {:query [{:session/authenticated-user (comp/get-query users/NavBar)}
           {:ui/main-router (comp/get-query MainRouter)}
           {:ui/login (comp/get-query users/Login)}
           {:ui/airport-input (comp/get-query pc4.ui.snomed/Autocomplete)}]
   :initial-state #_{:ui/main-router             {}
                     :ui/login                   {}
                     :session/authenticated-user {}}
   (fn [p] {:ui/main-router             (comp/get-initial-state MainRouter)
            :ui/login                   (comp/get-initial-state users/Login)
            :session/authenticated-user (comp/get-initial-state users/NavBar)
            :ui/airport-input           (comp/get-initial-state pc4.ui.snomed/Autocomplete {:id :airports})})}
  (div
    (if-not (seq authenticated-user)
      (users/ui-login login)
      (comp/fragment (users/ui-nav-bar authenticated-user)
                     (dom/div :.m-8.p-8 {:class "w-1/4"}
                       (pc4.ui.snomed/ui-autocomplete airport-input {:onSelect #(println "SELECTED " %)}))
                     #_(ui-main-router router)))))

(comment
  (comp/get-query Root))

