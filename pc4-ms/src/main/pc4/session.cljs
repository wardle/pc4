(ns pc4.session
  (:require [clojure.string :as str]
            [goog.crypt.base64 :as b64]
            [taoensso.timbre :as log]
            [com.fulcrologic.fulcro.components :as comp]
            [pc4.app :refer [SPA]])
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

(defonce authentication-token (atom nil))

(defn check-token []
  (let [token @authentication-token]
    (log/info "checking valid token, and whether expiring soon")
    (if (jwt-valid? token)
      (when (jwt-expires-in-seconds? token 90)
        (log/info "token expires within 90s.... refreshing token")
        (comp/transact! @SPA [(list 'pc4.users/refresh-token {:token token})]))
      (do                                                   ;; force logout         ;; TODO: clear currently logged in user
        ))))

(defonce do-timer (do (js/setInterval check-token 60000)))
