(ns pc4.report.interface-test
  (:require
   [clojure.spec.gen.alpha :as gen]
   [clojure.spec.test.alpha :as stest]
   [clojure.test :as test :refer [deftest is]]
   [pc4.report.interface :as report]))

(stest/instrument)

(deftest encounter-report
  (doseq [data (gen/sample report/gen-encounter-report)]
    (let [pdf-bytes (report/encounter-report data)]
      (is (bytes? pdf-bytes))
      (is (< 0 (count pdf-bytes)))
      (let [stamped-pdf-bytes (-> pdf-bytes
                                  (report/stamp-pdf-text {:centre "DRAFT"})
                                  (report/stamp-pdf-template pdf-bytes))] ;; stamp with itself just to test!
        (is (bytes? stamped-pdf-bytes))
        (is (< 0 (count stamped-pdf-bytes)))))))
