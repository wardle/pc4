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

(def service-error-handler
  (int-err/error-dispatch [ctx ex]
    [{:exception-type :java.lang.ArithmeticException :interceptor ::another-bad-one}]
    (assoc ctx :response {:status 400 :body "Another bad one"})


    [{:exception-type :java.lang.ArithmeticException}]
    (assoc ctx :response {:status 400 :body "A bad one"})

    :else
    (do
      (log/info "error: " ex)
      (assoc ctx :io.pedestal.interceptor.chain/error ex))))

(def pathom-api
  "Simple interceptor that pulls out EQL from the request and responds with
  the result of its processing."
  {:name  ::pathom-api
   :enter (fn [context]
            (log/debug "api call; eql:" (get-in context [:request :transit-params]))
            (let [registry (:pathom-registry context)
                  result (p.eql/process registry (get-in context [:request :transit-params]))]
              (log/debug "api call; result:" result)
              (assoc context :response (ok result))))})

(def routes
  (route/expand-routes
    #{["/api" :post [service-error-handler pathom-api]]}))


(comment

  ;; from command line, send some transit+json data to the API endpoint
  ;; echo '["^ ","a",1,"b",[1,2.2,200000,null]]' | http -f POST localhost:8080/api Content-Type:application/transit+json;charset=UTF-8
  )