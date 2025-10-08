(ns pc4.workbench.controllers.project.register-patient
  "Patient registration workflow controllers.

  Thin HTTP layer that delegates to rsdb for all business logic and database operations."
  (:require
   [clojure.edn :as edn]
   [io.pedestal.http.route :as route]
   [next.jdbc :as jdbc]
   [pc4.demographic.interface :as demographic]
   [pc4.log.interface :as log]
   [pc4.pathom-web.interface :as pw]
   [pc4.rsdb.interface :as rsdb]
   [pc4.ui.interface :as ui]
   [pc4.web.interface :as web]))

(def register-patient-form
  "Display patient registration form.

  Shows:
  - Provider dropdown (external demographic services)
  - Identifier type selection (NHS number, CRN, etc.)
  - Value input field
  - Search button with HTMX"
  (pw/handler
   [:ui/navbar
    {:ui/current-project [:t_project/id (list :ui/project-menu {:selected :register-patient})]}
    :ui/csrf-token]
   (fn [request {:ui/keys [navbar current-project csrf-token]}]
     (let [project-id (get-in current-project [:t_project/id])
           demographic-svc (get-in request [:env :demographic])
           providers (demographic/available-providers demographic-svc)]
       (web/ok
        (ui/render-file
         "templates/project/register-patient-page.html"
         {:title      "Register Patient"
          :project-id project-id
          :navbar     navbar
          :menu       (:ui/project-menu current-project)
          :csrf-token csrf-token
          :providers  providers
          :search-url (route/url-for :project/register-patient-search :path-params {:project-id project-id})}))))))

(def search-patient
  "Search for patient by identifier (local database first, then external providers).

  POST params:
  - provider-system-id: composite id (e.g. 'wales-cav-pms|https://fhir.nhs.uk/Id/nhs-number')
  - value: identifier value

  Returns HTMX fragment with search results."
  (pw/handler
   [{:ui/current-project [:t_project/id]}
    :ui/csrf-token]
   (fn [request {:ui/keys [current-project csrf-token]}]
     (let [rsdb-svc (get-in request [:env :rsdb])
           demographic-svc (get-in request [:env :demographic])
           project-id (get-in current-project [:t_project/id])
           provider-system-id (get-in request [:params "provider-system-id"])
           {:keys [provider-id system]} (demographic/parse-provider-system-id provider-system-id)
           value (some-> (get-in request [:params "value"]) (clojure.string/replace #"\s" ""))
           patient {:org.hl7.fhir.Patient/identifier
                    [{:org.hl7.fhir.Identifier/system system
                      :org.hl7.fhir.Identifier/value value}]}
           local-pks (rsdb/exact-match-by-identifier rsdb-svc patient)
           n-local-pks (count local-pks)]
       (web/ok
         (cond
           (> n-local-pks 1)
           (ui/render [:div [:h3 "Multiple matches"] local-pks])
           (= n-local-pks 1)
           (ui/render [:div [:h3 "Single existing match" (first local-pks)]])
           :else
           (let [fhir-patients (demographic/patients-by-identifier demographic-svc system value {:provider-id provider-id})]
             (ui/render [:div [:h3 "External patients:" fhir-patients]]))))))))
