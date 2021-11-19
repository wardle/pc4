(ns app.application
  (:require
    [com.fulcrologic.fulcro.networking.http-remote :as net]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.data-fetch :as df]))

(def secured-request-middleware
  (->
    (net/wrap-fulcro-request)))

(defn global-eql-transform
  "As the default transform but also asking that any Pathom errors during load! are returned,
  so that they can be inspected e.g. in `:remote-error?`"
  [ast]
  (cond-> (app/default-global-eql-transform ast)
    (-> ast :type #{:root})
    (update :children conj (eql/expr->ast :com.wsscode.pathom.core/errors))))

(defonce SPA (app/fulcro-app
               {:remotes {:remote (net/fulcro-http-remote
                                    {:url                "http://localhost:8080/api"
                                     :request-middleware secured-request-middleware})}
                :global-eql-transform global-eql-transform}))

(comment
  (-> SPA
      (::app/runtime-atom)
      deref
      ::app/indexes)

  (require '[com.fulcrologic.fulcro.data-fetch :as df])
  (df/load! SPA [:info.snomed.Concept/id 24700007] app.ui.root/SnomedConcept {:target [:root/selected-concept]})
  )
