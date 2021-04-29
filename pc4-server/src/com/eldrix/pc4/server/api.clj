(ns com.eldrix.pc4.server.api
  (:require [clojure.tools.logging.readable :as log]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as intc]))

(set! *warn-on-reflection* true)

(defn inject
  "A simple interceptor to inject into the context."
  [m]
  {:name  ::inject
   :enter (fn [context] (merge context m))})

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))

(def pathom-api
  {:name  ::pathom-api
   :enter (fn [context]
            (println "transit params:" (get-in context [:request :transit-params]))
            (println context)
            (assoc context :response (ok {:message "Hello World"})))})

(def routes
  (route/expand-routes
    #{["/api" :post pathom-api]}))

;; TODO(mw): make a configuration option
(def service-map
  {::http/routes          routes
   ::http/type            :jetty
   ::http/port            8081
   ::http/host            "0.0.0.0"                         ;; TODO: this is a security risk so must be a configuration option
   ::http/allowed-origins "*"})                             ;; TODO: make a configuration option

(defn start-server
  ([svc port] (start-server svc port true))
  ([svc port join?]
   (log/info "starting server on port " port)
   (log/warn "binding to 0.0.0.0 - this will be changed in a future release")
   (-> service-map
       (assoc ::http/port port)
       (assoc ::http/join? join?)
       (http/default-interceptors)
       (update ::http/interceptors conj
               (intc/interceptor (inject {:wibble "wobble"}))
               (io.pedestal.http.body-params/body-params)
               http/transit-body)
       (http/create-server)
       (http/start))))

(defn stop-server [server]
  (http/stop server))

;; For interactive development
(defonce server (atom nil))

(defn start-dev [svc port]
  (reset! server
          (start-server svc port false)))

(defn stop-dev []
  (http/stop @server))

(comment
  (start-dev nil 8080)
  (stop-dev)

  )
  ;; from command line, send some transit+json data to the API endpoint
  ;; echo '["^ ","a",1,"b",[1,2.2,200000,null]]' | http -f POST localhost:8080/api Content-Type:application/transit+json;charset=UTF-8