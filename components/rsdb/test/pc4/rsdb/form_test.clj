(ns pc4.rsdb.form-test
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [pc4.rsdb.nform.impl.form :as form]
            [pc4.rsdb.nform.api]))

(deftest hydration-dehydration
  (let [forms (gen/sample (form/gen-form#) 10000)]
    (doseq [form forms]
      (let [form' (-> form form/dehydrate form/hydrate)]
        (is (set/subset? (set form) (set form')) "Generated form data should not be lost during dehydration/rehydration")
        (is (s/valid? ::form/core form) "Generated forms should be valid")
        (is (s/valid? ::form/core form') "Hydrated forms should be valid")
        (is (s/valid? (form/spec form) form) "Generated forms should be valid")
        (is (s/valid? (form/spec form') form') "Generated forms should be valid")))))

(deftest summarise
  (let [forms (gen/sample (form/gen-form#) 10000)]
    (doseq [form forms]
      (let [summary (form/summary form)]
        (is (not (str/blank? summary))
            (str "Form summaries should never be blank strings:" (:form_type form)))))))

(comment
  (gen/generate (form/gen-form#))
  (run-tests))