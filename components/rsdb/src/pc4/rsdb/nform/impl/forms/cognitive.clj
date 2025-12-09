(ns pc4.rsdb.nform.impl.forms.cognitive
  "Cognitive assessment forms: MMSE, MOCA, ACE-R."
  (:require
    [clojure.spec.alpha :as s]
    [pc4.rsdb.nform.impl.form :as form]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MMSE - Mini-Mental State Examination (max 30 points)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::orientation_time (s/nilable (s/int-in 0 6)))
(s/def ::orientation_place (s/nilable (s/int-in 0 6)))
(s/def ::recall_immmediate (s/nilable (s/int-in 0 4)))
(s/def ::recall_delayed (s/nilable (s/int-in 0 4)))
(s/def ::serial_sevens (s/nilable (s/int-in 0 6)))
(s/def ::pen_watch (s/nilable (s/int-in 0 3)))
(s/def ::repeat_sentence (s/nilable (s/int-in 0 2)))
(s/def ::obey_instruction (s/nilable (s/int-in 0 2)))
(s/def ::three_stage_command (s/nilable (s/int-in 0 4)))
(s/def ::write_sentence (s/nilable (s/int-in 0 2)))
(s/def ::copy_drawing (s/nilable (s/int-in 0 2)))
(s/def ::recorded_total_score (s/nilable (s/int-in 0 31)))

(defmethod form/spec :mmse/v1 [_]
  (s/keys :req-un [::orientation_time
                   ::orientation_place
                   ::recall_immmediate
                   ::recall_delayed
                   ::serial_sevens
                   ::pen_watch
                   ::repeat_sentence
                   ::obey_instruction
                   ::three_stage_command
                   ::write_sentence
                   ::copy_drawing
                   ::recorded_total_score]))

(defmethod form/compute :mmse/v1
  [{:keys [orientation_time orientation_place recall_immmediate recall_delayed
           serial_sevens pen_watch repeat_sentence obey_instruction
           three_stage_command write_sentence copy_drawing]}]
  (let [scores [orientation_time orientation_place recall_immmediate recall_delayed
                serial_sevens pen_watch repeat_sentence obey_instruction
                three_stage_command write_sentence copy_drawing]]
    (when (every? some? scores)
      {:total (reduce + scores)})))

(defmethod form/summary :mmse/v1
  [{:keys [recorded_total_score] :as form}]
  (if-let [total (or recorded_total_score (:total (form/compute form)))]
    (str total "/30")
    "."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MOCA - Montreal Cognitive Assessment (max 30 points)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::alternating_trail (s/nilable (s/int-in 0 2)))
(s/def ::draw_clock_contour (s/nilable (s/int-in 0 2)))
(s/def ::draw_clock_numbers (s/nilable (s/int-in 0 2)))
(s/def ::draw_clock_hands (s/nilable (s/int-in 0 2)))
(s/def ::naming (s/nilable (s/int-in 0 4)))
(s/def ::list_digits (s/nilable (s/int-in 0 3)))
(s/def ::list_letters (s/nilable (s/int-in 0 2)))
(s/def ::correct_subtractions_serial7s (s/nilable (s/int-in 0 6)))
(s/def ::repeat_sentences (s/nilable (s/int-in 0 3)))
(s/def ::verbal_fluency (s/nilable (s/int-in 0 30)))
(s/def ::abstraction (s/nilable (s/int-in 0 3)))
(s/def ::delayed_recall (s/nilable (s/int-in 0 6)))
(s/def ::delayed_recall_including_cues (s/nilable (s/int-in 0 6)))
(s/def ::immediate_recall (s/nilable (s/int-in 0 6)))
(s/def ::orientation (s/nilable (s/int-in 0 7)))
(s/def ::score (s/nilable (s/int-in 0 31)))
(s/def ::years_in_formal_education (s/nilable (s/int-in 0 100)))

(defmethod form/spec :moca/v1 [_]
  (s/keys :req-un [::alternating_trail
                   ::copy_drawing
                   ::draw_clock_contour
                   ::draw_clock_numbers
                   ::draw_clock_hands
                   ::naming
                   ::list_digits
                   ::list_letters
                   ::correct_subtractions_serial7s
                   ::repeat_sentences
                   ::verbal_fluency
                   ::abstraction
                   ::delayed_recall
                   ::delayed_recall_including_cues
                   ::immediate_recall
                   ::orientation
                   ::score
                   ::years_in_formal_education]))

(defn- moca-serial7s-score
  [correct]
  (cond
    (nil? correct) nil
    (>= correct 4) 3
    (>= correct 2) 2
    (= correct 1) 1
    :else 0))

(defn- moca-verbal-fluency-score
  [words]
  (when words (if (>= words 11) 1 0)))

(defn- moca-education-score
  [years]
  (when years (if (<= years 12) 1 0)))

(defmethod form/compute :moca/v1
  [{:keys [alternating_trail copy_drawing draw_clock_contour draw_clock_numbers
           draw_clock_hands naming list_digits list_letters correct_subtractions_serial7s
           repeat_sentences verbal_fluency abstraction delayed_recall orientation
           years_in_formal_education]}]
  (let [scores [alternating_trail copy_drawing draw_clock_contour draw_clock_numbers
                draw_clock_hands naming list_digits list_letters
                (moca-serial7s-score correct_subtractions_serial7s)
                repeat_sentences (moca-verbal-fluency-score verbal_fluency)
                abstraction delayed_recall orientation
                (moca-education-score years_in_formal_education)]]
    (when (every? some? scores)
      {:total (reduce + scores)})))

(defmethod form/summary :moca/v1
  [{:keys [score] :as form}]
  (if-let [total (or score (:total (form/compute form)))]
    (str total " (" (if (>= total 26) "Normal" "Abnormal") ")")
    "."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACE-R - Addenbrooke's Cognitive Examination Revised (placeholder form)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod form/spec :ace-r/v1 [_]
  (s/keys))

(defmethod form/summary :ace-r/v1 [_]
  ".")
