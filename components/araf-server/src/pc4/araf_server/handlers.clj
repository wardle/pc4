(ns pc4.araf-server.handlers
  (:require
    [clojure.string :as str]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [io.pedestal.interceptor :as intc]
    [pc4.araf.interface :as araf]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn]
    [selmer.parser :as selmer]))

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

(defn bad-request
  ([] (bad-request nil))
  ([body] {:status 400, :body (or body "Bad request")}))

(defn redirect [url]
  {:status  303
   :headers {"Location" url}})

(defn hx-redirect
  [path]
  {:status  200
   :headers {"HX-Redirect" path}})

(defn error-page
  [message]
  (ok (selmer/render-file "araf/templates/error.html"
                          {:error message
                           :url   (route/url-for :welcome)})))

;;
;; WELCOME
;;

(defn welcome-page
  "Welcome page asking user to enter their NHS number and access key."
  [request {:keys [error nhs-number access-key]}]
  (let [url (route/url-for :search)
        csrf-token (csrf/existing-token request)]
    (ok (selmer/render-file
          "araf/templates/welcome.html"
          {:title      "ARAF"
           :url        url
           :csrf-token csrf-token
           :error      error
           :access-key access-key
           :nhs-number nhs-number}))))

(def home-handler
  {:enter
   (fn [ctx]
     (assoc ctx
       :session {}
       :response (redirect (route/url-for :welcome))))})

(defn welcome-handler
  "Patient landing page when there is no direct link with an access key.
  Patient has to enter their NHS number and date of birth to proceed."
  [request]
  (welcome-page request {}))

(defn search-handler
  "HTTP POST handler taking form submission and redirecting to introduction
  page, or returning same welcome page with an error if required."
  [{:keys [form-params] :as request}]
  (let [{:keys [nhs-number access-key]} form-params
        nnn (nnn/normalise nhs-number)]
    (log/debug "search" form-params)
    (if-not (nnn/valid? nnn)
      (welcome-page request {:error "Invalid NHS number" :nhs-number (nnn/format-nnn nnn) :access-key access-key})
      (let [{:keys [error message] :as _araf-request}
            (araf/fetch-request (araf-svc request) (nnn/normalise nhs-number) access-key)]
        (if error
          (do
            (log/debug "failed to login" {:error error :message message})
            (welcome-page request {:error message :nhs-number (nnn/format-nnn nnn) :access-key access-key}))
          (redirect (route/url-for :introduction :path-params {:nhs-number nnn :access-key access-key})))))))

;;
;; Introductory page - routed manually from welcome page, or direct via URL
;; in a message or QR code.
;;

(defn intro-handler
  "Introductory page in which we load the request, show an introduction and
  prepare to start."
  [{:keys [path-params] :as request}]
  (let [{:keys [nhs-number access-key]} path-params
        {:request/keys [araf_type]} (araf/fetch-request (araf-svc request) nhs-number access-key)
        config (araf/form-config araf_type)]
    (if config
      (ok
        (selmer/render-file (str (:template-path config) "/intro.html")
                            (merge config
                                   {:csrf-token  (csrf/existing-token request)
                                    :form-action (route/url-for :question)
                                    :nhs-number  nhs-number
                                    :access-key  access-key
                                    :araf-type   (name araf_type)})))
      (do
        (log/error "no ARAF request or form found for" {:nhs-number nhs-number :access-key access-key :araf-type araf_type})
        (redirect (route/url-for :welcome))))))


;;
;; Question
;;

(defn render-question
  [template-path {:keys [step questions] :as params}]
  (log/debug "rendering step" step)
  (ok
    (selmer/render-file (str template-path "/question.html")
                        (assoc params :item (get questions (dec step))))))

(defn question-handler
  [{:keys [form-params] :as request}]
  (let [{:keys [acknowledge step action nhs-number access-key araf-type user]} form-params
        step# (or (some-> step parse-long) 0)
        {:keys [template-path questions]} (araf/form-config (keyword araf-type))
        total (count questions)
        params {:csrf-token (csrf/existing-token request)
                :nhs-number nhs-number, :access-key access-key, :araf-type (name araf-type)
                :step       step#, :questions questions, :total total
                :user       user
                :action     (route/url-for :question)}]
    (log/debug "question" {:action action :params (dissoc params :questions :action)})
    (cond
      ;; back from step 0 (who completing) -> redirect to introduction
      (and (= action "previous") (= step# 0))
      (hx-redirect (route/url-for :introduction :path-params {:nhs-number nhs-number :access-key access-key}))

      ;; back from step 1 -> go to step 0 (who completing)
      (and (= action "previous") (= step# 1))
      (ok (selmer/render-file "araf/templates/who-completing.html" params))

      ;; forward? -> check for acknowledgement
      (and (= action "next") (pos-int? step#) (not= acknowledge "on"))
      (render-question template-path
                       (assoc params
                         :error "You must acknowledge that you understand this information"))

      ;; forward but on last question? -> show signature page
      (and (= action "next") (= step# total))
      (ok (selmer/render-file "araf/templates/signature.html"
                              (assoc params
                                :back-action (route/url-for :question)
                                :submit-action (route/url-for :signature))))

      ;; forward
      (= action "next")
      (render-question template-path (update params :step inc))

      ;; back
      (= action "previous")
      (render-question template-path (update params :step dec))

      ;; no action, and step 0 (who completing) - render template
      (= step# 0)
      (ok (selmer/render-file "araf/templates/who-completing.html" params))

      ;; no action, and a step>=1 - render current question
      :else
      (render-question template-path params))))

;;
;; Signature
;;

(defn signature-handler
  "Handle signature page - either show signature form or process submission"
  [{:keys [form-params] :as request}]
  (let [{:keys [nhs-number access-key araf-type action user signature responsible]} form-params
        csrf-token (csrf/existing-token request)
        params {:csrf-token    csrf-token
                :nhs-number    nhs-number
                :access-key    access-key
                :user          user
                :responsible   responsible
                :araf-type     (name araf-type)
                :back-action   (route/url-for :signature)
                :submit-action (route/url-for :signature)}]
    (log/debug "signature" {:action action :has-signature (some? signature)})
    (cond
      ;; Going back to questions
      (= action "back")
      (hx-redirect (route/url-for :introduction :path-params {:nhs-number nhs-number :access-key access-key}))

      (and (= action "submit") (not= user "patient") (or (str/blank? responsible) (nil? signature)))
      (ok (selmer/render-file "araf/templates/signature.html"
                              (assoc params :error "You must include name of responsible person and signature.")))

      (and (= action "submit") (nil? signature))
      (ok (selmer/render-file "araf/templates/signature.html"
                              (assoc params :error "You must include a signature.")))

      ;; Form submission with name (when needed) and signature
      (= action "submit")
      (do
        ;; TODO: Process and store the signature
        (log/info "ARAF form completed with signature" {:nhs-number nhs-number :access-key access-key
                                                        :user       user :responsible responsible})
        (log/debug "signature" signature)
        (ok "Form submitted successfully! Thank you for completing the ARAF acknowledgement."))

      ;; Show signature page
      :else
      (ok (selmer/render-file "araf/templates/signature.html" params)))))