(ns com.eldrix.pc4.rsdb.results-test
  (:require [clojure.test :as test :refer [deftest is use-fixtures]]
            [com.eldrix.pc4.system :as pc4]
            [com.eldrix.pc4.rsdb.patients :as patients]
            [com.eldrix.pc4.rsdb.projects :as projects]
            [com.eldrix.pc4.rsdb.results :as results :refer [parse-count-lesions parse-change-lesions]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.gen.alpha :as gen])
  (:import (java.time LocalDate)
           (clojure.lang ExceptionInfo)
           (java.time.temporal ChronoUnit)))

(stest/instrument)
(def ^:dynamic *system* nil)
(def ^:dynamic *patient* nil)


(defn fetch-result [conn patient-identifier ^LocalDate date]
  (let [results (->> (results/results-for-patient conn patient-identifier)
                     (filter #(.isEqual (:t_result/date %) date)))]
    (case (count results)
      0 (throw (ex-info "No result found" {:patient-identifier patient-identifier :date date}))
      1 (first results)
      (throw (ex-info "More than one result found for single date" {:patient-identifier patient-identifier :date date})))))

(deftest parsing-lesions
  (is (= 2 (parse-change-lesions "+2")))
  (is (= -2 (parse-change-lesions "-2")))
  (is (= {:type :approximate :n 2} (parse-count-lesions "~2")))
  (is (= {:type :more-than :n 2} (parse-count-lesions ">2")))
  (is (= {:type :range :from 8 :to 12} (parse-count-lesions "10+/-2")))
  (is (= {:type :range :from 5 :to 10} (parse-count-lesions "5-10")))
  (is (nil? (parse-change-lesions "2")))
  (is (nil? (parse-count-lesions " 2")))
  (is (nil? (parse-count-lesions "2-")))
  (is (nil? (parse-count-lesions "hello")))
  (is (map results/lesion-range (gen/sample (s/gen ::results/lesion-count) 100))))

(deftest lesion-ranges
  (is (s/valid? ::results/lesion-count {:type :exact :n 5}))
  (is (s/valid? ::results/lesion-count {:type :more-than :n 5}))
  (is (s/valid? ::results/lesion-count {:type :range :from 5 :to 10}))
  (is (every? identity (map results/lesion-range (gen/sample (s/gen ::results/lesion-count) 1000)))))


(deftest normalize-mri-brain-annotations
  (is (= (results/normalize-mri-brain {:t_result_mri_brain/id                                                5
                                       :t_result_mri_brain/with_gadolinium                                   true
                                       :t_annotation_mri_brain_multiple_sclerosis_new/id                     24
                                       :t_annotation_mri_brain_multiple_sclerosis_new/change_t2_hyperintense "+2"})
         {:t_result_mri_brain/id                                             5
          :t_result_mri_brain/with_gadolinium                                true
          :t_result_mri_brain/annotation_mri_brain_multiple_sclerosis_new_id 24
          :t_result_mri_brain/change_t2_hyperintense                         "+2"})))



(deftest save-mri-brain-with-annotations
  (let [conn (:com.eldrix.rsdb/conn *system*)
        patient *patient*]
    (let [date (LocalDate/of 2020 1 1)
          data {:t_result_type/result_entity_name               "ResultMriBrain"
                :t_result_mri_brain/date                        date
                :t_result_mri_brain/report                      "Scan typical of multiple sclerosis."
                :t_result_mri_brain/with_gadolinium             true
                :t_result_mri_brain/total_gad_enhancing_lesions "~5"
                :t_result_mri_brain/total_t2_hyperintense       "2"
                :t_result_mri_brain/multiple_sclerosis_summary  "TYPICAL"
                :user_fk                                        1
                :patient_fk                                     (:t_patient/id patient)}
          result (results/save-result! conn data)
          fetched (fetch-result conn (:t_patient/patient_identifier patient) date)]
      (tap> {:data data :result result :fetched fetched})
      (is result)
      (is fetched)
      (is (= (:t_result_type/result_entity_name data) (:t_result_type/result_entity_name result) (:t_result_type/result_entity_name fetched)))
      (is (= (:t_result_mri_brain/report data) (:t_result_mri_brain/report result) (:t_result_mri_brain/report fetched)))
      (is (= (:t_result_mri_brain/total_t2_hyperintense data) (:t_result_mri_brain/total_t2_hyperintense result) (:t_result_mri_brain/total_t2_hyperintense fetched))))))

(deftest save-mri-brain-without-annotations
  (let [conn (:com.eldrix.rsdb/conn *system*)
        patient *patient*]
    (let [result (results/save-result! conn
                                       {:t_result_type/result_entity_name   "ResultMriBrain"
                                        :t_result_mri_brain/date            (LocalDate/of 2020 1 2)
                                        :t_result_mri_brain/report          "Scan typical of multiple sclerosis."
                                        :t_result_mri_brain/with_gadolinium true
                                        :t_result_mri_brain/user_fk         1
                                        :t_result_mri_brain/patient_fk      (:t_patient/id patient)})])))

(deftest save-and-update-brain
  (let [conn (:com.eldrix.rsdb/conn *system*)
        patient *patient*]
    (let [result (results/save-result! conn
                                       {:t_result_type/result_entity_name   "ResultMriBrain"
                                        :t_result_mri_brain/date            (LocalDate/of 2020 1 3)
                                        :t_result_mri_brain/report          "Scan typical of multiple sclerosis."
                                        :t_result_mri_brain/with_gadolinium true
                                        :t_result_mri_brain/user_fk         1
                                        :t_result_mri_brain/patient_fk      (:t_patient/id patient)})
          updated (results/save-result! conn (assoc result :t_result_mri_brain/report "Normal scan"))]
      (is (= "Normal scan" (:t_result_mri_brain/report updated)))
      (is (= (:t_result/id updated) (:t_result/id result)))
      (is (= (:t_result_mri_brain/id updated) (:t_result_mri_brain/id result))))))


(deftest save-full-blood-count
  (let [conn (:com.eldrix.rsdb/conn *system*)
        patient *patient*
        date (LocalDate/of 2020 1 4)
        result (results/save-result! conn {:t_result_type/result_entity_name "ResultFullBloodCount"
                                           :t_result_full_blood_count/date   date
                                           :patient_fk                       (:t_patient/id patient)
                                           :user_fk                          1})
        fetched (fetch-result conn (:t_patient/patient_identifier patient) date)]
    (is (= 24 (:t_result_type/id fetched)))
    (is (= "ResultFullBloodCount" (:t_result_type/result_entity_name fetched)))))

(deftest save-and-update-ecg
  (let [conn (:com.eldrix.rsdb/conn *system*)
        patient *patient*]
    (let [result (results/save-result! conn
                                       {:t_result_type/result_entity_name "ResultECG"
                                        :t_result_ecg/date                (LocalDate/of 2020 1 5)
                                        :t_result_ecg/notes               "Normal"
                                        :user_fk                          1
                                        :patient_fk                       (:t_patient/id patient)})
          updated (results/save-result! conn (assoc result :t_result_ecg/notes "Abnormal"))]
      (is (= "Abnormal" (:t_result_ecg/notes updated)))
      (is (= (:t_result/id updated) (:t_result/id result)))
      (is (= (:t_result_ecg/id updated) (:t_result_ecg/id result))))))

(deftest invalid-mri-brain-annotations
  (let [conn (:com.eldrix.rsdb/conn *system*)
        patient *patient*
        date (LocalDate/of 2020 1 6)
        base {:t_result_type/result_entity_name   "ResultMriBrain"
              :t_result_mri_brain/date            date
              :t_result_mri_brain/report          "Multiple T2 hyperintensities"
              :t_result_mri_brain/with_gadolinium true
              :user_fk                            1
              :patient_fk                         (:t_patient/id patient)}
        result (results/save-result! conn (merge base {:t_result_mri_brain/total_t2_hyperintense       "2"
                                                       :t_result_mri_brain/with_gadolinium             false
                                                       :t_result_mri_brain/multiple_sclerosis_summary  "TYPICAL"
                                                       :t_result_mri_brain/total_gad_enhancing_lesions nil}))
        result-id (:t_result_mri_brain/id result)]
    ;; invalid change specification:
    (is (thrown? ExceptionInfo (results/save-result! conn (merge base {:t_result_mri_brain/total_gad_enhancing_lesions    "~2"
                                                                       :t_result_mri_brain/change_t2_hyperintense         "2"
                                                                       :t_result_mri_brain/compare_to_result_mri_brain_fk result-id
                                                                       :t_result_mri_brain/multiple_sclerosis_summary     "TYPICAL"}))))
    ;; missing reference to comparison scan:
    (is (thrown? ExceptionInfo (results/save-result! conn (merge base {:t_result_mri_brain/total_gad_enhancing_lesions "~2"
                                                                       :t_result_mri_brain/change_t2_hyperintense      "2"
                                                                       :t_result_mri_brain/multiple_sclerosis_summary  "TYPICAL"}))))
    ;; including both total T2 intensities AND change
    (is (thrown? ExceptionInfo (results/save-result! conn (merge base {:t_result_mri_brain/total_gad_enhancing_lesions "~2"
                                                                       :t_result_mri_brain/total_t2_hyperintense       "2"
                                                                       :t_result_mri_brain/change_t2_hyperintense      "+2"
                                                                       :t_result_mri_brain/multiple_sclerosis_summary  "TYPICAL"}))))))

(deftest save-thyroid-function
  (let [conn (:com.eldrix.rsdb/conn *system*)
        patient *patient*
        date (LocalDate/of 2020 1 7)
        result (results/save-result! conn {:t_result_type/result_entity_name  "ResultThyroidFunction"
                                           :t_result_thyroid_function/date    date
                                           :t_result_thyroid_function/free_t4 15.4
                                           :patient_fk                        (:t_patient/id patient)
                                           :user_fk                           1})
        fetched (fetch-result conn (:t_patient/patient_identifier patient) date)]
    (is (= "ResultThyroidFunction" (:t_result_type/result_entity_name fetched)))
    (is (.isEqual date (:t_result_thyroid_function/date fetched)))))


(defn with-system [f]
  (pc4/load-namespaces :dev [:pathom/env])
  (binding [*system* (pc4/init :dev [:pathom/env])]
    (f)
    (pc4/halt! *system*)))

(defn with-patient
  [f]
  (binding [*patient* (projects/register-legacy-pseudonymous-patient ;; this is an idempotent operation, by design
                        (:com.eldrix.rsdb/conn *system*)
                        {:salt       (get-in *system* [:pathom/env :com.eldrix.rsdb/config :legacy-global-pseudonym-salt])
                         :user-id    1
                         :project-id 10
                         :nhs-number "2222222222"
                         :sex        :FEMALE
                         :date-birth (LocalDate/of 2000 1 1)})]
    (results/delete-all-results!! (:com.eldrix.rsdb/conn *system*) (:t_patient/patient_identifier *patient*))
    (f)
    (results/delete-all-results!! (:com.eldrix.rsdb/conn *system*) (:t_patient/patient_identifier *patient*))))

(use-fixtures :once with-system with-patient)

(defn make-mri-results
  "Creates a lazy sequence of base MRI results for the patient specified."
  [patient-pk & {:keys [start-date]}]
  (let [start-date' (or start-date (LocalDate/of 2010 1 1))
        date-seq (map #(.plusMonths start-date' %) (range))
        result-seq (partition 2 (interleave (range) date-seq))]
    (map (fn [[id date]]
           {:t_result_mri_brain/patient_fk patient-pk
            :t_result_mri_brain/id         id
            :t_result_mri_brain/date       date}) result-seq)))

(defn make-t2-test-data
  ([data] (make-t2-test-data (rand-int 10000) data))
  ([patient-pk data]
   (->> (map merge (make-mri-results patient-pk) data)
        (map #(assoc % :expected-range (results/lesion-range (results/parse-count-lesions (:expected %))))))))

(defn test-t2 [data]
  (->> (make-t2-test-data data)
       results/all-t2-counts
       (map #(let [[expected-lower expected-upper] (:expected-range %)]
               (and (= expected-lower (:t_result_mri_brain/t2_range_lower %))
                    (= expected-upper (:t_result_mri_brain/t2_range_upper %)))))))

(def t2-test-data
  [[{:t_result_mri_brain/total_t2_hyperintense "0" :expected 0}
    {:t_result_mri_brain/change_t2_hyperintense         "+2"
     :t_result_mri_brain/compare_to_result_mri_brain_fk 10
     :expected                                          2}
    {:t_result_mri_brain/total_t2_hyperintense "~6"
     :expected                                 "~6"}
    {:t_result_mri_brain/change_t2_hyperintense "+4"
     :expected                                  "~10"}]
   [{:t_result_mri_brain/total_t2_hyperintense "4" :expected 4}
    {:t_result_mri_brain/change_t2_hyperintense "+2" :expected 6}
    {:t_result_mri_brain/change_t2_hyperintense "+2" :expected 8}]])

(deftest t2-counts
  (is (every? true? (mapcat test-t2 t2-test-data)))
  (let [change-result (first (results/all-t2-counts (make-t2-test-data {:t_result_mri_brain/change_t2_hyperintense "+2"})))]
    (is (= 2 (:t_result_mri_brain/calc_change_t2 change-result)))))

(comment
  (t2-counts)
  (parsing-lesions)
  (take 5 (make-mri-results 1))

  (def *system* (pc4/init :dev [:pathom/env]))
  (clojure.test/run-tests)
  (save-full-blood-count))