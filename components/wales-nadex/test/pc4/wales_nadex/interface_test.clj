(ns pc4.wales-nadex.interface-test
  (:require [clojure.test :as test :refer [deftest is]]
            [pc4.wales-nadex.interface :as wales-nadex]))

(deftest test-mock-service
  (with-open [svc (wales-nadex/open {:users [{:username "ma090906"
                                              :password "password"
                                              :data {:sn "Wardle"
                                                     :givenName "Mark"}}]})]
    (is (wales-nadex/can-authenticate? svc "ma090906" "password"))
    (is (= (:sn (first (wales-nadex/search-by-username svc "ma090906"))) "Wardle"))))


