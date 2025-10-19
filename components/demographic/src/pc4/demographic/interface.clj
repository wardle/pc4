(ns pc4.demographic.interface
  "A demographic service is an opaque provider of demographic information. "
  (:refer-clojure :exclude [format])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.nhsnumber :as nnn]
            [integrant.core :as ig]
            [pc4.demographic.identifier :as identifier]
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
(s/def ::systems (s/coll-of ::system :kind vector? :min-count 1))
(s/def ::remote boolean?)

(s/def ::provider
  (s/keys :req-un [::id ::title ::svc ::systems]
          :opt-un [::remote]))

(s/def ::providers
  (s/coll-of ::provider))

(s/def ::config
  (s/keys :req-un [::providers]))

(defmethod ig/init-key ::svc
  [_ {:keys [providers] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "Invalid demographic service configuration"
                    (s/explain-data ::config config))))
  (log/info "creating demographic service with " (count providers) " providers")
  config)

(defn normalize
  "Normalize an identifier value for the given system."
  [system value]
  (identifier/normalize system value))

(defn validate
  "Validate an identifier value for the given system. Returns true if valid, false otherwise."
  [system value]
  (identifier/validate system value))

(defn format
  "Format an identifier value for display."
  [system value]
  (identifier/format system value))

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
   (let [normalized-value (normalize system value)
         by-provider-id (if provider-id (fn [{:keys [id]}] (= id provider-id)) (constantly true))
         by-system (if ask-all-systems (constantly true) (fn [{:keys [systems]}] (or (not systems) (some #{system} systems))))]
     (loop [providers (filter #(and (by-provider-id %) (by-system %)) providers)]
       (when-let [provider (first providers)]
         (if-let [patients (seq (p/fetch (:svc provider) system normalized-value))]
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

(def known-systems
  [{:url    "https://fhir.nhs.uk/Id/nhs-number"
    :title  "NHS Number"}
   {:url   "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
    :title "CRN (Cardiff and Vale)"}
   {:url   "https://fhir.ctmuhb.nhs.wales/Id/pas-identifier"
    :title "CRN (Cwm Taf Morgannwg)"}
   {:url   "https://fhir.abuhb.nhs.wales/Id/pas-identifier"
    :title "CRN (Aneurin Bevan)"}
   {:url   "https://fhir.sbuhb.nhs.wales/Id/pas-identifier"
    :title "CRN (Swansea Bay)"}
   {:url   "https://fhir.hduhb.nhs.wales/Id/pas-identifier"
    :title "CRN (Hywel Dda)"}
   {:url   "https://fhir.bcuhb.nhs.wales/Id/pas-identifier"
    :title "CRN (Betsi Cadwaladr)"}
   {:url   "https://fhir.powys.nhs.wales/Id/pas-identifier"
    :title "CRN (Powys)"}
   {:url   "https://fhir.nhs.wales/Id/empi-number"
    :title "NHS Wales eMPI Number"}])

(def url->system
  (reduce (fn [acc {:keys [url] :as system}]
            (assoc acc url system)) {} known-systems))

(defn make-system
  [{:keys [id] :as provider} url]
  (if-let [system (url->system url)]
    (assoc system :id (str (name id) "|" url))
    (throw (ex-info (str "no system found for url: " url) {:provider provider :url url}))))

(defn available-providers
  "Return the list of configured external demographic providers.

  Parameters:
  - svc: Demographic service from Integrant configuration

  Returns sequence of provider maps, each containing:
  - :id      - provider identifier keyword (e.g., :wales-cav-pms)
  - :title   - human-readable provider name
  - :svc     - the provider service implementation
  - :systems - a sequence of systems (:id :title :url)
  - :remote  - boolean indicating if this is a remote service

  Example:
  ```
  (available-providers svc)
  =>
  [{:id :wales-cav-pms
    :title \"Synthetic CAVUHB PMS\"
    :svc #<..>
    :systems [{:id \"wales-cav-pms|https://fhir.nhs.uk/Id/nhs-number\"
               :url \"https://fhir.nhs.uk/Id/nhs-number\"
               :title \"NHS Number\"
               :valid? #<..>}
              {:id \"wales-cav-pms|https://fhir.cavuhb.nhs.wales/Id/pas-identifier\"
               :url \"https://fhir.cavuhb.nhs.wales/Id/pas-identifier\"
               :title \"CRN (Cardiff and Vale)\"}]}
    {:id :wales-empi
     :title \"Synthetic NHS Wales eMPI\"
     :svc #<..>
     :systems nil
     :remote true}]
  ```"
  [{:keys [providers]}]
  (mapv
    (fn [provider]
      (update provider :systems
              (fn [urls]
                (when (seq urls)
                  (mapv (fn [url]
                          (make-system provider url))
                        urls)))))
    providers))

(defn parse-provider-system-id
  "Parse composite provider-system id back into provider-id and system.

  Format: 'provider-id|system-url' or just 'provider-id' if no system."
  [s]
  (let [[provider-id system] (str/split s #"\|" 2)]
    (cond-> {:provider-id (keyword provider-id)}
      system (assoc :system system))))

(defn provider-by-id
  "Return the provider with the given id, or nil if not found."
  [{:keys [providers]} provider-id]
  (some #(when (= provider-id (:id %)) %) providers))

(comment
  (parse-provider-system-id "floo")
  (require '[pc4.config.interface :as config])
  (config/config :dev)
  (available-providers (::svc (config/config :dev))))
