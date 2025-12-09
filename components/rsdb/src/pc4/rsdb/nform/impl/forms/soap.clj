(ns pc4.rsdb.nform.impl.forms.soap
  "SOAP (Subjective, Objective, Assessment, Plan) form implementation.
  A simple clinical note format with four text fields."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [pc4.rsdb.nform.impl.form :as form]
    [pc4.rsdb.html :as html]))


(defn truncate
  [s len]
  (if (> (count s) len) (str (subs s 0 (- len 3)) "...") s))

;; All fields are optional text (longText in the database)
(s/def ::subjective (s/nilable string?))
(s/def ::objective (s/nilable string?))
(s/def ::assessment (s/nilable string?))
(s/def ::plan (s/nilable string?))

(defmethod form/spec :soap/v1 [_]
  (s/keys :opt-un [::subjective ::objective ::assessment ::plan]))

(defmethod form/summary :soap/v1 [{:keys [subjective]}]
  (let [s (some-> subjective html/html->text str/split-lines first)
        s' (if (> (count s) 60) (str (subs s 0 57) "...") s)]
    (if (str/blank? s) "." s)))
