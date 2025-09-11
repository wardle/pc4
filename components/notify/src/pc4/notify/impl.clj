(ns pc4.notify.impl
  (:require [buddy.sign.jwt :as jwt]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [hato.client :as hc])
  (:import (java.time Instant)))


(defn parse-api-key [k]
  (let [parts (str/split k #"-")]
    {:key        k
     :key-name   (first parts)
     :service-id (str/join "-" (subvec parts 1 6))
     :secret-key (str/join "-" (subvec parts 6))}))

(defn make-jwt
  "Create a JWT suitable for use as a bearer token in the Authentication header
  for requests to the gov.uk notify service."
  [parsed-api-key]
  (jwt/sign {:iss (:service-id parsed-api-key)
             :iat (.getEpochSecond (Instant/now))}
            (:secret-key parsed-api-key)))

(def services
  {:sms   "/v2/notifications/sms"
   :email "/v2/notifications/email"})

(s/def ::api-key string?)
(s/def ::service (set (keys services)))
(s/def ::params map?)

(s/fdef notify!
  :args (s/cat :api-key ::api-key :service ::service :params ::params))
(defn notify!
  "Synchronously send a message using the `service` specified (:sms :email)
  using the parameters specified. Throws an exception if the request fails."
  [api-key service params]
  (hc/post (str "https://api.notifications.service.gov.uk" (get services service))
           {:headers               {"Authorization" (str "Bearer " (make-jwt (parse-api-key api-key)))}
            :content-type          :json
            :form-params           params
            :throw-entire-message? true
            :as                    :json}))

(defn send-sms [api-key phone-number template-id opts]
  (notify! api-key :sms {:phone_number    phone-number
                         :template_id     template-id
                         :personalisation opts}))

(defn send-email [api-key email template-id opts]
  (notify! api-key :email {:email_address   email
                           :template_id     template-id
                           :personalisation opts}))

