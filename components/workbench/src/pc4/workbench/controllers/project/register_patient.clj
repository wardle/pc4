(ns pc4.workbench.controllers.project.register-patient
  "Patient registration workflow controllers."
  (:require
    [clojure.string :as str]
    [com.eldrix.nhsnumber :as nnn]
    [io.pedestal.http.route :as route]
    [pc4.demographic.interface :as demographic]
    [pc4.fhir.interface :as fhir]
    [pc4.log.interface :as log]
    [pc4.pathom-web.interface :as pw]
    [pc4.rsdb.interface :as rsdb]
    [pc4.ui.interface :as ui]
    [pc4.web.interface :as web]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; View helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fhir-patient->banner
  "Convert FHIR Patient data to banner template format."
  [{:org.hl7.fhir.Patient/keys [name gender birthDate identifier address deceased]}]
  (let [{human-name-text :org.hl7.fhir.HumanName/text} (fhir/human-name-text (fhir/best-human-name name))
        {nhs-number :org.hl7.fhir.Identifier/value} (fhir/best-identifier "https://fhir.nhs.uk/Id/nhs-number" identifier)
        {:org.hl7.fhir.Address/keys [line city postalCode]} (fhir/best-address address)
        address-text (str/join ", " (remove str/blank? (concat line [city postalCode])))
        [date-death deceased?] (cond
                                 (instance? java.time.LocalDate deceased) [deceased true]
                                 (instance? java.time.LocalDateTime deceased) [(.toLocalDate deceased) true]
                                 (true? deceased) [nil true]
                                 :else [nil false])]
    {:name         human-name-text
     :gender       gender
     :date-birth   birthDate
     :nhs-number   (nnn/format-nnn nhs-number)
     :address      address-text
     :deceased     deceased?
     :date-death   date-death
     :pseudonymous false}))

(defn patient-banners-by-pk
  "Return banner data for each patient primary key."
  [request patient-ids]
  (when-let [ids (seq patient-ids)]
    (let [query (map (fn [pk] {[:t_patient/id pk] [:t_patient/patient_identifier :ui/patient-banner]}) ids)
          result (pw/process {} request query)]
      (keep (fn [pk]
              (let [ident [:t_patient/id pk]]
                (when-let [identifier (get-in result [ident :t_patient/patient_identifier])]
                  {:patient-identifier identifier
                   :banner             (get-in result [ident :ui/patient-banner])
                   :url                (route/url-for :patient/home :path-params {:patient-identifier identifier})})))
            ids))))

(defn render-local-patient
  "Render view for a local patient match.

  Core params:
  - banner - patient banner data
  - csrf-token - CSRF token
  - patient-pk - patient primary key
  - view-url - URL to patient record

  Optional params:
  - register-url - URL to register (only if not already registered)
  - status-message - message to display (e.g. already registered)
  - success-message - success message after registration"
  [{:keys [banner csrf-token patient-pk view-url register-url status-message success-message]}]
  (ui/render-file "templates/project/register-patient-found.html"
                  {:banner          banner
                   :csrf-token      csrf-token
                   :patient-pk      patient-pk
                   :view-url        view-url
                   :register-url    register-url
                   :status-message  status-message
                   :success-message success-message}))

(defn render-external-patient
  "Render view for an external patient match.

  Core params:
  - fhir-patient - FHIR patient data
  - csrf-token - CSRF token
  - provider-title - name of external provider

  Optional params for registration:
  - register-url - URL to register (only if can register)
  - provider-id - provider identifier (for form)
  - system - identifier system (for form)
  - value - identifier value (for form)

  Optional params for duplicates (blocks registration):
  - duplicate-ids - set of patient PKs that match (will be resolved to banners)

  Optional params for success:
  - success-message - success message after registration
  - view-url - URL to patient record

  Optional params for status:
  - status-message - custom status message
  - error-message - error message"
  [request {:keys [fhir-patient csrf-token provider-title register-url provider-id system value
                   duplicate-ids success-message view-url status-message error-message]}]
  (ui/render-file "templates/project/register-patient-external.html"
                  {:banner            (fhir-patient->banner fhir-patient)
                   :csrf-token        csrf-token
                   :provider-title    provider-title
                   :provider-id       (some-> provider-id name)
                   :system            system
                   :value             value
                   :register-url      register-url
                   :duplicate-banners (patient-banners-by-pk request duplicate-ids)
                   :success-message   success-message
                   :view-url          view-url
                   :status-message    status-message
                   :error-message     error-message}))

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
    [{:ui/current-project [:t_project/id :t_project/title]}
     :ui/csrf-token]
    (fn [request {:ui/keys [current-project csrf-token]}]
      (let [rsdb-svc (get-in request [:env :rsdb])
            demographic-svc (get-in request [:env :demographic])
            project-id (get-in current-project [:t_project/id])
            project-title (:t_project/title current-project)
            provider-system-id (get-in request [:params "provider-system-id"])
            {:keys [provider-id system]} (demographic/parse-provider-system-id provider-system-id)
            value (some-> (get-in request [:params "value"]) (str/replace #"\s" ""))
            result (rsdb/project-patient-search rsdb-svc demographic-svc
                                                {:system      system
                                                 :value       value
                                                 :project-id  project-id
                                                 :provider-id provider-id})]
        (web/ok
          (case (:outcome result)
            :local-match
            (let [{:keys [patient-pk patient-identifier already-registered?]} result
                  patient-ident [:t_patient/id patient-pk]
                  query [{patient-ident [:ui/patient-banner]}]
                  pathom-result (pw/process {} request query)
                  banner (get-in pathom-result [patient-ident :ui/patient-banner])
                  view-url (route/url-for :patient/home :path-params {:patient-identifier patient-identifier})
                  register-url (when-not already-registered?
                                 (route/url-for :project/register-internal-patient-action
                                                :path-params {:project-id project-id}))
                  status-message (when already-registered?
                                   (str "This patient is already registered to '" project-title "'."))]
              (render-local-patient
                {:banner         banner
                 :csrf-token     csrf-token
                 :patient-pk     patient-pk
                 :view-url       view-url
                 :register-url   register-url
                 :status-message status-message}))

            :local-multiple
            (ui/render (ui/alert-warning
                         {:title   "Multiple matches found"
                          :message (str "Multiple patients match this identifier. Please contact support to resolve this issue. Patient IDs: "
                                        (pr-str (:patient-pks result)))}))

            :external-match
            (let [{:keys [fhir-patient provider-id provider-title system value duplicate-ids]} result
                  register-url (when-not (seq duplicate-ids)
                                 (route/url-for :project/register-external-patient-action
                                                :path-params {:project-id project-id}))]
              (render-external-patient
                request
                {:fhir-patient   fhir-patient
                 :provider-id    provider-id
                 :provider-title provider-title
                 :system         system
                 :value          value
                 :csrf-token     csrf-token
                 :register-url   register-url
                 :duplicate-ids  duplicate-ids}))

            :external-multiple
            (ui/render (ui/alert-error
                         {:title   "Multiple patients returned"
                          :message "The external provider returned multiple patients. This should not happen. Please contact support."}))

            :not-found
            (let [{:keys [value provider-title]} result]
              (ui/render-file "templates/project/register-patient-not-found.html"
                              {:value value
                               :provider-title provider-title}))

            ;; Default case - should not happen
            (ui/render (ui/alert-error
                         {:title   "Unexpected Error"
                          :message (str "Unexpected outcome: " (:outcome result))}))))))))

(def register-internal-patient-action
  "Register an existing internal patient to the project.

  This handler is for patients already in the local database.

  POST params:
  - patient-pk: internal patient primary key (required)
  - open-record: if 'true', redirect to patient record after registration"
  (pw/handler
    [{:ui/current-project [:t_project/id :t_project/title]}
     :ui/csrf-token]
    (fn [request {:ui/keys [current-project csrf-token]}]
      (let [rsdb-svc (get-in request [:env :rsdb])
            project-id (get-in current-project [:t_project/id])
            project-title (:t_project/title current-project)
            user-id (get-in request [:session :authenticated-user :t_user/id])
            patient-pk (some-> (get-in request [:params "patient-pk"]) parse-long)
            open-record? (= "true" (get-in request [:params "open-record"]))
            register-url (route/url-for :project/register-internal-patient-action
                                        :path-params {:project-id project-id})]

        (cond
          (nil? user-id)
          (web/forbidden "User not authenticated")

          (nil? patient-pk)
          (web/bad-request "patient-pk is required")

          :else
          (let [patient-ident [:t_patient/id patient-pk]
                query [{patient-ident [:t_patient/patient_identifier
                                       :ui/patient-banner
                                       (list :t_patient/episodes {:t_project/id     project-id
                                                                  :t_episode/status :registered})]}]
                initial (pw/process {} request query)
                patient-identifier (get-in initial [patient-ident :t_patient/patient_identifier])
                banner (get-in initial [patient-ident :ui/patient-banner])
                episodes (get-in initial [patient-ident :t_patient/episodes])
                already-registered? (seq episodes)]

            (if already-registered?
              ;; Already registered - just show status
              (let [view-url (route/url-for :patient/home
                                            :path-params {:patient-identifier patient-identifier})
                    status-message (str "This patient is already registered to '" project-title "'.")]
                (if open-record?
                  (web/hx-redirect view-url)
                  (web/ok
                    (render-local-patient
                      {:banner         banner
                       :csrf-token     csrf-token
                       :patient-pk     patient-pk
                       :view-url       view-url
                       :status-message status-message}))))

              ;; Not registered - perform registration
              (do
                (log/info "Registering internal patient to project"
                          {:patient-id patient-pk :project-id project-id :user-id user-id})
                (rsdb/register-patient-project! rsdb-svc project-id user-id {:t_patient/id patient-pk})

                (let [refreshed (pw/process {} request query)
                      banner* (get-in refreshed [patient-ident :ui/patient-banner])
                      view-url (route/url-for :patient/home
                                              :path-params {:patient-identifier patient-identifier})
                      success-message "Patient successfully registered to project"]
                  (if open-record?
                    (web/hx-redirect view-url)
                    (web/ok
                      (render-local-patient
                        {:banner          banner*
                         :csrf-token      csrf-token
                         :patient-pk      patient-pk
                         :view-url        view-url
                         :success-message success-message}))))))))))))

(def register-external-patient-action
  "Register a patient from an external demographic provider.

  This handler:
  1. Fetches patient from external provider
  2. Checks for exact matches (identifier or demographics)
  3. Blocks if matches found
  4. Otherwise creates/updates patient and registers to project

  POST params:
  - external-provider-id: provider keyword (required)
  - external-system: identifier system (required)
  - external-value: identifier value (required)
  - open-record: if 'true', redirect to patient record after registration"
  (pw/handler
    [{:ui/current-project [:t_project/id :t_project/title]}
     :ui/csrf-token]
    (fn [request {:ui/keys [current-project csrf-token]}]
      (let [rsdb-svc (get-in request [:env :rsdb])
            demographic-svc (get-in request [:env :demographic])
            project-id (get-in current-project [:t_project/id])
            user-id (get-in request [:session :authenticated-user :t_user/id])
            external-provider-id (some-> (get-in request [:params "external-provider-id"]) not-empty keyword)
            external-system (some-> (get-in request [:params "external-system"]) str/trim)
            external-value (some-> (get-in request [:params "external-value"]) (str/replace #"\s" ""))
            open-record? (= "true" (get-in request [:params "open-record"]))
            register-url (route/url-for :project/register-external-patient-action
                                        :path-params {:project-id project-id})]

        (cond
          (nil? user-id)
          (web/forbidden "User not authenticated")

          (or (nil? external-provider-id)
              (str/blank? external-system)
              (str/blank? external-value))
          (web/bad-request "external-provider-id, external-system, and external-value are required")

          :else
          (let [provider (demographic/provider-by-id demographic-svc external-provider-id)
                provider-title (:title provider)
                raw-patients (demographic/patients-by-identifier demographic-svc external-system external-value {:provider-id external-provider-id})
                fhir-patients (vec (or raw-patients []))
                n-remote (count fhir-patients)]

            (cond
              ;; No patient found
              (zero? n-remote)
              (web/ok
                (ui/render-file "templates/project/register-patient-not-found.html"
                                {:value external-value
                                 :provider-title (or provider-title "the external demographic service")}))

              ;; Multiple patients found
              (> n-remote 1)
              (web/ok
                (ui/render (ui/alert-error
                             {:title   "Multiple patients returned"
                              :message "The external provider returned multiple patients. This should not happen. Please contact support."})))

              ;; Single patient - check for duplicates and register
              :else
              (let [fhir-patient (first fhir-patients)
                    exact-by-identifier (or (rsdb/exact-match-by-identifier rsdb-svc fhir-patient) #{})
                    exact-by-demography (or (rsdb/exact-match-on-demography rsdb-svc fhir-patient) #{})
                    all-matches (into exact-by-identifier exact-by-demography)]

                (if (seq all-matches)
                  ;; Block registration - show matching patients
                  (web/ok
                    (render-external-patient
                      request
                      {:fhir-patient   fhir-patient
                       :provider-id    external-provider-id
                       :provider-title provider-title
                       :system         external-system
                       :value          external-value
                       :csrf-token     csrf-token
                       :duplicate-ids  all-matches}))

                  ;; No matches - safe to register
                  (try
                    (let [{:keys [patient-pk created?]} (rsdb/upsert-patient-from-fhir! rsdb-svc fhir-patient)]
                      (log/info "Registering external patient to project"
                                {:patient-id patient-pk :project-id project-id :user-id user-id :created? created?})
                      (rsdb/register-patient-project! rsdb-svc project-id user-id {:t_patient/id patient-pk})

                      (let [patient-ident [:t_patient/id patient-pk]
                            query [{patient-ident [:t_patient/patient_identifier]}]
                            result (pw/process {} request query)
                            patient-identifier (get-in result [patient-ident :t_patient/patient_identifier])
                            view-url (route/url-for :patient/home :path-params {:patient-identifier patient-identifier})
                            success-message (if created?
                                              "Patient successfully created and registered to project"
                                              "Patient successfully registered to project")]
                        (if open-record?
                          (web/hx-redirect view-url)
                          (web/ok
                            (render-external-patient
                              request
                              {:fhir-patient    fhir-patient
                               :provider-id     external-provider-id
                               :provider-title  provider-title
                               :system          external-system
                               :value           external-value
                               :csrf-token      csrf-token
                               :success-message success-message
                               :view-url        view-url})))))

                    (catch clojure.lang.ExceptionInfo ex
                      (let [data (ex-data ex)]
                        (if-let [matching (:matching-patient-ids data)]
                          ;; Race condition - matches found during upsert
                          (web/ok
                            (render-external-patient
                              request
                              {:fhir-patient   fhir-patient
                               :provider-id    external-provider-id
                               :provider-title provider-title
                               :system         external-system
                               :value          external-value
                               :csrf-token     csrf-token
                               :duplicate-ids  matching
                               :error-message  "Multiple patients match the given identifiers. Please resolve the duplicates before registering."}))
                          (throw ex))))

                    (catch Exception ex
                      (log/error ex "Failed to create or register patient from external source"
                                 {:project-id project-id :provider external-provider-id})
                      (web/server-error "Unable to register patient at this time. Please try again."))))))))))))
