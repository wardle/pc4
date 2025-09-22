(ns pc4.pathom-web.interface
  "Provides a pathom handler that allows co-location of an EQL query with a
  handler. This means handlers can simply declare the data they need. Unlike
  conventional 'ring' or 'pedestal' handlers, the handler is a 2-arity function
  taking the http request and the result of the query."
  (:require [com.wsscode.pathom3.connect.operation :as pco]
            [edn-query-language.core :as eql]
            [pc4.pathom-web.impl :as impl]
            [pc4.rsdb.interface :as rsdb]
            [pc4.web.interface :as web]))

(defn set-boundary-interface!
  "Set a function to use to get to the boundary interface in the pathom
  environment."
  [f]
  (impl/set-boundary-interface! f))

(defn process [env request query]
  (impl/process env request query))

(defn handler
  "Return a handler function that will take a HTTP request with the results of
  executing the EQL query `query` specified. It is expected that the request
  will include a pathom 'boundary interface' which is a function that can
  execute a pathom query, with a 2-arity version that can take an additional
  context that will be merged into the Pathom ctx. `query` can be a vector, a
  map or a function that takes the request and returns the query."
  ([query f]
   (impl/handler query f))
  ([env query f]
   (impl/handler env query f)))

(pco/defresolver http-request
  [{:keys [request] :as env} _]
  {:http/request request})

(pco/defresolver authenticated-user
  [{:http/keys [request]}]
  {:session/authenticated-user (get-in request [:session :authenticated-user])})

(pco/defresolver authorization-manager
  [{:http/keys [request]}]
  {:session/authorization-manager (:authorization-manager request)})

(def all-resolvers
  "Returns all resolvers for pathom-web. The pathom environment is expected to have the following:
  - :request - http request
  - [:request :session] - http request session
  - [:request :session :authenticated-user] - current user, if any
  - [:request :authorization-manager] - authorization manager"
  [http-request authenticated-user authorization-manager])

(defn merge-queries                                         ;; TODO: deprecate once merged into upstream lib
  ([qa] qa)
  ([qa qb] (eql/merge-queries qa qb))
  ([qa qb & more]
   (some-> (reduce (fn [ast q] (eql/merge-asts ast (eql/query->ast q)))
                   (eql/merge-asts (eql/query->ast qa) (eql/query->ast qb)) more)
           (eql/ast->query))))