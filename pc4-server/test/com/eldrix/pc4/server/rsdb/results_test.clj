(ns com.eldrix.pc4.server.rsdb.results-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.eldrix.pc4.server.system :as pc4]
            [com.eldrix.pc4.server.rsdb.patients :as patients]
            [com.eldrix.pc4.server.rsdb.projects :as projects]
            [com.eldrix.pc4.server.rsdb.results :as results :refer [parse-count-lesions parse-change-lesions]])
  (:import (java.time LocalDate)))

(def system (atom nil))
(def patient (atom nil))

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
  (is (= (results/normalize-mri-brain {:t_result_mri_brain/id 5
                                       :t_result_mri_brain/with_gadolinium true
                                       :t_annotation_mri_brain_multiple_sclerosis_new/id 24
                                       :t_annotation_mri_brain_multiple_sclerosis_new/change_t2_hyperintense "+2"})
         {:t_result_mri_brain/id 5
          :t_result_mri_brain/with_gadolinium true
          :t_result_mri_brain/annotation_mri_brain_multiple_sclerosis_new_id 24
          :t_result_mri_brain/change_t2_hyperintense "+2"})))

(deftest save-mri-brain
  (let [conn (:com.eldrix.rsdb/conn @system)]
    (let [result (results/save-result! conn
                                       {:t_result_type/id                               9
                                        :t_result_mri_brain/date                        (LocalDate/of 2020 1 1)
                                        :t_result_mri_brain/report                      "Scan typical of multiple sclerosis."
                                        :t_result_mri_brain/with_gadolinium             true
                                        :t_result_mri_brain/total_gad_enhancing_lesions "~5"
                                        :t_result_mri_brain/total_t2_hyperintense       "2"
                                        :t_result_mri_brain/multiple_sclerosis_summary  "TYPICAL"
                                        :user_fk                                        1
                                        :patient_fk                                     (:t_patient/id @patient)})]
      (results/delete-result! conn (assoc result :t_result_type/id 9)))))

(deftest save-full-blood-count
  (let [conn (:com.eldrix.rsdb/conn @system)
        patient @patient]
    (results/save-result! conn {:t_result_type/id 24
                                :t_result_full_blood_count/date (LocalDate/now)
                                :patient_fk (:t_patient/id patient)
                                :user_fk 1})))

(defn with-system [f]
  (reset! system (pc4/init :dev [:pathom/env]))
  (reset! patient (projects/register-legacy-pseudonymous-patient
                    (:com.eldrix.rsdb/conn @system)
                    {:salt       (get-in @system [:pathom/env :com.eldrix.rsdb/config :legacy-global-pseudonym-salt])
                     :user-id    1
                     :project-id 10
                     :nhs-number "2222222222"
                     :sex        :FEMALE
                     :date-birth (LocalDate/of 2000 1 1)}))
  (f)
  (pc4/halt! @system))

(use-fixtures :once with-system)


(comment
  (clojure.test/run-tests))