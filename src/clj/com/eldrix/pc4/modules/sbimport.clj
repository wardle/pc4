(ns com.eldrix.pc4.modules.sbimport
  "Import code for Swansea Bay legacy data.
  
  Import is divided into three distinct stages.
  
  1. lookup-empi - performs an eMPI lookup against each patient 
     generating an EDN file representing a map of NHS number to a FHIR
     representation of the patient data in the NHS Wales eMPI.
  2. create-patients  - takes the CSV file and eMPI data and creates each 
     patient if they don't exist 
  3. register-projects - takes the CSV file and registers every patient to
     the SBUHB MS service and optionally the SB subcohort for MS Register
  4. diagnoses - takes the CSV file and updates data as necessary, 
     including diagnosis, MS registry project registration, and EDSS."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.data.csv :as csv]
   [com.eldrix.concierge.wales.empi :as empi]
   [com.eldrix.pc4.rsdb.demographics :as demog]
   [com.eldrix.pc4.rsdb.projects :as projects]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.sql]
   [com.eldrix.pc4.rsdb.patients :as patients]
   [com.eldrix.pc4.system :as pc4]
   [clojure.spec.test.alpha :as stest])
  (:import
   (java.time LocalDate LocalDateTime)
   (java.time.format DateTimeParseException)))

(def sbuhb-neuroinflamm-project-id 88)
(def sbuhb-ms-register-project-id 121)

;;;;;
;;;;;
;;;;;

