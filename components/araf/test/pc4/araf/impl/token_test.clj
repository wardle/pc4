(ns pc4.araf.impl.token-test
  (:require [clojure.test :refer :all]
            [pc4.araf.impl.token :as token])
  (:import (java.time Instant)))

(deftest test-jwt-functions
  (testing "generate and validate valid JWT"
    (let [svc {:secret "secret"}]
      (dotimes [_ 10]
        (is (token/valid-jwt? svc (token/gen-jwt svc))))))

  (testing "reject expired token"
    (let [svc {:secret "secret"}
          timestamp (Instant/parse "2025-01-01T12:00:00Z")
          token (token/gen-jwt svc {:now timestamp})
          current-time (Instant/parse "2025-01-01T12:10:00Z")] ; 10 minutes later
      (is (not (token/valid-jwt? svc token current-time)))))

  (testing "reject invalid token"
    (let [svc {:secret "secret"}]
      (is (not (token/valid-jwt? svc "invalid-token"))))))
