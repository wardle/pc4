(ns com.eldrix.pc4.server.api
  (:require [clojure.tools.logging.readable :as log]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.interceptor.error :as int-err]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.interface.eql :as p.eql]))

(set! *warn-on-reflection* true)

(defn inject
  "A simple interceptor to inject the map into the context."
  [m]
  {:name  ::inject
   :enter (fn [context] (merge context m))})

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))

(def pathom-api
  "Simple interceptor that pulls out EQL from the request and responds with
  the result of its processing."
  {:name  ::pathom-api
   :enter (fn [ctx]
            (log/debug "api auth " (get-in ctx [:request :headers "authorization"]))
            (log/debug "api call; eql:" (get-in ctx [:request :transit-params]))
            (let [boundary-interface (:pathom-boundary-interface ctx)
                  result (boundary-interface (get-in ctx [:request :transit-params]))]
              (log/info "api call; result:" result)
              (if-let [mutation-error (first (map :com.wsscode.pathom3.connect.runner/mutation-error (vals result)))]
                (do
                  (log/info "mutation error: " {:request (get-in ctx [:request :transit-params])
                                                :cause (:cause (Throwable->map  mutation-error))})
                  (assoc ctx :response {:status 400
                                      :body {:message (str "Mutation error:" (:cause (Throwable->map mutation-error)))}}))
                (assoc ctx :response (ok result)))))})

(def routes
  (route/expand-routes
    #{["/api" :post [pathom-api]]}))


(comment

  ;; from command line, send some transit+json data to the API endpoint
  ;; echo '["^ ","a",1,"b",[1,2.2,200000,null]]' | http -f POST localhost:8080/api Content-Type:application/transit+json;charset=UTF-8
  )