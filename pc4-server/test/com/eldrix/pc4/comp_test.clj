(ns com.eldrix.pc4.comp-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :as pp]
            [pc4.comp :as comp])
  (:import (java.time LocalDate)))


(deftest test-simple
  (let [tx [{[:t_project/id 1] [:t_project/id :t_project/title :t_project/date_from]}]
        response {[:t_project/id 1] #:t_project{:id 1, :date_from (LocalDate/of 2010 1 1) :title "Title"}}
        {db :db} (comp/target-results {} {:tx tx} response)
        pull-result (comp/pull-results db {:tx tx})]
    (is (= (vals response) pull-result))))

(deftest test-multiple-queries
  (let [tx [{[:t_project/id 1] [:t_project/id :t_project/title {:t_project/users [:t_user/id :t_user/username]}]}
            {[:t_user/id 1] [:t_user/id :t_user/first_names :t_user/last_name]}]
        response {[:t_project/id 1] #:t_project{:id    1, :title "Title",
                                                :users [#:t_user{:id 1, :username "system"}]},
                  [:t_user/id 1]    #:t_user{:id 1, :first_names "System", :last_name "Administrator"}}
        {db :db} (comp/target-results {} {:tx tx} response)
        [project user] (comp/pull-results db {:tx tx})]
    (is (= (get response [:t_project/id 1]) project))
    (is (= (get response [:t_user/id 1]) user))
    (is (= (get-in db [:t_user/id 1])
           #:t_user{:id 1 :username "system" :first_names "System" :last_name "Administrator"})
        "The user entity should have been normalised into a single place in entity db")))

(deftest test-non-ident
  (let [tx [{[:t_project/id 1] [:t_project/id :t_project/title]}
            {:reasons-for-stopping
             [:reason_for_stopping/id :reason_for_stopping/name]}]
        result {[:t_project/id 1]     #:t_project{:id 1, :title "Title"},
                :reasons-for-stopping [#:reason_for_stopping{:name "PATIENT_CHOICE_CONVENIENCE",
                                                             :id   :PATIENT_CHOICE_CONVENIENCE}
                                       #:reason_for_stopping{:name "NOT_APPLICABLE",
                                                             :id   :NOT_APPLICABLE}]}
        {db :db} (comp/target-results {} {:tx tx} result)
        [project reasons] (comp/pull-results db {:tx tx})]
    (is (= project (get result [:t_project/id 1])))
    (is (= reasons (:reasons-for-stopping result)))))

(deftest test-multiple-results-as-list
  (let [tx [{[:t_patient/patient_identifier 14032]
             [:t_patient/id
              :t_patient/patient_identifier
              :t_patient/nhs_number]}
            #:com.eldrix.rsdb{:all-ms-diagnoses [:t_ms_diagnosis/name :t_ms_diagnosis/id]}]
        response {[:t_patient/patient_identifier 14032] #:t_patient{:id 17490, :patient_identifier 14032, :nhs_number "1231231234"},
                  :com.eldrix.rsdb/all-ms-diagnoses     '(#:t_ms_diagnosis{:name "Poser cd", :id 1}
                                                           #:t_ms_diagnosis{:name "Poser lab", :id 2})}
        {db :db} (comp/target-results {} {:tx tx} response)
        {db2 :db} (comp/target-results db {:tx tx} response)]
    (is (= [#:t_patient{:id 17490, :patient_identifier 14032, :nhs_number "1231231234"}
            '(#:t_ms_diagnosis{:name "Poser cd", :id 1} #:t_ms_diagnosis{:name "Poser lab", :id 2})]
           (comp/pull-results db {:tx tx})))
    (is (= db db2)
        "Targeting should be idempotent")))


(deftest test-target
  (let [tx [{[:t_project/id 1] [:t_project/id :t_project/title]}
            {[:t_user/id 1] [:t_user/id :t_user/first_names :t_user/last_name]}
            :com.eldrix.rsdb/all-medication-reasons-for-stopping]
        targets {:com.eldrix.rsdb/all-medication-reasons-for-stopping [:lookups :all-medication-reasons-for-stopping]}
        response {[:t_project/id 1]                                    #:t_project{:id 1, :title "Legacy Multiple Sclerosis Database"},
                  [:t_user/id 1]                                       #:t_user{:id 1, :first_names "System", :last_name "Administrator"},
                  :com.eldrix.rsdb/all-medication-reasons-for-stopping [#:t_medication_reason_for_stopping{:name "PATIENT_CHOICE_CONVENIENCE",
                                                                                                           :id   :PATIENT_CHOICE_CONVENIENCE}
                                                                        #:t_medication_reason_for_stopping{:name "NOT_APPLICABLE",
                                                                                                           :id   :NOT_APPLICABLE}]}
        {db :db} (comp/target-results {} {:tx tx :targets targets} response)
        [project user reasons] (comp/pull-results db {:tx tx :targets targets})]
    (is (= project (get response [:t_project/id 1])))
    (is (= user (get response [:t_user/id 1])))
    (is (= reasons (get response :com.eldrix.rsdb/all-medication-reasons-for-stopping)))
    (is (= reasons (get-in db [:lookups :all-medication-reasons-for-stopping]))
        "Reasons for stopping should have been stored as-is in :lookups in database")))

(def example-component6
  {:tx      (fn [params]
              [{[:t_project/id (get-in params [:path :project-id])]
                [{(list :t_project/users {:group-by :user :active true})
                  [:t_user/id :t_user/username :t_user/has_photo :t_user/email :t_user/full_name :t_user/active?
                   :t_user/first_names :t_user/last_name :t_user/job_title
                   {:t_user/roles [:t_project_user/id
                                   :t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?]}]}]}])
   :targets (constantly [:current-project :team])})

