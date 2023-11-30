(ns pc4.comp
  "Route-driven components with data fetch, targeting and load.

  A component is simply a view function with additional data specifying queries
  and targets.

  There are three related processes:
  1. Data fetch - data is fetched from backend
  2. Data targeting  - data is stored in app-db
  3. Data pull - data is pulled from app-db

  As pc4 uses routes to define most state, the query will usually need to use
  parameters from the route. Data fetch uses Pathom to send one or more queries
  to the backend. There is currently no caching. Data targeting stores either
  normalised data in the app-db in an entity database or store non-normalised
  data at a specific target in the app-db. Data pull works locally on the app-db
  to make data available to the view component, denormalizing when appropriate
  or pulling as-is from a target when specified.

  An EQL transaction has one or more queries (or mutations). Data pull returns
  data in the same order as the query, making it easy for the view component
  to destructure the results of the query."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [edn-query-language.core :as eql]
            [pyramid.core :as pyr]))

(declare target-results*)

(defn ^:private target-result
  [targets {:keys [db entities] :as m} k result]
  (let [target (targets k)]
    #_(prn :target-result {:k k :result result :db db :targets targets})
    (cond
      ;; if we have a target, just conj result as-is without normalization
      target
      {:db       (assoc-in db target result)
       :entities (conj entities target)}
      ;; if result is actually a to-many, normalize and add top-level key with idents
      (sequential? result)
      (reduce (fn [{db' :db :as m} data]
                #_(prn :add-data {:data data})
                (assoc m :db (-> db'
                                 (pyr/add data)
                                 (update k (fnil conj []) (pyr/default-ident data)))))
              {:db       (dissoc db k)                      ;; remove any existing data under the key
               :entities (conj entities k)}                 ;; add the key at a top-level entity
              result)
      ;; otherwise, simply normalize using pyr/add-report
      :else
      (let [{db' :db, entities' :entities} (pyr/add-report db result)]
        #_(prn :normalize-result result)
        (cond
          ;; the common case is that the key matches the entity ident
          (entities' k)
          {:db db', :entities (into entities entities')}
          (vector? k)
          {:db (assoc-in db' k (pyr/identify db' result)), :entities (into entities entities')}
          :else
          {:db db', :entities (into entities entities')})))))

(defn ^:private target-results*
  "Given a map of results, keyed by `ident`, return a map of :db and :entities
  processed. A result will not be normalized if it is in `targets`; those will
  be added to :db using `assoc-in` with the target specified."
  [targets {:keys [db entities]} results]
  (reduce-kv
    (fn [acc k result]
      (target-result targets acc k result))
    {:db db, :entities entities}
    results))

(defn target-results
  "Given the results of a query, target the results to a database 'db'.
  Results will be normalized unless `targets` provide a sequence of keys for the
  key to which the result should be `assoc-in`'ed."
  [db {:keys [targets]} results]
  #_(prn :target-results results)
  (target-results* (or targets {}) {:db db :entities #{}} results))

(defn target-init
  "Initialize an entity, deleting any existing data with the same ident first."
  [db data]
  (let [ident (pyr/identify db data)]
    (if (get-in db ident)
      (-> db
          (pyr/delete (pyr/identify db data))
          (pyr/add data))
      (pyr/add db data))))

(defn delete
  [db ident]
  (pyr/delete db ident))

(defn ^:private remove-eql-parameters
  "Remove any parameterised clauses from an EQL AST"
  [ast]
  (walk/postwalk
    (fn [x]
      (if (and (associative? x) (:params x))
        (dissoc x :params) x)) ast))

(defn pull-results
  [db {:keys [query targets]}]
  (let [targets' (or targets {})
        ast (eql/query->ast query)
        all-keys (mapv :key (:children ast))                ;; get top-level keys from query
        n-keys (set (remove targets' all-keys))             ;; keys for which results will be normalized
        r-keys (set/difference (set all-keys) n-keys)
        n-query (-> ast
                    (remove-eql-parameters)
                    (update :children (fn [children] (filter #(n-keys (:key %)) children)))) ;; create query for those that are normalized
        n-results (pyr/pull db (eql/ast->query n-query))    ;; get results for any normalized keys
        r-results (reduce (fn [acc k] (assoc acc k (get-in db (targets k)))) {} r-keys)] ;; get results for non-normalized keys
    ;; return ordered results using either normalized or non-normalized
    (with-meta (mapv (fn [k] (or (get n-results k) (get r-results k))) all-keys)
               {:all-keys  all-keys
                :n-keys    n-keys
                :r-keys    r-keys
                :n-query   (eql/ast->query n-query)
                :n-results n-results
                :r-results r-results})))

(def example-component1
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])]
              [:t_project/id :t_project/title :t_project/date_from :t_project/pseudonymous]}])})

(def example-component2
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])] [:t_project/id :t_project/title {:t_project/users [:t_user/id :t_user/full_name]}]}
             {[:t_user/id 1] [:t_user/id :t_user/first_names :t_user/last_name]}])})

