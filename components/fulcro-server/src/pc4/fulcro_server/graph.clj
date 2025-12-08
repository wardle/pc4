(ns pc4.fulcro-server.graph
  "Pathom graph configuration for the Fulcro server.
  Defines auth resolvers and creates the pathom environment."
  (:require
   [clojure.spec.alpha :as s]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.error :as p.error]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [pc4.log.interface :as log]
   [pc4.rsdb.interface :as rsdb]))

(pco/defresolver authenticated-user
  "Extracts the authenticated user from the request session."
  [{:keys [request]} _]
  {::pco/output [{:session/authenticated-user [:t_user/id :t_user/username :t_role/is_system :t_user/active_roles]}]}
  {:session/authenticated-user (get-in request [:session :authenticated-user])})

(pco/defresolver authorization-manager
  "Derives the authorization manager from the authenticated user."
  [{rsdb :com.eldrix/rsdb} {:session/keys [authenticated-user]}]
  {::pco/input  [{:session/authenticated-user [:t_user/id :t_user/username :t_role/is_system :t_user/active_roles]}]
   ::pco/output [:session/authorization-manager]}
  {:session/authorization-manager
   (when authenticated-user
     (rsdb/authorization-manager authenticated-user))})

(def auth-resolvers
  [authenticated-user authorization-manager])

;; Specs for pathom configuration
(s/def ::ops (s/coll-of any?))
(s/def ::env map?)
(s/def ::config (s/keys :req-un [::ops ::env]))

(defn make-env
  "Create a pathom environment from ops and env configuration.
  Registers all resolvers/mutations."
  [{:keys [ops env]}]
  (let [all-ops (into (vec (map force (flatten ops))) auth-resolvers)]
    (log/info "creating pathom environment" {:n-ops (count all-ops)})
    (-> env
        (assoc ::p.error/lenient-mode? true)
        (pci/register all-ops))))

(defn boundary-interface
  "Create a pathom boundary interface from configuration."
  [config]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid pathom configuration" (s/explain-data ::config config))))
  (p.eql/boundary-interface (make-env config)))
