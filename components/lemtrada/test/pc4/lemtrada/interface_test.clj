(ns pc4.lemtrada.interface-test
  (:require [clojure.test :as test :refer [deftest is]]
            [integrant.core :as ig]
            [pc4.config.interface :as config]
            [pc4.lemtrada.interface :as lemtrada]))

(deftest dummy-test
  (is (= 1 1)))

(deftest test-env
  (ig/load-namespaces (config/config :dev) [:pc4.lemtrada.interface/env])
  (let [system (ig/init (config/config :dev) [:pc4.lemtrada.interface/env])]
    (is system)))
