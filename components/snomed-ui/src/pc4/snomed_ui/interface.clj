(ns pc4.snomed-ui.interface
  (:require [clojure.spec.alpha :as s]
            [integrant.core :as ig]
            [io.pedestal.interceptor :as intc]
            [pc4.snomed-ui.impl :as impl]))

(s/def ::hermes some?)
(s/def ::config (s/keys :req-un [::hermes]))
(s/def ::svc (s/keys :req-un [::hermes]))

(defmethod ig/init-key ::svc
  [_ {:keys [hermes] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid snomedui configuration" (s/explain-data ::config config))))
  config)

(s/def ::interceptors (s/coll-of ::interceptor :kind vector?))
(s/def ::params (s/keys :opt-un [::interceptors]))

(s/fdef routes
  :args (s/cat :svc ::svc :params (s/? ::params)))
(defn routes
  "Returns the routes for the hermes-ui service.
  Parameters:
  - svc : hermes-ui service
  - interceptors - a vector of interceptors to be prefixed, optional."
  ([svc]
   (routes svc {}))
  ([{:keys [hermes] :as svc} {:keys [interceptors]}]
   (when-not (s/valid? ::svc svc)
     (throw (ex-info "invalid hermes-ui service:" (s/explain-data ::svc svc))))
   #{["/ui/snomed/autocomplete" :post (conj interceptors impl/autocomplete-handler) :route-name :snomed/autocomplete]
     ["/ui/snomed/autocomplete-results" :post (conj interceptors (impl/autocomplete-results-handler hermes)) :route-name :snomed/autocomplete-results]
     ["/ui/snomed/autocomplete-selected-result" :post (conj interceptors (impl/autocomplete-selected-result-handler hermes)) :route-name :snomed/autocomplete-selected-result]}))

(defn ui-select-snomed
  "SNOMED CT concept selection control. Provides HTML select/autocomplete UI
  that can be dropped into any form. Users can select from common concepts
  if provided, or choose to search using an autocompletion textfield. To use
  this, you must also include [[routes]] into your web application
  routing table.

  Parameters:
  - :id             - Control identifier
  - :name           - Form field name (required)
  - :disabled       - Whether control is disabled
  - :selected-concept - Currently selected SNOMED concept
  - :common-concepts  - Collection of common SNOMED concepts for dropdown
  - :ecl            - SNOMED ECL expression for search filtering (required)
  - :max-hits       - Maximum search results (default 512)
  - :placeholder    - Search field placeholder text
  - :size           - Control size hint"
  [params]
  (impl/ui-select-autocomplete params))

(def parse-id+term
  impl/parse-id+term)

(def make-id+term
  impl/make-id+term)