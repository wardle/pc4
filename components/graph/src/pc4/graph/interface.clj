(ns pc4.graph.interface
  (:require
   [clojure.spec.alpha :as s]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.error :as p.error]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [com.wsscode.pathom3.plugin :as p.plugin]
   [com.wsscode.pathom3.connect.runner :as pcr]
   [integrant.core :as ig]
   [pc4.deprivare.interface]
   [pc4.ods.interface]
   [pc4.ods-weekly.interface]
   [pc4.dmd.interface]
   [pc4.log.interface :as log]
   [pc4.rsdb.interface]
   [pc4.graph.users]
   [pc4.graph.patients]
   [pc4.msbase.interface]
   [pc4.snomedct.interface]))

(defmethod ig/init-key ::ops
  [_ ops]
  (flatten ops))

(defmethod ig/init-key ::env
  [_ {:keys [ops] :as env}]
  (log/info "creating pathom registry" {:n-operations (count ops)})
  (run! #(log/trace "op: " %)
        (sort (map (fn [r] (str (get-in r [:config :com.wsscode.pathom3.connect.operation/op-name]))) ops)))
  (-> env
      (dissoc :pathom/ops)
      (assoc ::p.error/lenient-mode? true
             :com.wsscode.pathom3.format.eql/map-select-include #{:tempids}) ;; always include request for tempids
      (pci/register ops)

      ;; add plugin to handle resolver errors
      (p.plugin/register
       {::p.plugin/id `handle-resolver-err
        ::pcr/wrap-resolver-error
        (fn [_]
          (fn [_env node error]
            (when (instance? Throwable error)
              (.printStackTrace ^Throwable error))
            (log/error "pathom resolver error" {:node node :error error})))})

      ;; add plugin to handle mutation errors
      (p.plugin/register
       {::p.plugin/id `handle-mutation-err
        ::pcr/wrap-mutate
        (fn [mutate]
          (fn [env params]
            (try
              (mutate env params)
              (catch Throwable err
                (.printStackTrace err)
                {::pcr/mutation-error (ex-message err)}))))})

      ;; add plugin to process query parameters from query for Fulcro clients
      (p.plugin/register
       {::p.plugin/id `query-params->env
        ::p.eql/wrap-process-ast
        (fn [process]
          (fn [env ast]
            (let [children     (:children ast)
                  query-params (reduce
                                (fn [qps {:keys [type params]}]
                                  (cond-> qps
                                    (and (not= :call type) (seq params)) (merge params)))
                                {}
                                children)
                  env          (assoc env :query-params query-params)]
              (process env ast))))})))

(s/def ::connect-viz boolean?)
(s/def ::env map?)
(s/def ::boundary-service-config (s/keys :req-un [::env] :opt-un [::connect-viz]))

(defmethod ig/init-key ::boundary-interface
  [_ {:keys [env connect-viz] :as config}]
  (when-not (s/valid? ::boundary-service-config config)
    (throw (ex-info "invalid boundary service configuration" (s/explain-data ::boundary-service-config config))))
  (when connect-viz
    (log/info "connecting pathom-viz" config)
    (try
      (let [connect-env (requiring-resolve 'com.wsscode.pathom.viz.ws-connector.pathom3/connect-env)]
        (connect-env env (merge {:com.wsscode.pathom.viz.ws-connector.core/parser-id 'pc4} config)))
      (catch Exception _ (log/warn "unable to connect to pathom-viz as dependency not available in this build"))))
  (p.eql/boundary-interface env))

