(ns pc4.rsdb.msss
  "The Multiple Sclerosis Severity Score (MSSS) is a measure of MS severity 
 that uses EDSS and disease duration. One can obtain the lookups for global 
 MSSS datasets generated from thousands of patients. 
 
 This namespace provides access to a number of MSSS datasets:
 - :db              : generate 'live' MSSS from a local cohort of patients, 
                      allowing an end-user to dynamically choose a cohort
                      based on same sex, age range for disease onset.
 - :roxburgh        : See https://www.neurology.org/doi/10.1212/01.WNL.0000156155.19270.F8
 - :manouchehrinia  : Age-adjusted MSSS; this uses age not disease duration
 - :santoro         : Paediatric MSSS.
 
 The external datasets are from https://pypi.org/project/mssev/#files"
  (:require
   [charred.api :as csv]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [hugsql.core :as hugsql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [next.jdbc.specs]
   [pc4.snomedct.interface :as hermes]))

(declare fetch-msss-sqlvec) ;; hugsql automatically defines a function to generate the SQL

(hugsql/def-sqlvec-fns "rsdb/msss/msss-edss.sql")

(defn multiple-sclerosis-concept-ids
  [hermes]
  (long-array (hermes/with-historical hermes (hermes/all-children-ids hermes 24700007))))

(def supported-msss-datasets
  #{:db :roxburgh :manouchehrinia :santoro})

(s/def ::conn :next.jdbc.specs/proto-connectable)
(s/def ::ms-concept-ids (s/coll-of :info.snomed.Concept/id))
(s/def ::min-age-onset pos-int?)
(s/def ::max-age-onset pos-int?)
(s/def ::sex #{"MALE" "FEMALE" "UNKNOWN"})
(s/def ::disease-duration nat-int?)
(s/def ::edss #{0.0 0.5 1.0 1.5 2.0 2.5 3.0 3.5 4.0 4.5 5.0 5.5 6.0 6.5 7.0 7.5 8.0 8.5 9.0 9.5 10.0})
(s/def ::msss number?)
(s/def ::min-duration ::disease-duration)
(s/def ::max-duration ::disease-duration)
(s/def ::percentiles (s/coll-of ::msss))
(s/def ::type supported-msss-datasets)

(defmulti msss-params :type)

(defmethod msss-params :db [_]
  (s/keys :req-un [::type ::conn]
          :opt-un [::ms-concept-ids ::min-age-onset ::max-age-onset ::sex]))

(defmethod msss-params :default [_]
  (s/keys :opt-un [::type]))

(s/def ::params (s/multi-spec msss-params :type))

(comment
  (s/valid? ::params {:type :db :conn conn :ms-concept-ids []})
  (s/valid? ::params {:type :roxburgh})
  (s/valid? ::params {}))

(s/def ::lookup
  (s/map-of ::disease-duration (s/map-of ::edss ::msss)))

(s/fdef db-msss-sql
  :args (s/cat :params ::params))
(defn db-msss-sql
  "Generate a vector of SQL and parameters to return the local MSSS 
  dataset from the rsdb database. 
  Parameters: 
  - ms-concept-ids : a sequence of concept ids to match a diagnosis of MS 
  - min-age-onset  : limit to patients with this age at onset or greater.
  - max-age-onset  : limit to patients below this age at onset.
  - sex            : limit to patients of this sex, e.g. :male.
  
  It would be usual to generate a live sequence of concept ids using
  hermes via [[multiple-sclerosis-concept-ids]]."

  [{:keys [ms-concept-ids min-age-onset max-age-onset sex]}]
  (fetch-msss-sqlvec
   {:ms-concept-ids (or ms-concept-ids (long-array [24700007]))
    :min-age-onset  (or min-age-onset 0)
    :max-age-onset  (or max-age-onset 100)
    :sex            (if sex (vector (str/upper-case (name sex))) #{"MALE" "FEMALE" "UNKNOWN"})}))

(s/fdef db-msss-data
  :args (s/cat :params ::params))
(defn db-msss-data
  "Return 'local' MSSS data - a sequence of maps each containing
  :disease_duration, :edss and :msss - derived from the local 
  'live' database dataset. 
  e.g.
  ```
  [{:disease_duration 0, :edss 0.0, :msss 0.7466666666666667}
   {:disease_duration 0, :edss 1.0, :msss 2.3333333333333335}
   {:disease_duration 0, :edss 1.5, :msss 3.533333333333333}
   {:disease_duration 0, :edss 2.0, :msss 4.333333333333333}
  ```
  The calculation can take ~300ms so should probably be cached.
  
  It is usually more appropriate to use [[msss-lookup]]."
  [{:keys [conn] :as params}]
  (jdbc/execute!
   conn
   (db-msss-sql params)
   {:builder-fn rs/as-unqualified-maps}))

(defn ^:private local-msss-data
  "Read local MSSS data from the classpath."
  [n]
  (clojure.edn/read-string (slurp (io/resource n))))

(s/fdef msss-lookup
  :args (s/cat :params ::params)
  :ret ::lookup)
(defmulti msss-lookup
  "Returns a nested map of MSSS values keyed first by disease duration in 
  years, and then by EDSS. This defaults to using the Roxburgh dataset.
  ```
  (get-in (msss-lookup {}) [5 2.5])
  =>
  3.095137420718816
  ```."
  (fn [{msss-type :type :as params}]
    (if (s/valid? ::params params)
      (or msss-type :roxburgh)
      (throw (ex-info "invalid MSSS parameters" (s/explain-data ::params params))))))

(defmethod msss-lookup :db
  [{:keys [conn] :as params}]
  (reduce
   (fn [acc row]
     (assoc-in acc [(:disease_duration row) (:edss row)] (:msss row)))
   {}
   (jdbc/plan conn (db-msss-sql params))))

(defmethod msss-lookup :roxburgh
  [_]
  (local-msss-data "rsdb/msss/roxburgh.edn"))

(defmethod msss-lookup :manouchehrinia
  [_]
  (local-msss-data "rsdb/msss/manouchehrinia.edn"))

(defmethod msss-lookup :santoro
  [_]
  (local-msss-data "rsdb/msss/santoro.edn"))

;; generate pre-built MSSS datasets at the REPL... converting tsv -> csv files

(comment
  (def edss-scores [0.0 1.0 1.5 2.0 2.5 3.0 3.5 4.0 4.5 5.0 5.5 6.0 6.5 7.0 7.5 8.0 8.5 9.0 9.5])

  (defn ^:private parse-row
    [[duration & scores]]
    (hash-map (parse-long duration) (into (sorted-map) (zipmap edss-scores (map parse-double scores)))))

  (defn ^:private parse-msss-datafile
    [f]
    (with-open [f (io/reader f)]
      (let [[_header & rows] (csv/read-csv f :separator \tab)]
        (into (sorted-map) (apply merge (map parse-row rows))))))

  (def data-dir "/Users/mark/Downloads/mssev-0.5.2/mssev/data/msss")
  (def lookup (parse-msss-datafile (io/file data-dir "roxburgh.tsv")))
  (spit (io/file "roxburgh.edn") lookup)
  (def lookup (parse-msss-datafile (io/file data-dir "santoro.tsv")))
  (spit (io/file "santoro.edn") lookup)
  (def lookup (parse-msss-datafile (io/file data-dir "manouchehrinia.tsv")))
  (spit (io/file "manouchehrinia.edn") lookup)
  (s/valid? ::lookup (msss-lookup {:type :roxburgh})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Functions that use the lookup data
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef msss-for-duration-and-edss
  :args (s/cat :lookup ::lookup :duration-years ::disease-duration :edss ::edss))
(defn msss-for-duration-and-edss
  "Return the MSSS for a given EDSS and disease duration in years.
  Parameters:
  - lookup         : results of [[msss-lookup]]
  - duration-years : duration of disease in years
  - edss           : edss of the patient at the disease duration specified."
  [lookup duration-years edss]
  (get-in lookup [duration-years edss]))

(s/fdef edss-for-duration-and-msss
  :args (s/cat :lookup ::lookup :duration-years ::disease-duration :msss ::msss))
(defn edss-for-duration-and-msss
  "Return the closest EDSS for the given duration and MSSS.
  This is essentially the reverse of `msss-for-duration-and-edss`.
  Parameters:
  - lookup         : results of [[msss-lookup]]
  - duration-years : disease duration, in years
  - msss           : required MSSS
  
  For example,
  ```
  (edss-for-duration-and-msss (msss-lookup {}) 5 5.0)
  => 
  2.5
  ```
  "
  [lookup duration-years msss]
  (loop [[nearest diff :as old] [nil 0]
         msss-by-edss (seq (get lookup duration-years))]
    (if-not msss-by-edss
      nearest
      (let [[edss# msss#] (first msss-by-edss)
            diff'         (abs (- msss msss#))
            change?       (or (nil? nearest) (> diff diff'))]
        (recur
         (if change? [edss# diff'] old)
         (next msss-by-edss))))))

(s/fdef derived-edss-over-time
  :args (s/cat :lookup ::lookup :params (s/keys* :opt-un [::min-duration ::max-duration ::percentiles])))

(defn derived-edss-over-time
  "Returns nested maps of 5%, 25%, 50%, 75% and 95% centiles of disease duration to EDSS.
  Parameters:
  - lookup       : results from [[msss-lookup]]
  - min-duration : minimum disease duration in years, default 0.
  - max-duration : maximum disease duration in years, default 30.
  - percentiles  : percentiles to use, default [0.5 2.5 5.0 7.5 9.5].
  
  For example,
  ```
  (get (derived-edss-over-time (msss-lookup {}) {:min-duration 0 :max-duration 30}) 0.5)
  ```
  will return a map of disease duration from 0 to 30 to EDSS for the 50% 
  centile based on data from the whole cohort of patients."
  [lookup & {:keys [min-duration max-duration percentiles]}]
  (reduce
   (fn [acc [duration-years msss]]
     (assoc-in acc [msss duration-years] (edss-for-duration-and-msss lookup duration-years msss)))
   {}
   (for [duration-years (range (or min-duration 0) (inc (or max-duration 30)))
         msss (or percentiles [0.5 2.5 5.0 7.5 9.5])]
     [duration-years msss])))

(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (def conn (jdbc/get-datasource {:dbtype "postgresql" :dbname "rsdb"}))
  (def hermes (#'hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (def ms-concept-ids (multiple-sclerosis-concept-ids hermes))
  (db-msss-sql {:ms-concept-ids ms-concept-ids :sex "MALE"})
  (msss-lookup {:type :db :conn conn})
  (msss-lookup {:type :roxburgh})
  (msss-lookup {:type :santoro})
  (msss-lookup {:type :manouchehrinia})
  (msss-lookup {})

  ;;
  ;;

  (get-in (msss-lookup {}) [5 2.5])
  (edss-for-duration-and-msss (msss-lookup {:type :db :conn conn}) 5 5.0)
  (into (sorted-map) (get (derived-edss-over-time (msss-lookup {}) {:min-duration 0 :max-duration 30}) 5.0))

  ;; 
  ;;

  (time (def msss (msss-lookup {:conn conn :ms-concept-ids ms-concept-ids})))
  (def msss (msss-lookup {:type :roxburgh}))
  (s/valid? ::lookup msss)
  (s/valid? (:ret (s/get-spec `msss-lookup)) (msss-lookup {:type :roxburgh}))
  (edss-for-duration-and-msss msss 5 5.0)
  (edss-for-duration-and-msss (msss-lookup conn {:diagnosis-concept-ids ms-concept-ids}) 5 5.0)
  (get-in (msss-lookup conn {:diagnosis-concept-ids ms-concept-ids}) [5 2.5])
  (get (derived-edss-over-time msss {:min-duration 0 :max-duration 20}) 5.0)
  (time (db-msss-data {:conn conn
                       :ms-concept-ids ms-concept-ids
                       :min-age-onset 20
                       :max-age-onset 30
                       :sex :female})))
