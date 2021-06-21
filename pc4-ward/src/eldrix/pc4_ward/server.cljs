(ns eldrix.pc4-ward.server
  "Support for interacting with the pc4 server.
  The pc4 server provides a single HTTP POST API endpoint which accepts EQL.
  This means it can be used to perform graph queries or execute operations
  with the client deciding what properties to return.

  There are two main methods for invoking this API. The first is a direct
  call using cljs-ajax. The second is to use re-frame's http-fx handler.
  Both have the same result."
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [clojure.string :as str]
            [com.eldrix.pc4.commons.dates :as dates]
            [ajax.core :as ajax]
            [ajax.transit :as ajax-transit]
            [goog.crypt.base64 :as b64]
            [cljs.core.async :refer [<!]])
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
  [{:keys [token timeout _params _on-success _on-failure] :as opts :or {timeout 3000}}]
  (merge {:method          :post
          :uri             "http://localhost:8080/api"
          :timeout         timeout
          :format          (ajax-transit/transit-request-format {:handlers dates/transit-writers})
          :response-format (ajax-transit/transit-response-format {:handlers dates/transit-readers})
          :headers         (when token {:Authorization (str "Bearer " token)})}
         opts))

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

(defn make-ping-op
  [{:keys [uuid]}]
  [{(list 'pc4.users/ping
          {:uuid uuid})
    [:uuid]}])


(def user-query
  [:urn.oid.1.2.840.113556.1.4/sAMAccountName
   :io.jwt/token
   :urn.oid.2.5.4/givenName
   :urn.oid.2.5.4/surname
   :urn.oid.0.9.2342.19200300.100.1.3
   :urn.oid.2.5.4/commonName
   :urn.oid.2.5.4/title
   :urn.oid.2.5.4/telephoneNumber
   :org.hl7.fhir.Practitioner/telecom
   :org.hl7.fhir.Practitioner/identifier
   {:org.hl7.fhir.Practitioner/name
    [:org.hl7.fhir.HumanName/use
     :org.hl7.fhir.HumanName/family
     :org.hl7.fhir.HumanName/given]}])

(defn make-login-op
  [{:keys [system value password] :as params}]
  [{(list 'pc4.users/login
          params)
    user-query}])

(defn make-cav-fetch-patient-op
  [{:keys [pas-identifier]}]
  [{(list 'wales.nhs.cavuhb/fetch-patient
          {:system "http://fhir.cavuhb.nhs.wales/Id/pas-identifier" :value pas-identifier})
    [:org.hl7.fhir.Patient/birthDate
     :wales.nhs.cavuhb.Patient/DATE_DEATH
     :uk.nhs.cfh.isb1505/display-age
     :wales.nhs.cavuhb.Patient/IS_DECEASED
     :wales.nhs.cavuhb.Patient/ADDRESSES
     :wales.nhs.cavuhb.Patient/HOSPITAL_ID
     :uk.nhs.cfh.isb1504/nhs-number
     :uk.nhs.cfh.isb1506/patient-name
     :org.hl7.fhir.Patient/identifiers
     :wales.nhs.cavuhb.Patient/SEX
     :org.hl7.fhir.Patient/gender
     :org.hl7.fhir.Patient/deceased
     :org.hl7.fhir.Patient/currentAddress]}])

(defn do!
  "Execute a xhrio request on the server."
  [opts]
  (ajax/POST "http://localhost:8080/api" (make-xhrio-request opts)))

(defn default-error-handler [x]
  (js/console.log "error in request: " x))

(defn fetch-patient
  [s & {:keys [handler error-handler token] :or {error-handler default-error-handler}}]
  (do! {:params        (make-cav-fetch-patient-op {:pas-identifier s})
        :token         token
        :handler       #(handler (get % 'wales.nhs.cavuhb/fetch-patient))
        :error-handler error-handler}))

(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (make-login-op {:system "cymru.nhs.uk" :value "ma090906" :password "password"})

  (def results (atom nil))
  (do! {:params        (make-snomed-search-op {:s "amyloid" :max-hits 5})
        :handler       #(reset! results %)
        :error-handler #(println "failure: " %)})
  (vals @results)

  (do! {:params        (make-fetch-uk-orgs ["7A4BV" "7A4"])
        :handler       #(reset! results %)
        :error-handler #(println "failure: " %)})

  (fetch-uk-orgs ["7A4BV"] :handler #(println "received result: " %))

  (reset! results nil)
  (make-cav-search-op {:pas-identifier "A999998"})

  (do! {:params        (make-cav-fetch-patient-op {:pas-identifier "A999998"})
        :handler       #(reset! results %)
        :error-handler #(println "failure: " %)})
  @results
  (def pt (get @results 'wales.nhs.cavuhb/fetch-patient))
  pt
  (def a1 (first (:wales.nhs.cavuhb.Patient/ADDRESSES pt)))
  (println (:wales.nhs.cavuhb.Address/DATE_FROM a1))


  )