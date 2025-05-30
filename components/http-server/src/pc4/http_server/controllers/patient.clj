(ns pc4.http-server.controllers.patient
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [com.eldrix.nhsnumber :as nnn]
    [com.wsscode.pathom3.connect.operation :as pco]
    [edn-query-language.core :as eql]
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

(pco/defresolver current-diagnosis
  [{:keys [request]} _]
  {::pco/output [{:ui/current-diagnosis [:t_diagnosis/id]}]}
  {:ui/current-diagnosis
   {:t_diagnosis/id (some-> (get-in request [:path-params :diagnosis-id]) parse-long)}})

(pco/defresolver current-medication
  [{:keys [request]} _]
  {::pco/output [{:ui/current-medication [:t_medication/id]}]}
  {:ui/current-medication
   {:t_medication/id (some-> (get-in request [:path-params :medication-id]) parse-long)}})

(pco/defresolver current-ms-event
  [{:keys [request]} _]
  {::pco/output [{:ui/current-ms-event [:t_ms_event/id]}]}
  {:ui/current-ms-event
   {:t_ms_event/id (some-> request :path-params :ms-event-id parse-long)}})

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
            :pseudonymous false
            :expand-url   (when false (route/url-for :patient/banner :path-params {:patient-identifier patient_identifier}))}
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
                 {:id   :medications
                  :url  (route/url-for :patient/medications :path-params {:patient-identifier patient_identifier})
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
        {:items [{:text   "Add diagnosis..."
                  :hidden (not can-edit?)
                  :url    (route/url-for :patient/edit-diagnosis :path-params {:patient-identifier patient_identifier
                                                                               :diagnosis-id       "new"})}]}
        :medications
        {:items [{:text   "Add medication..."
                  :hidden (not can-edit?)
                  :url    (route/url-for :patient/edit-medication :path-params {:patient-identifier patient_identifier
                                                                                :medication-id      "new"})}]}
        :relapses
        {:items [{:text   "Add disease event..."
                  :hidden (not can-edit?)
                  :url    (route/url-for :patient/edit-ms-event :path-params {:patient-identifier patient_identifier
                                                                              :ms-event-id        "new"})}
                 {:text    "EDSS chart "
                  :hidden  false
                  :onClick "htmx.removeClass(htmx.find(\"#edss-chart\"), \"hidden\");"}]}
        :encounters
        {:items [{:content (web/render [:form {:hx-target "#list-encounters" :hx-trigger "change"
                                               :hx-get    (route/url-for :ui/list-encounters)}
                                        [:input {:type "hidden" :name "patient-identifier" :value patient_identifier}]
                                        (ui/ui-select-button {:name "view" :options [{:id :notes :text "Notes"}
                                                                                     {:id :users :text "Users"}
                                                                                     {:id :ninflamm :text "Neuroinflammatory"}
                                                                                     {:id :mnd :text "Motor neurone disease"}]})])}
                 {:text   "Add encounter..."
                  :hidden (not can-edit?)
                  :url    (route/url-for :patient/encounter :path-params {:patient-identifier patient_identifier
                                                                          :encounter-id       "new"})}]}
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


;;
;;
;;

(def resolvers [current-patient
                current-diagnosis
                current-medication
                current-ms-event
                patient->best-hospital-crn
                patient-banner patient-menu
                patient-page
                register-to-project-params])

;;
;;
;;

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
          {:patients            (map patient->result patients)
           :fallback-search-url (when (and (= mode :my) (pos-int? n-fallback)) (route/url-for :patient/search))})))))

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


(defn not-authorized-handler
  [request]
  (web/ok
    (web/render [:div (ui/box-error-message {:title "Not authorised" :message "You are not authorised to perform this action."})])))

(def ^:private editable-patient-query
  [{:ui/current-patient [:t_patient/permissions]}])

(defn editable-handler
  "Like pathom/handler but checks that current user has permission to edit
  current patient."
  ([query f]
   (editable-handler {} query f))
  ([env query f]
   (pathom/handler env
                   (fn wrapped-query [request]
                     (eql/merge-queries editable-patient-query (if (fn? query) (query request) query)))
                   (fn wrapped-handler [request {:ui/keys [current-patient] :as result}]
                     (let [permissions (:t_patient/permissions current-patient)]
                       (if (permissions :PATIENT_EDIT)
                         (f request result)
                         (not-authorized-handler request)))))))

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

(def expanded-banner
  "The content of the extended banner; shown when the user clicks the banner"
  (p/handler
    [{:ui/current-patient [:t_patient/patient_identifier
                           {:t_patient/address [:t_address/address1 :t_address/address2
                                                :t_address/address3 :t_address/address4
                                                :t_address/postcode_raw
                                                :t_address/lsoa]
                            :t_patient/telephones
                            :t_patient/patient_hospitals}]}]
    (fn [_ {:ui/keys [current-patient]}]
      (clojure.pprint/pprint current-patient)
      (web/ok
        (web/render-file "templates/patient/expanded-banner.html" {})))))

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


(defn documents [request])

(defn results [request])

(defn procedures [request])

(defn alerts [request])

(defn family [request])


(defn motorneurone [request])

(defn epilepsy [request])
