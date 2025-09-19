(ns pc4.araf.impl.server
  (:require
    [clojure.data.json :as json]
    [clojure.spec.alpha :as s]
    [io.pedestal.http.body-params :as bp]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.interceptor :as intc]
    [pc4.log.interface :as log]
    [pc4.araf.impl.handlers :as h]))

(defn env-interceptor
  "Return an interceptor to inject the given env into the request."
  [env]
  (intc/interceptor
    {:name  ::inject
     :enter (fn [context] (assoc-in context [:request :env] env))}))

(def result->json
  {:name :result->json
   :leave
   (fn [{:keys [result] :as ctx}]
     (if result
       (assoc ctx :response {:status  200
                             :headers {"Content-Type" "application/json"}
                             :body    (json/write-str result)})
       ctx))})

(defn csrf-error-handler
  [ctx]
  (log/error "missing CSRF token in request" (get-in ctx [:request :uri]))
  (assoc-in ctx [:response] {:status 403 :body "Forbidden; missing CSRF token in submission"}))



(s/def ::ds :next.jdbc.specs/proto-connectable)
(s/def ::secret string?)

(s/fdef routes
  :args (s/cat :svc (s/keys :req-un [::ds ::secret])))
(defn routes
  [svc]
  (let [env-intc (env-interceptor svc)
        web [(csrf/anti-forgery {:error-handler csrf-error-handler}) env-intc]
        api [env-intc h/authenticate result->json]]
    #{["/" :get (conj web h/welcome-handler) :route-name :welcome]
      ["/" :post (conj web h/search-handler) :route-name :search]
      ["/araf/form/:long-access-key" :get (conj web h/intro-handler) :route-name :introduction]
      ["/araf/form/:long-access-key/question/:step" :post (conj web h/question-handler) :route-name :question]
      ["/araf/form/:long-access-key/signature" :post (conj web h/signature-handler) :route-name :signature]
      ["/araf/api/request" :post (conj api h/api-create-request) :route-name :api/create-request]
      ["/araf/api/request/:long-access-key" :get (conj api h/api-get-request) :route-name :api/get-request]}))