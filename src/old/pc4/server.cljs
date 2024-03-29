(ns pc4.server
  "Support for interacting with the pc4 server.
  The pc4 server provides a single HTTP POST API endpoint which accepts EQL.
  This means it can be used to perform graph queries or execute operations
  with the client deciding what properties to return.

  There are two main methods for invoking this API. The first is a direct
  call using cljs-ajax. The second is to use re-frame's http-fx handler.
  Both have the same result."
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [ajax.core :as ajax]
            [ajax.transit :as ajax-transit]
            ["big.js" :as Big]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [goog.crypt.base64 :as b64]
            [goog.net.ErrorCode :as errors]
            [pc4.config :as config]
            [pc4.dates :as dates]
            [re-frame.core :as rf]
            [taoensso.timbre :as log])
  (:import (goog.async Debouncer)
           (goog.date UtcDateTime)
           (goog.net XhrIo)))

;; Thank you to Martin Klepsch for this code
;;  https://martinklepsch.org/posts/simple-debouncing-in-clojurescript.html
(defn debounce
  "Returns a function that will debounce with the interval specified."
  [f interval]
  (let [dbnc (Debouncer. f interval)]
    (fn [& args] (.apply (.-fire dbnc) dbnc (to-array args)))))

(def dispatch-debounced
  "A convenience debouncer with a predetermined interval."
  (debounce rf/dispatch 200))

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
  (or (str/blank? token)
      (let [now (int (/ (.getTime (js/Date.)) 1000))
            dln (+ now sec)
            exp (:exp (jwt-token-payload token))]
        (> dln exp))))

(defn jwt-expired?
  "Is the token either nil or expired?"
  [token]
  (or (str/blank? token)
      (let [now (int (/ (.getTime (js/Date.)) 1000))
            exp (:exp (jwt-token-payload token))]
        (> now exp))))

(defn make-xhrio-request
  [{:keys [token timeout _params _on-success _on-failure] :as opts :or {timeout 3000}}]
  (merge {:method          :post
          :uri             config/api-url
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
  [:urn:oid:1.2.840.113556.1.4/sAMAccountName
   :io.jwt/token
   :urn:oid:2.5.4/givenName
   :urn:oid:2.5.4/surname
   :urn:oid:0.9.2342.19200300.100.1.3
   :urn:oid:2.5.4/commonName
   :urn:oid:2.5.4/title
   :urn:oid:2.5.4/telephoneNumber
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

(defn do!
  "Execute a xhrio request on the server."
  [opts]
  (ajax/POST config/api-url (make-xhrio-request opts)))

(defn default-error-handler [x]
  (js/console.log "error in request: " x))


;;;;
;;;;

(defn make-ajax-xhrio-handler
  "Create a xhrio handler."
  [& {:keys [on-success on-failure xhrio]}]
  (fn [[success? response]]                                 ; see http://docs.closure-library.googlecode.com/git/class_goog_net_XhrIo.html
    (if success?
      (on-success response)
      (let [details (merge
                      {:uri             (.getLastUri ^js xhrio)
                       :last-method     (.-lastMethod_ ^js xhrio)
                       :last-error      (.getLastError ^js xhrio)
                       :last-error-code (.getLastErrorCode ^js xhrio)
                       :debug-message   (-> ^js xhrio .getLastErrorCode (errors/getDebugMessage))}
                      response)]
        (on-failure details)))))

(defn request->xhrio-options
  [{:keys [on-success on-failure timeout token] :or {timeout 10000} :as request}]
  (let [xhrio (new goog.net.XhrIo)
        csrf-token js/pc4_network_csrf_token]
    (when-not csrf-token
      (log/warn "WARNING: no CSRF token set"))
    (-> (merge {:method          :post
                :uri             config/api-url
                :timeout         timeout
                :format          (ajax-transit/transit-request-format
                                   {:handlers
                                    (merge dates/transit-writers
                                           {Big (transit/write-handler (constantly "f")
                                                                       (fn [^Big x] (.toString x)))})})
                :response-format (ajax-transit/transit-response-format
                                   {:handlers (merge dates/transit-readers
                                                     {"f" (transit/read-handler #(Big. %))})})
                :headers
                (cond-> {"X-CSRF-Token" csrf-token}
                        token (assoc "Authorization" (str "Bearer " token)))}
               request)
        (assoc
          :api xhrio
          :handler (make-ajax-xhrio-handler
                     :on-success #(rf/dispatch (conj on-success %))
                     :on-failure #(rf/dispatch (conj on-failure %))
                     :xhrio xhrio))
        (dissoc :on-success :on-failure :on-request))))

(defn pathom-effect
  "This is an effect handler that sends a pathom API request to the server.
  This currently uses an AJAX request."
  [{:keys [on-request] :as request}]
  (let [xhrio (-> request
                  request->xhrio-options
                  ajax/ajax-request)]
    (when on-request
      (rf/dispatch (conj on-request xhrio)))))

(re-frame.core/reg-fx
  :pathom
  (fn [request]
    (pathom-effect request)))

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
  (println (:wales.nhs.cavuhb.Address/DATE_FROM a1)))

