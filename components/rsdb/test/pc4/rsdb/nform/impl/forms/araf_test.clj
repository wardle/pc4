(ns pc4.rsdb.nform.impl.forms.araf-test
  (:require
    [clojure.test :refer :all]
    [clojure.spec.gen.alpha :as gen]
    [clojure.set :as set]
    [pc4.rsdb.nform.impl.forms.araf :as araf]
    [pc4.rsdb.nform.impl.form :as form])
  (:import (java.time LocalDate)))

(defn- parse-date
  "Parses a date string into a LocalDateTime instance for testing."
  [date-str]
  (LocalDate/.atStartOfDay (LocalDate/parse date-str)))

(defn- build-form
  "Generates a single, valid form map based on a DSL form map."
  [{:keys [date] :as form-data}]
  (gen/generate
    (form/gen-form {:using (-> form-data
                               (dissoc :date)
                               (assoc :date_time (parse-date date)))})))

(defn- build-forms
  "Generates a sequence of form maps from a DSL :forms vector."
  [forms]
  (map build-form forms))

(def scenarios
  "A collection of test scenarios defined using the DSL."
  [{:description "Scenario 1: No forms at all"
    :forms       []
    :checks      [{:on-date  "2023-01-11"
                   :expected {:at-risk true
                              :status  :at-risk
                              :tasks   #{:status :treatment :countersignature :risks :acknowledgement}}}]}

   {:description "Scenario 2: Patient is not at risk (permanently excluded)"
    :forms       [{:form_type :araf-val-f-s1-status/v2_0, :date "2023-01-10", :status :permanent}]
    :checks      [{:on-date  "2023-01-11"
                   :expected {:at-risk false
                              :status  :permanent
                              :tasks   #{:acknowledgement}}}]}

   {:description "Scenario 3: Patient is at-risk, but has not completed any other forms"
    :forms       [{:form_type :araf-val-f-s1-status/v2_0, :date "2023-01-10", :status :at-risk}]
    :checks      [{:on-date  "2023-01-11"
                   :expected {:at-risk      true
                              :status       :at-risk
                              :treatment    false
                              :acknowledged {:status :pending}
                              :tasks        #{:treatment :countersignature :risks :acknowledgement}}}]}

   {:description "Scenario 4: At-risk patient, treatment confirmed, other tasks pending"
    :forms       [{:form_type :araf-val-f-s1-status/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type :araf-val-f-s2-treatment-decision/v2_0, :date "2023-01-11", :confirm true}]
    :checks      [{:on-date  "2023-01-12"
                   :expected {:at-risk   true
                              :treatment true
                              :tasks     #{:countersignature :risks :acknowledgement}}}]}

   {:description "Scenario 5: At-risk patient, all risks NOT explained"
    :forms       [{:form_type :araf-val-f-s1-status/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type :araf-val-f-s3-risks/v2_0, :date "2023-01-11",
                   :all false, :eligible true, :discussed false, :regular_review true,
                   :serious_harm   true, :conditions true, :pregnancy_test false,
                   :contraception  true, :referral true, :contact true,
                   :risks_stopping true}]
    :checks      [{:on-date  "2023-01-12"
                   :expected {:at-risk true
                              :tasks   #{:treatment :countersignature :risks :acknowledgement}}}]}

   {:description "Scenario 6: At-risk patient, all risks explained"
    :forms       [{:form_type :araf-val-f-s1-status/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type      :araf-val-f-s3-risks/v2_0, :date "2023-01-11",
                   :all            true, :eligible true, :discussed true, :regular_review true,
                   :serious_harm   true, :conditions true, :pregnancy_test true,
                   :contraception  true, :referral true, :contact true,
                   :risks_stopping true}]
    :checks      [{:on-date  "2023-01-12"
                   :expected {:at-risk true
                              :tasks   #{:treatment :countersignature :acknowledgement}}}]}

   {:description "Scenario 7: At-risk patient, fully signed off, acknowledgement pending"
    :forms       [{:form_type :araf-val-f-s1-status/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type :araf-val-f-s2-treatment-decision/v2_0, :date "2023-01-11", :confirm true}
                  {:form_type :araf-val-f-s2-countersignature/v2_0, :date "2023-01-12", :confirm true, :eligible true}
                  {:form_type      :araf-val-f-s3-risks/v2_0, :date "2023-01-13",
                   :all            true, :eligible true, :discussed true, :regular_review true,
                   :serious_harm   true, :conditions true, :pregnancy_test true,
                   :contraception  true, :referral true, :contact true,
                   :risks_stopping true}]
    :checks      [{:on-date  "2023-01-14"
                   :expected {:at-risk     true
                              :treatment   true
                              :countersign true
                              :tasks       #{:acknowledgement}}}]}

   {:description "Scenario 8: Patient has declined acknowledgement"
    :forms       [{:form_type :araf-val-f-s4-acknowledgement/v2_0, :date "2023-01-10", :acknowledged false}]
    :checks      [{:on-date  "2023-01-11"
                   :expected {:acknowledged {:status :declined}
                              :tasks        #{:status :acknowledgement}}}]}

   {:description "Scenario 9: Acknowledgement is active"
    :forms       [{:form_type :araf-val-f-s4-acknowledgement/v2_0, :date "2023-01-15", :acknowledged true}]
    :checks      [{:on-date  "2023-02-01"
                   :expected {:acknowledged {:status :active}
                              :tasks        #{:status :treatment :countersignature :risks}}}]}

   {:description "Scenario 10: Acknowledgement is expiring soon"
    :forms       [{:form_type :araf-val-f-s4-acknowledgement/v2_0, :date "2022-03-01", :acknowledged true}]
    :checks      [{:on-date  "2022-05-01"
                   :expected {:acknowledged {:acknowledged true :status :active}
                              :tasks        #{:status :treatment :countersignature :risks}}}
                  {:on-date  "2023-02-15"
                   :expected {:acknowledged {:acknowledged true :status :expiring}
                              :tasks        #{:status :treatment :countersignature :risks}}}
                  {:on-date  "2023-03-02"
                   :expected {:acknowledged {:acknowledged false :status :expired}
                              :tasks        #{:status :treatment :countersignature :risks :acknowledgement}}}]}

   {:description "Scenario 11: Acknowledgement has expired"
    :forms       [{:form_type :araf-val-f-s4-acknowledgement/v2_0, :date "2022-01-01", :acknowledged true}]
    :checks      [{:on-date  "2023-02-01"
                   :expected {:at-risk      true
                              :excluded     false
                              :treatment    false
                              :countersign  false
                              :acknowledged {:acknowledged false, :status       :expired}
                              :tasks        #{:status :acknowledgement}}}]}])

(defn- submap?
  "Is `m1` a submap of `m2`?"
  [m1 m2]
  (cond
    (and (map? m1) (map? m2))
    (every? (fn [[k v]] (and (contains? m2 k) (submap? v (get m2 k)))) m1)
    (and (set? m1) (set? m2))
    (set/subset? m1 m2)
    :else
    (= m1 m2)))

(deftest test-araf-status
  (doseq [{:keys [description forms checks]} scenarios]
    (testing description
      (let [test-forms (build-forms forms)]
        (doseq [{:keys [on-date expected]} checks]
          (let [actual (araf/status test-forms {:now (parse-date on-date)})]
            (is (submap? expected actual)
                (str "check failed for date " on-date))))))))

(deftest test-generated
  (dotimes [_ 500]                                          ;; generate lots of forms and check status
    (let [forms (gen/sample (araf/gen-araf-form-dt {:encounter_fk 1 :patient_fk 1}))]
      (araf/status forms))))

(comment
  (run-tests)
  (gen/generate (araf/gen-araf-form-dt))
  (def scenario (nth scenarios 9))
  (def date (get-in scenario [:checks 0 :on-date]))
  (def expected (get-in scenario [:checks 0 :expected]))
  expected
  (def forms (build-forms (:forms scenario)))
  (araf/status forms)
  (submap? expected (araf/status forms)))