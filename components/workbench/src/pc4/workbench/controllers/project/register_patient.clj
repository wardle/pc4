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
            provider-system-id (get-in request [:params "provider-system-id"])
            {:keys [provider-id system]} (demographic/parse-provider-system-id provider-system-id)
            {:keys [title]} (demographic/provider-by-id demographic-svc provider-id)
            value (some-> (get-in request [:params "value"]) (clojure.string/replace #"\s" ""))
            patient {:org.hl7.fhir.Patient/identifier
                     [{:org.hl7.fhir.Identifier/system system
                       :org.hl7.fhir.Identifier/value  value}]}
            local-pks (rsdb/exact-match-by-identifier rsdb-svc patient)
            n-local-pks (count local-pks)]
        (web/ok
          (cond
            ;; multiple matches - show an error condition
            (> n-local-pks 1)
            (ui/render [:div [:h3 "Multiple matches"] [:pre local-pks]])

            ;; single local match - render banner and actions
            (= n-local-pks 1)
            (let [patient-pk (first local-pks)
                  patient-ident [:t_patient/id patient-pk]
                  result (pw/process {} request
                                     [{patient-ident [:t_patient/patient_identifier
                                                      :ui/patient-banner
                                                      (list :t_patient/episodes {:t_project/id     project-id
                                                                                :t_episode/status :registered})]}])
                  banner (get-in result [patient-ident :ui/patient-banner])
                  patient-identifier (get-in result [patient-ident :t_patient/patient_identifier])
                  already-registered (seq (get-in result [patient-ident :t_patient/episodes]))]
              (let [project-title (:t_project/title current-project)
                    status-message (when already-registered
                                     (str "This patient is already registered to '" project-title "'."))]
                (ui/render-file "templates/project/register-patient-found.html"
                                {:banner                    banner
                                 :register-url              (route/url-for :project/register-patient-action
                                                                             :path-params {:project-id project-id})
                                 :csrf-token                csrf-token
                                 :patient-pk                patient-pk
                                 :view-url                  (route/url-for :patient/home
                                                                             :path-params {:patient-identifier patient-identifier})
                                 :success-message           nil
                                 :status-message            status-message
                                 :show-register-form        (not already-registered)
                                 :register-label            "Register"
                                 :show-register-and-view-form (not already-registered)
                                 :register-and-view-label   "Register and view patient record >"
                                 :view-link-label           "View patient record >"})))
            :else
            (let [fhir-patients (demographic/patients-by-identifier demographic-svc system value {:provider-id provider-id})
                  n-remote (count fhir-patients)
                  matches (into (set (mapcat #(rsdb/exact-match-by-identifier rsdb-svc %) fhir-patients))
                                (mapcat #(rsdb/exact-match-on-demography rsdb-svc %) fhir-patients))]
              (cond
                (> n-remote 1)
                (ui/render [:div [:h3 "Multiple external" fhir-patients]
                            [:h3 "Matches:" matches]])
                (zero? n-remote)
                (ui/render-file "ui/templates/box-info-message.html"
                                {:title   "Patient Not Found"
                                 :message (str "<p>No patient found matching \"<strong class='text-gray-900'>" value "</strong>\" in the local database or " title ".</p>"
                                               "<p class='mt-2'>Please check the identifier and try again, or contact support if you believe this patient should exist.</p>")})
                :else
                (ui/render [:div
                            [:h3 "Single external patient:" (first fhir-patients)]
                            [:h3 "Matches:" matches]])))))))))

(def register-patient-action
  "Register a patient to the project.

  POST params:
  - patient-pk: internal patient primary key
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
            open-record? (= "true" (get-in request [:params "open-record"]))]
        (cond
          (nil? patient-pk)
          (web/bad-request "patient-pk is required")

          (nil? user-id)
          (web/forbidden "User not authenticated")

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
                already-registered (seq episodes)
                newly-registered?
                (when-not already-registered
                  (log/info "Registering patient to project"
                            {:patient-id patient-pk :project-id project-id :user-id user-id})
                  (rsdb/register-patient-project! rsdb-svc project-id user-id {:t_patient/id patient-pk})
                  true)
                refreshed (if newly-registered?
                            (pw/process {} request query)
                            initial)
                banner* (get-in refreshed [patient-ident :ui/patient-banner])
                registered (seq (get-in refreshed [patient-ident :t_patient/episodes]))
                show-register-form (not registered)
                show-register-and-view-form show-register-form
                show-already-registered? (and registered (not newly-registered?))
                status-message (when show-already-registered?
                                 (str "This patient is already registered to '" project-title "'."))
                view-url (route/url-for :patient/home
                                        :path-params {:patient-identifier patient-identifier})
                register-url (route/url-for :project/register-patient-action
                                            :path-params {:project-id project-id})
                success-message (when newly-registered? "Patient successfully registered to project")]
            (if open-record?
              (web/hx-redirect view-url)
              (web/ok
                (ui/render-file "templates/project/register-patient-found.html"
                                {:banner                    (or banner* banner)
                                 :register-url              register-url
                                 :csrf-token                csrf-token
                                 :patient-pk                patient-pk
                                 :view-url                  view-url
                                 :success-message           success-message
                                 :status-message            status-message
                                 :show-register-form        show-register-form
                                 :register-label            "Register"
                                 :show-register-and-view-form show-register-and-view-form
                                 :register-and-view-label   "Register and view patient record >"
                                 :view-link-label           "View patient record >"})))))))))
