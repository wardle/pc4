(ns pc4.araf-server.interface-test
  (:require
    [clojure.spec.test.alpha :as stest]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [integrant.core :as ig]
    [pc4.config.interface :as config]
    [pc4.araf.interface :as araf]
    [pc4.log.interface :as log])
  (:import (java.time Duration Instant)))

(stest/instrument)

(def ^:dynamic *system* nil)

(def system-keys
  [:pc4.araf-server.interface/server ::araf/clinician])

(defn with-system [f]
  (let [conf (config/config :dev)
        _ (ig/load-namespaces conf system-keys)
        system (ig/init (ig/deprofile conf :dev) system-keys)]
    (binding [*system* system]
      (try
        (f)
        (finally
          (ig/halt! system))))))

(use-fixtures :once with-system)

(deftest test-create-and-fetch
  (testing "Clinician service can create and retrieve requests from patient server"
    (let [svc (::araf/clinician *system*)
          nhs-number "1111111111"
          araf-type :valproate-f
          expires (Instant/.plus (Instant/now) (Duration/ofHours 1))
          {:keys [error access_key long_access_key] :as created}
          (araf/send-create-request svc {:nhs-number nhs-number
                                         :araf-type  araf-type
                                         :expires    expires})]
      (log/debug "created:" created)
      (is (not error))
      (is access_key)
      (is long_access_key)
      (when (and created (not error))
        (let [{error' :error, nhs-number' :nhs_number, araf-type' :araf_type, :as fetched}
              (araf/send-get-request svc long_access_key)]
          (is (= created fetched))
          (log/debug "fetched:" fetched)
          (is (not error'))
          (is (= nhs-number nhs-number'))
          (is (= (name araf-type) araf-type')))))))


