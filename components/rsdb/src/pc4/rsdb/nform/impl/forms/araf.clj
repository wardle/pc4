(ns pc4.rsdb.nform.impl.forms.araf
  "Specifications for 'Annual Risk Acknowledgement Forms (ARAF)."
  (:require
    [clojure.spec.alpha :as s]
    [pc4.rsdb.nform.impl.form :as form]))

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

(defmethod form/spec :araf-val-f-s2-countersignature/v2_0 [_]
  (s/keys :req-un [::confirm ::eligible]))

(defmethod form/summary :araf-val-f-s2-countersignature/v2_0
  [{:keys [confirm eligible]}]
  (if (and confirm eligible) "Countersigned" "Not countersigned"))

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