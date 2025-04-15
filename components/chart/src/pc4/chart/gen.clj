(ns pc4.chart.gen
  "Generators for chart data structures using clojure.spec.
   These generators create realistic data for testing and development."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [pc4.chart.edss :as edss]
    [pc4.chart.medication :as medication])
  (:import
    (java.time LocalDate)))

;; Utility generators
(def gen-date
  "Generator for random dates within the last 10 years"
  (gen/fmap (fn [days-ago]
              (-> (LocalDate/now)
                  (.minusDays days-ago)))
            (gen/choose 0 3650)))                           ; Up to 10 years ago

(def gen-non-empty-string
  "Generator for non-empty strings"
  (gen/not-empty (gen/string-alphanumeric)))

(def gen-positive-id
  "Generator for positive integer IDs under 100,000"
  (gen/choose 1 99999))

;; EDSS score generator
(def gen-edss-score
  "Generator for EDSS scores using valid EDSS values (0, 1.0, 1.5, 2.0...10.0)"
  (gen/fmap (fn [[id days-ago edss-idx in-relapse?]]
              {:id          id
               :date        (.minusDays (LocalDate/now) days-ago)
               :edss        (get [0.0 1.0 1.5 2.0 2.5 3.0 3.5 4.0 4.5 5.0 5.5 6.0 6.5 7.0 7.5 8.0 8.5 9.0 9.5 10.0] edss-idx)
               :in-relapse? in-relapse?})
            (gen/tuple
              gen-positive-id                             ; positive integer ID
              (gen/choose 0 3650)                           ; days ago (up to 10 years)
              (gen/choose 0 19)                             ; index into valid EDSS values array
              (gen/boolean))))

;; MS event generator
(def gen-ms-event
  "Generator for MS events"
  (gen/fmap (fn [[id days-ago type abbrev]]
              {:id           id
               :date         (.minusDays (LocalDate/now) days-ago)
               :type         (keyword type)
               :abbreviation abbrev})
            (gen/tuple
              gen-positive-id                               ; positive integer ID
              (gen/choose 0 3650)                           ; days ago (up to 10 years)
              (gen/elements ["relapse" "progression" "mri" "treatment"])
              (gen/elements ["R" "P" "M" "T"]))))

;; LogMAR score generator
(def gen-logmar-score
  "Generator for visual acuity scores in LogMAR format"
  (gen/fmap (fn [[id days-ago left right]]
              {:id           id
               :date         (.minusDays (LocalDate/now) days-ago)
               :left-logmar  left
               :right-logmar right})
            (gen/tuple
              gen-positive-id                               ; positive integer ID
              (gen/choose 0 3650)                           ; days ago (up to 10 years)
              (gen/double* {:min -0.4 :max 3.0 :infinite? false :NaN? false})
              (gen/double* {:min -0.4 :max 3.0 :infinite? false :NaN? false}))))

;; Medication generator
(def gen-medication
  "Generator for medication records"
  (gen/fmap (fn [[id name start-days-ago end-days-ago daily-dose]]
              (let [start-date (.minusDays (LocalDate/now) start-days-ago)
                    end-date (when (< end-days-ago start-days-ago)
                               (.minusDays (LocalDate/now) end-days-ago))]
                {:id         id
                 :name       name
                 :start-date start-date
                 :end-date   end-date
                 :daily-dose daily-dose}))
            (gen/tuple
              gen-positive-id                               ; positive integer ID
              (gen/elements ["Interferon beta-1a" "Glatiramer acetate" "Natalizumab"
                             "Fingolimod" "Ocrelizumab" "Dimethyl fumarate" "Teriflunomide"])
              (gen/choose 0 3650)                           ; start days ago
              (gen/choose 0 3650)                           ; end days ago
              (gen/double* {:min 0.0 :max 100.0 :NaN? false :infinite? false})))) ; daily-dose

;; Enhanced spec generators using our custom generators
(s/def ::edss-scores (s/with-gen ::edss/edss-scores #(gen/vector gen-edss-score 1 20)))
(s/def ::ms-events (s/with-gen ::edss/ms-events #(gen/vector gen-ms-event 1 10)))
(s/def ::logmar-scores (s/with-gen ::edss/logmar-scores #(gen/vector gen-logmar-score 0 10)))
(s/def ::medications (s/with-gen ::medication/medications #(gen/vector gen-medication 0 5)))

;; Helper functions for tests and examples
(defn generate-sample-data
  "Generate a complete set of sample data for charting
   
   Returns a map with :edss-scores, :ms-events, :logmar-scores, and :medications"
  []
  {:edss-scores   (gen/generate (gen/vector gen-edss-score 10))
   :ms-events     (gen/generate (gen/vector gen-ms-event 5))
   :logmar-scores (gen/generate (gen/vector gen-logmar-score 8))
   :medications   (gen/generate (gen/vector gen-medication 3))})

(defn generate-minimal-data
  "Generate minimal valid data for a chart - just one EDSS score and one MS event"
  []
  {:edss-scores [{:id   1
                  :date (LocalDate/now)
                  :edss 3.5}]
   :ms-events   [{:id           1
                  :date         (LocalDate/now)
                  :type         :relapse
                  :abbreviation "R"}]})