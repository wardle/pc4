(ns pc4.http-server.pathom
  (:require [com.wsscode.pathom3.interface.eql :as p.eql]))

(defn process
  "Execute the `query` in the context of the http `request`.
  The pathom environment has the request included under key :request.
  To support the legacy Fulcro-based model we were using, we also push in keys
  :session/authenticated-user and :session/authorization-manager. Ideally this
  would be replaced with resolvers that simply pull from the request as required
  and limit what 'magic' values need to be in the env itself.
  TODO: rewrite any resolvers using these 'magic' keys."
  [env request query]
  (let [pathom (or (get-in request [:env :pathom])
                   (throw (ex-info "missing pathom boundary interface in request" (:env request))))]
    (let [result (pathom
                   (merge {:request                       request
                           :session                       (:session request)
                           :session/authenticated-user    (get-in request [:session :authenticated-user])
                           :session/authorization-manager (:authorization-manager request)
                           :disable-auth                  true}
                          env)
                   query)]
      (when (:com.wsscode.pathom3.connect.runner/attribute-errors result)
        (clojure.pprint/pprint result))
      result)))

(defn handler
  "Return a handler function that will take a HTTP request with the results of
  executing the EQL query `query` specified. It is expected that the request
  will include a pathom 'boundary interface' which is a function that can
  execute a pathom query, with a 2-arity version that can take an additional
  context that will be merged into the Pathom ctx. `query` can be a vector, a
  map or a function that takes the request and returns the query."
  ([query f]
   (handler {} query f))
  ([env query f]
   (fn handler*
     ([request]
      (handler* request (process env request (if (fn? query) (query request) query))))
     ([request output]
      (f request output)))))



