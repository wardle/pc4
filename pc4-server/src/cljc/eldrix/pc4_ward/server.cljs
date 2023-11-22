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
            [com.eldrix.pc4.commons.comp :as comp]
            [com.eldrix.pc4.commons.dates :as dates]
            [ajax.core :as ajax]
            [ajax.transit :as ajax-transit]
            [goog.crypt.base64 :as b64]
            [goog.net.ErrorCode :as errors]
            [cljs.core.async :refer [<!]]
            [re-frame.core :as rf]
            ["big.js" :as Big]
            [cognitect.transit :as transit]
            [pyramid.core :as pyr])
  (:import (goog.date UtcDateTime)
           (goog.net XhrIo)))

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
      (> exp now))))

(defn make-xhrio-request
  [{:keys [token timeout _params _on-success _on-failure] :as opts :or {timeout 3000}}]
  (merge {:method          :post
          :uri             eldrix.pc4-ward.config/api-url
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
  (ajax/POST eldrix.pc4-ward.config/api-url (make-xhrio-request opts)))

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
      (println "WARNING: no CSRF token set"))
    (-> (merge {:method          :post
                :uri             eldrix.pc4-ward.config/api-url
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

(rf/reg-event-fx ::load
  (fn [{:keys [db]} [_ {:keys [query] :as config}]]
    (js/console.log "Performing pathom load:" query)
    {:db (assoc db :loading true)                           ;; we're starting some network loading
     :fx [[:pathom {:params     query
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-load-success config]
                    :on-failure [::handle-load-failure config]}]]}))

(rf/reg-event-fx ::handle-load-success  ;; HTTP success, but the response may contain an error
  (fn [{db :db} [_ {:keys [failed? on-success on-failure] :as config} response]]
    (let [failed? (or failed? (constantly false))]
      (js/console.log "Pathom load response" response)
      (if (or (:error response) (failed? response))
        (do (js/console.log "load error" (-> response :error :cause))
            (cond-> {:db (assoc db :loading false)}
                    (fn? on-failure)
                    (assoc :fx [[:dispatch ((:on-failure config) response)]])
                    (vector? on-failure)
                    (assoc :fx [[:dispatch (:on-failure config)]])))
        (cond-> {:db (let [{entity-db :db} (comp/target-results (:entity-db db) config response)]
                       (assoc db :loading false, :entity-db entity-db))}
                (fn? on-success)
                (assoc :fx [[:dispatch ((:on-success config) response)]])
                (vector? on-success)
                (assoc :fx [[:dispatch (:http-no-on-success config)]]))))))

(rf/reg-sub ::pull
  (fn [db [_ query targets]]
    (comp/pull-results (:entity-db db) {:query query :targets targets})))

(rf/reg-event-fx ::handle-load-failure
  (fn [{:keys [db]} [_ config response]]
    (js/console.log "load error" response)
    (tap> {::handle-load-failure {:config config :response response}})))

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

