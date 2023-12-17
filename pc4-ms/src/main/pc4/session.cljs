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
      (> exp now))))

(defonce authentication-token (atom nil))

(defn check-token []
  (let [token @authentication-token]
    (cond
      ;; if we have a valid token but it is due to expire, please refresh it
      (and (jwt-valid? token) (jwt-expires-in-seconds? token 90))
      (comp/transact! @SPA [(list 'pc4.users/refresh-token {:token token})])

      ;; if we have a token and it is invalid, clear the token and logout
      (and token (not (jwt-valid? token)))
      (comp/transact! @SPA [(list 'pc4.users/logout {:message "Your session timed out."})])

      token
      (log/debug "active session with valid token, expiring in" (jwt-expires-seconds token) "seconds")

      ;; we have no token, so do nothing
      :else
      (log/debug "no currently logged in user"))))

(defonce do-timer (do (js/setInterval check-token 5000)))
