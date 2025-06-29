(ns pc4.http-server.controllers.encounters
  "Encounter management controllers."
  (:require
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.http-server.controllers.user.select :as user-select]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.ui :as ui]
    [pc4.http-server.web :as web]
    [pc4.nhs-number.interface :as nnn]
    [pc4.rsdb.html :as html]
    [pc4.rsdb.interface :as rsdb]
    [rum.core :as rum])
  (:import [java.time LocalDate]))

;; List encounters functionality
(defn safe-parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s)))

(def encounter-param-parsers
  {:patient-identifier    parse-long
   :patient-pk            parse-long
   :with-project          parse-boolean
   :with-patient          parse-boolean
   :with-address          parse-boolean
   :with-crns             parse-boolean
   :user-id               parse-long
   :project-id            parse-long
   :episode-id            parse-long
   :encounter-template-id parse-long
   :deleted               parse-boolean
   :in-person             parse-boolean
   :from                  safe-parse-local-date
   :to                    safe-parse-local-date
   :limit                 parse-long
   :offset                parse-long
   :view                  (fn [x] (or (keyword x) :notes))})

(defn parse-list-encounter-params
  "Parse HTTP request parameters into a map suitable for q-encounters.
  Returns a map that conforms to pc4.rsdb.encounters/::query spec."
  [{:keys [form-params] :as _request}]
  (reduce-kv
    (fn [acc k v]
      (assoc acc k ((get encounter-param-parsers k identity) v)))
    {}
    form-params))

