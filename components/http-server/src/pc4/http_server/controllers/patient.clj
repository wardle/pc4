(ns pc4.http-server.controllers.patient
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [com.eldrix.hermes.core :as hermes]
    [com.eldrix.nhsnumber :as nnn]
    [com.wsscode.pathom3.connect.operation :as pco]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.http-server.controllers.snomed :as snomed]
    [pc4.http-server.controllers.user :as user]
    [pc4.http-server.pathom :as p]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.web :as web]
    [pc4.http-server.ui :as ui]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]
    [pc4.snomedct.interface :as snomedct])
  (:import (java.time LocalDate)))

(defn safe-parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s)))

(pco/defresolver current-patient
  [{:keys [request]} _]
  {::pco/output [{:ui/current-patient [:t_patient/patient_identifier]}]}
  {:ui/current-patient
   {:t_patient/patient_identifier (some-> (get-in request [:path-params :patient-identifier]) parse-long)}})

(pco/defresolver current-diagnosis
  [{:keys [request]} _]
  {::pco/output [{:ui/current-diagnosis [:t_diagnosis/id]}]}
  {:ui/current-diagnosis
   {:t_diagnosis/id (some-> (get-in request [:path-params :diagnosis-id]) parse-long)}})

(pco/defresolver patient->best-hospital-crn
  [{rsdb :com.eldrix/rsdb} {current-project :ui/current-project, hospitals :t_patient/hospitals}]
  {::pco/input  [{:ui/current-project [:t_project/id]}
                 {:t_patient/hospitals [:t_patient_hospital/authoritative_demographics
                                        :t_patient_hospital/hospital_fk
                                        :t_patient_hospital/hospital_identifier
                                        :t_patient_hospital/patient_identifier
                                        :t_patient_hospital/authoritative]}]
   ::pco/output [{:t_patient/hospital_crn [:t_patient_hospital/patient_identifier
                                           :t_patient_hospital/hospital_fk]}]}
  {:t_patient/hospital_crn
   ((rsdb/make-best-hospital-crn-fn rsdb (:t_project/id current-project)) hospitals)})

(pco/defresolver patient-banner
  [{:t_patient/keys [patient_identifier break_glass date_birth date_death status sex address lsoa11 hospital_crn]
    patient-name    :uk.nhs.cfh.isb1506/patient-name
    age             :uk.nhs.cfh.isb1505/display-age
    nnn             :uk.nhs.cfh.isb1504/nhs-number
    e-lsoa-name     :england-imd-2019-ranks/lsoa_name
    w-lsoa-name     :wales-imd-2019-ranks/lsoa_name :as params}]
  {::pco/input  [:t_patient/patient_identifier
                 :t_patient/break_glass
                 {:t_patient/address [:uk.nhs.cfh.isb1500/address-horizontal]}
                 :uk.nhs.cfh.isb1506/patient-name
                 :uk.nhs.cfh.isb1505/display-age :uk.nhs.cfh.isb1504/nhs-number
                 :t_patient/lsoa11
                 (pco/? :wales-imd-2019-ranks/lsoa_name)
                 (pco/? :england-imd-2019-ranks/lsoa_name)
                 :t_patient/sex :t_patient/status
                 :t_patient/date_birth :t_patient/date_death
                 {:t_patient/hospital_crn [:t_patient_hospital/patient_identifier]}]
   ::pco/output [:ui/patient-banner]}
  {:ui/patient-banner
   (cond-> {:name         patient-name
            :gender       (when sex (name sex))
            :nhs-number   nnn
            :crn          (:t_patient_hospital/patient_identifier hospital_crn)
            :date-birth   date_birth
            :break-glass? break_glass
            :age          age
            :date-death   date_death
            :address      (if address (:uk.nhs.cfh.isb1500/address-horizontal address)
                                      (if date_death "No known address" "No known current address"))
            :pseudonymous false}
     (= status :PSEUDONYMOUS)                               ;; if pseudonymous, replace some details
     (-> (assoc :pseudonymous true
                :name (some-> sex name str/upper-case)
                :address (or e-lsoa-name w-lsoa-name lsoa11))
         (dissoc :nhs-number :gender)))})

