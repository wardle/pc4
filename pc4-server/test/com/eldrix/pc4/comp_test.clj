(ns com.eldrix.pc4.comp-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :as pp]
            [com.eldrix.pc4.commons.comp :as comp])
  (:import (java.time LocalDate)))


(deftest test-simple
  (let [query [{[:t_project/id 1] [:t_project/id :t_project/title :t_project/date_from]}]
        result {[:t_project/id 1] #:t_project{:id 1, :date_from (LocalDate/of 2010 1 1) :title "Title"}}
        {db :db} (comp/target-results {} {:query query} result)
        pull-result (comp/pull-results db {:query query})]
    (is (= (vals result) pull-result))))

(deftest test-multiple-queries
  (let [query [{[:t_project/id 1] [:t_project/id :t_project/title {:t_project/users [:t_user/id :t_user/username]}]}
               {[:t_user/id 1] [:t_user/id :t_user/first_names :t_user/last_name]}]
        result {[:t_project/id 1] #:t_project{:id    1, :title "Title",
                                              :users [#:t_user{:id 1, :username "system"}]},
                [:t_user/id 1]    #:t_user{:id 1, :first_names "System", :last_name "Administrator"}}
        {db :db} (comp/target-results {} {:query query} result)
        [project user] (comp/pull-results db {:query query})]
    (is (= (get result [:t_project/id 1]) project))
    (is (= (get result [:t_user/id 1]) user))
    (is (= (get-in db [:t_user/id 1])
           #:t_user{:id 1 :username "system" :first_names "System" :last_name "Administrator"})
        "The user entity should have been normalised into a single place in entity db")))

(deftest test-non-ident
  (let [query [{[:t_project/id 1] [:t_project/id :t_project/title]}
               {:reasons-for-stopping
                [:reason_for_stopping/id :reason_for_stopping/name]}]
        result {[:t_project/id 1]     #:t_project{:id 1, :title "Title"},
                :reasons-for-stopping [#:reason_for_stopping{:name "PATIENT_CHOICE_CONVENIENCE",
                                                             :id   :PATIENT_CHOICE_CONVENIENCE}
                                       #:reason_for_stopping{:name "NOT_APPLICABLE",
                                                             :id   :NOT_APPLICABLE}]}
        {db :db} (comp/target-results {} {:query query} result)
        [project reasons] (comp/pull-results db {:query query})]
    (is (= project (get result [:t_project/id 1])))
    (is (= reasons (:reasons-for-stopping result)))))

(deftest test-target)
(let [query [{[:t_project/id 1] [:t_project/id :t_project/title]}
             {[:t_user/id 1] [:t_user/id :t_user/first_names :t_user/last_name]}
             :com.eldrix.rsdb/all-medication-reasons-for-stopping]
      targets {:com.eldrix.rsdb/all-medication-reasons-for-stopping [:lookups :all-medication-reasons-for-stopping]}
      result {[:t_project/id 1] #:t_project{:id 1, :title "Legacy Multiple Sclerosis Database"},
              [:t_user/id 1] #:t_user{:id 1, :first_names "System", :last_name "Administrator"},
              :com.eldrix.rsdb/all-medication-reasons-for-stopping [#:t_medication_reason_for_stopping{:name "PATIENT_CHOICE_CONVENIENCE",
                                                                                                       :id :PATIENT_CHOICE_CONVENIENCE}
                                                                    #:t_medication_reason_for_stopping{:name "NOT_APPLICABLE",
                                                                                                       :id :NOT_APPLICABLE}]}
      {db :db} (comp/target-results {} {:query query :targets targets} result)
      [project user reasons] (comp/pull-results db {:query query :targets targets})]
  (is (= project (get result [:t_project/id 1])))
  (is (= user (get result [:t_user/id 1])))
  (is (= reasons (get result :com.eldrix.rsdb/all-medication-reasons-for-stopping)))
  (is (= reasons (get-in db [:lookups :all-medication-reasons-for-stopping]))
      "Reasons for stopping should have been stored as-is in :lookups in database"))

(def example-component6
  {:query   (fn [params]
              [{[:t_project/id (get-in params [:path :project-id])]
                [{(list :t_project/users {:group-by :user :active true})
                  [:t_user/id :t_user/username :t_user/has_photo :t_user/email :t_user/full_name :t_user/active?
                   :t_user/first_names :t_user/last_name :t_user/job_title
                   {:t_user/roles [:t_project_user/id
                                   :t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?]}]}]}])
   :targets (constantly [:current-project :team])})

