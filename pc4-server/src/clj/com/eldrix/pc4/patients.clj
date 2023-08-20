(ns com.eldrix.pc4.patients
  (:require
    [clojure.tools.logging.readable :as log]
    [com.eldrix.concierge.wales.cav-pms :as cavpms]
    [com.eldrix.concierge.wales.empi :as empi]
    [com.eldrix.nhsnumber :as nnn]
    [com.eldrix.pc4.dates :as dates]
    [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [clojure.string :as str])
  (:import (java.time LocalDate)))

(defn date-in-range?
  "Is the date in the range specified, or is the range 'current'?"
  ([^LocalDate from ^LocalDate to]
   (date-in-range? from to nil))
  ([^LocalDate from ^LocalDate to ^LocalDate date]
   (let [date' (or date (LocalDate/now))]
     (and (or (nil? from) (not (.isBefore date' from)))
          (or (nil? to) (.isBefore date' to))))))

(defn record->map
  "Turn a record into a namespaced map."
  [n r]
  (reduce-kv (fn [m k v] (assoc m (keyword n (name k)) v)) {} r))

(defn fake-cav-patients []
  {"A999997" {:HOSPITAL_ID "A999997"
              :NHS_NUMBER  "2222222222"
              :LAST_NAME   "Duck"
              :FIRST_NAMES "Donald"
              :DATE_BIRTH  (LocalDate/of 1984 12 3)
              :DATE_DEATH  (LocalDate/of 1992 1 1)}
   "A999998" {:HOSPITAL_ID      "A999998"
              :NHS_NUMBER       "1111111111"
              :LAST_NAME        "Dummy"
              :FIRST_NAMES      "Albert"
              :TITLE            "Mr"
              :SEX,             "M"
              :DATE_BIRTH       (LocalDate/of 1970 1 1)
              :DATE_DEATH       nil
              :HOME_PHONE_NO    "0292074747"
              :WORK_PHONE_NO    "02920747747",
              :COUNTRY_OF_BIRTH "UK" :ETHNIC_ORIGIN "???"
              :MARITAL_STATUS   "???" :OCCUPATION "???"
              :PLACE_OF_BIRTH   "" :PLACE_OF_DEATH ""
              :GP_ID            "" :GPPR_ID ""
              :ADDRESSES        [{:ADDRESS1  "University Hospital Wales"
                                  :ADDRESS2  "Heath Park"
                                  :ADDRESS3  "Cardiff"
                                  :ADDRESS4  ""
                                  :POSTCODE  "CF14 4XW"
                                  :DATE_FROM (LocalDate/of 2020 01 01) :DATE_TO nil}
                                 {:ADDRESS1  "Llandough Hospital"
                                  :ADDRESS2  ""
                                  :ADDRESS3  ""
                                  :ADDRESS4  ""
                                  :POSTCODE  "CF14 4XW"
                                  :DATE_FROM (LocalDate/of 2019 01 01) :DATE_TO nil}]}})

(defn add-namespace-cav-patient [pt]
  (when pt (assoc (record->map "wales.nhs.cavuhb.Patient" pt)
             :wales.nhs.cavuhb.Patient/ADDRESSES (map #(record->map "wales.nhs.cavuhb.Address" %) (:ADDRESSES pt)))))

(pco/defmutation fetch-cav-patient
  "Fetch patient details from the Cardiff and Vale PAS.
  Why is this a mutation? Because it is an operation? It should log access etc.
  But it could simply be a resolver too.

  When I used as a resolver, I wanted a way to perhaps change resolution
  based on other context - e.g. supplement CAV data with eMPI data. It
  felt better as a procedure to call, rather than a simple fetch of data.
  This *will* change but it serves a purpose to get patient data available
  in a demonstrator client. The nice property of defmutation is that they are
  named, so this could be deprecated very easily or co-exist with a more
  'proper' version that comes later."
  ;;TODO: think more about this
  [{config :wales.nhs.cavuhb/pms} {:keys [system value]}]
  {::pco/op-name 'wales.nhs.cavuhb/fetch-patient
   ::pco/output  [:wales.nhs.cavuhb.Patient/HOSPITAL_ID
                  :wales.nhs.cavuhb.Patient/NHS_NUMBER
                  :wales.nhs.cavuhb.Patient/LAST_NAME
                  :wales.nhs.cavuhb.Patient/FIRST_FORENAME
                  :wales.nhs.cavuhb.Patient/SECOND_FORENAME
                  :wales.nhs.cavuhb.Patient/OTHER_FORENAMES
                  :wales.nhs.cavuhb.Patient/DATE_BIRTH
                  :wales.nhs.cavuhb.Patient/DATE_DEATH
                  :wales.nhs.cavuhb.Patient/TITLE
                  :wales.nhs.cavuhb.Patient/GPPR_ID
                  :wales.nhs.cavuhb.Patient/GP_ID]}
  (log/info "cavuhb fetch patient: " {:config config :system system :value value})
  (cond
    ;; if there is no active configuration, run in development mode
    (empty? config)
    (do
      (log/info "generating fake patient for cavuhb: " system value)
      (add-namespace-cav-patient (get (fake-cav-patients) (str/upper-case value))))

    (or (= system :wales.nhs.cavuhb.id/pas-identifier) (= system "http://fhir.cavuhb.nhs.wales/Id/pas-identifier"))
    (add-namespace-cav-patient (cavpms/fetch-patient-by-crn config value))

    (or (= system :uk.nhs.id/nhs-number) (= system "https://fhir.nhs.uk/Id/nhs-number"))
    (add-namespace-cav-patient (cavpms/fetch-patient-by-nnn config value))))

(pco/defresolver resolve-cav-patient
  [{config :wales.nhs.cavuhb/pms} {crn :wales.nhs.cavuhb.Patient/HOSPITAL_ID}]
  {::pco/input  [:wales.nhs.cavuhb.Patient/HOSPITAL_ID]
   ::pco/output [:wales.nhs.cavuhb.Patient/HOSPITAL_ID
                 :wales.nhs.cavuhb.Patient/NHS_NUMBER
                 :wales.nhs.cavuhb.Patient/LAST_NAME
                 :wales.nhs.cavuhb.Patient/FIRST_FORENAME
                 :wales.nhs.cavuhb.Patient/SECOND_FORENAME
                 :wales.nhs.cavuhb.Patient/OTHER_FORENAMES
                 :wales.nhs.cavuhb.Patient/DATE_BIRTH
                 :wales.nhs.cavuhb.Patient/DATE_DEATH
                 :wales.nhs.cavuhb.Patient/TITLE
                 :wales.nhs.cavuhb.Patient/GPPR_ID
                 :wales.nhs.cavuhb.Patient/GP_ID]}
  (log/info "cavuhb resolve patient: " {:config config :crn crn})
  (when (not-empty config)
    (add-namespace-cav-patient (cavpms/fetch-patient-by-crn config crn))))

(pco/defresolver cav->admissions
  [{config :wales.nhs.cavuhb/pms} {crn :wales.nhs.cavuhb.Patient/HOSPITAL_ID}]
  {::pco/input  [:wales.nhs.cavuhb.Patient/HOSPITAL_ID]
   ::pco/output [{:wales.nhs.cavuhb.Patient/ADMISSIONS [:wales.nhs.cavuhb.Admission/DATE_FROM
                                                        :wales.nhs.cavuhb.Admission/DATE_TO]}]}
  (when config
    {:wales.nhs.cavuhb.Patient/ADMISSIONS
     (->> (cavpms/fetch-admissions config :crn crn)
          (mapv #(hash-map :wales.nhs.cavuhb.Admission/DATE_FROM (:DATE_ADM %)
                           :wales.nhs.cavuhb.Admission/DATE_TO (:DATE_DISCH %))))}))


(pco/defresolver resolve-cav-patient-first-names
  [{:wales.nhs.cavuhb.Patient/keys [FIRST_FORENAME SECOND_FORENAME OTHER_FORENAMES]}]
  {::pco/input  [:wales.nhs.cavuhb.Patient/FIRST_FORENAME
                 (pco/? :wales.nhs.cavuhb.Patient/SECOND_FORENAME)
                 (pco/? :wales.nhs.cavuhb.Patient/OTHER_FORENAMES)]
   ::pco/output [:wales.nhs.cavuhb.Patient/FIRST_NAMES]}
  {:wales.nhs.cavuhb.Patient/FIRST_NAMES
   (str/join " " (remove str/blank? [FIRST_FORENAME SECOND_FORENAME OTHER_FORENAMES]))})

(pco/defresolver cav->fhir-identifiers
  [{:wales.nhs.cavuhb.Patient/keys [NHS_NUMBER HOSPITAL_ID]}]
  {::pco/input  [:wales.nhs.cavuhb.Patient/HOSPITAL_ID
                 (pco/? :wales.nhs.cavuhb.Patient/NHS_NUMBER)]
   ::pco/output [{:org.hl7.fhir.Patient/identifier [:org.hl7.fhir.Identifier/system :org.hl7.fhir.Identifier/value]}]}
  {:org.hl7.fhir.Patient/identifier
   (cond-> [{:org.hl7.fhir.Identifier/system :wales.nhs.cavuhb.id/pas-identifier
             :org.hl7.fhir.Identifier/value  HOSPITAL_ID}]
           (not (str/blank? NHS_NUMBER))
           (conj {:org.hl7.fhir.Identifier/system :uk.nhs.id/nhs-number
                  :org.hl7.fhir.Identifier/value  NHS_NUMBER}))})

(pco/defresolver cav->fhir-names
  [{:wales.nhs.cavuhb.Patient/keys [TITLE FIRST_NAMES LAST_NAME]}]
  {::pco/output [{:org.hl7.fhir.Patient/name [:org.hl7.fhir.HumanName/prefix
                                              :org.hl7.fhir.HumanName/given
                                              :org.hl7.fhir.HumanName/family
                                              :org.hl7.fhir.HumanName/use]}]}
  {:org.hl7.fhir.Patient/name [{:org.hl7.fhir.HumanName/prefix TITLE
                                :org.hl7.fhir.HumanName/given  (str/split FIRST_NAMES #" ")
                                :org.hl7.fhir.HumanName/family LAST_NAME}]})

(pco/defresolver cav->fhir-gender
  [{:wales.nhs.cavuhb.Patient/keys [SEX]}]
  {::pco/output [:org.hl7.fhir.Patient/gender]}
  {:org.hl7.fhir.Patient/gender
   (case SEX "M" :org.hl7.fhir.administrative-gender/male
             "F" :org.hl7.fhir.administrative-gender/female
             :org.hl7.fhir.administrative-gender/unknown)})

(pco/defresolver cav->fhir-deceased
  [{DATE_DEATH :wales.nhs.cavuhb.Patient/DATE_DEATH}]
  {:org.hl7.fhir.Patient/deceased (boolean DATE_DEATH)})

(defn make-cav-fhir-address
  [{:wales.nhs.cavuhb.Address/keys [ADDRESS1 ADDRESS2 ADDRESS3 ADDRESS4 POSTCODE DATE_FROM DATE_TO]}]
  {:org.hl7.fhir.Address/use        :org.hl7.fhir.address-use/home
   :org.hl7.fhir.Address/type       :org.hl7.fhir.address-type/both
   :org.hl7.fhir.Address/text       (str/join ", " (remove str/blank? [ADDRESS1 ADDRESS2 ADDRESS3 ADDRESS4 POSTCODE]))
   :org.hl7.fhir.Address/line       [ADDRESS1]
   :org.hl7.fhir.Address/district   ADDRESS2
   :org.hl7.fhir.Address/city       ADDRESS3
   :org.hl7.fhir.Address/state      ADDRESS4
   :org.hl7.fhir.Address/postalCode POSTCODE
   :org.hl7.fhir.Address/period     {:org.hl7.fhir.Period/start DATE_FROM
                                     :org.hl7.fhir.Period/end   DATE_TO}})

(def fhir-address-properties
  [:org.hl7.fhir.Address/use :org.hl7.fhir.Address/type
   :org.hl7.fhir.Address/text
   :org.hl7.fhir.Address/line :org.hl7.fhir.Address/city :org.hl7.fhir.Address/district
   :org.hl7.fhir.Address/state :org.hl7.fhir.Address/postalCode
   :org.hl7.fhir.Address/country
   {:org.hl7.fhir.Address/period [:org.hl7.fhir.Period/start
                                  :org.hl7.fhir.Period/end]}])

(pco/defresolver cav->fhir-addresses
  [{:wales.nhs.cavuhb.Patient/keys [ADDRESSES]}]
  {::pco/output
   [{:org.hl7.fhir.Patient/address
     fhir-address-properties}]}
  {:org.hl7.fhir.Patient/address (map make-cav-fhir-address ADDRESSES)})

(pco/defresolver cav->current-address
  "Resolve the current address."
  [{:wales.nhs.cavuhb.Patient/keys [ADDRESSES]}]
  {::pco/output
   [{:wales.nhs.cavuhb.Patient/CURRENT_ADDRESS
     [:wales.nhs.cavuhb.Address/ADDRESS1 :wales.nhs.cavuhb.Address/ADDRESS2 :wales.nhs.cavuhb.Address/ADDRESS3
      :wales.nhs.cavuhb.Address/ADDRESS4 :wales.nhs.cavuhb.Address/POSTCODE
      :wales.nhs.cavuhb.Address/DATE_FROM :wales.nhs.cavuhb.Address/DATE_TO]}]}
  {:wales.nhs.cavuhb.Patient/CURRENT_ADDRESS
   (->> (sort-by :wales.nhs.cavuhb.Address/DATE_FROM ADDRESSES)
        reverse
        (filter #(dates/in-range? (:wales.nhs.cav.uhb.Address/DATE_FROM %) (:wales.nhs.cavuhb.Address/DATE_TO %)))
        first)})

(pco/defresolver cav->fhir-active-addresses
  [{:wales.nhs.cavuhb.Patient/keys [ADDRESSES]}]
  {::pco/output [{:org.hl7.fhir.Patient/activeAddress fhir-address-properties}]}
  {:org.hl7.fhir.Patient/activeAddress
   (->> (sort-by :wales.nhs.cavuhb.Address/DATE_FROM ADDRESSES)
        reverse
        (filter #(dates/in-range? (:wales.nhs.cav.uhb.Address/DATE_FROM %) (:wales.nhs.cavuhb.Address/DATE_TO %)))
        (map make-cav-fhir-address))})

(pco/defresolver fhir-current-address
  [{activeAddresses :org.hl7.fhir.Patient/activeAddress}]
  {::pco/output [{:org.hl7.fhir.Patient/currentAddress fhir-address-properties}]}
  {:org.hl7.fhir.Patient/currentAddress
   (first activeAddresses)})

(pco/defresolver cav->age
  [env {:wales.nhs.cavuhb.Patient/keys [DATE_BIRTH DATE_DEATH]}]
  {::pco/input  [:wales.nhs.cavuhb.Patient/DATE_BIRTH
                 (pco/? :wales.nhs.cavuhb.Patient/DATE_DEATH)]
   ::pco/output [:wales.nhs.cavuhb.Patient/AGE]}
  (let [on-date (or (get env :date) (LocalDate/now))
        age (dates/calculate-age DATE_BIRTH :date-death DATE_DEATH :on-date on-date)]
    (when age
      {:wales.nhs.cavuhb.Patient/AGE age})))

(pco/defresolver cav->cui-display-age
  "Returns a display age formatted as per NHS Connecting for Health (CfH)
   CUI standard ISB1505.
   See https://webarchive.nationalarchives.gov.uk/20150107150145/http://www.isb.nhs.uk/documents/isb-1505/dscn-09-2010/"
  [env {:wales.nhs.cavuhb.Patient/keys [DATE_BIRTH DATE_DEATH]}]
  {::pco/input  [:wales.nhs.cavuhb.Patient/DATE_BIRTH
                 (pco/? :wales.nhs.cavuhb.Patient/DATE_DEATH)]
   ::pco/output [:uk.nhs.cfh.isb1505/display-age]}
  (let [display-age (dates/age-display DATE_BIRTH (or (get env :date) (LocalDate/now)))]
    (when (and (not DATE_DEATH) display-age)
      {:uk.nhs.cfh.isb1505/display-age display-age})))

(pco/defresolver cav->is-deceased?
  [{:wales.nhs.cavuhb.Patient/keys [DATE_DEATH]}]
  {:wales.nhs.cavuhb.Patient/IS_DECEASED (some? DATE_DEATH)})

(pco/defresolver nhs-number->cui-formatted
  "Returns an NHS number formatted to the NHS Connecting for Health (CfH) NHS
  number formatting standard, ISB-1504.
  See https://webarchive.nationalarchives.gov.uk/20150107145557/http://www.isb.nhs.uk/library/standard/135"
  [{nnn :uk.nhs.id/nhs-number}]
  {:uk.nhs.cfh.isb1504/nhs-number (com.eldrix.nhsnumber/format-nnn nnn)})

(pco/defresolver cav->cui-patient-name
  "Returns a CAV patient name formatted to the NHS Connecting for Health (CfH)
  patient name standard, ISB-1506.
  See https://webarchive.nationalarchives.gov.uk/20150107150129/http://www.isb.nhs.uk/library/standard/137"
  [{:wales.nhs.cavuhb.Patient/keys [FIRST_NAMES LAST_NAME TITLE]}]
  {::pco/input [:wales.nhs.cavuhb.Patient/FIRST_NAMES
                :wales.nhs.cavuhb.Patient/LAST_NAME
                (pco/? :wales.nhs.cavuhb.Patient/TITLE)]}
  {:uk.nhs.cfh.isb1506/patient-name
   (str (str/upper-case LAST_NAME) ", "
        FIRST_NAMES
        (when-not (str/blank? TITLE) (str " (" TITLE ")")))})


(pco/defmutation fetch-empi-patients
  "Fetch patient details from the NHS Wales' enterprise master patient index.
  As the concierge service provides eMPI data as a FHIR representation natively,
  we have no need to perform any mapping here."
  [{empi :wales.nhs/empi} {:keys [system value]}]
  {::pco/op-name 'wales.nhs.empi/fetch-patient
   ::pco/output  [{:org.hl7.fhir.Patient/identifiers [:org.hl7.fhir.Identifier/system
                                                      :org.hl7.fhir.Identifier/value]}
                  {:org.hl7.fhir.Patient/name [:org.hl7.fhir.HumanName/family
                                               :org.hl7.fhir.HumanName/given
                                               :org.hl7.fhir.HumanName/prefix]}
                  :org.hl7.fhir.Patient/dateBirth
                  {:org.hl7.fhir.Patient/deceased [:deceasedBoolean :deceasedDateTime]}
                  :org.hl7.fhir.Patient/gender
                  {:org.hl7.fhir.Patient/telecom [:org.hl7.fhir.ContactPoint/system
                                                  :org.hl7.fhir.ContactPoint/value
                                                  :org.hl7.fhir.ContactPoint/use]}
                  {:org.hl7.fhir.Patient/generalPractitioner [:org.hl7.fhir.Reference/type
                                                              {:org.hl7.fhir.Reference/identifier [:org.hl7.fhir.Identifier/system
                                                                                                   :org.hl7.fhir.Identifier/value]}]}]}
  (log/info "wales empi fetch patients: " {:empi empi :system system :value value})
  (if (seq empi)
    (empi/resolve! empi (or system "https://fhir.nhs.uk/Id/nhs-number") value)
    (empi/resolve-fake system value)))

(def all-resolvers [fetch-cav-patient
                    resolve-cav-patient
                    cav->admissions
                    resolve-cav-patient-first-names
                    cav->fhir-identifiers
                    cav->fhir-names
                    cav->fhir-gender
                    cav->fhir-deceased
                    cav->fhir-addresses
                    cav->current-address
                    cav->fhir-active-addresses
                    fhir-current-address
                    cav->age
                    cav->cui-display-age
                    cav->is-deceased?
                    nhs-number->cui-formatted
                    cav->cui-patient-name
                    (pbir/alias-resolver :wales.nhs.cavuhb.Address/POSTCODE :uk.gov.ons.nhspd/PCDS)
                    (pbir/alias-resolver :wales.nhs.cavuhb.Patient/NHS_NUMBER :uk.nhs.id/nhs-number)
                    (pbir/alias-resolver :wales.nhs.cavuhb.Patient/DATE_BIRTH :org.hl7.fhir.Patient/birthDate)
                    (pbir/alias-resolver :wales.nhs.cavuhb.Patient/DATE_DEATH :org.hl7.fhir.Patient/deceased)
                    fetch-empi-patients])

(comment
  (require '[com.eldrix.pc4.system :as pc4-system]
           '[integrant.core :as ig])
  (def system (pc4-system/init :dev))
  (ig/halt! system)

  (do
    (ig/halt! system)
    (def system (pc4-system/init :dev)))

  ;(connect-viz (:pathom/registry system))

  (add-namespace-cav-patient (get fake-cav-patients "A999998"))
  (get fake-cav-patients "A999998")

  (def env (pci/register all-resolvers))

  (require '[com.wsscode.pathom3.interface.eql :as p.eql])
  (p.eql/process env [{'(wales.nhs.cavuhb/fetch-patient
                          {:system "http://fhir.cavuhb.nhs.wales/Id/pas-identifier" :value "A999998"})
                       [:wales.nhs.cavuhb.Patient/LAST_NAME
                        :wales.nhs.cavuhb.Patient/ADDRESSES
                        :wales.nhs.cavuhb.Patient/HOSPITAL_ID
                        :org.hl7.fhir.Patient/identifiers
                        :wales.nhs.cavuhb.Patient/SEX
                        :org.hl7.fhir.Patient/gender
                        {:wales.nhs.cavuhb.Patient/CURRENT_ADDRESS [:wales.nhs.cavuhb.Address/ADDRESS1 :uk.gov.ons.nhspd/PCDS]}]}])
  (cav->fhir-identifiers (add-namespace-cav-patient (get fake-cav-patients "A999998"))))

