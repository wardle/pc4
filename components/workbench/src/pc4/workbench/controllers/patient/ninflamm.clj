(ns pc4.workbench.controllers.patient.ninflamm
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    [io.pedestal.http.route :as route]
    [pc4.pathom-web.interface :as pw]
    [pc4.web.interface :as web]
    [pc4.ui.interface :as ui]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]
    [pc4.workbench.controllers.patient :as patient])
  (:import (java.time LocalDate)))

(def ms-event-properties
  [:t_ms_event/id
   :t_ms_event/date
   :t_ms_event/impact
   :t_ms_event/ms_event_type_fk
   :t_ms_event/is_relapse
   :t_ms_event/is_progressive
   :t_ms_event/notes
   :t_ms_event/site_unknown
   :t_ms_event/site_arm_motor
   :t_ms_event/site_leg_motor
   :t_ms_event/site_limb_sensory
   :t_ms_event/site_sphincter
   :t_ms_event/site_sexual
   :t_ms_event/site_face_motor
   :t_ms_event/site_face_sensory
   :t_ms_event/site_diplopia
   :t_ms_event/site_vestibular
   :t_ms_event/site_bulbar
   :t_ms_event/site_ataxia
   :t_ms_event/site_optic_nerve
   :t_ms_event/site_psychiatric
   :t_ms_event/site_other
   :t_ms_event/site_cognitive
   :t_ms_event/summary_multiple_sclerosis_fk
   {:t_ms_event/type [:t_ms_event_type/abbreviation]}])

(def impact-choices ["UNKNOWN" "NON_DISABLING" "DISABLING" "SEVERE"])

(def ms-event-sites
  [{:k :t_ms_event/site_unknown :s "UK" :title "Unknown"}
   {:k :t_ms_event/site_arm_motor :s "UE" :title "Upper extremity (arm motor)"}
   {:k :t_ms_event/site_leg_motor :s "LE" :title "Lower extremity (leg motor)"}
   {:k :t_ms_event/site_limb_sensory :s "SS" :title "Limb sensory"}
   {:k :t_ms_event/site_sphincter :s "SP" :title "Sphincter"}
   {:k :t_ms_event/site_sexual :s "SX" :title "Sexual"}
   {:k :t_ms_event/site_face_motor :s "FM" :title "Face motor"}
   {:k :t_ms_event/site_face_sensory :s "FS" :title "Face sensory"}
   {:k :t_ms_event/site_diplopia :s "OM" :title "Oculomotor (diplopia)"}
   {:k :t_ms_event/site_vestibular :s "VE" :title "Vestibular"}
   {:k :t_ms_event/site_bulbar :s "BB" :title "Bulbar"}
   {:k :t_ms_event/site_ataxia :s "CB" :title "Cerebellar (ataxia)"}
   {:k :t_ms_event/site_optic_nerve :s "ON" :title "Optic nerve"}
   {:k :t_ms_event/site_psychiatric :s "PS" :title "Psychiatric"}
   {:k :t_ms_event/site_other :s "OT" :title "Other"}
   {:k :t_ms_event/site_cognitive :s "MT" :title "Cognitive"}])

(defn parse-event-sites
  "Returns an 'event' updating based on the triggering component, and other form data.
  For example, if site_unknown is selected by the user, all other sites are deselected."
  [event hx-trigger {:keys [site_unknown] :as form-params}]
  (log/debug "hx-trigger" hx-trigger)
  (cond
    ;; user has selected site unknown -> deselect all but site unknown
    (and (= "on" site_unknown) (= "site_unknown" hx-trigger))
    (reduce (fn [acc {:keys [k]}] (assoc acc k (= :t_ms_event/site_unknown k))) event ms-event-sites)

    ;; user has selected another site -> set all values as per form, except site unknown
    (and (not (str/blank? hx-trigger)) (str/starts-with? hx-trigger "site") (not= "site_unknown" hx-trigger) (get form-params (keyword hx-trigger)))
    (reduce (fn [acc {:keys [k]}] (assoc acc k (if (= :t_ms_event/site_unknown k) false (= "on" (get form-params (keyword (name k))))))) event ms-event-sites)

    ;; otherwise, just set all values unchanged
    :else
    (reduce (fn [acc {:keys [k]}] (assoc acc k (= "on" (get form-params (keyword (name k)))))) event ms-event-sites)))

