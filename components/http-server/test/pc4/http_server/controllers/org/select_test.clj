(ns pc4.http-server.controllers.org.select-test
  (:require [clojure.test :refer [deftest is testing]]
            [pc4.http-server.controllers.org.select-org :as org-select]))

(deftest test-make-config
  (testing "basic configuration with required parameters"
    (let [config (org-select/make-config {:id "test-org" :roles "RO76"})]
      (is (= "test-org" (:id config)))
      (is (= "test-org" (:name config)))
      (is (= "RO76" (:roles config)))
      (is (= #{:name} (:display-fields config)))
      (is (= true (:only-active? config)))
      (is (= 100 (:limit config)))
      (is (= "= CHOOSE =" (:placeholder config)))))
  
  (testing "configuration with custom display fields"
    (let [config (org-select/make-config {:id "test" 
                                          :roles ["RO76" "RO177"]
                                          :display-fields #{:name :town :postcode}
                                          :only-active? false
                                          :limit 50})]
      (is (= ["RO76" "RO177"] (:roles config)))
      (is (= #{:name :town :postcode} (:display-fields config)))
      (is (= false (:only-active? config)))
      (is (= 50 (:limit config)))))
  
  (testing "configuration validation"
    (is (thrown? Exception 
                 (org-select/make-config {:roles "RO76"}))) ; missing id/name
    (is (thrown? Exception 
                 (org-select/make-config {:id "test"})))))   ; missing roles

(deftest test-format-org-display
  (testing "format active organisation with name only"
    (let [org {:orgId {:extension "7A4"}
               :name "Test Hospital"
               :active true
               :location {:town "Cardiff" :postcode "CF14 4XW"}}
          result (org-select/format-org-display org #{:name})]
      (is (= "7A4" (:org-code result)))
      (is (= "Test Hospital" (:org-name result)))
      (is (= nil (:org-address result)))
      (is (= true (:org-active result)))))
  
  (testing "format inactive organisation with address"
    (let [org {:orgId {:extension "OLD1"}
               :name "Old Hospital"
               :active false
               :location {:town "Cardiff" :postcode "CF14 4XW"}}
          result (org-select/format-org-display org #{:name :town :postcode})]
      (is (= "OLD1" (:org-code result)))
      (is (= "Old Hospital (inactive)" (:org-name result)))
      (is (= "Cardiff, CF14 4XW" (:org-address result)))
      (is (= false (:org-active result)))))
  
  (testing "format with complex address"
    (let [org {:orgId {:extension "ABC1"}
               :name "Complex Hospital"
               :active true
               :location {:address1 "123 Main St"
                         :address2 "Suite 100"
                         :town "Cardiff"
                         :county "South Glamorgan"
                         :postcode "CF14 4XW"}}
          result (org-select/format-org-display org #{:name :address1 :address2 :town :postcode})]
      (is (= "123 Main St, Suite 100, Cardiff, CF14 4XW" (:org-address result)))))
  
  (testing "handle nil organisation"
    (is (nil? (org-select/format-org-display nil #{:name})))))

(deftest test-make-org
  (testing "convert ODS organisation to internal format"
    (let [ods-org {:orgId {:extension "7A4"}
                   :name "Test Hospital"
                   :active true
                   :location {:town "Cardiff"}}
          result (org-select/make-org ods-org)]
      (is (= "7A4" (:org-code result)))
      (is (= "Test Hospital" (:org-name result)))
      (is (= true (:org-active result)))
      (is (= {:town "Cardiff"} (:org-location result)))))
  
  (testing "handle nil organisation"
    (is (nil? (org-select/make-org nil)))))

(deftest test-add-org-action
  (testing "add action to organisation"
    (let [org {:org-code "7A4" :org-name "Test"}
          result (org-select/add-org-action org :select)]
      (is (= "7A4" (:org-code result)))
      (is (= "Test" (:org-name result)))
      (is (= "[:select {:org-code \"7A4\", :org-name \"Test\"}]" (:action result))))))

(deftest test-minimum-chars
  (testing "return string when meets minimum length"
    (is (= "hello" (org-select/minimum-chars "hello" 3)))
    (is (= "abc" (org-select/minimum-chars "abc" 3))))
  
  (testing "return nil when below minimum length"
    (is (nil? (org-select/minimum-chars "ab" 3)))
    (is (nil? (org-select/minimum-chars "" 1)))))