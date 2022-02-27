(ns com.eldrix.pc4.server.rsdb.results-test
  (:require [clojure.test :as test :refer [deftest is use-fixtures]]
            [com.eldrix.pc4.server.system :as pc4]
            [com.eldrix.pc4.server.rsdb.patients :as patients]
            [com.eldrix.pc4.server.rsdb.projects :as projects]
            [com.eldrix.pc4.server.rsdb.results :as results :refer [parse-count-lesions parse-change-lesions]]
            [clojure.spec.test.alpha :as stest])
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
  (is (= {:change 2} (parse-change-lesions "+2")))
  (is (= {:change -2} (parse-change-lesions "-2")))
  (is (= {:approximate-count 2} (parse-count-lesions "~2")))
  (is (= {:more-than 2} (parse-count-lesions ">2")))
  (is (= {:approximate-range {:count 10 :plus-minus 2}} (parse-count-lesions "10+/-2")))
  (is (= {:range {:from 5 :to 10}} (parse-count-lesions "5-10")))
  (is (nil? (parse-change-lesions "2")))
  (is (nil? (parse-count-lesions " 2")))
  (is (nil? (parse-count-lesions "2-")))
  (is (nil? (parse-count-lesions "hello"))))

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


(defn with-system [f]
  (binding [*system* (pc4/init :dev [:pathom/env])]
    (f)
    (pc4/halt! *system*)))

(defn with-patient [f]
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

(comment
  (clojure.test/run-tests)
  (save-full-blood-count))