(comment
  (parse-event-sites {} "site_unknown" {:site_unknown   "off"
                                        :site_arm_motor "on"}))


(defn safe-parse-local-date [s]
  (when-not (str/blank? s) (LocalDate/parse s)))

(defn parse-ms-event-params
  [hx-trigger form-params]
  (log/debug "parse-ms-event-params" form-params)
  (let [event-id (some-> form-params :ms-event-id parse-long)
        type-id (some-> form-params :type-id parse-long)
        summary-id (some-> form-params :summary-multiple-sclerosis-id parse-long)]
    (let [event (cond-> {:t_ms_event/date                          (-> form-params :date safe-parse-local-date)
                         :t_ms_event/impact                        (:impact form-params)
                         :t_ms_event/notes                         (:notes form-params)
                         :t_ms_event/ms_event_type_fk              type-id
                         :t_ms_event/summary_multiple_sclerosis_fk summary-id}

                  ;; Add event ID if present
                  event-id (assoc :t_ms_event/id event-id))]
      (parse-event-sites event hx-trigger form-params))))

(defn ui-ms-event-row
  [{:t_ms_event/keys [id date type is_relapse is_progressive impact site_unknown site_arm_motor site_leg_motor site_limb_sensory
                      site_sphincter site_sexual site_face_motor site_face_sensory site_diplopia
                      site_vestibular site_bulbar site_ataxia site_optic_nerve site_psychiatric
                      site_other site_cognitive notes]}
   {:keys [patient-identifier can-edit]}]
  (ui/ui-table-row
    {:key     id
     :classes (cond-> ["hover:bg-gray-200"]
                (not is_progressive) (conj "bg-red-50/50")
                (not is_relapse) (conj "border-t" "border-dashed" "border-gray-400")
                is_progressive (conj "italic" "bg-blue-50/25"))}
    (ui/ui-table-cell
      {:classes ["whitespace-nowrap"]}
      (if can-edit
        [:a {:href (route/url-for :patient/edit-ms-event
                                  :path-params {:patient-identifier patient-identifier
                                                :ms-event-id        id})}
         (ui/format-date date)]
        (ui/format-date date)))
    (ui/ui-table-cell {} (:t_ms_event_type/abbreviation type))
    (ui/ui-table-cell {} impact)
    (ui/ui-table-cell {} (when site_unknown "UK"))
    (ui/ui-table-cell {} (when site_arm_motor "UE"))
    (ui/ui-table-cell {} (when site_leg_motor "LE"))
    (ui/ui-table-cell {} (when site_limb_sensory "SS"))
    (ui/ui-table-cell {} (when site_sphincter "SP"))
    (ui/ui-table-cell {} (when site_sexual "SX"))
    (ui/ui-table-cell {} (when site_face_motor "FM"))
    (ui/ui-table-cell {} (when site_face_sensory "FS"))
    (ui/ui-table-cell {} (when site_diplopia "OM"))
    (ui/ui-table-cell {} (when site_vestibular "VE"))
    (ui/ui-table-cell {} (when site_bulbar "BB"))
    (ui/ui-table-cell {} (when site_ataxia "CB"))
    (ui/ui-table-cell {} (when site_optic_nerve "ON"))
    (ui/ui-table-cell {} (when site_psychiatric "PS"))
    (ui/ui-table-cell {} (when site_other "OT"))
    (ui/ui-table-cell {} (when site_cognitive "MT"))))

(defn ui-ms-events-table
  [events {:keys [patient-identifier can-edit] :as opts}]
  (ui/ui-table
    (ui/ui-table-head
      (ui/ui-table-row
        {}
        (map (fn [{:keys [s title]}]
               (ui/ui-table-heading (cond-> {:key s} title (assoc :title title)) s))
             (concat [{:s "Date"} {:s "Type"} {:s "Impact"}] ms-event-sites))))
    (ui/ui-table-body
      (for [event (sort-by :t_ms_event/date events)]
        (ui-ms-event-row event opts)))))

