(ns app.ui.root
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.dom :as dom :refer [div ul li p h3 button b]]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro-css.css :as css]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [app.ui.components]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.data-fetch :as df]))

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

(dr/defrouter TopRouter [this props]
  {:router-targets [Main  Settings]})

(def ui-top-router (comp/factory TopRouter))


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

(defsc Root [this {:root/keys [ selected-concept]}]
  {:query         [{:root/selected-concept (comp/get-query SnomedConcept)}]
   :initial-state {}}
  (div (dom/h1 "Hi there")
       (ui-snomed-concept selected-concept)
       (app.ui.components/ui-placeholder {:w 50 :h 50 :label "avatar"} )))

(comment
  (comp/get-query Root)

  )