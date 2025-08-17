(ns pc4.araf-server.handlers
  (:require
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.log.interface :as log]
    [pc4.nhs-number.interface :as nnn]
    [selmer.parser :as selmer])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

(defn ok [body]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    body})

(defn welcome
  [request {:keys [error nhs-number date-of-birth]}]
  (let [submit-url (route/url-for :start)
        csrf-token (csrf/existing-token request)
        today (.format (LocalDate/now) DateTimeFormatter/ISO_LOCAL_DATE)
        min-date (.format (.minusYears (LocalDate/now) 100) DateTimeFormatter/ISO_LOCAL_DATE)]
    (ok (selmer/render-file "araf/templates/welcome.html" {:title "ARAF" 
                                                           :url submit-url 
                                                           :csrf-token csrf-token 
                                                           :error error
                                                           :nhs-number nhs-number
                                                           :date-of-birth date-of-birth
                                                           :min-date min-date
                                                           :max-date today}))))


(defn welcome-handler
  "Patient landing page when there is no direct link with an access key.
  Patient has to enter their NHS number and date of birth to proceed."
  [request]
  (welcome request {}))

(defn start-handler
  [{:keys [form-params] :as request}]
  (let [{:keys [nhs-number date-of-birth]} form-params]
    (log/debug "start" request)
    (clojure.pprint/pprint form-params)
    (log/debug "nhs number" (nnn/valid? (nnn/normalise nhs-number)))
    (cond
      (not (nnn/valid? (nnn/normalise nhs-number)))
      (welcome request {:error "Invalid NHS number" :nhs-number nhs-number :date-of-birth date-of-birth})
      :else
      (welcome request {:error "Success"}))))