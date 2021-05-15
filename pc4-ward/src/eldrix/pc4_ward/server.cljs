(ns eldrix.pc4-ward.server
  (:require [clojure.string :as str]
            [ajax.core :as ajax]
            [goog.crypt.base64 :as b64])
  (:import [goog.date UtcDateTime]))

(defn jwt-token-payload
  "Extracts the payload from a JWT token"
  [token]
  (js->clj (.parse js/JSON (b64/decodeString (second (str/split token #"\.")))) :keywordize-keys true))

(defn jwt-expiry-date
  "Determines the expiry date of the token"
  [token]
  (some-> (:exp (jwt-token-payload token))
          (* 1000)                                          ;; jwt token expiry is in seconds from epoch. convert to milliseconds
          UtcDateTime/fromTimestamp))

(defn jwt-expires-seconds
  "Gives the number of seconds that this token will remain valid"
  [token]
  (if (str/blank? token)
    0
    (- (:exp (jwt-token-payload token)) (int (/ (.getTime (js/Date.)) 1000)))))

(defn jwt-expires-in-seconds?
  "Does the token expire within the next (x) seconds? Returns true if no token"
  [token sec]
  (if (str/blank? token)
    true
    (let [now (int (/ (.getTime (js/Date.)) 1000))
          dln (+ now sec)
          exp (:exp (jwt-token-payload token))]
      (> dln exp))))

(defn jwt-valid?
  "Is the token non-nil and not expired? This does not test the token cryptographically"
  [token]
  (if (str/blank? token)
    false
    (let [now (int (/ (.getTime (js/Date.)) 1000))
          exp (:exp (jwt-token-payload token))]
      (> now exp))))

(defn make-xhrio-request
  [{:keys [token params on-success on-failure]}]
  {:method          :post
   :uri             "http://localhost:8080/api"
   :timeout         3000
   :format          (ajax/transit-request-format)
   :response-format (ajax/transit-response-format)
   :headers         (when token {:Authorization (str "Bearer " token)})
   :params          params
   :on-success      on-success
   :on-failure      on-failure})

(defn make-snomed-search-op
  [{:keys [_s _constraint] :as params}]
  [{(list 'info.snomed.Search/search
          params)
    [:info.snomed.Concept/id
     :info.snomed.Description/id
     :info.snomed.Description/term
     {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}])

(defn make-refresh-token-op
  [{:keys [token]}]
  [{(list 'pc4.users/refresh-token
          {:token token})
    [:io.jwt/token]}])

(defn make-login-op
  [{:keys [system value password] :as params}]
  [{(list 'pc4.users/login
          params)
    [:urn.oid.1.2.840.113556.1.4/sAMAccountName
     :io.jwt/token
     :urn.oid.2.5.4/givenName
     :urn.oid.2.5.4/surname
     :urn.oid.2.5.4/commonName
     {:org.hl7.fhir.Practitioner/name
      [:org.hl7.fhir.HumanName/use
       :org.hl7.fhir.HumanName/family
       :org.hl7.fhir.HumanName/given]}]}])