(def all-headings
  [{:id    :date-time
    :title "Date/time"
    :f2    (fn [{:keys [patient-identifier]} {:t_encounter/keys [id date_time] patient-identifier# :t_patient/patient-identifier}]
             [:a {:href (route/url-for :patient/encounter :path-params {:patient-identifier (or patient-identifier# patient-identifier)
                                                                        :encounter-id       id})} (ui/format-date-time date_time)])}
   {:id    :project
    :title "Project"
    :f     :t_project/title}
   {:id    :encounter-template
    :title "Type"
    :f     :t_encounter_template/title}
   {:id    :notes
    :title "Notes"
    :f     (fn [{:t_encounter/keys [notes]}] (html/html->text notes))}
   {:id    :patient
    :title "Patient"
    :f     (fn [{:t_patient/keys [title first_names last_name]}] (str last_name ", " first_names (when-not (str/blank? title) (str " (" title ")"))))}
   {:id    :nhs-number
    :title "NHS number"
    :f     (fn [{:t_patient/keys [nhs_number]}] (nnn/format-nnn nhs_number))}
   {:id    :crns
    :title "CRN(s)"
    :f     :crns}
   {:id    :address
    :title "Address"
    :f     (fn [{:t_address/keys [address1 address2 postcode_raw]}] (str/join ", " (remove nil? [address1 address2 postcode_raw])))}
   {:id    :sro
    :title "Responsible"
    :f     :sro}
   {:id    :users
    :title "Users"
    :f     :users}
   {:id    :edss
    :title "EDSS"
    :f     :t_form_edss/edss_score}
   {:id    :alsfrs
    :title "ALSFRS"
    :f     (constantly "")}
   {:id    :in-relapse
    :title "In relapse"
    :f     :t_form_ms_relapse/in_relapse}
   {:id    :weight
    :title "Weight/height"
    :f     :t_form_weight_height/weight_kilogram}
   {:id    :lung-function
    :title "Lung function"
    :f     :t_form_lung_function/fvc_sitting}])

(def heading-by-id
  (reduce
    (fn [acc {:keys [id] :as heading}]
      (assoc acc id heading))
    {} all-headings))

(defn core-headings
  [{:keys [with-patient with-crns with-address with-project]}]
  (-> (cond-> [:date-time]
        with-patient
        (conj :patient :nhs-number)
        with-crns
        (conj :crns)
        with-address
        (conj :address)
        with-project
        (conj :project))
      (conj :encounter-template)))

(def extra-headings
  {:notes    [:notes]
   :users    [:sro :users]
   :ninflamm [:edss :in-relapse]
   :mnd      [:weight :alsfrs]})

(defn headings [{:keys [view] :or {view :notes} :as params}]
  (let [core (core-headings params)]
    (->> (into core (get extra-headings view []))
         (map #(or (heading-by-id %) (throw (ex-info (str "no heading found with id: " %) {:id %})))))))

(def default-params
  {:view         :notes
   :with-project true
   :with-patient false})

(defn list-encounters-handler
  [request]
  (let [rsdb (get-in request [:env :rsdb])
        parsed-params (merge default-params (parse-list-encounter-params request))
        encounters (when (seq parsed-params) (rsdb/list-encounters rsdb parsed-params))
        headings# (headings parsed-params)]
    (web/ok
      (web/render
        (ui/ui-table
          (ui/ui-table-head
            (for [{:keys [title]} headings#]
              (ui/ui-table-heading {} title)))
          (ui/ui-table-body
            (for [encounter encounters]
              (ui/ui-table-row
                {}
                (for [{:keys [f f2] :or {f (constantly "")}} headings#]
                  (ui/ui-table-cell {} (cond
                                         f2 (f2 parsed-params encounter)
                                         f (f encounter)
                                         :else "")))))))))))

(def create-encounter-handler
  "Handler for creating new encounters with user selection demonstration."
  (pathom/handler
    [:ui/csrf-token
     {:ui/authenticated-user [:t_user/id :t_user/username :t_user/full_name :t_user/job_title]}]
    (fn [request {:ui/keys [csrf-token authenticated-user]}]
      (web/page
        {}
        [:div.p-6
         (ui/ui-title {:title    "Create New Encounter"
                       :subtitle "Test page demonstrating user selection components"})

         [:form.space-y-6 {:method "POST" :action "#"}
          [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]

          ;; Basic encounter fields
          (ui/ui-simple-form
            (ui/ui-simple-form-title {:title "Encounter Details"})

            (ui/ui-simple-form-item {:label "Encounter Date"}
              (ui/ui-textfield {:id       "encounter-date"
                                :name     "encounter-date"
                                :type     "date"
                                :required true}))

            (ui/ui-simple-form-item {:label "Notes"}
              (ui/ui-textarea {:id   "notes"
                               :name "notes"
                               :rows 4} nil)))

          ;; User selection demonstration
          [:div.space-y-4
           [:h3.text-lg.font-medium.text-gray-900 "User Selection Examples"]

           ;; Single user selection
           [:div.p-4.border.border-gray-200.rounded-lg
            [:h4.text-md.font-medium.text-gray-700.mb-3 "Single User Selection"]
            [:p.text-sm.text-gray-600.mb-3 "Select a responsible clinician for this encounter:"]
            (user-select/ui-select-user {:id               "responsible-user"
                                         :name             "responsible-user-id"
                                         :label            "Responsible Clinician"
                                         :only-responsible true
                                         :csrf-token       csrf-token
                                         :required         false})]

           ;; Multiple user selection
           [:div.p-4.border.border-gray-200.rounded-lg
            [:h4.text-md.font-medium.text-gray-700.mb-3 "Multiple User Selection"]
            [:p.text-sm.text-gray-600.mb-3 "Select team members involved in this encounter:"]
            (user-select/ui-select-users {:id         "team-members"
                                          :name       "team-member-ids"
                                          :label      "Team Members"
                                          :csrf-token csrf-token
                                          :required   false})]

           ;; Optional user selection
           [:div.p-4.border.border-gray-200.rounded-lg
            [:h4.text-md.font-medium.text-gray-700.mb-3 "Optional User Selection"]
            [:p.text-sm.text-gray-600.mb-3 "Optionally assign a reviewer:"]
            (user-select/ui-select-user {:id         "reviewer"
                                         :csrf-token csrf-token
                                         :name       "reviewer-id"
                                         :label      "Reviewer (Optional)"
                                         :required   false})]

           ;; Pre-selected users example
           [:div.p-4.border.border-gray-200.rounded-lg
            [:h4.text-md.font-medium.text-gray-700.mb-3 "Pre-selected User"]
            [:p.text-sm.text-gray-600.mb-3 "Current user pre-selected as default:"]
            (user-select/ui-select-user {:id         "created-by"
                                         :name       "created-by-id"
                                         :label      "Created By"
                                         :csrf-token csrf-token
                                         :disabled   false
                                         :selected   {:user-id   (:t_user/id authenticated-user)
                                                      :full-name (:t_user/full_name authenticated-user)
                                                      :job-title (:t_user/job_title authenticated-user)}
                                         :required   true})]]

          ;; Action buttons
          (ui/ui-action-bar
            (ui/ui-submit-button {} "Create Encounter")
            (ui/ui-cancel-button {:href "/"} "Cancel"))

          ;; Debug section
          [:div.mt-8.p-4.bg-gray-50.rounded-lg
           [:h4.text-sm.font-medium.text-gray-700.mb-2 "Current User Info (for testing)"]
           [:pre.text-xs.text-gray-600 (pr-str authenticated-user)]]]]))))

