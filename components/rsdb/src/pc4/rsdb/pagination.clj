(ns pc4.rsdb.pagination
  (:require
    [honey.sql :as sql]
    [honey.sql.helpers :as h]
    [clojure.edn :as edn]
    [clojure.spec.alpha :as s]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import (java.util Base64)))

(s/def ::column-name keyword?)
(s/def ::sort-dir #{:asc :desc})
(s/def ::sort-column (s/tuple ::column-name ::sort-dir))
(s/def ::sort-columns (s/coll-of ::sort-column))
(s/def ::row (s/nilable map?))
(s/def ::cursor (s/nilable string?))
(s/def ::query map?)
(s/def ::page-size pos-int?)

(s/fdef encode-cursor
  :args (s/cat :cursor-data ::row)
  :ret ::cursor)

(defn encode-cursor
  "Encode cursor data as base64 EDN"
  [cursor-data]
  (when cursor-data
    (->> cursor-data
         pr-str
         .getBytes
         (.encodeToString (Base64/getEncoder)))))


(s/fdef decode-cursor
  :args (s/cat :cursor-str ::cursor)
  :ret ::row)

(defn decode-cursor
  "Decode base64 cursor back to data"
  [^String cursor-str]
  (when cursor-str
    (->> cursor-str
         (.decode (Base64/getDecoder))
         (String.)
         edn/read-string)))

(s/fdef create-cursor
  :args (s/cat :row ::row :sort-columns ::sort-columns)
  :ret ::row)

(defn create-cursor
  "Create cursor from result row and sort columns.

  Extracts only the values needed for pagination.
  Client must ensure sort columns include a unique column for deterministic ordering.
  Throws exception if any sort key is missing from the row.

  Example: (create-cursor {:id 123 :last_name \"Smith\" :first_names \"John\" :other \"data\"}
                          [[:last_name :asc] [:first_names :desc] [:id :asc]])
  => {:last_name \"Smith\" :first_names \"John\" :id 123}"
  [row sort-columns]
  (reduce
    (fn [cursor [column _]]
      (let [value (get row column ::not-found)]
        (if-not (= value ::not-found)
          (assoc cursor column value)
          (throw (ex-info "Sort column missing from cursor row"
                          {:column column :row row :sort-columns sort-columns})))))
    {}
    sort-columns))

(s/fdef with-page-limit
  :args (s/cat :query ::query :page-size ::page-size)
  :ret ::query)

(defn with-page-limit
  "Add LIMIT clause to query. Adds 1 extra to detect if more results exist."
  [query page-size]
  (h/limit query (inc page-size)))

(s/fdef with-order-by
  :args (s/cat :query ::query :sort-columns ::sort-columns)
  :ret ::query)

(defn with-order-by
  "Add ORDER BY clause using sort columns.

  Client must ensure sort-columns include a unique column for cursor stability.
  'sort-columns' should be a vector of tuples of column name and direction.

  Example: [[:last_name :asc] [:first_names :asc] [:id :asc]]"
  [query sort-columns]
  (if (empty? sort-columns)
    query
    (apply h/order-by query sort-columns)))

(defn equality-clauses
  "Build equality clauses for cursor pagination from sort columns.

  Example: (equality-clauses {:last_name \"Smith\"} [[:last_name :asc] [:first_names :desc]])
  => [[:= :last_name \"Smith\"] [:= :first_names \"John\"]]"
  [cursor-row sort-columns]
  (mapv (fn [[col _]] [:= col (cursor-row col)]) sort-columns))

(defn comparison-clause
  "Build comparison clause for cursor pagination from column and direction.

  Example: (comparison-clause {:last_name \"Smith\"} [:last_name :asc])
  => [:> :last_name \"Smith\"]"
  [cursor-row [col dir]]
  (let [op (if (= dir :asc) :> :<)]
    [op col (cursor-row col)]))

(defn cursor-where-clause
  "Build a single WHERE clause from cursor data and sort columns.

  Uses butlast to get columns that must match exactly (equality conditions)
  and peek to get the final column for comparison (> or < condition).

  For [[:last_name :asc] [:first_names :desc]]:
  - butlast gives [[:last_name :asc]] for equality: last_name = 'Smith'
  - peek gives [:first_names :desc] for comparison: first_names < 'John'
  - Result: [:and [:= :last_name \"Smith\"] [:< :first_names \"John\"]]]"
  [cursor-row sort-columns]
  (let [eq-clauses (equality-clauses cursor-row (butlast sort-columns))
        comp-clause (comparison-clause cursor-row (peek sort-columns))]
    (if (empty? eq-clauses)
      comp-clause
      (into [:and] (conj eq-clauses comp-clause)))))

(s/fdef with-cursor
  :args (s/cat :query ::query :cursor-str ::cursor :sort-columns ::sort-columns)
  :ret ::query)

(defn with-cursor
  "Add WHERE clause for cursor-based pagination.

  Client must ensure sort-columns match those used in ORDER BY and include a unique column."
  [query cursor-str sort-columns]
  (if-let [cursor-row (decode-cursor cursor-str)]
    (let [prefixes (rest (reductions conj [] sort-columns))
          conditions (map #(cursor-where-clause cursor-row %) prefixes)]
      (h/where query (into [:or] conditions)))
    query))

(s/def ::opts (s/keys :opt-un [::cursor ::page-size]))

(s/fdef paginate
  :args (s/cat :query ::query
               :sort-columns ::sort-columns
               :opts ::opts)
  :ret ::query)

(defn paginate
  "Paginate a 'query' sorting by 'sort-columns'.

  Parameters:
  - query        : base query
  - sort-columns : a sequence of tuples of column and either :asc or :desc
  - options :
          - :cursor - opaque string from previous result
          - :limit  - page size.

  The sort-columns MUST include a tie-breaker column for correct operation. It
  is advised to use a primary key as the final sort e.g. [... [:id :asc]].

  Example:
  (paginate {:select [:*] :from [:users]}
            [[:last_name :asc] [:id :asc]]
            {:cursor \"eyJpZCI6MTIzLCJsYXN0X25hbWUiOiJTbWl0aCJ9\"
             :limit 20})"
  [query sort-columns {:keys [cursor limit] :or {limit 200}}]
  (cond-> (with-order-by query sort-columns)
    cursor (with-cursor cursor sort-columns)
    limit (with-page-limit limit)))

(s/fdef paginated-results
  :args (s/cat :fn fn?
               :query ::query
               :sort-columns ::sort-columns
               :opts ::opts))
(defn paginated-results
  "Returns a page of paginated results. If cursor is 'nil' then the first page
  is returned, but otherwise the cursor is used to fetch the next page.

  Parameters:

  - execute-fn   : a function that can execute a query
  - query        : the base query
  - sort-columns : a sequence of tuples of column and either :asc or :desc
  - options :
    - cursor - a cursor returned from a prior call to paginated-results or nil
    - limit  - page size, default 200

  The query passed in, and the query passed to 'execute-fn' should be Clojure
  data structures that can be formatted into a SQL statement and values by
  HoneySQL.

  The sort-columns MUST include a tie-breaker column for correct operation. It
  is advised to use a primary key as the final sort e.g. [... [:id :asc]]."
  [execute-fn query sort-columns {:keys [cursor limit] :or {limit 200}}]
  (let [paged-query (paginate query sort-columns {:cursor cursor :limit limit})
        all-results (execute-fn paged-query)
        has-more? (> (count all-results) limit)
        results (if has-more? (take limit all-results) all-results)
        next-cursor (when has-more?
                      (-> (last results)
                          (create-cursor sort-columns)
                          encode-cursor))]
    {:results results, :next-cursor next-cursor}))

(s/fdef execute-paginated!
  :args (s/cat :conn :next.jdbc.specs/proto-connectable
               :query ::query
               :sort-columns ::sort-columns
               :opts ::opt))
(defn execute-paginated!
  "Returns a page of paginated results. If cursor is 'nil' then the first page
  is returned, but otherwise the cursor is used to fetch the next page.

  Parameters:

  - conn         : a next.jdbc Connectable
  - query        : the base query
  - sort-columns : a sequence of tuples of column and either :asc or :desc
  - options :
    - cursor - a cursor returned from a prior call to paginated-results or nil
    - limit  - page size, default 200

  Options are ALSO passed unchanged to next.jdbc/execute! so one can use options
  such as {:builder-fn rs/as-as-unqualified-maps} should they be required.

  The query passed in, and the query passed to 'execute-fn' should be Clojure
  data structures that can be formatted into a SQL statement and values by
  HoneySQL.

  The sort-columns MUST include a tie-breaker column for correct operation. It
  is advised to use a primary key as the final sort e.g. [... [:id :asc]]."
  [conn query sort-columns options]
  (paginated-results
    (fn [q] (jdbc/execute! conn (sql/format q) options))
    query sort-columns options))

(comment
  (def conn (jdbc/get-connection "jdbc:postgresql:rsdb"))
  (def opts {:limit 2, :builder-fn rs/as-unqualified-maps})
  (execute-paginated! conn {:select :id :from :t_patient} [[:id :asc]] opts)
  (execute-paginated! conn {:select :id :from :t_patient} [[:id :asc]]
                      (assoc opts :cursor "ezppZCAxMH0="))
  (execute-paginated! conn {:select :id :from :t_patient} [[:id :asc]]
                      (assoc opts :cursor "ezppZCAyMH0=")) )