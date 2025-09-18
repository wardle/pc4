(ns pc4.arafc.interface
  "ARAF clinician-facing service providing:
  - creation of a patient 'araf' request
  - checking status of a patient 'araf' request
  - generation of a QR code given a long access key
  - a set of http routes for a web application managing araf workflows, to
  be embedded within a larger application, or as a standalone mini-application."
  (:require
    [clojure.spec.alpha :as s]
    [hato.client :as hc]
    [integrant.core :as ig]
    [pc4.arafc.impl.client :as client]
    [pc4.arafc.impl.qr :as qr]
    [pc4.log.interface :as log]))

(s/def ::url string?)
(s/def ::http-client-options map?)                          ;; see hato client documentation
(s/def ::http-client some?)
(s/def ::config (s/keys :req-un [::url ::secret] :opt-un [::http-client-options]))
(s/def ::svc (s/keys :req-un [::url ::secret ::http-client]))

(defmethod ig/init-key ::svc
  [_ {:keys [url http-client-options] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid araf-clinician configuration" (s/explain-data ::config config))))
  (log/info "starting araf clinician service to remote patient server:" {:url url})
  (-> config
      (assoc :http-client (hc/build-http-client http-client-options))))

(defmethod ig/halt-key! ::svc
  [_ _service]
  (log/info "stopping araf clinician service"))

(s/def ::size pos-int?)

(s/fdef generate-qr-code
        :args (s/cat :svc ::svc :long-access-key string? :opts (s/? (s/keys :opt-un [::size]))))
(defn generate-qr-code
  "Generates a QR code for the given base URL and long access key.
   Returns a byte array of the PNG image."
  ([svc long-access-key]
   (generate-qr-code svc long-access-key {}))
  ([{:keys [url]} long-access-key options]                  ;; TODO: could generate URL dynamically from routing table?
   (qr/generate (str url "araf/form/" long-access-key) options)))

(s/fdef create-request
        :args (s/cat :svc ::svc :params ::client/create-request-params))
(defn create-request
  "Create an ARAF request via the clinician service (calls patient server API).

  Parameters:
  - svc    : clinician service
  - params : map with :nhs-number, :araf-type, :expires

  Returns the created request from the patient server."
  [svc params]
  (client/create-request svc params))

(s/fdef get-request
        :args (s/cat :svc ::svc :long-access-key ::client/long-access-key))
(defn get-request
  "Fetch an ARAF request via the clinician service (calls patient server API).

  Parameters:
  - svc              : clinician service
  - long-access-key  : the request's long access key

  Returns the request from the patient server."
  [svc long-access-key]
  (client/get-request svc long-access-key))


