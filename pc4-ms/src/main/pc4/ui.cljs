(ns pc4.ui
  (:require
    [pc4.mutations :as api]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div p h3]]))

(defsc Description [this {:info.snomed.Description/keys [id term]}]
  {:query [:info.snomed.Description/id :info.snomed.Description/term]}
  (div
    (p "Term: " term)))

(def ui-description (comp/factory Description {:keyfn :info.snomed.Description/id}))

(defsc Concept [this {:info.snomed.Concept/keys [id active preferredDescription] :as props}]
  {:query [:info.snomed.Concept/id :info.snomed.Concept/active {:info.snomed.Concept/preferredDescription (comp/get-query Description)}]
   :ident (fn [] [:info.snomed.Concept/id (:info.snomed.Concept/id props)])}
  (div "Concept:"
       (p "concept-id:" id)
       (p "active?" active)
       (p "preferred description " (ui-description preferredDescription))))

(def ui-concept (comp/factory Concept {:keyfn :info.snomed.Concept/id}))

(defsc Person [this {:person/keys [name age]}]
  {:query         [:person/name :person/age]
   :initial-state (fn [{:keys [name age] :as params}] {:person/name name :person/age age})}
  (dom/li
    (dom/h5 (str name " (age: " age ")"))))

;; The keyfn generates a react key for each element based on props. See React documentation on keys.
(def ui-person (comp/factory Person {:keyfn :person/name}))

(defsc PersonList [this {:list/keys [label people]}]
  (dom/div
    (dom/h4 label)
    (dom/ul
      (map ui-person people))))

(def ui-person-list (comp/factory PersonList))

(defsc Root [this {:keys [concept]}]
  {:query [{:concept (comp/get-query Concept)}]}
    (div
      (h3 "Concept")
      (ui-concept concept)
      ))

