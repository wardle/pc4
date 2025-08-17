(ns pc4.araf-server.handlers
  (:require
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [io.pedestal.interceptor :as intc]
    [pc4.araf.interface :as araf]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn]
    [selmer.parser :as selmer])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

(defn env-interceptor
  "Return an interceptor to inject the given env into the request."
  [env]
  (intc/interceptor
    {:name  ::inject
     :enter (fn [context] (assoc-in context [:request :env] env))}))

(defn araf-svc
  "Return the ARAF svc from the request."
  [request]
  (or (get-in request [:env :svc])
      (throw (ex-info "missing araf svc in environment" (:env request)))))

(defn ok [body]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    body})

(defn redirect [url]
  {:status  303
   :headers {"Location" url}})

(defn welcome
  [request {:keys [error access-key nhs-number]}]
  (let [submit-url (route/url-for :search)
        csrf-token (csrf/existing-token request)]
    (ok (selmer/render-file "araf/templates/welcome.html" {:title      "ARAF"
                                                           :url        submit-url
                                                           :csrf-token csrf-token
                                                           :error      error
                                                           :access-key access-key
                                                           :nhs-number nhs-number}))))


(defn welcome-handler
  "Patient landing page when there is no direct link with an access key.
  Patient has to enter their NHS number and date of birth to proceed."
  [request]
  (welcome request {}))

(defn search-handler
  [{:keys [form-params] :as request}]
  (let [{:keys [nhs-number access-key]} form-params
        nnn (nnn/normalise nhs-number)]
    (log/debug "search" form-params)
    (if-not (nnn/valid? nnn)
      (welcome request {:error "Invalid NHS number" :nhs-number (nnn/format-nnn nnn) :access-key access-key})
      (let [{:keys [error message]}
            (araf/fetch-request (araf-svc request) access-key (nnn/normalise nhs-number))]
        (if error
          (welcome request {:error message :nhs-number (nnn/format-nnn nnn) :access-key access-key})
          (redirect (route/url-for :start :path-params {:access-key access-key :nhs-number nnn})))))))

(defn start-handler
  [{:keys [path-params] :as request}]
  (let [{:keys [access-key nhs-number]} path-params
        {:keys [error] :as araf-request}
        (araf/fetch-request (araf-svc request) access-key (nnn/normalise nhs-number))]
    (if error
      (do
        (log/warn "start handler; invalid request:" path-params)
        (redirect (route/url-for :welcome)))
      (ok
        (str araf-request)))))