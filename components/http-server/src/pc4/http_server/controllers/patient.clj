(ns pc4.http-server.controllers.patient
  (:require
    [clojure.string :as str]
    [com.eldrix.hermes.core :as hermes]
    [com.eldrix.nhsnumber :as nnn]
    [com.wsscode.pathom3.connect.operation :as pco]
    [io.pedestal.http.route :as route]
    [pc4.http-server.controllers.user :as user]
    [pc4.http-server.pathom :as p]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.web :as web]
    [pc4.http-server.ui :as ui]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]
    [pc4.snomedct.interface :as snomedct]))

(pco/defresolver current-patient
  [{:keys [request]} _]
  {::pco/output [{:ui/current-patient [:t_patient/patient_identifier]}]}
  {:ui/current-patient
   {:t_patient/patient_identifier (some-> (get-in request [:path-params :patient-identifier]) parse-long)}})

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
  [{:keys [request menu] :com.eldrix/keys [hermes] :as env} {:t_patient/keys [patient_identifier diagnoses] :as patient}]
  {::pco/input  [:t_patient/patient_identifier :t_patient/diagnoses]
   ::pco/output [:ui/patient-menu]}
  (let [selected menu
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
                   {:id  :mnd
                    :sub true
                    :url (route/url-for :patient/motorneurone :path-params {:patient-identifier patient_identifier})
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
                    :text "Seizure disorder"})
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
                  :text "Research"}]}}))

(pco/defresolver patient-page
  [{:ui/keys [navbar current-patient]}]
  {::pco/input  [:ui/navbar
                 {:ui/current-patient [:ui/patient-banner
                                       :ui/patient-menu]}]
   ::pco/output [:ui/patient-page]}
  {:ui/patient-page
   {:navbar navbar
    :banner (:ui/patient-banner current-patient)
    :menu   (:ui/patient-menu current-patient)}})

(def resolvers [current-patient patient->best-hospital-crn
                patient-banner patient-menu patient-page])

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
    [:ui/patient-page]
    (fn [_ {:ui/keys [patient-page]}]
      (web/ok
        (web/render-file
          "templates/patient/home-page.html"
          patient-page)))))

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
  (let [{:strs [explanation administrator redirect-url]} (:params request)
        admin-user-id (some-> administrator parse-long)
        patient-identifier (some-> (get-in request [:path-params :patient-identifier]) parse-long)]
    (log/debug "break-glass" {:patient-identifier patient-identifier :explanation explanation :admin-user-id admin-user-id})
    (if (str/blank? explanation)
      (break-glass (assoc request :flash "You must explain why you are using 'break-glass' to access this patient record."
                                  :params {:redirect-url redirect-url}))
      (assoc (web/redirect-found redirect-url)
        :session (assoc session :break-glass patient-identifier)))))

(defn nhs [request])

(defn projects [request])

(defn admissions [request])

(defn register [request])

(def register-to-project
  "A HTTP POST to register a given patient to a project. We check that the user
  is permitted to register patients to the given project and then redirect to
  the original requested URL."
  (p/handler
    [{:ui/current-patient [:t_patient/id :t_patient/patient_identifier]}
     :ui/authorization-manager
     {:ui/authenticated-user [:t_user/id :t_user/username]}]
    (fn
      [{:keys [params] :as request} {:ui/keys [current-patient authenticated-user authorization-manager]}]
      (let [rsdb (get-in request [:env :rsdb])
            {:strs [redirect-url project-id]} params
            patient-identifier (:t_patient/patient_identifier current-patient)
            user-id (:t_user/id authenticated-user)
            project-id# (some-> project-id parse-long)
            authorized? (rsdb/authorized? authorization-manager #{project-id#} :PATIENT_REGISTER)]
        (log/debug "register-to-project" {:authorized? authorized? :patient patient-identifier :project-id project-id# :user-id user-id})
        (if authorized?
          (do
            (log/debug "registering patient to project" {:patient patient-identifier :project-id project-id# :user-id user-id})
            (rsdb/register-patient-project! rsdb project-id# user-id current-patient)
            (web/redirect-found redirect-url))
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
            "templates/patient/home-page.html"
            (assoc patient-page
              :content
              (web/render
                [:div
                 [:div (diagnoses-table "Active diagnoses" patient_identifier active-diagnoses)]
                 (when (seq inactive-diagnoses) [:div.pt-4 (diagnoses-table "Inactive diagnoses" patient_identifier inactive-diagnoses)])]))))))))

(defn edit-diagnosis [request])

(defn medication [request])

(defn documents [request])

(defn results [request])

(defn procedures [request])

(defn alerts [request])

(defn family [request])

(defn neuroinflammatory [request])

(defn motorneurone [request])

(defn epilepsy [request])