(def example-component3
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])] [:t_project/id :t_project/title {:t_project/users [:t_user/id :t_user/full_name]}]}
             {[:t_user/id 1] [:t_user/id :t_user/first_names :t_user/last_name]}
             :com.eldrix.rsdb/all-medication-reasons-for-stopping])})

(def example-component4
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])] [:t_project/id :t_project/title {:t_project/users [:t_user/id :t_user/full_name]}]}
             {[:t_user/id 1] [:t_user/id :t_user/first_names :t_user/last_name]}
             {:com.eldrix.rsdb/all-medication-reasons-for-stopping
              [:t_medication_reason_for_stopping/id
               :t_medication_reason_for_stopping/name]}])})

(def example-component5
  {:query   (fn [params]
              [{[:t_project/id (get-in params [:path :project-id])] [:t_project/id :t_project/title {:t_project/users [:t_user/id :t_user/full_name]}]}
               {[:t_user/id 1] [:t_user/id :t_user/first_names :t_user/last_name]}
               :com.eldrix.rsdb/all-medication-reasons-for-stopping])
   :targets {:com.eldrix.rsdb/all-medication-reasons-for-stopping [:lookups :all-medication-reasons-for-stopping]}})

(def example-component6
  {:query   (fn [params]
              [{[:t_project/id (get-in params [:path :project-id])]
                [{(list :t_project/users {:group-by :user :active true})
                  [:t_user/id :t_user/username :t_user/has_photo :t_user/email :t_user/full_name :t_user/active?
                   :t_user/first_names :t_user/last_name :t_user/job_title
                   {:t_user/roles [:t_project_user/id
                                   :t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?]}]}]}])
   :targets (constantly [:current-project :team])})

(comment
  (require '[com.eldrix.pc4.system])
  (com.eldrix.pc4.system/load-namespaces :dev [:pathom/boundary-interface])
  (def system (com.eldrix.pc4.system/init :dev [:pathom/boundary-interface]))
  (keys system)
  (def pathom (:pathom/boundary-interface system))
  (pathom {:pathom/entity {:t_project/id 1}
           :pathom/eql    [:t_project/id :t_project/title]})
  (pathom [{[:t_project/id 1] [:t_project/id :t_project/title]}
           {[:t_user/id 1] [:t_user/id :t_user/full_name]}])
  (pathom [{[:t_project/id 1]
            [{(list :t_project/users {:group-by :user})
              [:t_user/id :t_user/username :t_user/has_photo :t_user/email :t_user/full_name :t_user/active?
               :t_user/first_names :t_user/last_name :t_user/job_title
               {:t_user/roles [:t_project_user/date_from :t_project_user/date_to :t_project_user/role :t_project_user/active?]}]}]}])

  (defn test-component
    [db {:keys [query targets] :as component}]
    (let [query' (query {:path {:project-id 1}})
          results (pathom query')
          {db' :db} (target-results db {:query query' :targets targets} results)]
      {:db      db'
       :query   query'
       :targets targets
       :results {:raw  results
                 :pull (pull-results db' {:query query' :targets targets})}}))

  (test-component {} example-component1)
  (test-component {} example-component2)
  (test-component {} example-component3)
  (test-component {} example-component4)
  (test-component {} example-component5)
  (test-component {} example-component6))


