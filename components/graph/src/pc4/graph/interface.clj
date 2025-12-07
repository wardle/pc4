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
    [pc4.snomed.interface]))

(defmethod ig/init-key ::ops
  [_ ops]
  (map force (flatten ops)))                                ;; force will deref a delay or nop if not

(defmethod ig/init-key ::env
  [_ {:keys [ops] :as env}]
  (log/info "creating pathom registry" {:n-operations (count (flatten ops))})
  (run! #(log/trace "op: " %)
        (sort (map (fn [r] (str (get-in r [:config :com.wsscode.pathom3.connect.operation/op-name]))) ops)))
  (-> env
      (dissoc :pathom/ops)
      (assoc ::p.error/lenient-mode? true)
      (pci/register ops)))

(s/def ::connect-viz boolean?)
(s/def ::env map?)
(s/def ::boundary-service-config (s/keys :req-un [::env] :opt-un [::connect-viz]))

(defmethod ig/init-key ::boundary-interface
  [_ {:keys [env connect-viz] :as config}]
  (when-not (s/valid? ::boundary-service-config config)
    (throw (ex-info "invalid boundary service configuration" (s/explain-data ::boundary-service-config config))))
  (when connect-viz
    (try
      (let [connect-env (requiring-resolve 'com.wsscode.pathom.viz.ws-connector.pathom3/connect-env)]
        (connect-env env (merge {:com.wsscode.pathom.viz.ws-connector.core/parser-id 'pc4} config))
        (log/info "connecting pathom-viz"))
      (catch Exception _ (log/info "unable to connect to pathom-viz as dependency not available in this build"))))
  (p.eql/boundary-interface env))
