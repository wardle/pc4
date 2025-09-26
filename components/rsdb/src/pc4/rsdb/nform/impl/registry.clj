(ns pc4.rsdb.nform.impl.registry
  "Form registry; independent of storage mechanism. This namespace deals only
  with pure data. Forms are returned from the store and hydrated and must be
  dehydrated before being passed to the store. This permits as required
  parameter transformation, or generation of computed properties, and the
  provision of a 'summary'.

  Form definition - a definition of a form with title, and specification
  Form type       - a keyword defining a versioned form  <form-name>/<version>"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

;;
;; Form definitions
;; This is a central registry of form definitions encompassing both new forms
;; and legacy WO forms
;;

(s/def ::id keyword?)
(s/def ::title string?)

(defmulti form-def-spec :store)

(defmethod form-def-spec :nf [_]
  (s/keys :req-un [::id ::title]))

(defmethod form-def-spec :wo [_]
  (s/keys :req-un [::id ::title ::table ::entity]))

(s/def ::form-definition (s/multi-spec form-def-spec :store))

(def all-form-definitions
  [{:id     :alsfrs-r/v1
    :title  "ALSFRS-R"
    :store  :wo
    :table  :t_form_alsfrs
    :entity "FormALSFRS"}

   {:id    :araf-val-f-s1-evaluation/v2_0
    :title "Valproate Annual Risk Acknowledgement (female) - Step 1: Status"
    :store :nf}

   {:id    :araf-val-f-s2-treatment-decision/v2_0
    :title "Valproate Annual Risk Acknowledgement (female) - Step 2: Treatment decision"
    :store :nf}

   {:id    :araf-val-f-s3-risks/v2_0
    :title "Valproate Annual Risk Acknowledgement (female) - Step 3: Risks explained"
    :store :nf}

   {:id    :araf-val-f-s2-countersignature/v2_0
    :title "Valproate Annual Risk Acknowledgement (female) - Step 2: Countersignature"
    :store :nf}

   {:id    :araf-val-f-s4-request-acknowledgement/v2_0
    :title "Valproate Annual Risk Acknowledgement (female) - Step 4: Request for acknowledgement"
    :store :nf}

   {:id    :araf-val-f-s4-acknowledgement/v2_0
    :title "Valproate Annual Risk Acknowledgement (female) - Step 4: Acknowledgement"
    :store :nf}

   {:id     :edss/v1
    :title  "Expanded Disability Status Score (EDSS)"
    :store  :wo
    :table  :t_form_edss
    :entity "FormEdss"}

   {:id     :relapse/v1
    :title  "Neuroinflammatory relapse and disease course"
    :store  :wo
    :table  :t_form_ms_relapse
    :entity "FormMsRelapse"}])

(def all-form-types
  "A set of form type identifiers"
  (into #{} (map :id) all-form-definitions))

(def all-wo-tables
  (into #{} (comp (map :table) (remove nil?)) all-form-definitions))

(s/def ::form_type all-form-types)
(s/def ::table all-wo-tables)

(doseq [fd all-form-definitions]
  (when-not (s/valid? ::form-definition fd)
    (throw (ex-info (str "Invalid form definition:" (s/explain-str ::form-definition fd)) {:fd fd :err (s/explain-data ::form-definition fd)}))))

(defn gen-id
  "Returns a generator of ids for the given form definition"
  [{:keys [store table] :as form-definition}]
  (case store
    :nf (gen/uuid)
    :wo (gen/tuple (gen/return table) (gen/fmap (comp inc abs) (gen/large-integer)))))

(def form-definition-by-form-type
  "Map of form-type id to form definition"
  (reduce (fn [acc {:keys [id] :as form-def}]
            (assoc acc id form-def))
          {} all-form-definitions))

(def form-definition-by-table
  (reduce (fn [acc {:keys [table] :as form-def}]
            (if table (assoc acc table form-def) acc))
          {} all-form-definitions))

(comment
  (form-definition-by-form-type :edss/v1))