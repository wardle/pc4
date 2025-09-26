(ns pc4.rsdb.nform.impl.forms.araf
  "Specifications for 'Annual Risk Acknowledgement Forms (ARAF)."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [pc4.rsdb.nform.impl.form :as form])
  (:import (java.time LocalDateTime Period)))

(s/def ::nilable-local-date-time
  (s/with-gen
    (s/nilable #(instance? LocalDateTime %))
    #(gen/fmap (fn [days-ago]
                 (when days-ago (LocalDateTime/.minusSeconds (LocalDateTime/now) days-ago)))
               (gen/frequency [[8 (gen/choose 0 (* 10 365 24 60 60))]
                               [2 (gen/return nil)]]))))

(s/def ::eligible boolean?)
(s/def ::confirm boolean?)
(s/def ::contact boolean?)
(s/def ::contraception boolean?)
(s/def ::conditions boolean?)
(s/def ::discussed boolean?)
(s/def ::regular_review boolean?)
(s/def ::risks_stopping boolean?)
(s/def ::serious_harm boolean?)
(s/def ::referral boolean?)
(s/def ::pregnancy_test boolean?)
(s/def ::status #{:at-risk :pre-menarche :permanent :other})
(s/def ::review-dt ::form/local-date-time)
(s/def ::text (s/nilable string?))
(s/def ::acknowledged boolean?)
(s/def ::method #{:web :paper})
(s/def ::araf-request-id string?)

(s/def ::programme #{:valproate-f :valproate-m})

;;
;; All ARAF programmes can report an outcome for any given patient.
;;
(s/def ::excluded (s/nilable #{:temporary :permanent}))     ;; patient excluded?
(s/def ::completed boolean?)                                ;; has all paperwork been completed?
(s/def ::expiry ::nilable-local-date-time)                  ;; when does paperwork expire?
(s/def ::task (s/tuple keyword? boolean?))
(s/def ::tasks (s/coll-of ::task))
(s/def ::outcome
  (s/keys :req-un [::excluded ::completed ::expiry]
          :opt-un [::tasks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;; ARAF VALPROATE FEMALE ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; STEP 1 - evaluate 'prevent' programme :araf-val-f-s1-evaluate/v1
;;
(defmethod form/spec :araf-val-f-s1-evaluation/v2_0 [_]
  (s/keys :req-un [::status ::text ::review-dt]))

(defmethod form/hydrate :araf-val-f-s1-evaluation/v2_0
  [{:keys [status] :as form}]
  (let [status# (keyword status)
        excluded (not= :at-risk status#)]
    (-> form
        (assoc :status status#
               :excluded excluded)
        (update :review-dt #(some-> % LocalDateTime/parse)))))

(defmethod form/summary :araf-val-f-s1-evaluation/v2_0
  [{:keys [status text]}]
  (case status
    :at-risk "Patient at risk of reproductive harm"
    :pre-menarche "Not eligible for 'prevent' - not yet reached menarche"
    :permanent (str "Permanently excluded from 'prevent' - '" text "'")
    :other (str "Not eligible for 'prevent': '" text "'")))

(defmethod form/dehydrate :araf-val-f-s1-evaluation/v2_0
  [form]
  (-> form
      (update :review-dt #(some-> % str))
      (dissoc :excluded)))

;;
;;
;;

(defmethod form/spec :araf-val-f-s2-treatment-decision/v2_0 [_]
  (s/keys :req-un [::confirm]))

(defmethod form/summary :araf-val-f-s2-treatment-decision/v2_0
  [{:keys [confirm]}]
  (if confirm "Confirmed" "Not confirmed"))

;;
;;
;;

(defmethod form/spec :araf-val-f-s2-countersignature/v2_0 [_]
  (s/keys :req-un [::confirm ::eligible]))

(defmethod form/summary :araf-val-f-s2-countersignature/v2_0
  [{:keys [confirm eligible]}]
  (if (and confirm eligible) "Countersigned" "Not countersigned"))


;;
;;
;;

(defmethod form/spec :araf-val-f-s3-risks/v2_0 [_]
  (s/keys :req-un [::eligible ::discussed ::regular_review ::serious_harm
                   ::conditions ::pregnancy_test ::contraception ::referral
                   ::contact ::risks_stopping]))

(defmethod form/hydrate :araf-val-f-s3-risks/v2_0
  [{:keys [eligible contact contraception conditions discussed
           regular_review risks_stopping serious_harm referral
           pregnancy_test] :as form}]
  (assoc form
    :all (and eligible contact contraception conditions discussed
              regular_review risks_stopping serious_harm referral
              pregnancy_test)))

(defmethod form/summary :araf-val-f-s3-risks/v2_0 [{:keys [all]}]
  (if all "All risks explained" "Not all risks explained"))

(defmethod form/dehydrate :araf-val-f-s3-risks/v2_0 [form]
  (dissoc form :all))

;;
;;
;;


(defmethod form/spec :araf-val-f-s4-request-acknowledgement/v2_0 [_]
  (s/keys :req-un [::method]
          :opt-un [::araf-request-id]))

(defmethod form/summary :araf-val-f-s4-request-acknowledgement/v2_0
  [{:keys [method araf-request-id]}]
  (str "Requested by "
       (case method
         :web (str "PatientCare v4: araf using request id: " araf-request-id)
         :paper (str "Paper questionnaire posted"))))

(defmethod form/hydrate :araf-val-f-s4-request-acknowledgement/v2_0
  [form]
  (update form :method keyword))

(defmethod form/spec :araf-val-f-s4-acknowledgement/v2_0 [_]
  (s/keys :req-un [::acknowledged ::method]
          :opt-un [::araf-request-id]))

(defmethod form/summary :araf-val-f-s4-acknowledgement/v2_0
  [{:keys [acknowledged]}]
  (if acknowledged "Acknowledged" "Not acknowledged"))

(defmethod form/hydrate :araf-val-f-s4-acknowledgement/v2_0
  [form]
  (-> form
      (update :method keyword)
      (assoc :period (Period/ofYears 1))))

(defmethod form/dehydrate :araf-val-f-s4-acknowledgement/v2_0
  [form]
  (dissoc form :expires))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def programmes
  {:valproate-f
   {:forms #{:araf-val-f-s1-evaluation/v2_0
             :araf-val-f-s2-treatment-decision/v2_0
             :araf-val-f-s2-countersignature/v2_0
             :araf-val-f-s3-risks/v2_0
             :araf-val-f-s4-request-acknowledgement/v2_0
             :araf-val-f-s4-acknowledgement/v2_0}}})

(def all-araf-forms
  (->> (vals programmes) (mapcat :forms) (into #{})))

(defn forms-for-programme [programme]
  (get-in programmes [programme :forms]))

(defn form-before-date
  ([]
   (form-before-date nil))
  ([on-date]
   (fn [{:keys [date_time] :as form}]
     (LocalDateTime/.isBefore
       (or date_time (throw (ex-info "form missing date_time" form)))
       (or on-date (LocalDateTime/now))))))

(defn form-of-type
  "Predicate to match forms of type 'form-type'."
  [form-type]
  (fn [{:keys [form_type]}]
    (= form-type form_type)))

(defn most-recent
  "Return the most recent form in 'forms' of type 'form-type'."
  [form-type forms]
  (->> forms
       (filter (form-of-type form-type))
       (sort-by :date_time #(compare %2 %1))
       (first)))


(defmulti outcome
  "Given a 'programme', a sequence of ARAF forms for a single patient, and an options map, determine the patient's
  outcome on the [[java.time.LocalDateTime]] 'now' or now, by default."
  (fn [programme forms opts] programme))


;; outcome for the 'valproate female' ARAF programme
(defmethod outcome :valproate-f
  [_ forms {:keys [now]}]
  (let [now (or now (LocalDateTime/now))
        forms' (filter (form-before-date now) forms)
        {:keys [excluded status] :as s1-evaluation} (most-recent :araf-val-f-s1-evaluation/v2_0 forms')
        permanent (= :permanent status)
        s2-treatment (most-recent :araf-val-f-s2-treatment-decision/v2_0 forms')
        s2-countersig (most-recent :araf-val-f-s2-countersignature/v2_0 forms')
        s3-risks (most-recent :araf-val-f-s3-risks/v2_0 forms')
        {:keys [acknowledged date_time period]} (most-recent :araf-val-f-s4-acknowledgement/v2_0 forms')
        expiry (when (and date_time period) (LocalDateTime/.plus date_time period))
        active (when expiry (LocalDateTime/.isBefore now expiry))
        completed (boolean
                    (or excluded                            ;; 'completed' if excluded
                        (and s1-evaluation
                             (:confirm s2-treatment)
                             (:confirm s2-countersig)
                             (:eligible s2-countersig)
                             (:all s3-risks)
                             acknowledged
                             active)))]
    {:excluded  (when excluded (if (= :permanent status) :permanent :temporary))
     :completed completed
     :expiry    (when (and (not permanent) completed) (or expiry (:review-dt s1-evaluation)))
     :tasks     [[:s1 (some? s1-evaluation)]
                 [:s2 (boolean (and (:confirm s2-treatment) (:confirm s2-countersig) (:eligible s2-countersig)))]
                 [:s3 (boolean (:all s3-risks))]
                 [:s4 (boolean (and acknowledged active))]]}))

(defn gen-araf-form
  "Returns a generator of ARAF forms. "
  ([]
   (gen-araf-form {}))
  ([params]
   (gen/bind
     (gen/elements all-araf-forms)
     (fn [form-type]
       (form/gen-form (assoc-in params [:using :form_type] form-type))))))

(defn gen-araf-form-dt
  "Returns a generator of ARAF forms that include an 'encounter' date_time."
  ([]
   (gen-araf-form-dt {}))
  ([params]
   (gen/fmap
     (fn [[form date_time]]
       (assoc form :date_time date_time))
     (gen/tuple (gen-araf-form params)
                (or (when-let [dt (get-in params [:using :date_time])] (gen/return dt))
                    (s/gen ::form/local-date-time))))))

(comment
  (require '[clojure.spec.gen.alpha :as gen])
  (def forms (gen/sample (gen-araf-form-dt) 30))
  forms
  (->> (gen/sample (form/gen-form {:using {:form_type :araf-val-f-s1-evaluation/v2_0}}) 200)
       (filter (form-of-type :araf-val-f-s1-evaluation/v2_0)))
  (filter (form-of-type :araf-val-f-s4-acknowledgement/v2_0) forms)
  (most-recent :araf-val-f-s4-acknowledgement/v2_0 forms)
  (status :valproate-f forms {}))

