(ns pc4.araf.impl.handlers
  (:require
    [clojure.string :as str]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [io.pedestal.interceptor :as intc]
    [pc4.araf.impl.db :as db]
    [pc4.araf.impl.forms :as forms]
    [pc4.araf.impl.token :as token]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn]
    [selmer.parser :as selmer])
  (:import
    [java.util Base64]
    [java.time Instant]))

(defn parse-data-uri
  "Parse data URI into [mime-type bytes].
   Currently expects format: data:image/png;base64,<base64-data>"
  [data-uri]
  (when (and (not (str/blank? data-uri)) (str/starts-with? data-uri "data:image/png;base64,"))
    ["image/png" (.decode (Base64/getDecoder) (subs data-uri 22))]))

(defn araf-conn
  "Return the ARAF database ds/connectable from the request."
  [request]
  (or (get-in request [:env :ds])
      (throw (ex-info "missing araf connection in environment" (:env request)))))

(defn araf-secret
  [request]
  (or (get-in request [:env :secret])
      (throw (ex-info "missing araf server secret in environment" (:env request)))))

(defn csrf-token
  [request]
  (get request csrf/anti-forgery-token))

(defn url-for-search []
  (route/url-for :search))

(defn url-for-question
  [long-access-key step]
  (route/url-for :question :path-params {:long-access-key long-access-key :step step}))

(defn url-for-welcome []
  (route/url-for :welcome))

(defn url-for-introduction
  [long-access-key]
  (route/url-for :introduction :path-params {:long-access-key long-access-key}))

(defn url-for-signature
  [long-access-key]
  (route/url-for :signature :path-params {:long-access-key long-access-key}))

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
                           :url   (url-for-welcome)})))
;
;; WELCOME
;;

(defn welcome-page
  "Welcome page asking user to enter their NHS number and access key."
  [request {:keys [error nhs-number access-key]}]
  (ok (selmer/render-file
        "araf/templates/welcome.html"
        {:title      "ARAF"
         :url        (url-for-search)
         :csrf-token (csrf-token request)
         :error      error
         :access-key access-key
         :nhs-number nhs-number})))

(defn welcome-handler
  "Patient landing page when there is no direct link with an access key.
  Patient or responsible person has to enter their NHS number and date of birth to proceed."
  [request]
  (welcome-page request {}))

(defn search-handler
  "HTTP POST handler taking form submission and redirecting to introduction
  page, or returning welcome page with an error if required. Nonsensical input
  is screened out without hitting the database."
  [{:keys [form-params] :as request}]
  (let [{:keys [nhs-number access-key]} form-params
        nnn (nnn/normalise nhs-number)]
    (log/debug "search" form-params)
    (cond
      (not (nnn/valid? nnn))
      (welcome-page request {:error "Invalid NHS number"})

      (> (count access-key) 16)
      (welcome-page request {:error "Invalid access token" :nhs-number (nnn/format-nnn nnn)})

      :else
      (let [{:keys [error message long_access_key] :as araf-request}
            (db/fetch-request (araf-conn request) nnn access-key)]
        (log/debug "search result" araf-request)
        (if error
          (welcome-page request {:error message :nhs-number (nnn/format-nnn nnn)})
          (redirect (url-for-introduction long_access_key)))))))

;;
;; Introductory page - routed manually from welcome page, or direct via URL
;; in a message or QR code.
;;

(defn intro-handler
  "Introductory page in which we load the request, show an introduction and
  prepare to start. This uses a long-access key from the URL path parameters."
  [{:keys [path-params] :as request}]
  (let [{:keys [long-access-key]} path-params
        {:keys [araf_type completed] :as araf-request} (db/fetch-request (araf-conn request) long-access-key)
        config (forms/form-config araf_type)]
    (log/debug "intro" {:araf araf_type :form long-access-key})
    (cond
      completed
      (ok (selmer/render-file "araf/templates/notice.html"
                              {:success     true
                               :title       "Form Already Completed"
                               :description "This form has already been completed and submitted. If you need to make changes or have questions, please contact your healthcare provider."
                               :sections    [{:title "What happens next?"
                                              :items ["Your completed form will be automatically recorded in your medical notes"
                                                      "Your specialist prescriber will review your acknowledgements"
                                                      "Contact your healthcare provider if you have any concerns"]}]}))

      config
      (ok
        (selmer/render-file (str (:template-path config) "/intro.html")
                            (merge config
                                   {:csrf-token      (csrf-token request)
                                    :form-action     (url-for-question long-access-key 0)
                                    :long-access-key long-access-key
                                    :araf-type       (name araf_type)})))
      :else
      (ok (selmer/render-file "araf/templates/notice.html"
                              {:title       "Form expired"
                               :description "This form has expired or does not exist. Please contact your healthcare provider."
                               :sections    [{:title "What can I do next?"
                                              :items ["For your security, acknowledgment forms have a fixed expiry date"
                                                      "Your healthcare provider can request another form on your behalf"]}]})))))

;;
;; Question
;;

(defn render-question
  [template-path {:keys [step questions] :as params}]
  (ok
    (selmer/render-file (str template-path "/question.html")
                        (assoc params :item (get questions (dec step))))))

