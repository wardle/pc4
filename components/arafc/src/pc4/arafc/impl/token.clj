(ns pc4.arafc.impl.token
  (:require [buddy.sign.jwt :as jwt])
  (:import (java.time Instant)))

(defn gen-jwt
  "Generate an authentication token with default 5-minute expiry."
  ([secret]
   (gen-jwt secret {:now (Instant/now) :expiry-seconds 300}))
  ([secret {:keys [now expiry-seconds] :or {expiry-seconds 300}}]
   (let [exp-time (Instant/.plusSeconds (or now (Instant/now)) expiry-seconds)] ; 5 minutes
     (jwt/sign {:exp (Instant/.getEpochSecond exp-time)} secret))))