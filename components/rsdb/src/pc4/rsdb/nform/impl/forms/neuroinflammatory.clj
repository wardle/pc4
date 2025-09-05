(ns pc4.rsdb.nform.impl.forms.neuroinflammatory
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [pc4.rsdb.nform.impl.form :as form]))

(def edss-score->score
  "The EDSS is stored in the database as 'SCORE0_0' but we map back and forth
  during hydration/dehydration."
  {"SCORE0_0"          "0.0"
   "SCORE1_0"          "1.0"
   "SCORE1_5"          "1.5"
   "SCORE2_0"          "2.0"
   "SCORE2_5"          "2.5"
   "SCORE3_0"          "3.0"
   "SCORE3_5"          "3.5"
   "SCORE4_0"          "4.0"
   "SCORE4_5"          "4.5"
   "SCORE5_0"          "5.0"
   "SCORE5_5"          "5.5"
   "SCORE6_0"          "6.0"
   "SCORE6_5"          "6.5"
   "SCORE7_0"          "7.0"
   "SCORE7_5"          "7.5"
   "SCORE8_0"          "8.0"
   "SCORE8_5"          "8.5"
   "SCORE9_0"          "9.0"
   "SCORE9_5"          "9.5"
   "SCORE10_0"         "10.0"
   "SCORE_LESS_THAN_4" "<4"})

(def edss-score->score'
  (reduce-kv (fn [acc k v] (assoc acc v k)) {} edss-score->score))

(s/def ::edss (set (vals edss-score->score)))

(defmethod form/spec :edss/v1 [_]
  (s/keys :req-un [::edss]))

(defmethod form/hydrate :edss/v1 [{:keys [edss_score] :as form}]
  (-> form
      (dissoc :edss_score)
      (assoc :edss (edss-score->score edss_score))))

(defmethod form/summary :edss/v1 [{:keys [edss]}]
  edss)

(defmethod form/dehydrate :edss/v1 [{:keys [edss] :as form}]
  (-> form
      (dissoc :edss)
      (assoc :edss_score (edss-score->score' edss))))

;;
;;
;;

(def ms-disease-courses
  [{:id 1 :title "Unknown" :value :unknown}
   {:id 2 :title "Clinically isolated syndrome" :value :clinically-isolated-syndrome}
   {:id 3 :title "Relapsing remitting" :value :relapsing-remitting}
   {:id 4 :title "Secondary progressive" :value :secondary-progressive}
   {:id 5 :title "Primary progressive" :value :primary-progressive}
   {:id 6 :title "Secondary progressive with relapses" :value :secondary-progressive-relapses}
   {:id 7 :title "Relapsing with sequelae" :value :relapsing-with-sequelae}
   {:id 8 :title "Primary progressive with relapses" :value :primary-progressive-relapses}])

(def disease-course-by-key
  (reduce (fn [acc {:keys [value] :as dc}]
            (assoc acc value dc))
          {} ms-disease-courses))
(def disease-course-by-id
  (reduce (fn [acc {:keys [id] :as dc}]
            (assoc acc id dc))
          {} ms-disease-courses))

(s/def ::in_relapse boolean?)
(s/def ::ms_disease_course (into #{} (map :value) ms-disease-courses))

(defmethod form/spec :relapse/v1 [_]
  (s/keys :req-un [::in_relapse ::ms_disease_course]))

(defmethod form/hydrate :relapse/v1
  [{:keys [ms_disease_course_fk] :as form}]
  (-> form
      (dissoc :ms_disease_course_fk)
      (assoc :ms_disease_course (:value (disease-course-by-id ms_disease_course_fk)))
      (update :in_relapse parse-boolean)))

(defmethod form/summary :relapse/v1
  [{:keys [in_relapse ms_disease_course]}]
  (str (if in_relapse "In relapse" "Not in relapse")
       (when-let [{:keys [title]} (disease-course-by-key ms_disease_course)]
         (str ": " (str/lower-case title)))))

(defmethod form/dehydrate :relapse/v1
  [{:keys [ms_disease_course] :as form}]
  (-> form
      (dissoc :ms_disease_course)
      (assoc :ms_disease_course_fk (:id (disease-course-by-key ms_disease_course)))
      (update :in_relapse str)))
