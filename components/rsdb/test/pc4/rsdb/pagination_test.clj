(ns pc4.rsdb.pagination-test
  (:require [pc4.rsdb.pagination :as p]
            [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]))


(def gen-row (gen/map gen/keyword gen/any-equatable))

(deftest cursor-roundtrip-generative-test
  (testing "cursor roundtrip with generated data"
    (let [row-samples (gen/sample gen-row 50)]
      (doseq [row row-samples]
        (is (= row (p/decode-cursor (p/encode-cursor row)))
            (str "Failed roundtrip for row: " row))))))

(deftest nil-cursor-handling
  (testing "encode-cursor with nil returns nil"
    (is (nil? (p/encode-cursor nil))))
  (testing "decode-cursor with nil returns nil"
    (is (nil? (p/decode-cursor nil)))))

(def create-cursor-tests
  [{:description "Extract sort columns from result row"
    :input {:row {:id 123 :last_name "Smith" :first_names "John" :other "data"}
            :sort-columns [[:last_name :asc] [:first_names :desc] [:id :asc]]}
    :expected {:last_name "Smith" :first_names "John" :id 123}}

   {:description "Single column extraction"
    :input {:row {:id 456 :name "Alice" :age 30}
            :sort-columns [[:name :desc] [:id :asc]]}
    :expected {:name "Alice" :id 456}}

   {:description "Missing sort column throws exception"
    :input {:row {:id 123 :name "John"}
            :sort-columns [[:last_name :asc] [:id :asc]]}
    :expected :throws}

   {:description "Nil value in cursor handled correctly"
    :input {:row {:id 123 :name nil}
            :sort-columns [[:name :asc] [:id :asc]]}
    :expected {:name nil :id 123}}

   {:description "Empty sort columns returns empty cursor"
    :input {:row {:id 123 :name "John"}
            :sort-columns []}
    :expected {}}])

(deftest create-cursor-test
  (doseq [{:keys [description input expected]} create-cursor-tests]
    (testing description
      (if (= expected :throws)
        (is (thrown? Exception (p/create-cursor (:row input) (:sort-columns input))))
        (is (= expected (p/create-cursor (:row input) (:sort-columns input))))))))

(def cursor-where-clause-tests
  [{:description "Single sort column"
    :input {:cursor-row {:last_name "Smith" :id 123}
            :sort-columns [[:last_name :asc] [:id :asc]]}
    :expected [:or
               [:> :last_name "Smith"]
               [:and [:= :last_name "Smith"] [:> :id 123]]]}

   {:description "Two columns ASC/DESC"
    :input {:cursor-row {:last_name "Jones" :first_names "Alice" :id 456}
            :sort-columns [[:last_name :asc] [:first_names :desc] [:id :asc]]}
    :expected [:or
               [:> :last_name "Jones"]
               [:and [:= :last_name "Jones"] [:< :first_names "Alice"]]
               [:and [:= :last_name "Jones"] [:= :first_names "Alice"] [:> :id 456]]]}

   {:description "Three columns all ASC"
    :input {:cursor-row {:last_name "Smith" :first_names "Bob" :id 2}
            :sort-columns [[:last_name :asc] [:first_names :asc] [:id :asc]]}
    :expected [:or
               [:> :last_name "Smith"]
               [:and [:= :last_name "Smith"] [:> :first_names "Bob"]]
               [:and [:= :last_name "Smith"] [:= :first_names "Bob"] [:> :id 2]]]}

   {:description "Three columns mixed ASC/DESC/ASC"
    :input {:cursor-row {:last_name "Jones" :first_names "Charlie" :id 5}
            :sort-columns [[:last_name :asc] [:first_names :desc] [:id :asc]]}
    :expected [:or
               [:> :last_name "Jones"]
               [:and [:= :last_name "Jones"] [:< :first_names "Charlie"]]
               [:and [:= :last_name "Jones"] [:= :first_names "Charlie"] [:> :id 5]]]}

   {:description "Single DESC column"
    :input {:cursor-row {:date_birth #inst "1980-01-01" :id 789}
            :sort-columns [[:date_birth :desc] [:id :asc]]}
    :expected [:or
               [:< :date_birth #inst "1980-01-01"]
               [:and [:= :date_birth #inst "1980-01-01"] [:> :id 789]]]}])

(deftest cursor-where-clause-test
  (doseq [{:keys [description input expected]} cursor-where-clause-tests]
    (testing description
      (let [cursor-str (p/encode-cursor (:cursor-row input))
            result (p/with-cursor {} cursor-str (:sort-columns input))
            actual (:where result)]
        (is (= expected actual))))))

(def page-limit-tests
  [{:description "Basic page limit adds one"
    :input {:query {:select [:id] :from :users}
            :page-size 25}
    :expected {:select [:id] :from :users :limit 26}}

   {:description "Small page size"
    :input {:query {:select [:name] :from :products}
            :page-size 1}
    :expected {:select [:name] :from :products :limit 2}}])

(deftest page-limit-test
  (doseq [{:keys [description input expected]} page-limit-tests]
    (testing description
      (let [actual (p/with-page-limit (:query input) (:page-size input))]
        (is (= expected actual))))))

(deftest paginated-results-test
  (testing "pagination flow from first to second page"
    (let [all-data (for [i (range 7)] {:id i :name (str "User" i)})

          ;; First page - mock returns all data, function takes first 5
          mock-execute-page1 (constantly all-data)
          page1 (p/paginated-results mock-execute-page1
                                     {:select [:*] :from [:users]}
                                     [[:id :asc]]
                                     {:limit 5})]

      ;; Verify first page
      (is (= 5 (count (:results page1))))
      (is (= (take 5 all-data) (:results page1)))
      (is (some? (:next-cursor page1)))

      ;; Second page - use cursor from page1, mock returns remaining data
      (let [mock-execute-page2 (constantly (drop 5 all-data))
            page2 (p/paginated-results mock-execute-page2
                                       {:select [:*] :from [:users]}
                                       [[:id :asc]]
                                       {:cursor (:next-cursor page1) :limit 5})]

        ;; Verify second page
        (is (= 2 (count (:results page2))))
        (is (= (drop 5 all-data) (:results page2)))
        (is (nil? (:next-cursor page2))))))  ; No more pages

  (testing "empty results"
    (let [mock-execute (constantly [])
          result (p/paginated-results mock-execute
                                      {:select [:*] :from [:users]}
                                      [[:id :asc]]
                                      {:limit 5})]
      (is (= [] (:results result)))
      (is (nil? (:next-cursor result))))))

