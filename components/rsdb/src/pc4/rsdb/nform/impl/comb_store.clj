(ns pc4.rsdb.nform.impl.comb-store
  "The combination store is a simple facade delegating to defined concrete store
  implementations."
  (:require
    [pc4.rsdb.nform.impl.protocols :as p]
    [pc4.rsdb.nform.impl.registry :as registry]))

(deftype CombinedFormStore
  [nf-store wo-store]

  p/FormStore
  (upsert [_ {:keys [form_type] :as form}]
    (if-let [{:keys [store]} (registry/form-definition-by-form-type form_type)]
      (case store
        :nf (p/upsert nf-store form)
        :wo (p/upsert wo-store form))
      (throw (ex-info (str "unsupported form; no definition found for form type:" form_type) form))))

  (form [_ id]
    (if (uuid? id)
      (p/form nf-store id)
      (p/form wo-store id)))

  (forms [_ params]
    (into (or (p/forms nf-store params) [])
          (p/forms wo-store params))))
