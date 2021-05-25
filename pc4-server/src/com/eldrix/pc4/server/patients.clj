(ns com.eldrix.pc4.server.patients
  (:require
    [clojure.tools.logging.readable :as log]
    [com.eldrix.concierge.wales.cav-pms :as cavpms]
    [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [clojure.string :as str]))

(defn record->map
  "Turn a record into a namespaced map."
  [n r]
  (reduce-kv (fn [m k v] (assoc m (keyword n (name k)) v)) {} r))

(def fake-cav-patients
  {"A999998" {:LAST_NAME "Dummy" :FIRST_NAMES "Albert"
              :ADDRESSES [{:ADDRESS1 "University Hospital Wales" :POSTCODE "CF14 4XW"}]}})

(defn add-namespace-cav-patient [pt]
  (assoc (record->map "wales.nhs.cavuhb.Patient" pt)
    :wales.nhs.cavuhb.Patient/ADDRESSES (map #(record->map "wales.nhs.cavuhb.Address" %) (:ADDRESSES pt))))

(pco/defmutation fetch-cav-patient
  [{config :wales.nhs.cavuhb/pms} {:keys [system value]}]
  {::pco/op-name 'wales.nhs.cavuhb/fetch-patient}
  (cond
    ;; if there is no active configuration, run in development mode
    (empty? config)
     (do) (add-namespace-cav-patient (get fake-cav-patients (str/upper-case value)))

    (or (= system :wales.nhs.cavuhb.id/pas-identifier) (= system "http://fhir.cavuhb.nhs.wales/Id/pas-identifier"))
    (when-let [pt (cavpms/fetch-patient-by-crn config value)]
      (add-namespace-cav-patient pt))))

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

  (def env (pci/register [fetch-cav-patient]))

  (require '[com.wsscode.pathom3.interface.eql :as p.eql])
  (p.eql/process env [{'(wales.nhs.cavuhb/fetch-patient
                          {:system "http://fhir.cavuhb.nhs.wales/Id/pas-identifier" :value "A999998"})
                       [:wales.nhs.cavuhb.Patient/LAST_NAME
                        :wales.nhs.cavuhb.Patient/ADDRESSES]}])
  )
