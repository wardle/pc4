(ns com.eldrix.pc4.server.rsdb.results-test
  (:require [clojure.test :refer [deftest is]]
            [com.eldrix.pc4.server.rsdb.results :refer [parse-lesions]]))

(deftest parsing-lesions
  (is (= {:change 2} (parse-lesions "+2")))
  (is (= {:change -2} (parse-lesions "-2")))
  (is (= {:approximate-count 2} (parse-lesions "~2")))
  (is (= {:more-than 2} (parse-lesions ">2")))
  (is (= {:approximate-range {:count 10 :plus-minus 2}} (parse-lesions "10+/-2")))
  (is (= {:range {:from 5 :to 10}} (parse-lesions "5-10")))
  (is (nil? (parse-lesions " 2")))
  (is (nil? (parse-lesions "2-")))
  (is (nil? (parse-lesions "hello"))))

(comment
  (clojure.test/run-tests))