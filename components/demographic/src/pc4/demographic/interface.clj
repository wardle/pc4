(ns pc4.demographic.interface
  "A demographic service is an opaque provider of demographic information. "
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [pc4.demographic.synthetic :as synth]
            [pc4.demographic.protos :as p]
            [pc4.wales-cav-pms.interface :as cavpms]
            [pc4.wales-empi.interface :as empi]
            [pc4.log.interface :as log]))

;; Configuration specs
(s/def ::system string?)
(s/def ::id keyword?)
(s/def ::title string?)
(s/def ::svc #(satisfies? p/PatientsByIdentifier %))
(s/def ::systems (s/coll-of ::system :kind set? :min-count 1))
(s/def ::remote boolean?)

(s/def ::provider
  (s/keys :req-un [::id ::title ::svc]
          :opt-un [::systems ::remote]))

(s/def ::providers
  (s/coll-of ::provider :kind vector? :min-count 1))

(s/def ::config
  (s/keys :req-un [::providers]))

(defmethod ig/init-key ::svc
  [_ {:keys [providers] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "Invalid demographic service configuration"
                    (s/explain-data ::config config))))
  (log/info "creating demographic service with " (count providers) " providers")
  config)

(defn patients-by-identifier
  "Fetch patients matching the system and value specified. Will throw an exception if 'only-single-match' specified and
  there are multiple matches. It is almost always better to display multiple matches to the user, but
  'only-single-match' can be used for non-interactive workflows.

  This iterates through all known demographic providers, screening by system when possible and unless 'ask-all-systems'
  is true, but calling a provider speculatively if not. Some providers can be very slow to respond, so if that is a
  concern, then perform in a background thread. No attempt to parallelize the checks is made.

  Parameters:
  - svc     : Demographic service
  - system  : string representing system e.g. \"https://fhir.nhs.uk/Id/nhs-number\"
  - value   : value e.g. \"1111111111\"
  - options:
      |- :provider-id  - to use only a single provider
      |- :only-single-match - throw an exception if multiple matches, and return a single result not a sequence.
      |- :ask-all-systems - do not use provider configured 'systems' to screen out a provider.

  For example
  ```
  (patients-by-identifier svc \"https://fhir.nhs.uk/Id/nhs-number\" \"1111111111\")
  =>
  [{:org.hl7.fhir.Patient/identifier [...]}]
  ```"
  ([svc system value]
   (patients-by-identifier svc system value {}))
  ([{:keys [providers]} system value {:keys [provider-id only-single-match ask-all-systems]}]
   (log/debug "patients-by-identifier" {:provider-id provider-id :system system :value value})
   (let [by-provider-id (if provider-id (fn [{:keys [id]}] (= id provider-id)) (constantly true))
         by-system (if ask-all-systems (constantly true) (fn [{:keys [systems]}] ((or systems (constantly true)) system)))]
     (loop [providers (filter #(and (by-provider-id %) (by-system %)) providers)]
       (when-let [provider (first providers)]
         (if-let [patients (seq (p/fetch (:svc provider) system value))]
           (if only-single-match
             (if (= 1 (count patients))
               (first patients)
               (throw (ex-info (str "multiple patient matches when only one expected with " (:id provider) " for " system "#" value)
                               {:provider-id (:id provider) :system system :value value :results patients})))
             patients)
           (recur (rest providers))))))))

(defmethod ig/init-key ::wales-cav-pms-service
  [_ svc]
  (reify p/PatientsByIdentifier
    (fetch [_ system value]
      (case system
        "https://fhir.nhs.uk/Id/nhs-number"
        (when-let [pt (cavpms/patient->fhir (cavpms/fetch-patient-by-nnn svc value))]
          [pt])
        "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
        (when-let [pt (cavpms/patient->fhir (cavpms/fetch-patient-by-crn svc value))]
          [pt])
        nil))))

(defmethod ig/init-key ::wales-empi-service
  [_ svc]
  (reify p/PatientsByIdentifier
    (fetch [_ system value]
      (empi/pdq! svc system value))))


(defmethod ig/init-key ::fake-service
  [_ {:keys [conn]}]
  (synth/make-synthetic-provider))