(ns pc4.araf.impl.token
  (:require
    [buddy.core.codecs :as codecs]
    [buddy.core.nonce :as nonce]
    [buddy.sign.jwt :as jwt]
    [clojure.string :as str])
  (:import
    (java.security SecureRandom)
    (java.time Instant)))

(defn gen-long-access-key
  "Generates a 'long' access key suitable for use in direct links in an email or
  in a QR code."
  []
  (codecs/bytes->hex (nonce/random-nonce 32)))

(def ^:private base32-chars "ABCDEFGHJKMNPQRSTVWXYZ23456789")

(defn gen-access-key
  "Generates a secure 12-character token using base32 encoding. Designed for
  entry by humans so uses characters A-Z (excluding I,L,O,U) and digits 2-9 to
  avoid confusion. This only creates 60 bits of entropy, but is used in
  combination with the NHS number AND rate limiting / access lockout."
  []
  (let [rng (SecureRandom.)
        n (count base32-chars)]
    (str/join (take 12 (repeatedly #(nth base32-chars (.nextInt rng n)))))))

(defn gen-jwt
  "Generate an authentication token with default 5-minute expiry."
  ([secret]
   (gen-jwt secret {:now (Instant/now) :expiry-seconds 300}))
  ([secret {:keys [now expiry-seconds] :or {expiry-seconds 300}}]
   (let [exp-time (Instant/.plusSeconds (or now (Instant/now)) expiry-seconds)] ; 5 minutes
     (jwt/sign {:exp (Instant/.getEpochSecond exp-time)} secret))))

(defn valid-jwt?
  "Validate an authentication token. Returns true if valid and not expired."
  ([secret token]
   (try (boolean (jwt/unsign token secret))
        (catch Exception _ false)))
  ([secret token ^Instant current-time]
   (try (boolean (jwt/unsign token secret {:now (Instant/.getEpochSecond current-time)}))
        (catch Exception _ false))))

(comment
  (gen-access-key)
  (codecs/bytes->hex (gen-long-access-key))
  (valid-jwt? "secret" (gen-jwt "secret"))
  (gen-jwt "M5EB6WAD"))