(pco/defresolver patient-menu
  [{:keys [request menu] :com.eldrix/keys [hermes] :as env}
   {:t_patient/keys [patient_identifier diagnoses permissions] :as patient}]
  {::pco/input  [:t_patient/patient_identifier :t_patient/diagnoses :t_patient/permissions]
   ::pco/output [:ui/patient-menu]}
  (let [selected menu
        can-edit? (permissions :PATIENT_EDIT)
        diagnosis-ids (set (map :t_diagnosis/concept_fk (filter rsdb/diagnosis-active? diagnoses)))
        ninflamm (snomedct/intersect-ecl hermes diagnosis-ids "<<39367000")
        epilepsy (snomedct/intersect-ecl hermes diagnosis-ids "<<128613002")
        mnd (snomedct/intersect-ecl hermes diagnosis-ids "<<37340000")]
    {:ui/patient-menu
     {:selected selected
      :items    [{:id   :home
                  :url  (route/url-for :patient/home :path-params {:patient-identifier patient_identifier})
                  :text "Home"}
                 {:id   :diagnoses
                  :url  (route/url-for :patient/diagnoses :path-params {:patient-identifier patient_identifier})
                  :text "Diagnoses"}
                 (when (seq mnd)
                   {:id   :mnd
                    :sub  true
                    :url  (route/url-for :patient/motorneurone :path-params {:patient-identifier patient_identifier})
                    :text "Motor neurone disease"})
                 (when (seq ninflamm)
                   {:id   :relapses
                    :sub  true
                    :url  (route/url-for :patient/neuroinflammatory :path-params {:patient-identifier patient_identifier})
                    :text "Neuro-inflammatory"})
                 (when (seq epilepsy)
                   {:id   :epilepsy
                    :sub  true
                    :url  (route/url-for :patient/epilepsy :path-params {:patient-identifier patient_identifier})
                    :text "Epilepsy / seizure disorder"})
                 {:id   :medication
                  :url  (route/url-for :patient/medication :path-params {:patient-identifier patient_identifier})
                  :text "Medication"}

                 {:id   :encounters
                  :url  (route/url-for :patient/encounters :path-params {:patient-identifier patient_identifier})
                  :text "Encounters"}
                 {:id   :results
                  :url  (route/url-for :patient/results :path-params {:patient-identifier patient_identifier})
                  :text "Results"}
                 {:id   :admissions
                  :url  (route/url-for :patient/diagnoses :path-params {:patient-identifier patient_identifier})
                  :text "Admissions"}
                 {:id   :research
                  :url  (route/url-for :patient/diagnoses :path-params {:patient-identifier patient_identifier})
                  :text "Research"}]
      :submenu
      (case selected
        :diagnoses
        {:items [{:text "Add diagnosis..."
                  :url  (route/url-for :patient/edit-diagnosis :path-params {:patient-identifier patient_identifier
                                                                             :diagnosis-id       "new"})}]}
        {:items []})}}))

(pco/defresolver patient-page
  "Return data for a full patient page including main patient menu"
  [{:ui/keys [navbar current-patient]}]
  {::pco/input  [:ui/navbar
                 {:ui/current-patient [:ui/patient-banner
                                       :ui/patient-menu]}]
   ::pco/output [:ui/patient-page]}
  {:ui/patient-page
   {:navbar navbar
    :banner (:ui/patient-banner current-patient)
    :menu   (:ui/patient-menu current-patient)}})

(s/def ::ordered-diagnosis-dates
  (fn [{:t_diagnosis/keys [date_birth date_onset date_diagnosis date_to date_death date_now]}]
    (->> [date_birth date_onset date_diagnosis date_to date_death date_now]
         (remove nil?)
         (partition 2 1)
         (every? (fn [[a b]] (nat-int? (.compareTo b a)))))))