(defn ^:private read-csv
  "Read a CSV file as a sequence of maps"
  [f]
  (with-open [reader (io/reader f)]
    (let [csv-data (csv/read-csv reader)]
      (doall (map zipmap
                  (->> (first csv-data) ;; First row is the header
                       (map (comp keyword str/lower-case #(str/replace % #":" "") #(str/replace % #"\s" "_")))
                       repeat)
                  (rest csv-data))))))

(defn write-csv
  "Writes out a collection of maps as CSV. Parameters
  - out     : anything coercible by `clojure.java.io/writer`
  - columns : a sequence of column keywords to be used as column titles and 
              fns to get data
  - coll    : a collection of maps representing the data to be written."
  [out columns coll]
  (with-open [writer (io/writer out)]
    (let [headers (map name columns)
          rows (map #(map % columns) coll)]
      (csv/write-csv writer (cons headers rows)))))

(defn ^:private fetch-single-empi
  "Return a FHIR representation of a patient from the NHS Wales eMPI."
  [empi-config nnn]
  (when empi-config                           ;; return nil if there is no valid empi configuration to use
    (let [results (empi/resolve! empi-config "https://fhir.nhs.uk/Id/nhs-number" nnn)]
      (case (count results)
        0 nil
        1 (first results)
        (do
          (println "ERROR: returning first of multiple results for NHS number" nnn)
          (pp/pprint results)
          (first results))))))

(defn check-usage [spec params & ss]
  (when-not (s/valid? spec (or params {}))
    (println
     (str/join \newline
               (into ["ERROR: invalid parameters"
                      (s/explain-str spec params)]
                     ss)))
    (System/exit 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Process SB CSV and create an edn file containing results of eMPI lookups for each patient
;;; 

(s/def ::profile keyword?)
(s/def ::f string?)
(s/def ::out string?)
(s/def ::sleep number?)

(defn lookup-empi
  [{:keys [profile f out sleep] :or {sleep 1000} :as params}]
  (check-usage (s/keys :req-un [::profile ::f ::out] :opt-un [::sleep])
               params
               "Usage: clj -X:dev com.eldrix.pc4.modules/make-empi-registry :profile :cvx :f '\"my-file.csv\"' :out '\"registry.edn\"'")
  (let [{empi-config :wales.nhs/empi} (pc4/init profile [:wales.nhs/empi])
        _ (when-not empi-config (println "WARNING: no eMPI configuration so creating empty registry"))
        result (reduce (fn [acc {:keys [nhs_no]}] (Thread/sleep sleep)
                         (if-not (str/blank? nhs_no)
                           (if-let [pt (fetch-single-empi empi-config nhs_no)]
                             (assoc acc nhs_no pt)
                             acc)
                           acc))
                       {} (read-csv f))]
    (with-open [w (io/writer out)]
      (binding [*out* w]
        (pr result)))))

;;;;;
;;;;;
;;;;;
;;;;;
;;;;;
;;;;;

(defn parse-sb-date [s]
  (when-not (str/blank? s)
    (try
      (LocalDate/parse s (java.time.format.DateTimeFormatter/ofPattern "dd/MM/yyyy"))
      (catch DateTimeParseException e
        (println "WARNING: failed to parse date " s)))))

(defn sb->fhir-patient
  [{family :name, :keys [nhs_no gender first_name dob email_address]}]
  (cond-> {:org.hl7.fhir.Patient/identifier
           [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
             :org.hl7.fhir.Identifier/value nhs_no}]
           :org.hl7.fhir.Patient/birthDate (parse-sb-date dob)
           :org.hl7.fhir.Patient/gender (when gender (str/lower-case gender))
           :org.hl7.fhir.Patient/name
           [{:org.hl7.fhir.HumanName/family family
             :org.hl7.fhir.HumanName/given first_name}]}
    (not (str/blank? email_address))
    (assoc
     :org.hl7.fhir.Patient/telecom
     [{:org.hl7.fhir.ContactPoint/system "email"
       :org.hl7.fhir.ContactPoint/value email_address
       :org.hl7.fhir.ContactPoint/use "usual"}])))

(defn patient-matches [conn fhir-patient]
  (let [existing-by-identifiers (demog/exact-match-by-identifier conn fhir-patient)
        existing-by-demog (demog/exact-match-on-demography conn fhir-patient)]
    (set/union existing-by-identifiers existing-by-demog)))

(defn create-patient*
  [conn empi-registry {:keys [nhs_no] :as row}]
  (let [fhir-patient (sb->fhir-patient row)
        matches1     (patient-matches conn fhir-patient) ;; existing patient
        empi-patient (get empi-registry nhs_no)
        matches2     (when empi-patient (patient-matches conn empi-patient))  ;; eMPI data
        n-matches    (max (count matches1) (count matches2))
        error        (= 2 n-matches)
        patient-pk   (when (and (= 1 n-matches) (or (= matches1 matches2) (empty? matches2))) (first matches1))
        existing-pt  (when patient-pk (patients/fetch-patient conn {:t_patient/id patient-pk}))
        status       (cond
                       error        :ERROR     ;; we have an error
                       existing-pt  :KEEP      ;; keep existing patient record
                       empi-patient :CREATE    ;; need to create a new patient record
                       :else        :NONE)]
    {:status     status
     :row        (-> (select-keys row [:nhs_no :gender :name :first_name :dob :email_address :ms_register :diagnosis])
                     (assoc :status status
                            :existing_patient_pk (:t_patient/id existing-pt)
                            :existing_patient_id (:t_patient/patient_identifier existing-pt)
                            :existing_nhs_no (:t_patient/nhs_number existing-pt)
                            :existing_last_name (:t_patient/last_name existing-pt)
                            :existing_first_names (:t_patient/first_names existing-pt)
                            :existing_dob (:t_patient/date_birth existing-pt)
                            :empi_ids (str/join " " (get-in empi-patient [:org.hl7.fhir.Patient/identifier :org.hl7.fhir.Identifier :org.hl7.fhir.Identifier/value]))
                            :empi_last_name (get-in empi-patient [:org.hl7.fhir.Patient/name 0 :org.hl7.fhir.HumanName/family])
                            :empi_first_names (get-in empi-patient [:org.hl7.fhir.Patient/name 0 :org.hl7.fhir.HumanName/given])
                            :empi_dob (get-in empi-patient [:org.hl7.fhir.Patient/birthDate])))
     :sb-pt       fhir-patient
     :empi-pt     empi-patient
     :existing-pt existing-pt}))

(def columns
  [:nhs_no :gender :name :first_name :dob :email_address
   :existing_patient_pk :existing_patient_id :existing_last_name :existing_first_names :existing_dob
   :empi_ids :empi_last_name :empi_first_names :empi_dob
   :created_patient_fk :created_patient_id :created_last_name :created_first_names :created_dob
   :status])

(defn create-patient!
  [txn empi-svc {:keys [nhs_no status] :as row}]
  (if (= :CREATE status) ;; if status=CREATE we know there is a valid empi patient AND no existing patient
    (let [patient (projects/create-patient! txn {:nhs_number   nhs_no
                                                 :first_names  ""
                                                 :last_name    ""
                                                 :title        ""
                                                 :date_created (LocalDateTime/now)
                                                 :authoritative_demographics "EMPI"})
          update-patient-sql (or (demog/update-patient txn empi-svc patient) (throw (ex-info "Unable to create new patient!" row)))]
      (doseq [stmt update-patient-sql]
        (jdbc/execute! txn stmt))
      (let [patient' (patients/fetch-patient txn {:t_patient/id (:t_patient/id patient)})]
        (assoc row
               :created_patient-pk  (:t_patient/id patient')
               :created_patient_id  (:t_patient/patient_identifier patient')
               :created_last_name   (:t_patient/last_name patient')
               :created_first_names (:t_patient/first_names patient')
               :created_dob         (:t_patient/date_birth patient'))))
    row))

(comment
  (def  conn (jdbc/get-connection "jdbc:postgresql:rsdb"))
  (clojure.spec.test.alpha/instrument)
  (def empi-patients-by-nnn (clojure.edn/read-string {:default default-edn-tag-reader} (slurp "/Users/mark/Desktop/sbuhb/registry.edn")))
  (def empi-svc (fn [_authority {:org.hl7.fhir.Identifier/keys [value]}] (get empi-patients-by-nnn value)))
  (def pt (projects/create-patient! conn {:nhs_number  "7244486596"
                                          :first_names  ""
                                          :last_name    ""
                                          :title        ""
                                          :authoritative_demographics "EMPI"
                                          :date_created (LocalDateTime/now)}))

  (empi-svc nil "7244486596")
  (demog/update-patient conn empi-svc pt))

(defn default-edn-tag-reader
  [tag [clazz _ object-string]]
  (case clazz
    java.time.LocalDateTime (LocalDateTime/parse object-string)
    java.time.LocalDate     (LocalDate/parse object-string)
    (throw (ex-info (str "Unsupported edn tag " clazz) {:tag tag :class clazz :value object-string}))))

;;
;;
;;
(s/def ::empi string?)
(s/def ::execute boolean?)
(s/def ::rollback boolean?)

(defn create-patients
  "Register any patients from the csv file 'f' that do not already exist within
  rsdb."
  [{:keys [profile f out empi execute rollback] :or {execute false, rollback true} :as params}]
  (check-usage (s/keys :req-un [::profile ::f ::out ::empi] :opt-un [::execute ::rollback])
               params
               "Usage: clj -X:dev com.eldrix.pc4.modules/create-patients :profile cvx :f '\"my-file.csv\"' :empi '\"registry.edn\"' :out '\"output.csv\"'"
               ""
               "Parameters:"
               "- :profile  : keyword for pc4 profile, e.g. :dev e.g. :cvx"
               "- :f        : string - path to SB csv"
               "- :empi     : string - path to empi registry edn file"
               "- :execute  : boolean - whether to execute SQL, default false"
               "- :rollback : boolean - whether to rollback execution, default true")
  (let [{conn :com.eldrix.rsdb/conn} (pc4/init profile [:com.eldrix.rsdb/conn])
        rows (read-csv f)
        empi-patients-by-nnn (clojure.edn/read-string {:default default-edn-tag-reader} (slurp empi))
        empi-svc (fn [_authority {:org.hl7.fhir.Identifier/keys [value]}] (get empi-patients-by-nnn value))
        results (map #(create-patient* conn empi-patients-by-nnn %) rows)]
    (if-not execute
      (do (println "Writing file " out)
          (write-csv out columns (map :row results)))
      (do (println "Registering patients; rollback: " rollback)
          (jdbc/with-transaction [txn conn {:rollback-only rollback}]
            (write-csv out columns (map #(create-patient! txn empi-svc %) (map :row results))))))))

;;;;;
;;;;;
;;;;;
;;;;;

(defn sb->single-exact-matched-patient
  [conn {:keys [nhs_no] :as row}]
  (let [fhir-patient (sb->fhir-patient row)
        patient-pks (demog/exact-match-by-identifier conn fhir-patient)
        patient-pk (first patient-pks)]
    (case (count patient-pks)
      0 nil
      1 patient-pk
      (throw (ex-info (str "multiple matches for patient" nhs_no) fhir-patient)))))

(defn update-patients
  [{:keys [profile f empi] :as params}]
  (check-usage (s/keys :req-un [::profile ::f ::empi])
               params
               "Usage: clj -X:dev com.eldrix.pc4.modules/update-project-patients params")
  (let [{conn :com.eldrix.rsdb/conn} (pc4/init profile [:com.eldrix.rsdb/conn])
        rows (read-csv f)
        empi-patients-by-nnn (edn/read-string {:default default-edn-tag-reader} (slurp empi))
        empi-svc (fn [_authority {:org.hl7.fhir.Identifier/keys [value]}] (get empi-patients-by-nnn value))]
    (doseq [{:keys [nhs_no] :as row} rows]
      (try
        (let [patient-pk (sb->single-exact-matched-patient conn row)
              stmts (demog/update-patient conn empi-svc {:t_patient/id patient-pk})]
          (doseq [stmt stmts]
            (jdbc/with-transaction [txn conn]
              (jdbc/execute! txn (sql/format stmt)))))
        (catch Exception e (println "Failed to process " nhs_no (ex-message e)))))))

;;;
;;;

(def sb-diagnoses
  {"RIS"     16415361000119105
   "CIS"     445967004
   "Sarciud" 31541009
   "Sarcoid" 31541009
   "Not MS"  nil
   "ADEM"    83942000
   "NMO"     25044007
   "MS"      24700007
   "NOT MS"  nil
   "NMO - antibody negative" 25044007
   "MOGAD" 1237194006
   "NMO - antibody negitive" 25044007
   "MS " 24700007
   "Radiologically isolated syndrome" 16415361000119105
   "CNS angitis" 427020007
   "ms" 24700007
   "Radiologically Isolated Syndrome" 16415361000119105
   "Neurosarcoid" 230193008
   "NMO - aquaporin 4 positive" 25044007
   "Myelopathy" 48522003})

(defn parse-ms-register-id
  "Return an MS Register id."
  [s]
  (or (parse-long s) (re-matches #"^\d+/\d+$" s) (= s "y")))

(comment
  (parse-ms-register-id "y")
  (parse-ms-register-id "12345")
  (parse-ms-register-id "12345/67890")
  (parse-ms-register-id "sarcoid"))

(defn register-to-ms-registry
  "The SB dataset contains a column for the MS registry. This is usually
  a five digit number (e.g 111000) , but is sometimes two (e.g. 402888/175600)
  and sometimes just text (e.g. 'no' or 'sarcoid'). Does nothing if not a
  valid external identifier for the MS Register project. "
  [txn patient-pk ms-register-id]
  (when-let [id (parse-ms-register-id ms-register-id)]
    (let [episode (projects/register-patient-project! txn sbuhb-ms-register-project-id 1 {:t_patient/id patient-pk})]
      (when (string? id)
        (projects/update-episode! txn (assoc episode :t_episode/external_identifier id))))))

(defn register-to-sb-ms-service
  [txn patient-pk]
  (projects/register-patient-project! txn sbuhb-neuroinflamm-project-id 1 {:t_patient/id patient-pk}))

(defn register-projects
  [{:keys [profile f rollback] :or {rollback true} :as params}]
  (check-usage (s/keys :req-un [::profile ::f] :opt-un [::rollback]) params
               "Usage: clj -X:dev com.eldrix.pc4.modules/register-projects :profile cvx :f '\"my-file.csv\"'")
  (println "Registering patients from" f "; rollback:" rollback)
  (let [{conn :com.eldrix.rsdb/conn} (pc4/init profile [:com.eldrix.rsdb/conn])
        rows (read-csv f)]
    (jdbc/with-transaction [txn conn {:rollback-only rollback}]
      (doseq [{:keys [nhs_no ms_register] :as row} rows]
        (if-let [patient-pk (sb->single-exact-matched-patient conn row)]
          (do (register-to-sb-ms-service txn patient-pk) ;; idempotent operations, so no need to check whether already a member
              (register-to-ms-registry txn patient-pk ms_register))
          (println "WARNING: no match for patient " nhs_no))))))

(defn add-diagnosis*
  [{:keys [diagnosis] :as row}]
  (if-let [concept-id (get sb-diagnoses diagnosis)]
    (assoc row :diagnosis-concept-id concept-id)
    row))

(defn has-diagnosis? [env patient-pk diagnosis-concept-id]
  (boolean (seq (patients/diagnoses env patient-pk {:ecl (str "<<" diagnosis-concept-id)}))))

(defn add-diagnoses
  [{:keys [profile f] :as params}]
  (check-usage (s/keys :req-un [::profile ::f]) params
               "Usage: clj -X:dev com.eldrix.pc4.modules/add-diagnoses :profile cvx :f '\"my-file.csv\"'")
  (println "Adding diagnoses for patients in" f)
  (let [{conn :com.eldrix.rsdb/conn, hermes :com.eldrix/hermes :as system} (pc4/init profile [:com.eldrix.rsdb/conn :com.eldrix/hermes])
        rows (read-csv f)]
    (doseq [{:keys [nhs_no diagnosis date_of_diagnosis] :as row} rows]
      (if-let [patient-pk (sb->single-exact-matched-patient conn row)]
        (when-let [diagnosis-concept-id (get sb-diagnoses diagnosis)]
          (when-not (has-diagnosis? system patient-pk diagnosis-concept-id)
            (println (patients/create-diagnosis! conn
                                                 {:t_patient/id patient-pk}
                                                 {:t_diagnosis/concept_fk diagnosis-concept-id
                                                  :t_diagnosis/date_diagnosis (parse-sb-date date_of_diagnosis)
                                                  :t_diagnosis/status :ACTIVE
                                                  :t_diagnosis/full_description (str "Imported from legacy SBUHB data:" diagnosis)}))))
        (println "Unable to update diagnosis for patient" nhs_no ": no exact match found")))))

(comment
  (require '[integrant.repl.state :as state])
  (def system (pc4/init :dev [:com.eldrix/hermes :com.eldrix.rsdb/conn]))
  (keys system)
  (patients/diagnoses system 14032)
  (patients/diagnoses system 14032 {:ecl "<<24700007"})
  (patients/diagnoses system 14032 {:ecl "<<31384009"})
  (has-diagnosis? system 14032 1384009)
  (def system integrant.repl.state/system)
  (def empi-config (:wales.nhs/empi system))
  clojure.core/default-data-readers
  (def fake-fhir-patient (empi/resolve-fake "https://fhir.nhs.uk/Id/nhs-number" "1234567890"))
  (with-open [w (io/writer "flibble.edn")]
    (binding [*out* w]
      (pr fake-fhir-patient)))
  (= fake-fhir-patient (edn/read-string {:default default-edn-tag-reader} (slurp "flibble.edn")))
  #_(edn/read)

  (keys system)
  (def data (read-csv "/Users/mark/Desktop/sbuhb/swansea-ms-data.csv"))

  (def fhir-data (map sb->fhir-patient data))
  (take 5 fhir-data)
  (def conn (jdbc/get-connection "jdbc:postgresql:rsdb"))

  (sort (keys (first data)))
  (take 20 (map :nhs_no data))
  (keys (first data))
  (take 3 data)

  (take 50 (remove nil? (map :date_of_diagnosis data)))
  (into #{} (map :ms_register data)))
