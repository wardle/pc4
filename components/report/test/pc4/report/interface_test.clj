(ns pc4.report.interface-test
  (:require
    [clojure.java.io :as io]
    [clojure.spec.gen.alpha :as gen]
    [clojure.spec.test.alpha :as stest]
    [clojure.test :refer [deftest is]]
    [pc4.report.interface :as report])
  (:import (java.io File)))

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

(defn load-test-image []
  (with-open [in (io/input-stream (io/resource "report/test-image.png"))
              baos (java.io.ByteArrayOutputStream.)]
    (io/copy in baos)
    (.toByteArray baos)))

(def form-tests
  [{:patient "John Smith\n123 High Street\nCardiff\nCF14 4XW"
    :drug "Methotrexate"
    :consultant "Dr. Williams"
    :hospital "General Hospital"
    :department "Rheumatology"
    :condition "Rheumatoid Arthritis"
    :date "13 October 2025"
    :telephone "01234567890"
    :signature (load-test-image)
    :to "M Jones"
    :cv-number "55"
    :return-name-and-address "Dr. Williams\nGeneral Hospital\n02920747747"
    :write true}
   {"patient" "Jane Doe" "consultant" "Dr. Williams"}
   {:hospital "General Hospital" :telephone "123456"}
   {}])

(deftest pdf-form-test
  (let [test-form (io/resource "report/scp-request-v2.4.pdf")]
    (doseq [test-data form-tests
            protect-fields [true false]]
      (let [data (dissoc test-data :write)
            filled-pdf (report/fill-pdf-form test-form data :protect-fields protect-fields)]
        (is (bytes? filled-pdf))
        (is (pos? (count filled-pdf)))
        (when (and (:write test-data) protect-fields)
          (let [temp-file (File/createTempFile "filled-scp-form-" ".pdf")]
            (with-open [out (io/output-stream temp-file)]
              (io/copy filled-pdf out))
            (println "Sample filled PDF saved to:" (.getAbsolutePath temp-file))))))))
