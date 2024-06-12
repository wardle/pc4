(ns com.eldrix.pc4.modules.sbimport
  "Import code for Swansea Bay legacy data"
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.data.csv :as csv]
   [com.eldrix.concierge.wales.empi :as empi]
   [com.eldrix.pc4.rsdb.demographics :as demog]
   [com.eldrix.pc4.rsdb.projects :as projects]
   [next.jdbc :as jdbc]
   [com.eldrix.pc4.rsdb.patients :as patients]
   [com.eldrix.pc4.system :as pc4])
  (:import
   (java.time LocalDateTime)))

(def sbuhb-neuroinflamm-project-id 88)
(def sbuhb-ms-register-project-id 121)

(defn csv-data->maps
  [csv-data]
  (map zipmap
       (->> (first csv-data) ;; First row is the header
            (map (comp keyword str/lower-case #(str/replace % #":" "") #(str/replace % #"\s" "_"))) ;; Drop if you want string keys instead
            repeat)
       (rest csv-data)))

(defn fetch-empi
  "Return a FHIR representation of a patient from the NHS Wales eMPI."
  [empi-config nnn]
  (when empi-config
    (empi/resolve! empi-config "https://fhir.nhs.uk/Id/nhs-number" nnn)))

(defn sb->fhir-patient [{family :name, :keys [nhs_no gender first_name dob email_address]}]
  (cond-> {:org.hl7.fhir.Patient/identifier
           [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/nhs-number"
             :org.hl7.fhir.Identifier/value nhs_no}]
           :org.hl7.fhir.Patient/birthDate dob
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

(defn process-row
  [{:keys [conn empi-config]} {:keys [nhs_no] :as row}]
  (let [fhir-patient (sb->fhir-patient row)
        matches1     (patient-matches conn fhir-patient)
        empi-patient (when (empty? matches1) (fetch-empi empi-config nhs_no))
        matches2     (when empi-patient (patient-matches empi-config empi-patient))
        n-matches    (max (count matches1) (count matches2))
        error        (= 2 n-matches)
        patient-pk   (when (and (= 1 n-matches) (or (= matches1 matches2) (empty? matches2))) (first matches1))
        existing-pt  (when patient-pk (patients/fetch-patient conn {:t_patient/id patient-pk}))]
    (when empi-config  ;; slow down repeated calls to eMPI to prevent denial of service
      (println "Processing " nhs_no)
      (Thread/sleep 1000))
    {:row          (-> (select-keys row [:nhs_no :gender :name :first_name :dob :email_address])
                       (assoc :error error
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
     :sb-patient   fhir-patient
     :empi-patient empi-patient
     :existing-pt  existing-pt}))

;;; for each row
;;; does patient already exist?
;;;   -> if yes, leave alone or update from eMPI? check authority for each?
;;;   -> if no, fetch data from eMPI
;;;             check date of birth matches
;;;             create patient
;;;             update new record with data from eMPI

(def columns
  [:nhs_no :gender :name :first_name :dob :email_address
   :existing_patient_pk :existing_patient_id :existing_last_name :existing_first_names :existing_dob
   :empi_ids :empi_last_name :empi_first_names :empi_dob :error
   :new_patient_fk :new_patient_id :new_last_name :new_first_names :new_dob
   :action])

(defn write-csv
  [out data]
  (with-open [writer (io/writer out)]
    (let [headers (map name columns)
          rows (map #(map % columns) data)]
      (csv/write-csv writer (cons headers rows)))))

(defn process-csv [{:keys [conn empi-config] :as config} x]
  (->> (csv-data->maps (csv/read-csv (io/reader x)))
       (map #(process-row {:conn conn :empi-config empi-config} %))))

(defn execute-row
  [txn empi-svc {:keys [row sb-patient empi-patient existing-pt]}]
  (cond
    existing-pt
    (do (projects/register-patient-project! txn sbuhb-neuroinflamm-project-id 1 existing-pt)
        (assoc row :action :register-project))
    empi-patient
    (let [patient (projects/create-patient! txn {:nhs_number (:nhs_no row)
                                                 :first_names ""
                                                 :last_name ""
                                                 :title "" :date_created (LocalDateTime/now)
                                                 :authoritative_demographics "EMPI"})
          patient' (demog/update-patient txn empi-svc patient)]
      (assoc row :new_patient_id (:t_patient/patient_identifier patient')
             :new_patient_fk (:t_patient/id patient')
             :new_last_name (:t_patient/last_name patient')
             :new_first_names (:t_patient/first_names patient')
             :new_dob (:t_patient/date_birth patient')
             :action :new-patient))
    :else
    (assoc row :action :none)))

(defn process
  [{:keys [profile csv-file execute rollback] :or {execute false rollback true}}]
  (if (or (not profile) (not (keyword? profile))
          (not csv-file) (not (string? csv-file)))
    (println (str/join \newline
                       ["Usage: "
                        "  clj -X:dev com.eldrix.pc4.modules.sbimport/process :profile PROFILE :csv-file CSV-FILE"
                        "Parameters : "
                        "  :profile  PROFILE  : a keyword - can be specified unquoted e.g. :cav"
                        "  :csv-file CSV-FILE : a quoted string e.g. '\"/Users/mark/Downloads/sb-patients.csv\"'"
                        "  :execute  false    : a boolean - default false - can be specified unquoted"
                        "  :rollback true     : a boolean - default true - rollback any executions for a dry run"
                        " "
                        "As such, to actually execute and commit any changes:"
                        "  clj -X:dev com.eldrix.pc4.modules.sbimport/process :profile :cvx :csv-file '\"data.csv\"' :execute? true :rollback? false"]))
    (do
      (println "Processing" csv-file " using pc4 system profile" profile)
      (pc4/load-namespaces profile [:com.eldrix.rsdb/conn :wales.nhs/empi])
      (let [{conn :com.eldrix.rsdb/conn, empi-conf :wales.nhs/empi} (pc4/init profile [:com.eldrix.rsdb/conn :wales.nhs/empi])
            conf {:conn conn :empi-config empi-conf}
            empi-service (when empi-conf (fn [system value] (empi/resolve! empi-conf system value)))
            data (process-csv conf csv-file)]
        (if execute
          (if (nil? empi-conf)
            (println "ERROR: cannot execute changes as required as no eMPI configuration in profile" profile)
            (jdbc/with-transaction [txn conn {:rollback-only rollback}]
              (do (println "Executing actual import into database; rollback:" rollback)
                  (write-csv "output.csv" (map #(execute-row txn empi-service %) data)))))
          (do (println "INFO: dry run; reporting results of internal and eMPI lookups only")
              (println "Outputting read-only report to output.csv")
              (write-csv "output.csv" (map :row data))))))))

(comment
  (require '[integrant.repl.state :as state])
  (def system integrant.repl.state/system)
  (def empi-config (:wales.nhs/empi system))
  (keys system)
  (def data (csv-data->maps (csv/read-csv (io/reader "/Users/mark/Desktop/swansea-ms.csv"))))
  (def data2 (->> data
                  (map #(process-row {:conn conn :empi-config nil} %))
                  (filter :existing-pt)
                  (map :row)))

  (def fhir-data (map sb->fhir-patient data))
  (take 5 fhir-data)
  (def conn (jdbc/get-connection "jdbc:postgresql:rsdb"))

  (sort (keys (first data)))
  (take 20 (map :nhs_no data))
  (take 3 data))
