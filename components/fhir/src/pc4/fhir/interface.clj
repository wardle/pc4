(ns pc4.fhir.interface
  "Specifications and utility functions for pc4 FHIR data structures. 
  Automated conversion from the FHIR specifications would be possible, but
  the FHIR standard is too open and needs to be constrained in any particular
  implementation. As such, these specifications tighten the more broad 
  general purpose specifications for use within pc4."
  (:require [clojure.spec.alpha :as s]
            [clojure.core :as c]
            [clojure.string :as str])
  (:import (java.time LocalDate LocalDateTime)))

(def administrative-gender #{"male" "female" "other" "unknown"})
(def address-use #{"home" "work" "temp" "old" "billing"})
(def contact-point-system #{"phone" "fax" "email" "pager" "url" "sms" "other"})
(def contact-point-use #{"home" "work" "temp" "old" "mobile"})
(def human-name-use #{"anonymous" "maiden" "nickname" "official" "old" "temp" "usual"})
(def identifier-use #{"usual" "official" "temp" "secondary" "old"})

(s/def :org.hl7.fhir.Attachment/contentType string?)
(s/def :org.hl7.fhir.Attachment/data string?)
(s/def :org.hl7.fhir.Attachment/url string?)
(s/def :org.hl7.fhir.Attachment/language string?)
(s/def :org.hl7.fhir.Attachment/size pos-int?)
(s/def :org.hl7.fhir.Attachment/hash string?)
(s/def :org.hl7.fhir.Attachment/title string?)
(s/def :org.hl7.fhir.Attachment/width pos-int?)
(s/def :org.hl7.fhir.Attachment/height pos-int?)
(s/def :org.hl7.fhir/Attachment
  (s/keys :req [:org.hl7.fhir.Attachment/contentType
                (or :org.hl7.fhir.Attachment/data :org.hl7.fhir.Attachment/url)]
          :opt [:org.hl7.fhir.Attachment/language
                :org.hl7.fhir.Attachment/size
                :org.hl7.fhir.Attachment/hash
                :org.hl7.fhir.Attachment/title
                :org.hl7.fhir.Attachment/width
                :org.hl7.fhir.Attachment/height]))

(s/def :org.hl7.fhir.Coding/system string?)
(s/def :org.hl7.fhir.Coding/code string?)
(s/def :org.hl7.fhir.Coding/display string?)
(s/def :org.hl7.fhir.Coding/version string?)
(s/def :org.hl7.fhir.Coding/userSelected string?)
(s/def :org.hl7.fhir/Coding
  (s/keys :req [:org.hl7.fhir.Coding/system
                :org.hl7.fhir.Coding/code]
          :opt [:org.hl7.fhir.Coding/version
                :org.hl7.fhir.Coding/display
                :org.hl7.fhir.Coding/userSelected]))

(s/def :org.hl7.fhir.CodeableConcept/coding (s/coll-of :org.hl7.fhir/Coding))
(s/def :org.hl7.fhir.CodeableConcept/text string?)
(s/def :org.hl7.fhir/CodeableConcept
  (s/keys :req [:org.hl7.fhir.CodeableConcept/coding]
          :opt [:org.hl7.fhir.CodeableConcept/text]))

(s/def :org.hl7.fhir.Period/start (s/nilable #(instance? LocalDate %)))
(s/def :org.hl7.fhir.Period/end (s/nilable #(instance? LocalDate %)))
(s/def :org.hl7.fhir/Period
  (s/keys :opt [:org.hl7.fhir.Period/start :org.hl7.fhir.Period/end]))

(s/def :org.hl7.fhir.Identifier/system string?)
(s/def :org.hl7.fhir.Identifier/value string?)
(s/def :org.hl7.fhir.Identifier/use identifier-use)
(s/def :org.hl7.fhir.Identifier/period :org.hl7.fhir/Period)
(s/def :org.hl7.fhir/Identifier
  (s/keys :req [:org.hl7.fhir.Identifier/system
                :org.hl7.fhir.Identifier/value]
          :opt [:org.hl7.fhir.Identifier/period
                :org.hl7.fhir.Identifier/use]))

(s/def :org.hl7.fhir.HumanName/family string?)
(s/def :org.hl7.fhir.HumanName/given (s/coll-of string?))
(s/def :org.hl7.fhir.HumanName/prefix (s/coll-of string?))
(s/def :org.hl7.fhir.HumanName/suffix (s/coll-of string?))
(s/def :org.hl7.fhir.HumanName/use (s/nilable human-name-use))
(s/def :org.hl7.fhir/HumanName
  (s/keys :req [:org.hl7.fhir.HumanName/use
                :org.hl7.fhir.HumanName/family
                :org.hl7.fhir.HumanName/given
                :org.hl7.fhir.HumanName/prefix]
          :opt [:org.hl7.fhir.HumanName/suffix]))

(s/def :org.hl7.fhir.Address/use address-use)
(s/def :org.hl7.fhir.Address/line (s/coll-of string?))
(s/def :org.hl7.fhir.Address/city (s/nilable string?))
(s/def :org.hl7.fhir.Address/district (s/nilable string?))
(s/def :org.hl7.fhir.Address/country (s/nilable string?))
(s/def :org.hl7.fhir.Address/postalCode (s/nilable string?))
(s/def :org.hl7.fhir.Address/period :org.hl7.fhir/Period)
(s/def :org.hl7.fhir/Address
  (s/keys :req [:org.hl7.fhir.Address/line
                :org.hl7.fhir.Address/postalCode]
          :opt [:org.hl7.fhir.Address/city
                :org.hl7.fhir.Address/district
                :org.hl7.fhir.Address/country
                :org.hl7.fhir.Address/use
                :org.hl7.fhir.Address/period]))

(s/def :org.hl7.fhir.ContactPoint/value string?)
(s/def :org.hl7.fhir.ContactPoint/system contact-point-system)
(s/def :org.hl7.fhir.ContactPoint/use contact-point-use)
(s/def :org.hl7.fhir.ContactPoint/rank pos-int?)
(s/def :org.hl7.fhir.ContactPoint/period :org.hl7.fhir/Period)
(s/def :org.hl7.fhir/ContactPoint
  (s/keys :req [:org.hl7.fhir.ContactPoint/use
                :org.hl7.fhir.ContactPoint/system
                :org.hl7.fhir.ContactPoint/value]
          :opt [:org.hl7.fhir.ContactPoint/rank
                :org.hl7.fhir.ContactPoint/period]))

(s/def :org.hl7.fhir.Patient/name (s/coll-of :org.hl7.fhir/HumanName))
(s/def :org.hl7.fhir.Patient/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.Patient/active boolean?)
(s/def :org.hl7.fhir.Patient/birthDate (s/or :date #(instance? LocalDate %) :date-time #(instance? LocalDateTime %)))
(s/def :org.hl7.fhir.Patient/deceased (s/nilable (s/or :boolean boolean? :date #(instance? LocalDate %) :date-time #(instance? LocalDateTime %))))
(s/def :org.hl7.fhir.Patient/gender administrative-gender)
(s/def :org.hl7.fhir.Patient/address (s/coll-of :org.hl7.fhir/Address))
(s/def :org.hl7.fhir.Patient/telecom (s/coll-of :org.hl7.fhir/ContactPoint))
(s/def :org.hl7.fhir/Patient
  (s/keys :req [:org.hl7.fhir.Patient/identifier
                :org.hl7.fhir.Patient/active
                :org.hl7.fhir.Patient/name
                :org.hl7.fhir.Patient/birthDate
                :org.hl7.fhir.Patient/gender
                :org.hl7.fhir.Patient/address
                :org.hl7.fhir.Patient/telecom
                :org.hl7.fhir.Patient/generalPractitioner]
          :opt [:org.hl7.fhir.Patient/deceased]))

(s/def :org.hl7.fhir.Practitioner/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.Practitioner/active boolean?)
(s/def :org.hl7.fhir.Practitioner/name (s/coll-of :org.hl7.fhir/HumanName))
(s/def :org.hl7.fhir.Practitioner/telecom (s/coll-of :org.hl7.fhir/ContactPoint))
(s/def :org.hl7.fhir.Practitioner/gender administrative-gender)
(s/def :org.hl7.fhir.Practitioner/photo (s/coll-of :org.hl7.fhir/Attachment))
(s/def :org.hl7.fhir/Practitioner
  (s/keys :req [:org.hl7.fhir.Practitioner/identifier
                :org.hl7.fhir.Practitioner/active
                :org.hl7.fhir.Practitioner/name
                :org.hl7.fhir.Practitioner/telecom
                :org.hl7.fhir.Practitioner/gender
                :org.hl7.fhir.Practitioner/photo]))

(s/def :org.hl7.fhir.PractitionerRole/active boolean?)
(s/def :org.hl7.fhir.PractitionerRole/identifier (s/coll-of :org.hl7.fhir/Identifier))
(s/def :org.hl7.fhir.PractitionerRole/code (s/coll-of :org.hl7.fhir/CodeableConcept))
(s/def :org.hl7.fhir.PractitionerRole/specialty (s/coll-of :org.hl7.fhir/CodeableCoding))
(s/def :org.hl7.fhir/PractitionerRole
  (s/keys :req [:org.hl7.fhir.PractitionerRole/code]
          :opt [:org.hl7.fhir.PractitionerRole/identifier
                :org.hl7.fhir.PractitionerRole/period
                :org.hl7.fhir.PractitionerRole/active
                :org.hl7.fhir.PractitionerRole/specialty]))

(defn gp-surgery-identifiers
  "Given a FHIR patient, return identifiers for the GP surgeries."
  [pt]
  (->> (:org.hl7.fhir.Patient/generalPractitioner pt)
       (filter #(= "https://fhir.nhs.uk/Id/ods-organization-code"
                   (get-in % [:org.hl7.fhir.Reference/identifier :org.hl7.fhir.Identifier/system])))
       (map :org.hl7.fhir.Reference/identifier)))

(defn general-practitioner-identifiers
  "Given a FHIR patient, return identifiers for the general practitioners"
  [pt]
  (->> (:org.hl7.fhir.Patient/generalPractitioner pt)
       (filter #(= "https://fhir.hl7.org.uk/Id/gmp-number"
                   (get-in % [:org.hl7.fhir.Reference/identifier :org.hl7.fhir.Identifier/system])))
       (map :org.hl7.fhir.Reference/identifier)))

(defn match-identifier
  "Returns a predicate to match a FHIR identifier based on information given. 
  For example,
  ```
  (match-identifier :usage \"official\")
  ```
  will return a predicate that matches only identifiers with use 'official'."
  [& {:keys [system value use]}]
  (fn [{system# :org.hl7.fhir.Identifier/system
        value#  :org.hl7.fhir.Identifier/value
        use#    :org.hl7.fhir.Identifier/use}]
    (and
      (or (nil? system) (= system system#))
      (or (nil? value) (= value value#))
      (or (nil? use) (= use use#)))))

(defn match-human-name
  [& {:keys [use family]}]
  (fn [{use#    :org.hl7.fhir.HumanName/use
        family# :org.hl7.fhir.HumanName/family}]
    (and
      (= use use#)
      (or (nil? family) (.equalsIgnoreCase family family#)))))

(defn best-match*
  "Determine the 'best' match based on the sequence of predicates specified.
  Each predicate will be tried in turn so predicates should be ordered in
  terms of specificity. Returns nil if no predicates match.

  Examples:
  ```
  (best-match* [(match-identifier :use \"official\")] ids)
  (best-match* [pred1 pred2] coll)
  ```"
  [preds coll]
  (some (fn [pred]
          (when-let [matched (seq (filter pred coll))]
            (first matched)))
        preds))

(defn best-match
  "Determine the 'best' match based on the sequence of predicates specified.
  Each predicate will be tried in turn so predicates should be ordered in
  terms of specificity. Returns first item in coll if no predicates match.

  Examples:
  ```
  (best-match [(match-identifier :use \"official\")] ids)
  (best-match [pred1 pred2] coll)
  ```"
  [preds coll]
  (or (best-match* preds coll)
      (first coll)))

(defn by-period
  "A Comparator of FHIR Period to return in descending order. 
  A 'nil' start is considered as the distant past, and a 'nil' end is 
  considered as the distant future."
  [{s1 :org.hl7.fhir.Period/start
    e1 :org.hl7.fhir.Period/end}
   {s2 :org.hl7.fhir.Period/start
    e2 :org.hl7.fhir.Period/end}]
  (compare [(or e2 LocalDate/MAX) (or s2 LocalDate/MIN)]
           [(or e1 LocalDate/MAX) (or s1 LocalDate/MIN)]))

(defn by-periods
  "Create an indirect comparator that sorts according to
  period where 'k' is the key for each item to return a 
  period."
  [k]
  (fn [a b] (by-period (k a) (k b))))

(defn valid-period?
  "Is the FHIR Period valid? A Period is valid on a date if the date
  is equal or after the start date, and if the date is before the
  end date. If either start or end are nil, they are assumed to be
  open limits so that for the purposes of comparison, the start is 
  in the distant past, and the end in the distant future."
  ([period]
   (valid-period? period (LocalDate/now)))
  ([{:org.hl7.fhir.Period/keys [start end]} ^LocalDate on-date]
   (let [start# (or start LocalDate/MIN)
         end# (or end LocalDate/MAX)]
     (and
       (or (.isAfter on-date start#) (.isEqual on-date start#))
       (.isBefore on-date end#)))))

(defn valid-by-period?
  "Test the validity of structure 'x' by testing the validity of the 
  FHIR Period returned from 'x' using function (or keyword) 'k'."
  ([k] (fn [x] (valid-period? (k x))))
  ([k on-date] (fn [x] (valid-period? (k x) on-date))))

(defn valid-identifiers
  "Return an ordered sequence of valid FHIR identifiers matching the system specified. 
  Ordering will be in most recent first."
  ([system identifiers]
   (valid-identifiers system (LocalDate/now) identifiers))
  ([system on-date identifiers]
   (->> identifiers
        (filter #(= system (:org.hl7.fhir.Identifier/system %))) ;; limit by system
        (filter (valid-by-period? :org.hl7.fhir.Identifier/period on-date))
        (sort (by-periods :org.hl7.fhir.Identifier/period))))) ;; limit to valid identifiers

(defn best-identifier
  ([system identifiers]
   (best-identifier system (LocalDate/now) identifiers))
  ([system on-date identifiers]
   (best-match
     [(match-identifier :use "official")
      (match-identifier :use "usual")
      (match-identifier :use "secondary")]
     (valid-identifiers system on-date identifiers))))

(defn best-human-name
  "Returns the 'best' human name from a collection. They are filtered to remove
  invalid/outdated names."
  ([human-names]
   (best-human-name (LocalDate/now) human-names))
  ([on-date human-names]
   (->> human-names
        (filter (valid-by-period? :org.hl7.fhir.HumanName/period on-date))
        (sort (by-periods :org.hl7.fhir.HumanName/period))
        (best-match
          [(match-human-name :use "official")
           (match-human-name :use "usual")
           (match-human-name :use "old")
           (match-human-name :use "maiden")])))
  ([on-date name-use human-names]
   (->> human-names
        (filter (valid-by-period? :org.hl7.fhir.HumanName/period on-date))
        (sort (by-periods :org.hl7.fhir.HumanName/period))
        (best-match [(match-human-name :use name-use)]))))

(defn human-name-text
  "Returns a human name with :org.hl7.fhir.HumanName/text added if not present or blank.

  Options:
  - :format - :isb1506 (default, 'SURNAME, first names (Title)') or :natural ('Mr John Smith')"
  ([human-name]
   (human-name-text human-name {}))
  ([{:org.hl7.fhir.HumanName/keys [family given prefix] :as human-name}
    {:keys [format] :or {format :isb1506}}]
   (update human-name :org.hl7.fhir.HumanName/text
           (fn [nm]
             (if (str/blank? nm)
               (str/trim
                 (case format
                   :isb1506
                   (str (str/upper-case (or family ""))
                        ", " (str/join " " given)
                        (when (seq prefix)
                          (str " (" (str/join " " (map str/capitalize prefix)) ")")))
                   :natural
                   (str (str/join " " prefix) " "
                        (str/join " " given) " "
                        family)))
               nm)))))

(defn match-address
  "Returns a predicate function that matches an address by use."
  [& {:keys [use]}]
  (fn [{use# :org.hl7.fhir.Address/use}]
    (= use use#)))

(defn- temp-address-within-3-months?
  "Check if address is temporary and within 3 months of its business-effective from date."
  [on-date addr]
  (and (= "temp" (:org.hl7.fhir.Address/use addr))
       (when-let [start (get-in addr [:org.hl7.fhir.Address/period :org.hl7.fhir.Period/start])]
         (.isBefore (.minusMonths on-date 3) start))))

(defn best-address
  "Returns the 'best' address from a collection.

  Options:
  - :on - LocalDate to check validity (default: today)
  - :priority - keyword for predefined ruleset or sequence of predicates
  - :priority-fn - function taking (valid-addresses on-date) for full control

  Predefined rulesets:
  - :home-first (default) - home > work > temp > old > billing
  - :home-only - only home addresses
  - :correspondence - NHS Notify letter rules (temp within 3mo > billing > home)

  Examples:
  ```
  (best-address {} addresses)
  (best-address {:on (LocalDate/of 2024 1 1)} addresses)
  (best-address {:priority :home-only} addresses)
  (best-address {:priority :correspondence} addresses)
  (best-address {:priority [(match-address :use \"work\")
                            (match-address :use \"home\")]} addresses)
  (best-address {:priority-fn my-custom-fn} addresses)
  ```"
  ([addresses]
   (best-address {} addresses))
  ([{:keys [on priority priority-fn]
     :or {on (LocalDate/now)
          priority :home-first}} addresses]
   (let [valid-addrs (->> addresses
                          (filter (valid-by-period? :org.hl7.fhir.Address/period on))
                          (sort (by-periods :org.hl7.fhir.Address/period)))]
     (cond
       priority-fn
       (priority-fn valid-addrs on)

       (= priority :home-first)
       (best-match
         [(match-address :use "home")
          (match-address :use "work")
          (match-address :use "temp")
          (match-address :use "old")
          (match-address :use "billing")]
         valid-addrs)

       (= priority :home-only)
       (first (filter (match-address :use "home") valid-addrs))

       (= priority :correspondence)
       (best-match*
         [#(temp-address-within-3-months? on %)
          (match-address :use "billing")
          (match-address :use "home")]
         valid-addrs)

       (sequential? priority)
       (best-match* priority valid-addrs)

       :else
       (throw (ex-info "Invalid priority option" {:priority priority}))))))

(defn by-contact-point-rank-and-period
  [{rank1   :org.hl7.fhir.ContactPoint/rank
    period1 :org.hl7.fhir.ContactPoint/period}
   {rank2   :org.hl7.fhir.ContactPoint/rank
    period2 :org.hl7.fhir.ContactPoint/period}]
  (compare [rank1
            (or (:org.hl7.fhir.Period/end period2) LocalDate/MAX)
            (or (:org.hl7.fhir.Period/start period2) LocalDate/MIN)]
           [rank2
            (or (:org.hl7.fhir.Period/end period1) LocalDate/MAX)
            (or (:org.hl7.fhir.Period/start period1) LocalDate/MIN)]))

(defn valid-contact-points
  "Returns an ordered sequence of FHIR ContactPoints based on nested FHIR Period
  data structures and the ContactPoint rank."
  ([contact-points]
   (valid-contact-points (LocalDate/now) contact-points))
  ([on-date contact-points]
   (->> contact-points
        (filter (valid-by-period? :org.hl7.fhir.ContactPoint/period on-date))
        (sort by-contact-point-rank-and-period))))

(defn best-contact-point
  ([system contact-points]
   (best-contact-point system (LocalDate/now) contact-points))
  ([system on-date contact-points]
   (->> contact-points
        (filter #(= system (:org.hl7.fhir.ContactPoint/system %)))
        (filter (valid-by-period? :org.hl7.fhir.ContactPoint/period on-date))
        (sort by-contact-point-rank-and-period)
        first)))


