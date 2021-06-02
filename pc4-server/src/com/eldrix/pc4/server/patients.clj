(ns com.eldrix.pc4.server.patients
  (:require
    [clojure.tools.logging.readable :as log]
    [com.eldrix.concierge.wales.cav-pms :as cavpms]
    [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [clojure.string :as str])
  (:import (java.time LocalDate)))

(defn record->map
  "Turn a record into a namespaced map."
  [n r]
  (reduce-kv (fn [m k v] (assoc m (keyword n (name k)) v)) {} r))

(defn fake-cav-patients []
  {"A999998" {:HOSPITAL_ID      "A999998"
              :NHS_NUMBER       "1111111111"
              :LAST_NAME        "Dummy" :FIRST_NAMES "Albert"
              :SEX,             "M" :DATE_BIRTH (LocalDate/of 1970 1 1) :DATE_DEATH nil
              :HOME_PHONE_NO    :WORK_PHONE_NO,
              :COUNTRY_OF_BIRTH :ETHNIC_ORIGIN :MARITAL_STATUS :OCCUPATION
              :PLACE_OF_BIRTH   :PLACE_OF_DEATH
              :GP_ID            :GPPR_ID
              :ADDRESSES        [{:ADDRESS1  "University Hospital Wales"
                                  :ADDRESS2  "Heath Park"
                                  :ADDRESS3  "Cardiff"
                                  :ADDRESS4  nil
                                  :POSTCODE  "CF14 4XW"
                                  :DATE_FROM (LocalDate/of 1970 01 01) :DATE_TO ""}]}})

(defn add-namespace-cav-patient [pt]
  (assoc (record->map "wales.nhs.cavuhb.Patient" pt)
    :wales.nhs.cavuhb.Patient/ADDRESSES (map #(record->map "wales.nhs.cavuhb.Address" %) (:ADDRESSES pt))))

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
                  :wales.nhs.cavuhb.Patient/NHS_NO]}
  (cond
    ;; if there is no active configuration, run in development mode
    (empty? config)
    (do (add-namespace-cav-patient (get (fake-cav-patients) (str/upper-case value))))

    (or (= system :wales.nhs.cavuhb.id/pas-identifier) (= system "http://fhir.cavuhb.nhs.wales/Id/pas-identifier"))
    (when-let [pt (cavpms/fetch-patient-by-crn config value)]
      (add-namespace-cav-patient pt))

    (or (= system :uk.nhs.id/nhs-number) (= system "https://fhir.nhs.uk/Id/nhs-number"))
    (when-let [pt (cavpms/fetch-patient-by-nnn config value)]
      (add-namespace-cav-patient pt))))


(pco/defresolver cav->fhir-identifiers
  [{:wales.nhs.cavuhb.Patient/keys [NHS_NUMBER HOSPITAL_ID]}]
  {::pco/input  [:wales.nhs.cavuhb.Patient/HOSPITAL_ID
                 (pco/? :wales.nhs.cavuhb.Patient/NHS_NUMBER)]
   ::pco/output [{:org.hl7.fhir.Patient/identifiers [:org.hl7.fhir.Identifier/system :org.hl7.fhir.Identifier/value]}]}
  {:org.hl7.fhir.Patient/identifiers
   (cond-> [{:org.hl7.fhir.Identifier/system :wales.nhs.cavuhb.id/pas-identifier
             :org.hl7.fhir.Identifier/value  HOSPITAL_ID}]
           (not (str/blank? NHS_NUMBER))
           (conj {:org.hl7.fhir.Identifier/system :uk.nhs.id/nhs-number
                  :org.hl7.fhir.Identifier/value  NHS_NUMBER}))})

(pco/defresolver cav->fhir-gender
  [{:wales.nhs.cavuhb.Patient/keys [SEX]}]
  {::pco/output [:org.hl7.fhir.Patient/gender]}
  {:org.hl7.fhir.Patient/gender
   (case SEX "M" :org.hl7.fhir.administrative-gender/male
             "F" :org.hl7.fhir.administrative-gender/female
             :org.hl7.fhir.administrative-gender/unknown)})



(def all-resolvers [fetch-cav-patient
                    cav->fhir-identifiers
                    cav->fhir-gender])

(comment
  (require '[com.eldrix.pc4.server.system :as pc4-system])
  (def system (pc4-system/init :dev))
  (ig/halt! system)
  (do
    (ig/halt! system)
    (def system (pc4-system/init :dev)))
  (connect-viz (:pathom/registry system))


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
                        :org.hl7.fhir.Patient/gender]}])
  (cav->fhir-identifiers (add-namespace-cav-patient (get fake-cav-patients "A999998")))
  )
