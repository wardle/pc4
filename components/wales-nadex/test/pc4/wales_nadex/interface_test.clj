(ns pc4.wales-nadex.interface-test
  (:require
   [clojure.spec.gen.alpha :as gen]
   [clojure.test :as test :refer [deftest is use-fixtures]]
   [integrant.core :as ig]
   [pc4.config.interface :as config]
   [pc4.wales-nadex.interface :as wales-nadex]
   [clojure.spec.alpha :as s]))

(def mock-users
  [{:username "ma090906"
    :password "password"
    :data {:sn "Wardle"
           :givenName "Mark"}}])

(def ^:dynamic *svc* nil)

(defn fixture [test-fn]
  ;; discard the 'dev' conn, and replace with a mock conn for tests
  (let [config (assoc-in (config/config :dev) [:pc4.wales-nadex.interface/svc :conn] {:users mock-users})
        system (ig/init config [:pc4.wales-nadex.interface/svc])
        svc (:pc4.wales-nadex.interface/svc system)]
    (binding [*svc* svc]
      (test-fn)
      (ig/halt! system))))

(use-fixtures :once fixture)

(deftest test-mock-service
  (is (wales-nadex/can-authenticate? *svc* "ma090906" "password"))
  (is (= (:sn (first (wales-nadex/search-by-username *svc* "ma090906"))) "Wardle")))

(deftest test-mock-repeated-calls
  (let [user (wales-nadex/search-by-username *svc* "ma090906")]
    (dotimes [_ 10]
      (is (= user (wales-nadex/search-by-username *svc* "ma090906"))))))

(deftest test-ldap->fhir-r4
  (let [users (gen/sample (wales-nadex/gen-user))
        ret-spec (:ret (s/get-spec `wales-nadex/user->fhir-r4))]
    (doseq [user users]
      (let [user# (wales-nadex/user->fhir-r4 *svc* user)]
        (is (s/valid? ret-spec user#) (s/explain-str ret-spec user#))))))

(deftest test-job-title->fhir-r4
  (let [users (gen/sample (wales-nadex/gen-user))]
    (doseq [user users]
      (let [role (wales-nadex/user->fhir-r4-practitioner-role *svc* user)]
        (is (s/valid? :org.hl7.fhir/PractitionerRole role) (s/explain-str :org.hl7.fhir/PractitionerRole role))))))

(comment
  (clojure.test/run-tests)
  (:pc4.wales-nadex.interface/svc (assoc-in (config/config :dev) [:pc4.wales-nadex.interface/svc :conn] {:hi :there}))
  (def system (ig/init (config/config :dev) [:pc4.wales-nadex.interface/svc]))
  (def users (gen/sample (wales-nadex/gen-user))))