(s/def ::update-ms-diagnosis
  (s/keys :req [:t_user/id
                :t_ms_diagnosis/id
                :t_patient/patient_identifier]))

(s/def ::save-ms-event
  (s/keys :req [:t_ms_event/date
                :t_ms_event/ms_event_type_fk
                :t_ms_event/summary_multiple_sclerosis_fk]
          :opt [:t_ms_event/id
                :t_ms_event/impact :t_ms_event/notes]))

(defn ui-edit-ms-event
  [{:t_ms_event/keys [id ms_event_type_fk date impact notes summary_multiple_sclerosis_fk] :as ms-event
    :or              {impact "UNKNOWN"}}
   {:keys [patient-identifier can-edit all-ms-event-types csrf-token error]}]
  (let [url (route/url-for :patient/save-ms-event :path-params {:patient-identifier patient-identifier})
        patient-birth-date (:t_patient/date_birth ms-event)
        is-new? (nil? id)
        valid? (s/valid? ::save-ms-event ms-event)]
    [:div {:id "edit-ms-event"}
     (ui/active-panel
       {:title (if is-new? "Add relapse / disease event" "Edit relapse / disease event")}
       [:form {:method    "post"
               :action    url
               :hx-target "#edit-ms-event"}
        [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
        (when id [:input {:type "hidden" :name "ms-event-id" :value id}])
        [:input {:type "hidden" :name "summary-multiple-sclerosis-id" :value summary_multiple_sclerosis_fk}]

        (ui/ui-simple-form
          (when error
            (ui/box-error-message {:title "Error" :message error}))

          (ui/ui-simple-form-item
            {:label "Date"}
            (ui/ui-local-date
              {:name     "date"
               :disabled (not can-edit)
               :min      (when patient-birth-date (str patient-birth-date))
               :max      (str (LocalDate/now))
               :hx-post  url :hx-target "#edit-ms-event" :hx-swap "outerHTML" :hx-vals "{\"partial\":true}"}
              date))

          (ui/ui-simple-form-item
            {:label "Type"}
            (ui/ui-select-button
              {:name        "type-id"
               :disabled    (not can-edit)
               :selected-id ms_event_type_fk
               :options     (map (fn [{:t_ms_event_type/keys [id abbreviation name]}]
                                   {:id   id
                                    :text (str abbreviation ": " name)})
                                 (sort-by :t_ms_event_type/id all-ms-event-types))}))

          (ui/ui-simple-form-item
            {:label "Impact"}
            (ui/ui-select-button
              {:name        "impact"
               :disabled    (not can-edit)
               :selected-id impact
               :options     (map (fn [impact] {:id impact :text impact}) impact-choices)}))

          (ui/ui-simple-form-item
            {:label "Site(s) affected" :sublabel "Record when appropriate"})
          [:div.grid.grid-cols-1.sm:grid-cols-2.md:grid-cols-3.lg:grid-cols-4.gap-2.text-sm
           (for [{:keys [k title]} ms-event-sites]
             [:div.flex.items-center
              [:input.mr-2
               {:id       (name k)
                :name     (name k)
                :type     "checkbox"
                :checked  (get ms-event k)
                :disabled (not can-edit)
                :class    (into ["focus:ring-indigo-500" "h-4" "w-4" "text-indigo-600" "border-gray-300" "rounded"]
                                (if (not can-edit)
                                  ["bg-gray-100" "opacity-50" "cursor-not-allowed"]
                                  ["bg-white" "cursor-pointer"]))
                :hx-post  url :hx-target "#edit-ms-event" :hx-swap "outerHTML" :hx-vals "{\"partial\":true}"}]
              [:label {:for   (name k)
                       :class (if (not can-edit) "text-gray-500 cursor-not-allowed" "text-gray-900 cursor-pointer")} title]])]

          ;; Notes field
          (ui/ui-simple-form-item
            {:label "Notes"})
          (ui/ui-textarea
            {:name     "notes"
             :disabled (not can-edit)}
            notes)

          ;; Action buttons
          (ui/ui-action-bar
            (ui/ui-submit-button
              {:disabled (or (not valid?) (not can-edit))}
              "Save")
            (ui/ui-cancel-button
              {:onclick "history.back()"}
              "Cancel")
            (when (and id can-edit)
              (ui/ui-delete-button
                {:hx-delete  (route/url-for :patient/delete-ms-event :path-params {:patient-identifier patient-identifier :ms-event-id id})
                 :hx-headers (json/write-str {"X-CSRF-Token" csrf-token})
                 :hx-params  nil
                 :hx-confirm "Are you sure you wish to delete this event?"}
                "Delete"))))])]))

;; Handler for editing MS event
(def edit-ms-event-handler
  (pw/handler
    [:ui/csrf-token
     :ui/patient-page
     :com.eldrix.rsdb/all-ms-event-types
     {:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/id
       :t_patient/date_birth
       :t_patient/permissions
       {:t_patient/summary_multiple_sclerosis
        [:t_summary_multiple_sclerosis/id]}]}
     {:ui/current-ms-event ms-event-properties}]
    (fn [_ {:ui/keys              [csrf-token patient-page current-patient current-ms-event]
            :com.eldrix.rsdb/keys [all-ms-event-types]}]
      (log/info "edit ms event" current-ms-event)
      (let [{:t_patient/keys [patient_identifier permissions summary_multiple_sclerosis]} current-patient
            ms-event-id (:t_ms_event/id current-ms-event)
            is-new? (nil? ms-event-id)
            can-edit (:PATIENT_EDIT permissions)]
        (if (or is-new?                                     ;; new event
                (= (:t_summary_multiple_sclerosis/id summary_multiple_sclerosis)
                   (:t_ms_event/summary_multiple_sclerosis_fk current-ms-event))) ;; existing event for this patient
          (web/ok
            (ui/render-file
              "templates/patient/base.html"
              (assoc patient-page
                :content
                (ui/render
                  (ui-edit-ms-event
                    (if (not is-new?)
                      current-ms-event
                      {:t_ms_event/summary_multiple_sclerosis_fk (:t_summary_multiple_sclerosis/id summary_multiple_sclerosis)
                       :t_ms_event/ms_event_type_fk              11 ;; default to 'unknown' type
                       :t_patient/date_birth                     (:t_patient/date_birth current-patient)
                       :t_ms_event/site_unknown                  true})
                    {:patient-identifier patient_identifier
                     :can-edit           can-edit
                     :all-ms-event-types all-ms-event-types
                     :csrf-token         csrf-token})))))
          (web/forbidden "Not authorized"))))))