(defn question-handler
  [{:keys [form-params path-params] :as request}]
  (let [{:keys [long-access-key step]} path-params
        {:keys [araf_type]} (db/fetch-request (araf-conn request) long-access-key)
        {:keys [acknowledge action user]} form-params
        step# (or (some-> step parse-long) 0)
        {:keys [template-path questions]} (forms/form-config (keyword araf_type))
        total (count questions)
        params {:csrf-token   (csrf-token request)
                :step         step#
                :questions    questions
                :total        total
                :user         user
                :previous-url (url-for-question long-access-key (dec step#))}]
    (log/debug "question" {:araf araf_type :form long-access-key :step step# :action action :ack acknowledge})
    (cond
      (= step# -1)
      (hx-redirect (url-for-introduction long-access-key))

      (= step# 0)
      (ok (selmer/render-file "araf/templates/who-completing.html"
                              (assoc params :next-url (url-for-question long-access-key 1))))

      (> step# total)
      (ok (selmer/render-file "araf/templates/signature.html"
                              (assoc params
                                :back-action (url-for-question long-access-key total)
                                :submit-action (url-for-signature long-access-key))))

      ;; forward? -> check for acknowledgement, and render prior question if not
      (and (= action "next") (> step# 1) (not= acknowledge "on"))
      (render-question template-path (-> params
                                         (update :step dec)
                                         (assoc :error "You must acknowledge that you understand this information"
                                                :next-url (url-for-question long-access-key step#))))

      ;; no action, and a step>=1 - render current question
      :else
      (render-question template-path (assoc params :next-url (url-for-question long-access-key (inc step#)))))))

;;
;; Signature
;;

(defn signature-handler
  "Handle signature page - either show signature form or process submission"
  [{:keys [form-params path-params] :as request}]
  (let [{:keys [long-access-key]} path-params
        {:keys [action user signature responsible]} form-params
        params {:csrf-token  (csrf-token request)
                :user        user
                :responsible responsible
                :action      (url-for-signature long-access-key)}
        [mime-type signature-bytes] (parse-data-uri signature)]
    (log/debug "signature" {:form long-access-key :action action :mime-type mime-type :has-signature (some? signature)})
    (cond
      ;; back to questions
      (= action "back")
      (hx-redirect (url-for-introduction long-access-key))

      ;; submitting but need name of responsible person and it is missing
      (and (= action "submit") (not= user "patient") (or (str/blank? responsible) (nil? signature)))
      (ok (selmer/render-file "araf/templates/signature.html"
                              (assoc params :error "You must include name of responsible person and signature.")))

      ;; form submission with name (when needed) and valid signature
      (and (= action "submit") (some? signature-bytes) (not (str/blank? mime-type)))
      (do
        (db/submit-form! (araf-conn request) long-access-key {:signature signature-bytes :mime_type mime-type :name responsible})
        (log/info "ARAF form saved" {:long-access-key long-access-key :user user :responsible responsible})
        (ok "Form submitted successfully! Thank you for completing the ARAF acknowledgement."))

      ;; submitting but no signature
      (= action "submit")
      (ok (selmer/render-file "araf/templates/signature.html" (assoc params :error "You must include a signature.")))

      ;; show signature page
      :else
      (ok (selmer/render-file "araf/templates/signature.html" params)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API endpoints ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;
;; API Authentication
;;

(def authenticate
  (intc/interceptor
    {:name ::authenticate
     :enter
     (fn [{:keys [request] :as ctx}]
       (let [auth-header (get-in request [:headers "authorization"])]
         (if (and auth-header (str/starts-with? auth-header "Bearer "))
           (let [token (subs auth-header 7)]                ; Remove "Bearer "
             (if (token/valid-jwt? (araf-secret request) token)
               ctx
               (assoc ctx :response {:status 401 :body {:error "Invalid or expired token"}})))
           (assoc ctx :response {:status 401 :body {:error "Missing Authorization header"}}))))}))



(def api-create-request
  (intc/interceptor
    {:name :api-create-request
     :enter
     (fn [{:keys [request] :as ctx}]
       (let [params (:json-params request)
             {:keys [nhs-number araf-type expires]} params]
         (if-not (and nhs-number araf-type expires)
           (assoc ctx :result {:status 400 :error "Missing required fields: nhs-number, araf-type, expires"})
           (let [nnn (nnn/normalise nhs-number)
                 expires-instant (Instant/parse expires)]
             (if-not (nnn/valid? nnn)
               (assoc ctx :result {:status 400 :error "Invalid NHS number"})
               (let [araf-request (db/create-request (araf-conn request) nnn (keyword araf-type) expires-instant)]
                 (assoc ctx :result araf-request)))))))}))

(def api-get-request
  (intc/interceptor
    {:name :api-create-request
     :enter
     (fn [{:keys [request] :as ctx}]
       (let [params (:path-params request)
             long-access-key (:long-access-key params)]
         (if (str/blank? long-access-key)
           (assoc ctx :response {:status 400 :body "Missing long-access-key"})
           (let [result (db/fetch-request (araf-conn request) long-access-key)]
             (if (:error result)
               (assoc ctx :response {:status 400 :body (:message result)})
               (assoc ctx :result result))))))}))

(def api-get-responses
  "API interceptor to get all responses from a given id."
  (intc/interceptor
    {:name  ::api-get-responses
     :enter (fn [{:keys [request] :as ctx}]
              (let [{:keys [from]} (get-in ctx [:request :query-params])
                    from-id (some-> from parse-long)]
                (if from-id
                  (assoc ctx :result (db/get-responses-from (araf-conn request) from-id))
                  (assoc ctx :response {:status 400 :body "Missing or invalid 'from' parameter"}))))}))