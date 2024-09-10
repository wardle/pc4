(ns pc4.wales-nadex.interface-test
  (:require
   [clojure.spec.gen.alpha :as gen]
   [clojure.test :as test :refer [deftest is]]
   [pc4.wales-nadex.interface :as wales-nadex]
   [clojure.spec.alpha :as s]))

(deftest test-mock-service
  (with-open [svc (wales-nadex/open {:users [{:username "ma090906"
                                              :password "password"
                                              :data {:sn "Wardle"
                                                     :givenName "Mark"}}]})]
    (is (wales-nadex/can-authenticate? svc "ma090906" "password"))
    (is (= (:sn (first (wales-nadex/search-by-username svc "ma090906"))) "Wardle"))))

(deftest test-ldap->fhir-r4
  (let [users (gen/sample (wales-nadex/gen-user))
        ret-spec (:ret (s/get-spec `wales-nadex/user->fhir-r4))]
    (doseq [user users]
      (let [user# (wales-nadex/user->fhir-r4 user)]
        (is (s/valid? ret-spec user#))))))