(def save-ms-event-handler
  (pw/handler
    [{:ui/current-patient
      [:t_patient/id
       :t_patient/patient_identifier
       :t_patient/permissions
       :t_patient/date_birth
       {:t_patient/summary_multiple_sclerosis
        [:t_summary_multiple_sclerosis/id]}]}
     :ui/csrf-token
     :com.eldrix.rsdb/all-ms-event-types
     {:ui/authenticated-user [:t_user/id]}]
    (fn [{:keys [env form-params] :as request}
         {:ui/keys              [csrf-token current-patient authenticated-user]
          :com.eldrix.rsdb/keys [all-ms-event-types]}]
      (let [{:t_patient/keys [patient_identifier date_birth permissions summary_multiple_sclerosis]} current-patient
            hx-trigger (web/hx-trigger request)
            data (parse-ms-event-params hx-trigger form-params)
            valid? (s/valid? ::save-ms-event data)
            partial? (:partial form-params)                 ;; form is being submitted but not for a 'save'
            can-edit (:PATIENT_EDIT permissions)
            rsdb (:rsdb env)
            neuroinflammatory-url (route/url-for :patient/neuroinflammatory :path-params {:patient-identifier patient_identifier})
            ;; a partial page response just rendering our form
            response
            (fn [params]
              (web/ok
                (ui/render
                  (ui-edit-ms-event
                    (assoc data :t_patient/date_birth date_birth)
                    (merge {:patient-identifier patient_identifier
                            :can-edit           can-edit
                            :all-ms-event-types all-ms-event-types
                            :csrf-token         csrf-token}
                           params)))))]
        (log/debug "save-ms-event" data)
        (cond
          ;; no permission to edit => should not happen so simply return an error
          (not can-edit)
          (web/forbidden "Not authorized")

          ;; full save and valid data -> let's perform save
          (and (not partial?) valid?)
          (do (rsdb/save-ms-event! rsdb data)
              (web/hx-redirect neuroinflammatory-url))

          ;; full save but invalid data -> show error to user
          (and (not partial?) (not valid?))
          (response {:error "Could not save event: invalid data"})
          ;; partial -> so simply update form
          :else
          (response {}))))))

