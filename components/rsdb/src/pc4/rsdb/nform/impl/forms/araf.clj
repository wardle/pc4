(ns pc4.rsdb.nform.impl.forms.araf
  "Specifications for 'Annual Risk Acknowledgement Forms (ARAF)."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [pc4.rsdb.nform.impl.form :as form])
  (:import (java.time LocalDateTime)))

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
(s/def ::text (s/nilable string?))
(s/def ::acknowledged boolean?)
(s/def ::source #{:web :paper})
(s/def ::araf-request-id string?)

;;
;; STEP 1 - status in 'prevent' programme :araf-val-f-s1-status/v1
;;
(defmethod form/spec :araf-val-f-s1-status/v2_0 [_]
  (s/keys :req-un [::status ::text]))

(defmethod form/hydrate :araf-val-f-s1-status/v2_0
  [{:keys [status] :as form}]
  (let [excluded (not= :at-risk status)]
    (-> form
        (update :status keyword)
        (assoc :excluded excluded))))

(defmethod form/summary :araf-val-f-s1-status/v2_0
  [{:keys [status text]}]
  (case status
    :at-risk "Patient at risk of reproductive harm"
    :pre-menarche "Not eligible for 'prevent' - not yet reached menarche"
    :permanent (str "Permanently excluded from 'prevent' - '" text "'")
    :other (str "Not eligible for 'prevent': '" text "'")))

(defmethod form/dehydrate :araf-val-f-s1-status/v2_0
  [form]
  (dissoc form :form :excluded))

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

(defmethod form/spec :araf-val-f-s4-acknowledgement/v2_0 [_]
  (s/keys :req-un [::acknowledged ::source]
          :opt-un [::araf-request-id]))

(defmethod form/summary :araf-val-f-s4-acknowledgement/v2_0
  [{:keys [acknowledged]}]
  (if acknowledged "Acknowledged" "Not acknowledged"))

(defmethod form/hydrate :araf-val-f-s4-acknowledgement/v2_0
  [form]
  (update form :source keyword))

(def all-araf-forms
  #{:araf-val-f-s1-status/v2_0
    :araf-val-f-s2-treatment-decision/v2_0
    :araf-val-f-s2-countersignature/v2_0
    :araf-val-f-s3-risks/v2_0
    :araf-val-f-s4-acknowledgement/v2_0})

(defn fetch-form-params
  [patient-pk]
  {:patient-fk patient-pk
   :form-types all-araf-forms})

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
  [form-type forms]
  (->> forms
       (filter (form-of-type form-type))
       (sort-by :date_time #(compare %2 %1))
       (first)))


(defn datetime-status
  [now date-time]
  (let [expired (LocalDateTime/.minusYears now 1)
        expiring (LocalDateTime/.minusMonths now 1)]
    (cond
      (or (nil? date-time) (LocalDateTime/.isBefore date-time expired))
      :expired
      (LocalDateTime/.isAfter date-time expiring)
      :expiring
      :else
      :active)))

(defn status
  "Given a sequence of ARAF forms for a given patient, determine the patient's
  status on the [[java.time.LocalDateTime]] 'now' or now, by default."
  ([forms]
   (status forms {:now (LocalDateTime/now)}))
  ([forms {:keys [now]}]
   (let [forms' (filter (form-before-date now) forms)
         {:keys [excluded] :as s1-status} (most-recent :araf-val-f-s1-status/v2_0 forms')
         s2-treatment (most-recent :araf-val-f-s2-treatment-decision/v2_0 forms')
         s2-countersig (most-recent :araf-val-f-s2-countersignature/v2_0 forms')
         s3-risks (most-recent :araf-val-f-s3-risks/v2_0 forms')
         s4-ack (most-recent :araf-val-f-s4-acknowledgement/v2_0 forms')
         at-risk (not excluded)]
     {:at-risk      (not excluded)
      :status       (:status s1-status)
      :excluded     (boolean excluded)
      :status-text  (:text s1-status)
      :treatment    (boolean (:confirm s2-treatment))
      :countersign  (boolean (when (and (:confirm s2-countersig) (:eligible s2-countersig))
                               {:signed true, :user (:user_fk s2-countersig)
                                :date   (:date_time s2-countersig)}))
      :acknowledged {:acknowledged (boolean (:acknowledged s4-ack))
                     :date         (some-> s4-ack :date_time (LocalDateTime/.toLocalDate))
                     :status       (cond
                                     (:acknowledged s4-ack) (datetime-status now (:date_time s4-ack))
                                     (nil? s4-ack) :pending
                                     (not (:acknowledged s4-ack)) :declined)}
      :forms        [s1-status s2-treatment s2-countersig s3-risks s4-ack]
      :tasks        (cond-> []
                      (nil? (:status s1-status))
                      (conj :status)
                      (and at-risk (not (:confirm s2-treatment)))
                      (conj :treatment)
                      (and at-risk (not (:confirm s2-countersig)))
                      (conj :countersignature)
                      (and at-risk (not (:all s3-risks)))
                      (conj :risks)
                      (not (:acknowledged s4-ack))
                      (conj :acknowledgement))})))


(defn gen-araf-form []
  (gen/bind
    (gen/elements all-araf-forms)
    (fn [form-type]
      (form/gen-form {:using {:form_type form-type}}))))

(defn gen-araf-form-with-date-time []
  (gen/fmap
    (fn [[form date_time]]
      (assoc form :date_time date_time))
    (gen/tuple (gen-araf-form) (s/gen ::form/local-date-time))))

(comment
  (require '[clojure.spec.gen.alpha :as gen])
  (def forms (gen/sample (gen-araf-form-with-date-time) 3))
  forms
  (filter (form-of-type :araf-val-f-s4-acknowledgement/v2_0) forms)
  (most-recent :araf-val-f-s4-acknowledgement/v2_0 forms)
  (status forms))

