(ns pc4.rsdb.nform.impl.forms.ataxia
  "Ataxia-related forms: ICARS, SARA."
  (:require
    [clojure.spec.alpha :as s]
    [pc4.rsdb.nform.impl.form :as form]))

;; ICARS - placeholder form with no form-specific fields
(defmethod form/spec :icars/v1 [_]
  (s/keys))

(defmethod form/summary :icars/v1 [_]
  ".")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SARA - Scale for the Assessment and Rating of Ataxia (max 40 points)
;; 8 items: gait, stance, sitting, speech, finger-chase, nose-finger,
;; fast alternating hand movements, heel-shin slide
;; Items 5-8 are assessed bilaterally; mean values used for total
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def :sara/gait (s/int-in 0 9))
(s/def :sara/stance (s/int-in 0 7))
(s/def :sara/sitting (s/int-in 0 5))
(s/def :sara/speech (s/int-in 0 7))
(s/def :sara/finger_chase_left (s/int-in 0 5))
(s/def :sara/finger_chase_right (s/int-in 0 5))
(s/def :sara/nose_finger_left (s/int-in 0 5))
(s/def :sara/nose_finger_right (s/int-in 0 5))
(s/def :sara/fast_alternating_left (s/int-in 0 5))
(s/def :sara/fast_alternating_right (s/int-in 0 5))
(s/def :sara/heel_shin_left (s/int-in 0 5))
(s/def :sara/heel_shin_right (s/int-in 0 5))

(defmethod form/spec :sara/v1 [_]
  (s/keys :req-un [:sara/gait
                   :sara/stance
                   :sara/sitting
                   :sara/speech
                   :sara/finger_chase_left
                   :sara/finger_chase_right
                   :sara/nose_finger_left
                   :sara/nose_finger_right
                   :sara/fast_alternating_left
                   :sara/fast_alternating_right
                   :sara/heel_shin_left
                   :sara/heel_shin_right]))

(defmethod form/compute :sara/v1
  [{:keys [gait stance sitting speech
           finger_chase_left finger_chase_right
           nose_finger_left nose_finger_right
           fast_alternating_left fast_alternating_right
           heel_shin_left heel_shin_right]}]
  (let [all-scores [gait stance sitting speech
                    finger_chase_left finger_chase_right
                    nose_finger_left nose_finger_right
                    fast_alternating_left fast_alternating_right
                    heel_shin_left heel_shin_right]]
    (when (every? some? all-scores)
      (let [finger-chase (/ (+ finger_chase_left finger_chase_right) 2.0)
            nose-finger (/ (+ nose_finger_left nose_finger_right) 2.0)
            fast-alternating (/ (+ fast_alternating_left fast_alternating_right) 2.0)
            heel-shin (/ (+ heel_shin_left heel_shin_right) 2.0)
            total (+ gait stance sitting speech finger-chase nose-finger fast-alternating heel-shin)]
        {:total total
         :severity (cond
                     (<= total 5) :mild
                     (<= total 15) :moderate
                     :else :severe)}))))

(defmethod form/summary :sara/v1
  [form]
  (if-let [{:keys [total severity]} (form/compute form)]
    (str total "/40 (" (name severity) ")")
    "."))