(def delete-ms-event-handler
  (pw/handler
    [{:ui/current-patient [:t_patient/patient_identifier :t_patient/permissions]}
     {:ui/current-ms-event [:t_ms_event/id]}]
    (fn [{:keys [env]} {:ui/keys [current-patient current-ms-event]}]
      (let [patient-identifier (:t_patient/patient_identifier current-patient)
            permissions (:t_patient/permissions current-patient)
            can-edit? (boolean (:PATIENT_EDIT permissions))]
        (if-not can-edit?
          (web/forbidden "Unauthorized")
          (do (log/info "delete ms event" {:patient current-patient :ms-event current-ms-event})
              (rsdb/delete-ms-event! (:rsdb env) current-ms-event)
              (web/hx-redirect (route/url-for :patient/neuroinflammatory {:patient-identifier patient-identifier}))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Neuroinflammatory diagnosis
;;
;;
(defn ui-ms-diagnosis-selection
  [{:t_summary_multiple_sclerosis/keys [ms_diagnosis id]}
   {:keys [all-ms-diagnoses patient-identifier can-edit csrf-token]}]
  (let [not-ms-diagnosis (first (filter #(= 15 (:t_ms_diagnosis/id %)) all-ms-diagnoses))
        current-diagnosis (or ms_diagnosis not-ms-diagnosis)
        update-url (route/url-for :patient/save-ms-diagnosis
                                  :path-params {:patient-identifier patient-identifier})]
    (log/debug "current neuroinflamm diagnosis:" ms_diagnosis)
    (ui/active-panel
      {:classes ["mb-4"]
       :id      "ms-diagnosis-selection"}
      (ui/ui-simple-form
        (ui/ui-simple-form-item
          {:label "Neuroinflammatory diagnostic category"}
          [:form
           [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
           (ui/ui-select-button
             {:name          "ms-diagnosis-id"
              :selected-id   (:t_ms_diagnosis/id current-diagnosis)
              :default-value not-ms-diagnosis
              :disabled      (not can-edit)
              :options       (map #(hash-map :id (:t_ms_diagnosis/id %) :text (:t_ms_diagnosis/name %)) all-ms-diagnoses)
              :hx-post       update-url
              :hx-trigger    "change"
              :hx-target     "#ms-diagnosis-selection"})])))))

(def save-ms-diagnosis-handler
  (pw/handler
    [{:ui/current-patient
      [:t_patient/id
       :t_patient/patient_identifier
       :t_patient/permissions
       {:t_patient/summary_multiple_sclerosis
        [:t_summary_multiple_sclerosis/id
         :t_summary_multiple_sclerosis/ms_diagnosis]}]}
     :ui/csrf-token
     :com.eldrix.rsdb/all-ms-diagnoses
     {:ui/authenticated-user [:t_user/id]}]
    (fn [{:keys [env form-params] :as request}
         {:ui/keys              [current-patient csrf-token authenticated-user]
          :com.eldrix.rsdb/keys [all-ms-diagnoses]}]
      (let [{:t_patient/keys [patient_identifier id permissions summary_multiple_sclerosis]} current-patient
            can-edit (boolean (:PATIENT_EDIT permissions))
            ms-diagnosis-id (some-> form-params :ms-diagnosis-id parse-long)
            user-id (:t_user/id authenticated-user)
            params {:t_ms_diagnosis/id            ms-diagnosis-id
                    :t_patient/patient_identifier patient_identifier
                    :t_user/id                    user-id}
            rsdb-svc (:rsdb env)]
        (log/debug "save-ms-diagnosis" form-params)
        (if (and can-edit ms-diagnosis-id (s/valid? ::update-ms-diagnosis params))
          (let [updated-sms (rsdb/save-ms-diagnosis! rsdb-svc params)]
            (log/info "updated MS diagnosis" {:params params :saved updated-sms})
            (web/no-content))
          (web/bad-request))))))

;; Main handler for the neuroinflammatory page
(def neuroinflammatory-handler
  (pw/handler
    {:menu :relapses}
    [:ui/csrf-token
     :ui/patient-page
     :com.eldrix.rsdb/all-ms-diagnoses
     {:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/id
       :t_patient/permissions
       {:t_patient/summary_multiple_sclerosis
        [:t_summary_multiple_sclerosis/id
         :t_summary_multiple_sclerosis/event_ordering_errors
         :t_summary_multiple_sclerosis/ms_diagnosis
         {:t_summary_multiple_sclerosis/events ms-event-properties}]}]}]
    (fn [request {:ui/keys              [csrf-token patient-page current-patient]
                  :com.eldrix.rsdb/keys [all-ms-diagnoses]}]
      (let [{:t_patient/keys [patient_identifier permissions summary_multiple_sclerosis]} current-patient
            not-ms-diagnosis (first (filter #(= 15 (:t_ms_diagnosis/id %)) all-ms-diagnoses)) ;; "NOT MS"
            has-summary? (:t_summary_multiple_sclerosis/id summary_multiple_sclerosis)
            show-ms? (and has-summary? (not= not-ms-diagnosis (:t_summary_multiple_sclerosis/ms_diagnosis summary_multiple_sclerosis)))
            can-edit (boolean (:PATIENT_EDIT permissions))]
        (web/ok
          (ui/render-file
            "templates/patient/base.html"
            (-> patient-page
                (assoc-in [:menu :submenu] {:items [{:text   "Add disease event..."
                                                      :hidden (not can-edit)
                                                      :url    (route/url-for :patient/edit-ms-event :path-params {:patient-identifier patient_identifier
                                                                                                                  :ms-event-id        "new"})}
                                                     {:text    "EDSS chart "
                                                      :hidden  false
                                                      :onClick "htmx.removeClass(htmx.find(\"#edss-chart\"), \"hidden\");"}]})
                (assoc :content
                       (ui/render
                         [:div
                          ;; Display MS diagnosis selection
                          (ui-ms-diagnosis-selection summary_multiple_sclerosis
                                                     {:all-ms-diagnoses   all-ms-diagnoses
                                                      :patient-identifier patient_identifier
                                                      :can-edit           can-edit
                                                      :csrf-token         csrf-token})

                          ;; Display ordering errors if any
                          (when (and show-ms? (seq (:t_summary_multiple_sclerosis/event_ordering_errors summary_multiple_sclerosis)))
                            [:div.pb-4
                             (ui/box-error-message
                               {:title   "Warning: invalid disease relapses and events"
                                :message [:ul
                                          (for [error (:t_summary_multiple_sclerosis/event_ordering_errors summary_multiple_sclerosis)]
                                            [:li error])]})])
                          (ui/ui-modal {:id      "edss-chart"
                                        :hidden? true
                                        :size    :xl
                                        :cancel  {:onClick "htmx.addClass(htmx.find(\"#edss-chart\"), \"hidden\");"}}
                                       [:img {:src (route/url-for :patient/chart :query-params {:type "edss" :width 1600 :height 800})}])
                          ;; Display MS events if any and applicable
                          (when show-ms?
                            [:div
                             (if (seq (:t_summary_multiple_sclerosis/events summary_multiple_sclerosis))
                               (ui-ms-events-table (:t_summary_multiple_sclerosis/events summary_multiple_sclerosis)
                                                   {:patient-identifier patient_identifier
                                                    :can-edit           can-edit})
                               [:div.text-center.py-4.text-gray-500 "No disease events recorded"])])])))))))))