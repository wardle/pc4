(ns pc4.http-server.controllers.patient.encounters
  (:require
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.http-server.controllers.patient :as patient]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.ui :as ui]
    [pc4.http-server.web :as web]
    [pc4.rsdb.forms :as forms]
    [pc4.rsdb.html :as html]
    [pc4.rsdb.interface :as rsdb])
  (:import [java.time LocalDate]))

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
  [{:keys [params] :as _request}]
  (clojure.pprint/pprint params)
  (reduce-kv
    (fn [acc k v]
      (assoc acc k ((get encounter-param-parsers k identity) v)))
    {}
    params))


(def all-headings
  [{:id    :date-time
    :title "Date/time"
    :fn    (fn [{:t_encounter/keys [date_time]}] (ui/format-date-time date_time))}
   {:id    :project
    :title "Project"
    :fn    :t_project/title}
   {:id    :encounter-template
    :title "Type"
    :fn    :t_encounter_template/title}
   {:id    :notes
    :title "Notes"
    :fn    (fn [{:t_encounter/keys [notes]}] (html/html->text notes))}
   {:id    :patient
    :title "Patient"
    :fn    (fn [{:t_patient/keys [title first_names last_name]}] (str last_name ", " first_names (when-not (str/blank? title) (str " (" title ")"))))}
   {:id    :nhs-number
    :title "NHS number"
    :fn    (fn [{:t_patient/keys [nhs_number]}] (pc4.nhs-number.interface/format-nnn nhs_number))}
   {:id    :crns
    :title "CRN(s)"
    :fn    :crns}
   {:id    :address
    :title "Address"
    :fn    (fn [{:t_address/keys [address1 address2 postcode_raw]}] (str/join ", " (remove nil? [address1 address2 postcode_raw])))}
   {:id    :sro
    :title "Responsible"
    :fn    :sro}
   {:id    :users
    :title "Users"
    :fn    :users}
   {:id    :edss
    :title "EDSS"
    :fn    :t_form_edss/edss_score}
   {:id    :alsfrs
    :title "ALSFRS"
    :fn    (constantly "")}
   {:id    :in-relapse
    :title "In relapse"
    :fn    :t_form_ms_relapse/in_relapse}
   {:id    :weight
    :title "Weight/height"
    :fn    :t_form_weight_height/weight_kilogram}
   {:id    :lung-function
    :title "Lung function"
    :fn    :t_form_lung_function/fvc_sitting}])

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

(comment
  (headings {:with-patient true}))


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
                (for [{:keys [fn] :or {fn (constantly "")}} headings#]
                  (ui/ui-table-cell {} (fn encounter)))))))))))

(def encounter-handler
  (pathom/handler
    [{:ui/current-encounter
      [:t_encounter/id
       :t_encounter/date_time
       :t_encounter/lock_date_time
       :t_encounter/is_locked
       {:t_encounter/encounter_template [:t_encounter_template/title
                                         {:t_encounter_template/project [:t_project/id
                                                                         :t_project/title]}]}]}]
    (fn [request {:ui/keys [current-encounter]}]
      (web/ok (web/render "ok")))))

(defn encounter->display
  "Format an encounter for display in the list."
  [encounter]
  (let [{:t_encounter/keys [id date_time status encounter_template]} encounter
        {:t_encounter_template/keys [name title]} encounter_template]
    {:id          id
     :date-time   (ui/format-date-time date_time)
     :title       name
     :description title
     :status      status}))

(def encounters-handler
  (pathom/handler
    {:menu :encounters}
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/permissions]}]
    (fn [_ {:ui/keys [csrf-token patient-page current-patient]}]
      (let [{:t_patient/keys [patient_identifier permissions]} current-patient
            can-edit? (get-in permissions [:PATIENT_EDIT])]
        (web/ok
          (web/render-file
            "templates/patient/base.html"
            (assoc patient-page
              :content
              (web/render
                [:div
                 {:id "list-encounters"
                  :hx-get (route/url-for :ui/list-encounters :query-params {:patient-identifier patient_identifier})
                  :hx-trigger "load"}
                 (ui/ui-spinner {})]))))))))

