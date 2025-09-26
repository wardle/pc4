(ns pc4.rsdb.nform.api
  "This is a modern replacement for legacy forms.

  This API automatically manages legacy forms and makes them available
  seamlessly via a modern API."
  (:require
    [clojure.spec.alpha :as s]
    [next.jdbc :as jdbc]
    [next.jdbc.specs]
    [pc4.rsdb.nform.impl.form :as form]
    [pc4.rsdb.nform.impl.forms.all]
    [pc4.rsdb.nform.impl.registry :as registry]
    [pc4.rsdb.nform.impl.registry]
    [pc4.rsdb.nform.impl.protocols :as p]
    [pc4.rsdb.nform.impl.comb-store :as comb-store]
    [pc4.rsdb.nform.impl.nf-store :as nf-store]
    [pc4.rsdb.nform.impl.wo-store :as wo-store]))

(defn form-store?
  "Is 'x' a form store?"
  [x]
  (satisfies? p/FormStore x))

(s/def ::conn :next.jdbc.specs/connectable)
(s/def ::form-store form-store?)

(s/fdef make-form-store
  :args (s/cat :conn ::conn)
  :ret ::form-store)
(defn make-form-store
  "Return a combination form store that can make use of either NFFormStore or
  WOFormStore as required, and return results across both storage mechanisms. "
  [conn]
  (comb-store/->CombinedFormStore
    (nf-store/->NFFormStore conn)
    (wo-store/->WOFormStore conn)))

(defn upsert!
  "Insert or update a form, returning the form."
  [st {:keys [id] :as form}]
  (let [spec (if id ::form/form-for-update ::form/form-for-insert)]
    (if (s/valid? spec form)
      (form/hydrate (p/upsert st (form/dehydrate form)))
      (throw (ex-info "invalid form:" (s/explain-data spec form))))))

(defn form
  "Fetch a form by id where id is dependent on the underlying form store.
  - nf: UUID
  - wo: tuple of table name and pk"
  [st id]
  (form/hydrate (p/form st id)))

(defn forms
  "Fetch forms by parameters specified"
  [st {:keys [id encounter-id encounter-ids patient-pk] :as params}]
  (when-not (s/valid? ::p/fetch-params params)
    (throw (ex-info "invalid form store fetch params" (s/explain-data ::p/fetch-params params))))
  (map form/hydrate (p/forms st params)))

(defn form-definition
  "Return the form definition for the given form."
  [{:keys [form_type]}]
  (registry/form-definition-by-form-type form_type))

(comment
  (def ds (jdbc/get-datasource {:dbtype "postgresql" :dbname "rsdb"}))
  (require '[pc4.rsdb.interface :as rsdb])
  (require '[clojure.spec.gen.alpha :as gen])
  (def conn (jdbc/get-connection ds))
  conn
  (require '[clojure.spec.gen.alpha :as gen])
  (require '[clojure.spec.alpha :as s])
  (gen/sample (form/gen-form))
  (require '[pc4.rsdb.migrations :as m])
  (m/migrate ds)
  (m/roll)
  (s/explain ::form/form (gen/generate (form/gen-form)))
  (form (make-form-store ds) #uuid "4cf9e4a0-1b01-4457-9a67-d1c7c8db54f9"))