(s/def ::valid-diagnosis-status
  (fn [{:t_diagnosis/keys [date_to status]}]
    (if date_to
      (#{"INACTIVE_REVISED" "INACTIVE_IN_ERROR" "INACTIVE_RESOLVED"} status)
      (#{"ACTIVE"} status))))





(s/def ::patient
  (s/keys :req [:t_patient/id :t_patient/patient_identifier]))
(s/def ::project-id int?)
(s/def ::user-id int?)
(s/def ::redirect-url string?)
(s/def ::register-to-project
  (s/keys :req-un [::patient ::project-id ::user-id ::redirect-url]))

(pco/defresolver register-to-project-params
  [{:keys [request]} {:ui/keys [current-patient authenticated-user]}]
  {::pco/input [{:ui/current-patient [:t_patient/patient_identifier :t_patient/id]}
                {:ui/authenticated-user [:t_user/id]}]}
  {:params/register-to-project
   (let [data {:patient      current-patient
               :project-id   (some-> (get-in request [:params "project-id"]) parse-long)
               :user-id      (:t_user/id authenticated-user)
               :redirect-url (get-in request [:params "redirect-url"])}]
     {:data   data
      :valid? (s/valid? ::register-to-project data)})})

(def resolvers [current-patient current-diagnosis
                patient->best-hospital-crn
                patient-banner patient-menu
                patient-page
                register-to-project-params])

(defn patient->result
  [{:t_patient/keys [patient_identifier title first_names last_name date_birth date_death nhs_number]
    :t_address/keys [address1 address2 address3 address4 address5 postcode_raw]}]
  {:url         (route/url-for :patient/home :params {:patient-identifier patient_identifier})
   :title       title
   :first-names first_names
   :last-name   last_name
   :date-birth  date_birth
   :date-death  date_death
   :nhs-number  (nnn/format-nnn nhs_number)
   :address     (str/join ", " (remove str/blank? [address1 address2 address3 address4 address5 postcode_raw]))})

(def search-opts
  {:status   #{"FULL" "FAKE"}
   :query    {:select [[:t_patient/id] [:*]] :from :t_patient}
   :address? true})

(defn do-smart-search
  "Implements a patient 'smart search' which magically tries to find a matching patient.
  Returns a map that may contain :mode, :found, :patient, :results and :n-fallback.
  - :mode       : either :my or :all referencing the 'filter' used in the search.
  - :found      : one of :single, :multiple, :too-many, or :none
  - :patient    : a patient record if only a single patient matches
  - :patients   : a sequence of patient records if multiple matches
  - :n-fallback : number of records that would be found without a 'filter'.
  'n-fallback' will only be calculated if there is a filter applied."
  [rsdb ods authenticated-user {s :search, filter :filter, sorting :sort, hospital-identifier :hospital-identifier}]
  (let [mode (if (= "my" filter) :my :all)
        order-by (when-not (str/blank? sorting) (mapv keyword (str/split sorting #"\"")))
        project-ids (when (= "my" filter) (keys (:t_user/active_roles authenticated-user))) ;; when 'my' projects, limit to active projects of user
        n-results (:count (first (rsdb/patient-search rsdb (assoc search-opts :s s :project-ids project-ids :query {:select [[[:count :t_patient/id]]] :from :t_patient}))))
        too-many? (> n-results 1000)
        results (when-not too-many? (rsdb/patient-search rsdb (assoc search-opts :s s, :ods ods,
                                                                                 :hospital-identifier "7A4BV" ;; return CRNs in context of this org
                                                                                 :project-ids project-ids,
                                                                                 :order-by (or order-by [:name :asc]))))
        n-results (count results)
        n-fallback-results (when (and (not too-many?) (empty? results) (= "my" filter))
                             (:count (first (rsdb/patient-search rsdb (assoc search-opts :s s :query {:select [[[:count :t_patient/id]]] :from :t_patient})))))]
    (merge {:mode mode :s s}
           (cond
             (= 1 n-results)                                ;; one single result
             {:found   :single
              :patient (first results)}
             too-many?                                      ;; too many results
             {:found :too-many}
             (> n-results 1)                                ;; more than one result
             {:found    :multiple
              :patients results}
             (zero? n-results)                              ;; no result, but there may be results without applied filters
             {:found      :none
              :n-fallback n-fallback-results}))))

(defn search
  "Patient search - returns a fragment containing the search results, or a
  redirect directly to the patient record iff there is a single match."
  [request]
  (let [authenticated-user (get-in request [:session :authenticated-user])
        rsdb (get-in request [:env :rsdb])
        ods (get-in request [:env :ods])
        params (:form-params request)
        {:keys [mode found patient patients n-fallback]} (do-smart-search rsdb ods authenticated-user params)]
    (cond
      patient                                               ;; we have a single found patient -> go directly to record                            ;; single exact match -> load patient record
      (let [patient-identifier (:t_patient/patient_identifier patient)]
        (web/hx-redirect (route/url-for :patient/home :params {:patient-identifier patient-identifier})))

      (= found :none)
      (web/ok
        (web/render [:div (ui/box-error-message {:title   "Patient search"
                                                 :message (if (pos-int? n-fallback)
                                                            (str "No patients found registered to your services, but " n-fallback " patient(s) available if you search using 'all patients'.")
                                                            "No patients found.")})]))

      (= found :too-many)                                   ;; too many results -> return nothing
      (web/ok
        (web/render [:div (ui/box-error-message {:title "Patient search" :message "Too many results. Please use more specific search terms."})]))

      :else
      (web/ok
        (web/render-file
          "templates/patient/search-results.html"
          (assoc (user/session-env request)
            :patients (map patient->result patients)
            :fallback-search-url (when (and (= mode :my) (pos-int? n-fallback))
                                   (route/url-for :patient/search))))))))

(def authorized
  "Interceptor to check user is authorized to view this patient record.
  Redirects to 'break glass' route if no permission. "
  {:enter
   (fn [ctx]
     (let [rsdb (get-in ctx [:request :env :rsdb])
           patient-identifier (some-> (get-in ctx [:request :path-params :patient-identifier]) parse-long)
           authorization-manager (get-in ctx [:request :authorization-manager])
           patient-project-ids (rsdb/patient->active-project-identifiers rsdb patient-identifier)
           authorized? (rsdb/authorized? authorization-manager patient-project-ids :PATIENT_VIEW)
           authenticated-user (get-in ctx [:request :session :authenticated-user])
           break-glass? (= (get-in ctx [:request :session :break-glass]) patient-identifier)]
       (log/debug "checking authorization " {:username            (:t_user/username authenticated-user)
                                             :patient-identifier  patient-identifier
                                             :authorized?         authorized?
                                             :patient-project-ids patient-project-ids
                                             :user-roles          (:t_user/active_roles authenticated-user)})
       (if (or authorized? break-glass?)
         ctx
         (let [patient (rsdb/fetch-patient rsdb {:t_patient/patient_identifier patient-identifier})
               uri (get-in ctx [:request :uri])]
           (if patient
             (do (log/warn "user not authorised to access record" {:username (:t_user/username authenticated-user), :patient-identifier patient-identifier})
                 (assoc ctx :response (web/redirect-see-other (route/url-for :patient/break-glass :params {:patient-identifier patient-identifier
                                                                                                           :redirect-url       uri}))))
             (do (log/warn "user attempting to access record that does not exist" {:username (:t_user/username authenticated-user) :patient-identifier patient-identifier})
                 (assoc ctx :response (web/redirect-see-other (route/url-for :home)))))))))})

(def home
  (pathom/handler
    {:menu :home}
    [:ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/first_names
       :t_patient/title
       :t_patient/last_name
       :uk.nhs.cfh.isb1505/display-age
       :uk.nhs.cfh.isb1504/nhs-number
       :t_patient/date_birth
       :t_patient/date_death
       {:t_patient/death_certificate [:t_death_certificate/id
                                      :t_death_certificate/part1a
                                      :t_death_certificate/part1b
                                      :t_death_certificate/part1c
                                      :t_death_certificate/part2]}]}]
    (fn [_ {:ui/keys [patient-page current-patient]}]
      (let [{:t_patient/keys          [title first_names last_name date_birth date_death]
             :uk.nhs.cfh.isb1504/keys [nhs-number]
             :uk.nhs.cfh.isb1505/keys [display-age]}
            current-patient]
        (web/ok
          (web/render-file
            "templates/patient/home-page.html"
            (assoc patient-page
              :demographics {:title "Demographics"
                             :items [{:title "First names" :body first_names}
                                     {:title "Last name" :body last_name}
                                     {:title "Title" :body title}
                                     {:title "NHS Number" :body nhs-number}
                                     {:title "Date of birth" :body (str date_birth)}
                                     (if date_death {:title "Date of death"
                                                     :body  (str date_death)}
                                                    {:title "Current age"
                                                     :body  display-age})]})))))))

(def break-glass
  (p/handler
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient [:t_patient/suggested_registrations
                           :t_patient/patient_identifier
                           {:t_patient/administrators [:t_user/id :t_user/full_name :t_user/last_name :t_user/first_names]}]}]
    (fn [{:keys [flash] :as request} {:ui/keys [csrf-token patient-page current-patient]}]
      (let [patient-identifier (:t_patient/patient_identifier current-patient)]
        (web/ok
          (web/render-file
            "templates/patient/break-glass-page.html"
            (assoc patient-page
              :csrf-token csrf-token
              :error flash
              :redirect-url (get-in request [:params :redirect-url])
              :register-url (route/url-for :patient/do-register-to-project)
              :suggested-registrations
              (sort-by :text (map (fn [{:t_project/keys [id title]}] {:id id :text title})
                                  (:t_patient/suggested_registrations current-patient)))
              :break-glass
              {:administrators                              ;; the resolver administrators automatically adds system user
               (map (fn [{:t_user/keys [id full_name]}]
                      {:id id :text full_name})
                    (sort-by (juxt :t_user/last_name :t_user/first_names)
                             (:t_patient/administrators current-patient)))
               :url (route/url-for :patient/do-break-glass
                                   {:path-params {:patient-identifier patient-identifier}})})))))))

(defn do-break-glass
  [{:keys [session] :as request}]
  (let [authenticated-user (:authenticated-user session)
        {:strs [explanation administrator redirect-url]} (:params request)
        admin-user-id (some-> administrator parse-long)
        patient-identifier (some-> (get-in request [:path-params :patient-identifier]) parse-long)]
    (log/debug "break-glass" {:patient-identifier patient-identifier :explanation explanation :admin-user-id admin-user-id})
    (if (str/blank? explanation)
      (break-glass (assoc request :flash "You must explain why you are using 'break-glass' to access this patient record."
                                  :params {:redirect-url redirect-url}))
      (do
        (log/warn "break glass performed" {:user               (:t_user/username authenticated-user)
                                           :patient-identifier patient-identifier
                                           :explanation        explanation
                                           :admin-user-id      admin-user-id})
        ;; TODO: push event into our event system -> for logging and for messaging/email etc.
        (assoc (web/redirect-found redirect-url)
          :session (assoc session :break-glass patient-identifier))))))

(defn nhs [request])

(defn projects [request])

(defn admissions [request])

(defn register [request])

(def register-to-project
  "A HTTP POST to register a given patient to a project. We check that the user
  is permitted to register patients to the given project and then redirect to
  the original requested URL."
  (p/handler
    [:ui/authorization-manager :params/register-to-project]
    (fn
      [request {:ui/keys [authorization-manager] :params/keys [register-to-project]}]
      (let [rsdb (get-in request [:env :rsdb])
            {:keys [valid? data]} register-to-project
            {:keys [patient project-id user-id redirect-url]} data
            authorized? (rsdb/authorized? authorization-manager #{project-id} :PATIENT_REGISTER)]
        (log/debug "register-to-project" {:authorized? authorized? :valid? valid? :patient patient :project-id project-id :user-id user-id})
        (cond
          (not valid?)                                      ;; invalid form data
          (do (log/error "invalid 'register-to-project:" (s/explain-data ::register-to-project data))
              (web/forbidden "Invalid"))
          authorized?                                       ;; user authorized for this action
          (do
            (log/debug "registering patient to project" {:patient patient :project-id project-id :user-id user-id})
            (rsdb/register-patient-project! rsdb project-id user-id patient)
            (web/redirect-found redirect-url))
          :else                                             ;; user not authorized
          (web/forbidden "You do not have permission"))))))

(defn encounters [request])

(defn diagnoses-table
  [title patient-identifier diagnoses]
  [:div
   (ui/ui-title {:title title})
   (ui/ui-table
     (ui/ui-table-head
       (ui/ui-table-row
         {}
         (map #(ui/ui-table-heading {} %) ["Diagnosis" "Date onset" "Date diagnosis" "Date to" "Status"])))
     (ui/ui-table-body
       (->> diagnoses
            (sort-by #(get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term]))
            (map #(ui/ui-table-row
                    {:class "hover:bg-gray-50"}
                    (ui/ui-table-cell {}
                                      [:a {:href (route/url-for :patient/edit-diagnosis :path-params {:patient-identifier patient-identifier :diagnosis-id (:t_diagnosis/id %)})}
                                       (get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])])
                    (ui/ui-table-cell {} (str (:t_diagnosis/date_onset %)))
                    (ui/ui-table-cell {} (str (:t_diagnosis/date_diagnosis %)))
                    (ui/ui-table-cell {} (str (:t_diagnosis/date_to %)))
                    (ui/ui-table-cell {} (str/replace (str (:t_diagnosis/status %)) #"_" " ")))))))])

(def diagnoses
  (pathom/handler
    {:menu :diagnoses}
    [:ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       {:t_patient/diagnoses [:t_diagnosis/id :t_diagnosis/date_diagnosis :t_diagnosis/date_onset :t_diagnosis/date_to
                              :t_diagnosis/status {:t_diagnosis/diagnosis
                                                   [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}]}]
    (fn [_ {:ui/keys [patient-page current-patient]}]
      (let [{:t_patient/keys [patient_identifier diagnoses]} current-patient
            active-diagnoses (filter #(= "ACTIVE" (:t_diagnosis/status %)) diagnoses)
            inactive-diagnoses (filter #(not= "ACTIVE" (:t_diagnosis/status %)) diagnoses)]
        (web/ok
          (web/render-file
            "templates/patient/base.html"
            (assoc patient-page
              :content
              (web/render
                [:div
                 [:div (diagnoses-table "Active diagnoses" patient_identifier active-diagnoses)]
                 (when (seq inactive-diagnoses) [:div.pt-4 (diagnoses-table "Inactive diagnoses" patient_identifier inactive-diagnoses)])]))))))))


(defn ui-edit-diagnosis
  [{:keys [csrf-token can-edit diagnosis common-diagnoses error]}]
  (let [{:t_diagnosis/keys [id patient_fk concept_fk date_onset date_diagnosis date_to diagnosis status full_description]} diagnosis
        url (route/url-for :patient/do-edit-diagnosis)
        term (get-in diagnosis [:info.snomed.Concept/preferredDescription :info.snomed.Description/term])
        now (str (LocalDate/now))]
    (ui/active-panel
      {:id    "edit-diagnosis"
       :title (if id term "Add diagnosis")}
      [:form {:method "post" :action url :hx-target "#edit-diagnosis"}
       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
       [:input {:type "hidden" :name "diagnosis-id" :value id}]
       [:input {:type "hidden" :name "existing-term" :value term}]
       (when id [:input {:type "hidden" :name "concept-id" :value concept_fk}])
       (ui/ui-simple-form
         (when error
           (ui/box-error-message {:title "Invalid" :message "You have entered invalid data."}))
         (when-not id
           (ui/ui-simple-form-item {:label "Diagnosis"}
             (snomed/ui-select-autocomplete {:name             "concept-id"
                                             :placeholder      "Enter diagnosis"
                                             :ecl              "<404684003|Clinical finding|"
                                             :selected-concept diagnosis
                                             :common-concepts  common-diagnoses})))
         (ui/ui-simple-form-item {:label "Date of onset"}
           (ui/ui-local-date {:name "date-onset" :disabled (not can-edit) :max now} date_onset))
         (ui/ui-simple-form-item {:label "Date of diagnosis"}
           (ui/ui-local-date {:name "date-diagnosis" :disabled (not can-edit) :max now} date_diagnosis))
         (ui/ui-simple-form-item {:label "Date to"}
           (ui/ui-local-date {:name    "date-to" :disabled (not can-edit) :max now :hx-trigger "blur"
                              :hx-post url :hx-target "#edit-diagnosis" :hx-swap "outerHTML" :hx-vals "{\"partial\":true}"} date_to))
         (ui/ui-simple-form-item {:label "Status"}
           (ui/ui-select-button {:name        "status"
                                 :disabled    (not can-edit)
                                 :selected-id status
                                 :options     (if date_to [{:id "INACTIVE_REVISED" :text "Inactive - revised"}
                                                           {:id "INACTIVE_RESOLVED" :text "Inactive - resolved"}
                                                           {:id "INACTIVE_IN_ERROR" :text "Inactive - recorded in error"}]
                                                          [{:id "ACTIVE" :text "Active"}])}))
         (ui/ui-simple-form-item {:label "Notes"}
           (ui/ui-textarea {:name "full-description" :disabled (not can-edit)} full_description))
         (when (and id can-edit)
           [:p.text-sm.font-medium.leading-6.text-gray-600
            "To delete a diagnosis, record a 'to' date and update the status as appropriate."])
         (ui/ui-action-bar
           (ui/ui-submit-button {:label "Save" :disabled (not can-edit)})))])))

(def diagnosis-properties
  [:t_diagnosis/id :t_diagnosis/patient_fk
   :t_diagnosis/date_diagnosis :t_diagnosis/date_diagnosis_accuracy
   :t_diagnosis/date_onset :t_diagnosis/date_onset_accuracy
   :t_diagnosis/date_to :t_diagnosis/date_to_accuracy
   :t_diagnosis/status :t_diagnosis/full_description
   :t_diagnosis/concept_fk
   {:t_diagnosis/diagnosis
    [{:info.snomed.Concept/preferredDescription
      [:info.snomed.Description/term]}]}])

(s/def ::create-or-save-diagnosis
  (s/and
    (s/keys :req [:t_diagnosis/patient_fk
                  :t_diagnosis/concept_fk
                  :t_diagnosis/date_onset
                  :t_diagnosis/date_diagnosis
                  :t_diagnosis/date_to
                  :t_diagnosis/status
                  :t_diagnosis/full_description]
            :opt [:t_diagnosis/id])
    ::ordered-diagnosis-dates
    ::valid-diagnosis-status))

(defn parse-do-diagnosis-params [request]
  (let [form-params (:form-params request)
        concept-id (some-> form-params :concept-id parse-long)
        diagnosis-id (some-> form-params :diagnosis-id parse-long)]
    (cond-> {:t_diagnosis/patient_fk       (or (some-> form-params :patient-pk parse-long) (:t_patient/id current-patient))
             :t_diagnosis/concept_fk       concept-id
             :t_diagnosis/diagnosis        {:info.snomed.Concept/id                   concept-id
                                            :info.snomed.Concept/preferredDescription {:info.snomed.Description/term (:existing-term form-params)}}
             :t_diagnosis/date_birth       (:t_patient/date_birth current-patient)
             :t_diagnosis/date_death       (:t_patient/date_death current-patient)
             :t_diagnosis/date_now         (LocalDate/now)
             :t_diagnosis/date_onset       (-> form-params :date-onset safe-parse-local-date)
             :t_diagnosis/date_diagnosis   (-> form-params :date-diagnosis safe-parse-local-date)
             :t_diagnosis/date_to          (-> form-params :date-to safe-parse-local-date)
             :t_diagnosis/status           (:status form-params)
             :t_diagnosis/full_description (:full-description form-params)}
      diagnosis-id
      (assoc :t_diagnosis/id diagnosis-id))))

(def do-edit-diagnosis
  "Fragment to permit editing diagnosis. Designed to be used to replace page
  fragment for form validation.
  - on-cancel-url : URL to redirect if cancel
  - on-save-url   : URL to redirect after save"
  (pathom/handler
    [{:ui/authenticated-user [(list :t_user/common_concepts {:ecl "<404684003|Clinical finding|" :accept-language "en-GB"})]}]
    (fn [{:keys [env] :as request} {:ui/keys [authenticated-user]}]
      (let [{:keys [rsdb]} env
            data (parse-do-diagnosis-params request)
            common-diagnoses (:t_user/common_concepts authenticated-user)
            trigger (web/hx-trigger request)
            valid? (s/valid? ::create-or-save-diagnosis data)]
        (when-not valid?
          (log/debug "invalid diagnosis" (s/explain-data ::create-or-save-diagnosis data)))
        (cond
          (and valid? (nil? trigger))                       ;; if there was no trigger, this was a submit!
          (do
            (log/info "saving diagnosis" data)
            (if (:t_diagnosis/id data)
              (rsdb/update-diagnosis! rsdb data)
              (rsdb/create-diagnosis! rsdb {:t_patient/id (:t_diagnosis/patient_fk data)} data))
            (web/hx-redirect (route/url-for :patient/diagnoses)))
          :else                                             ;; just updating in place
          (web/ok (web/render (ui-edit-diagnosis {:csrf-token       (csrf/existing-token request)
                                                  :can-edit         true ;; by definition, we can edit. Permissions will also be checked on submit however
                                                  :error            (and (nil? trigger) (not valid?))
                                                  :diagnosis        data
                                                  :common-diagnoses common-diagnoses}))))))))

(def edit-diagnosis
  (pathom/handler
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier :t_patient/id :t_patient/permissions]}
     {:ui/current-diagnosis diagnosis-properties}
     {:ui/authenticated-user [(list :t_user/common_concepts {:ecl "<404684003|Clinical finding|" :accept-language "en-GB"})]}]
    (fn [_ {:ui/keys [authenticated-user csrf-token patient-page current-patient current-diagnosis]}]
      (log/debug "edit diagnosis" {:patient current-patient :diagnosis current-diagnosis})
      (let [common-diagnoses (:t_user/common_concepts authenticated-user)
            diagnosis-id (:t_diagnosis/id current-diagnosis)]
        (if (or (nil? diagnosis-id) (= (:t_patient/id current-patient) (:t_diagnosis/patient_fk current-diagnosis))) ;; check diagnosis is for same patient
          (web/ok                                           ;; render a whole page
            (web/render-file
              "templates/patient/base.html"
              (assoc patient-page
                :content
                (web/render
                  (ui-edit-diagnosis
                    {:csrf-token       csrf-token
                     :can-edit         (:PATIENT_EDIT (:t_patient/permissions current-patient))
                     :diagnosis        (assoc current-diagnosis :t_diagnosis/patient_fk (:t_patient/id current-patient))
                     :common-diagnoses common-diagnoses})))))
          (web/forbidden "Not authorized"))))))

(defn medication [request])

(defn documents [request])

(defn results [request])

(defn procedures [request])

(defn alerts [request])

(defn family [request])

(defn neuroinflammatory [request])

(defn motorneurone [request])

(defn epilepsy [request])
