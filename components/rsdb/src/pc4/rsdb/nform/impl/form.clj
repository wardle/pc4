(ns pc4.rsdb.nform.impl.form
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [pc4.rsdb.nform.impl.registry :as registry])
  (:import (java.time LocalDateTime)))

(defmulti spec
  "Return a clojure.spec for a store-independent form. "
  :form_type)

(defmulti summary
  "Generate a human-readable summary of the form."
  :form_type)

(defmulti hydrate
  "Transform a form from its store."
  :form_type)

(defmulti dehydrate
  "Transform a form for storage in its store."
  :form_type)

(defmulti compute
  "Compute any computed fields for a form."
  :form_type)

(defmulti init
  "Initialise a form within context of specified encounter.
  Parameters:
  - conn         : a next.jdbc 'Connectable'
  - form         : form, with at least :form_type specified
  - encounter-id : encounter identifier"
  (fn [_conn {:keys [form_type]} _encounter-id] form_type))

;; default implementations

(defmethod summary :default [form] "")
(defmethod hydrate :default [form] form)
(defmethod dehydrate :default [form] form)
(defmethod init :default [form] form)
(defmethod compute :default [form] nil)

;;
;;
;;

(s/def ::form_type ::registry/form_type)

(s/def ::patient_fk
  (s/with-gen int? #(gen/choose 1 10000)))

(s/def ::encounter_fk
  (s/with-gen int? #(gen/choose 1 50000)))

(s/def ::user_fk
  (s/with-gen int? #(gen/choose 1 1000)))

(s/def ::table registry/all-wo-tables)
(s/def ::pk pos-int?)
(s/def ::nfid uuid?)
(s/def ::id (s/nilable (s/or :nf ::nfid :wo (s/tuple ::table ::pk))))

(s/def ::created
  (s/with-gen
    #(instance? LocalDateTime %)
    #(gen/fmap (fn [days-ago]
                 (.minusDays (LocalDateTime/now) days-ago))
               (gen/choose 0 3650))))

(s/def ::is_deleted
  (s/with-gen boolean? #(gen/frequency [[9 (gen/return false)] [1 (gen/return true)]])))

;; all forms have a core set of properties, irrespective of underlying store
(s/def ::core
  (s/keys :req-un [::id ::form_type ::patient_fk ::encounter_fk ::user_fk ::created ::is_deleted]))

(s/def ::core-for-insert
  (s/keys :req-un [::form_type ::patient_fk ::encounter_fk ::user_fk ::is_deleted]
          :opt-un [::id ::created]))

(s/def ::core-for-update
  (s/keys :req-un [::id ::form_type ::is_deleted]           ;; must supply id form_type and is_deleted - e.g. if omitted is_deleted will be set to false!
          :opt-un [::patient_fk ::encounter_fk ::user_fk]))

;; and then forms have specific properties...
(s/def ::ext (s/multi-spec spec :form_type))



;;
;;
;;

(defn gen-form#
  "Returns a generator of forms.
  Parameters:
  - :mode   - specify generation 'mode'
  - :using  - specify fixed data items e.g. {:using {:id [:t_form_edss 123]}}
  There are three 'modes' available via setting ':mode':
  - :fetch  - generates a form as if fetched from a store
  - :insert - generate data suitable for an 'insert' to a store
  - :update - generate data suitable for an 'update' to a store
  Optionally, fixed parameters can be specified using :from.

  This uses gen/bind to ensure the correct specific form data is generated for the
  generated, or specified, type. It would be usual to use [[gen-form]] in
  preference to this as that adds additional dehydration/hydration steps to
  ensure the returned data contains computed and per-form property
  modifications."
  ([]
   (gen-form# {}))
  ([{:keys [mode using] :or {mode :fetch} :as params}]
   (let [{:keys [form_type id] :as form} using
         core-spec (case mode :fetch ::core :insert ::core-for-insert :update ::core-for-update)]
     (gen/bind                                              ;; set, or generate a form type
       (if form_type (gen/return form_type) (gen/elements registry/all-form-types))
       (fn [form-type]
         (if-let [{:keys [store] :as form-definition} (registry/form-definition-by-form-type form-type)]
           (gen/bind                                        ;; set or generate a form id
             (if (some? id)
               (gen/return id)
               (if (= mode :update) (throw (ex-info "missing id when generating form for an update" params))
                                    (registry/gen-id form-definition)))
             (fn [form-id]
               (gen/bind                                    ;; generate core form data
                 (gen/fmap #(cond-> (assoc % :form_type form-type) form-id (assoc :id form-id)) (s/gen core-spec))
                 (fn [core]
                   (gen/bind                                ;; generate form specific data for type
                     (s/gen (spec core))                    ;; (spec core) returns the spec for the specialised parameters of any given form
                     (fn [ext]                              ;; and then merge altogether
                       (gen/return (merge core ext form)))))))) ;; prioritising anything set explicitly
           (throw (ex-info (str "unknown form type: '" form-type "'") params))))))))

(defn gen-form
  "Return a generator for forms. As the spec for forms defines an intermediary representation, this generates that
  from the specs, and then manually dehydrates and then hydrates to ensure all computed properties are added.

  Parameters:
  - :mode  - :fetch :insert or :update
  - :using - fixed form properties e.g. {:form_type :edss/v1} will always create forms of that type

  Note, forms generated for 'insertion' will sometimes have an 'id' property generated for them. This is because the
  'nf' form store supports providing an id as it uses UUIDs. However, the 'wo' store does not support this, so you must
  manually remove this generated field if required.
  ```
  (gen/generate (gen/form))
  =>
  {:form_type :araf-val-f-s1-status/v1, ...}

  ```
  (gen/generate (gen/form {:using {:form_type :edss/v1}}
  =>
  {:id [:t_form_edss 29], :form_type :edss/v1, :patient_fk 2710, :encounter_fk 44937, :user_fk 596, :edss \"5.0\"}
  ```"
  ([]
   (gen-form {}))
  ([params]
   (gen/fmap (comp hydrate dehydrate) (gen-form# params))))

(s/def ::form (s/with-gen (s/and ::core ::ext) gen-form))
(s/def ::form-for-insert (s/with-gen (s/and ::core-for-insert ::ext) (fn [] (gen-form {:mode :insert}))))
(s/def ::form-for-update (s/with-gen (s/and ::core-for-update ::ext) (fn [] (gen-form {:mode :update}))))

(comment
  (gen/generate (gen-form))
  (gen/generate (s/gen ::form))
  (gen/generate (gen-form {:mode :update :using {:form_type :relapse/v1 :id [:t_form_ms_relapse 123]}}))
  (gen/generate (gen-form {:mode :insert :using {:form_type :edss/v1 :patient_fk 14032 :edss "9.5"}}))
  (gen/generate (gen-form {:mode :insert :using {:form_type :araf-val-f-s3-risks/v1}})))
