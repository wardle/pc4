(ns pc4.araf.impl.client
  (:require [hato.client :as hc]
            [pc4.araf.impl.token :as token]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json])
  (:import [java.time Instant]))

(s/def ::url string?)
(s/def ::secret string?)
(s/def ::svc (s/keys :req-un [::url ::secret]))

(s/def ::nhs-number string?)
(s/def ::araf-type keyword?)
(s/def ::expires #(instance? Instant %))
(s/def ::create-request-params
  (s/keys :req-un [::nhs-number ::araf-type ::expires]))

(s/def ::long-access-key string?)

(def parsers
  {:date_time Instant/parse
   :expires   Instant/parse})

(defn parse [m]
  (reduce-kv
    (fn [acc k v]
      (assoc acc k (if v ((get parsers k identity) v) v)))
    {} m))

(defn http
  [{:keys [http-client url secret]} method request-url payload]
  (let [jwt-token (token/gen-jwt secret)
        full-url (str url request-url)
        {:keys [status body]}
        (hc/request (cond-> {:request-method method
                             :url            full-url
                             :http-client    http-client
                             :headers        {"Authorization" (str "Bearer " jwt-token)
                                              "Content-Type"  "application/json"}}
                      payload (assoc :body (json/write-str payload))))]
    (if (= status 200)
      (parse (json/read-str body {:key-fn keyword}))
      {:error true})))

(s/fdef create-request
  :args (s/cat :svc ::svc :params ::create-request-params))

(defn create-request
  "Call the ARAF patient server to create a new 'araf request'.
  
  Parameters:
  - svc        : clinician araf service
  - nhs-number : patient NHS number
  - araf-type  : type of ARAF form (keyword)
  - expires    : [[java.time.Instant]] for expiration of request
    
  Returns generated 'araf request' from the server"
  [svc {:keys [_nhs-number _araf-type _expires] :as params}]
  (http svc :post "/araf/api/request" params))

(s/fdef get-request
  :args (s/cat :svc ::svc :long-access-key ::long-access-key))
(defn get-request [svc long-access-key]
  "Call the ARAF patient server to fetch an existing 'araf request'"
  (http svc :get (str "/araf/api/request/" long-access-key) nil))

(comment
  (def http-client (hc/build-http-client {}))
  (def dev-secret "M5EB6WAD")
  (token/gen-jwt dev-secret)
  (def svc {:http-client http-client
            :url         "http://127.0.0.1:8081" :secret dev-secret})
  (get-request svc "00000198db286f6996ee6a2a570c4c4f259696effe46857554c1a22cda94f759")
  (create-request svc
                  {:nhs-number "1111111111"
                   :araf-type  :valproate-f
                   :expires    (Instant/.plus (Instant/now) (java.time.Duration/ofDays 14))}))