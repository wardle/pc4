(ns pc4.arafc.impl.qr-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [pc4.arafc.impl.qr :as qr])
  (:import (java.io File)))

(deftest test-qr-code-generation
  (testing "generate-qr-code creates a QR code image file"
    (let [base-url "https://araf.patientcare.app/araf/form/"
          long-access-key "long-access-key"
          qr-bytes (qr/generate (str base-url long-access-key))
          temp-file (File/createTempFile "qr-test-" ".png")]
      (is (some? qr-bytes))
      (is (> (count qr-bytes) 0))
      (io/copy qr-bytes temp-file)
      (is (.exists temp-file))
      (is (> (.length temp-file) 0))
      (println "QR code written to:" (.getAbsolutePath temp-file)))))
