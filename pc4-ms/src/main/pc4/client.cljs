(ns pc4.client
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as net]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [edn-query-language.core :as eql]
    [pushy.core :as pushy]
    [taoensso.timbre :as log]
    [pc4.app :refer [SPA]]
    [pc4.session :as session]
    [pc4.ui.root :as root]
    [pc4.users]))

(defn ^:export refresh []
  (log/info "Hot code Remount")
  (app/mount! @SPA root/Root "app"))

(defn wrap-authentication-token
  "Client Remote Middleware to add bearer token to outgoing requests."
  ([] (wrap-authentication-token identity))
  ([handler]
   (fn [{:keys [headers body] :as request}]
     (if-let [token @session/authentication-token]
       (let [headers (assoc headers "Authorization" (str "Bearer " token))]
         (handler (merge request {:headers headers :method :post})))
       (handler request)))))

(defn global-eql-transform
  "As the default transform but also asking that any Pathom errors during load! are returned,
  so that they can be inspected e.g. in `:remote-error?`"
  [ast]
  (cond-> (app/default-global-eql-transform ast)
          (-> ast :type #{:root})
          (update :children conj (eql/expr->ast :com.wsscode.pathom.core/errors))))

(defn make-SPA []
  (app/fulcro-app
    {:global-eql-transform
     global-eql-transform
     :remotes
     {:remote
      (net/fulcro-http-remote
        {:url                "http://localhost:8080/api"
         :request-middleware (->
                               (net/wrap-fulcro-request)
                               (wrap-authentication-token))})
      :login
      (net/fulcro-http-remote
        {:url                "http://localhost:8080/login"
         :request-middleware (-> (net/wrap-fulcro-request))})}}))

(defonce history
  (pushy/pushy
    (fn [p]
      (let [route-segments (vec (rest (str/split p "/")))]
        (log/info :info route-segments)
        (if (empty route-segments)
          (dr/change-route SPA ["home"])
          (dr/change-route SPA route-segments))))
    identity))

(defn routing-start! []
  (pushy/start! history))

(defn route-to!
  "Change routes to the given route-string (e.g. \"/home\"."
  [route-string]
  (pushy/set-token! history route-string))

(defn ^:export init []
  (log/info "Application starting.")
  (reset! SPA (make-SPA))
  ;(inspect/app-started! @SPA)
  (app/set-root! @SPA root/Root {:initialize-state? true})
  (dr/initialize! @SPA)
  (routing-start!)
  (app/mount! @SPA root/Root "app" {:initialize-state? false}))

(comment
  (inspect/app-started! @SPA)
  (app/mounted? @SPA)
  (app/set-root! SPA root/Root {:initialize-state? true})


  (reset! (::app/state-atom SPA) {})

  (comp/get-query root/Settings (app/current-state SPA))

  (tap> @SPA)
  (com.fulcrologic.fulcro.algorithms.indexing/reindex)

  (merge/merge-component! SPA root/Settings {:account/time-zone "America/Los_Angeles"
                                             :account/real-name "Joe Schmoe"})
  (dr/initialize! SPA)
  (app/current-state SPA)
  (dr/change-route SPA ["settings"])
  (app/mount! SPA root/Root "app")
  (comp/get-query root/Root {})
  (comp/get-query root/Root (app/current-state SPA))

  (-> SPA ::app/runtime-atom deref ::app/indexes)
  (comp/class->any SPA root/Root))

(comment
  (-> @SPA
      (::app/runtime-atom)
      deref
      ::app/indexes)

  (require '[com.fulcrologic.fulcro.data-fetch :as df])

  @session/authentication-token
  (reset! session/authentication-token nil)
  (df/load! @SPA [:info.snomed.Concept/id 24700007] pc4.ui.root/SnomedConcept {:target [:root/selected-concept]})
  (comp/transact! @SPA [(pc4.users/login {:system "cymru.nhs.uk" :value "system" :password "password"})])
  (comp/transact! @SPA [(pc4.users/logout)])
  (comp/transact! @SPA [(pc4.users/refresh-token {:token @session/authentication-token})])

  @SPA
  (pc4.users/login {:username "system" :password "password"})
  (pc4.users/refresh-token {:token "abc"})
  (reset! authentication-token "eyJhbGciOiJIUzI1NiJ9.eyJzeXN0ZW0iOiJjeW1ydS5uaHMudWsiLCJ2YWx1ZSI6InN5c3RlbSIsImV4cCI6MTYzNzM5ODY5NX0.lU8CRsyvF6EfJIDbsO_-R9BJCNvqpV4YTgb2jrx3fI4")
  )