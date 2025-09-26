(ns pc4.rsdb.nform.impl.forms.araf-test
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :refer :all]
    [clojure.spec.gen.alpha :as gen]
    [clojure.set :as set]
    [pc4.rsdb.nform.impl.forms.araf :as araf]
    [pc4.rsdb.nform.impl.form :as form])
  (:import (java.time LocalDate LocalDateTime)))

(defn- parse-date
  "Parses a date string into a LocalDateTime instance for testing."
  [date-str]
  (LocalDate/.atStartOfDay (LocalDate/parse date-str)))

(defn- build-form
  "Generates a single, valid form map based on a DSL form map."
  [{:keys [date date_time] :as form-data}]
  (gen/generate
    (form/gen-form {:using (-> form-data
                               (dissoc :date)
                               (assoc :date_time (or date_time (parse-date date))))})))

(defn- build-forms
  "Generates a sequence of form maps from a DSL :forms vector."
  [forms]
  (map build-form forms))

(def scenarios
  "A collection of test scenarios defined using the DSL."
  [{:description "Scenario 1: No forms at all"
    :forms       []
    :checks      [{:on-date  "2023-01-11"
                   :expected
                   {:excluded nil :completed false :expiry nil}}]}

   {:description "Scenario 2: Patient is not at risk (permanently excluded)"
    :forms       [{:form_type :araf-val-f-s1-evaluation/v2_0, :date_time (LocalDateTime/of 2023 1 10 10 0), :status :permanent}]
    :checks      [{:on-date  "2023-01-11"
                   :expected {:excluded :permanent :completed true :expiry nil}}]}

   {:description "Scenario 3: Patient is at-risk, but has not completed any other forms"
    :forms       [{:form_type :araf-val-f-s1-evaluation/v2_0, :date "2023-01-10", :status :at-risk}]
    :checks      [{:on-date  "2023-01-11"
                   :expected {:excluded nil :completed false :expiry nil}}]}

   {:description "Scenario 4: At-risk patient, treatment confirmed, other tasks pending"
    :forms       [{:form_type :araf-val-f-s1-evaluation/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type :araf-val-f-s2-treatment-decision/v2_0, :date "2023-01-11", :confirm true}]
    :checks      [{:on-date  "2023-01-12"
                   :expected {:excluded nil :completed false :expiry nil}}]}

   {:description "Scenario 5: At-risk patient, all risks NOT explained"
    :forms       [{:form_type :araf-val-f-s1-evaluation/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type      :araf-val-f-s3-risks/v2_0, :date "2023-01-11",
                   :all            false, :eligible true, :discussed false, :regular_review true,
                   :serious_harm   true, :conditions true, :pregnancy_test false,
                   :contraception  true, :referral true, :contact true,
                   :risks_stopping true}]
    :checks      [{:on-date  "2023-01-12"
                   :expected {:excluded nil :completed false :expiry nil}}]}

   {:description "Scenario 6: At-risk patient, all risks explained"
    :forms       [{:form_type :araf-val-f-s1-evaluation/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type      :araf-val-f-s3-risks/v2_0, :date "2023-01-11",
                   :all            true, :eligible true, :discussed true, :regular_review true,
                   :serious_harm   true, :conditions true, :pregnancy_test true,
                   :contraception  true, :referral true, :contact true,
                   :risks_stopping true}]
    :checks      [{:on-date  "2023-01-12"
                   :expected {:excluded nil :completed false :expiry nil}}]}

   {:description "Scenario 7: At-risk patient, fully signed off, acknowledgement pending"
    :forms       [{:form_type :araf-val-f-s1-evaluation/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type :araf-val-f-s2-treatment-decision/v2_0, :date "2023-01-11", :confirm true}
                  {:form_type :araf-val-f-s2-countersignature/v2_0, :date "2023-01-12", :confirm true, :eligible true}
                  {:form_type      :araf-val-f-s3-risks/v2_0, :date "2023-01-13",
                   :all            true, :eligible true, :discussed true, :regular_review true,
                   :serious_harm   true, :conditions true, :pregnancy_test true,
                   :contraception  true, :referral true, :contact true,
                   :risks_stopping true}]
    :checks      [{:on-date  "2023-01-14"
                   :expected {:excluded nil :completed false :expiry nil}}]}

   {:description "Scenario 8: Patient has declined acknowledgement"
    :forms       [{:form_type :araf-val-f-s4-acknowledgement/v2_0, :date "2023-01-10", :acknowledged false}]
    :checks      [{:on-date  "2023-01-11"
                   :expected {:excluded nil :completed false :expiry nil}}]}

   {:description "Scenario 9: Acknowledgement active, due to expire, and expired"
    :forms       [{:form_type :araf-val-f-s1-evaluation/v2_0, :date "2023-01-10", :status :at-risk}
                  {:form_type :araf-val-f-s2-treatment-decision/v2_0, :date "2023-01-11", :confirm true}
                  {:form_type :araf-val-f-s2-countersignature/v2_0, :date "2023-01-12", :confirm true, :eligible true}
                  {:form_type      :araf-val-f-s3-risks/v2_0, :date "2023-01-13",
                   :all            true, :eligible true, :discussed true, :regular_review true,
                   :serious_harm   true, :conditions true, :pregnancy_test true,
                   :contraception  true, :referral true, :contact true,
                   :risks_stopping true}
                  {:form_type :araf-val-f-s4-acknowledgement/v2_0, :date_time (LocalDateTime/of 2023 1 15 10 0), :acknowledged true}]
    :checks      [{:on-date "2022-01-01"
                   :expected {:excluded nil :completed false :expiry nil}}
                  {:on-date  "2023-02-01"
                   :expected {:excluded nil :completed true :expiry (LocalDateTime/of 2024 1 15 10 0)}}]}])

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

(deftest test-araf-outcome
  (doseq [{:keys [description forms checks]} scenarios]
    (testing description
      (let [test-forms (build-forms forms)]
        (doseq [{:keys [on-date expected]} checks]
          (let [actual (araf/outcome :valproate-f test-forms {:now (parse-date on-date)})]
            (is (s/valid? ::araf/outcome actual)
                (s/explain-str ::araf/outcome actual))
            (is (submap? expected actual)
                (str "check failed for date " on-date))))))))

(deftest test-generated
  (dotimes [_ 500]                                          ;; generate lots of forms and check status
    (let [forms (gen/sample (araf/gen-araf-form-dt {:encounter_fk 1 :patient_fk 1}))
          outcome (araf/outcome :valproate-f forms {})]
      (is (s/valid? ::araf/outcome outcome)
          (s/explain-str ::araf/outcome outcome)))))

(comment
  (run-tests)
  (gen/generate (araf/gen-araf-form-dt))
  (def scenario (nth scenarios 9))
  (def date (get-in scenario [:checks 0 :on-date]))
  (def expected (get-in scenario [:checks 0 :expected]))
  expected
  (def forms (build-forms (:forms scenario)))
  (araf/status forms)
  (submap? expected (araf/status :valproate-f forms)))