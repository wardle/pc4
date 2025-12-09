(ns pc4.workbench.controllers.patient.episodes
  (:require
    [clojure.spec.alpha :as s]
    [io.pedestal.http.route :as route]
    [pc4.log.interface :as log]
    [pc4.pathom-web.interface :as pw]
    [pc4.rsdb.interface :as rsdb]
    [pc4.common-ui.interface :as ui]
    [pc4.web.interface :as web]))

(s/def :t_episode/status #{:registered :referred :discharged})
(s/def :t_project/type #{:RESEARCH :NHS :ALL_PATIENTS})
(s/def ::episode-project (s/keys :req [:t_project/title]
                                 :opt [:t_project/type :t_project/is_admission]))
(s/def :t_episode/project ::episode-project)
(s/def ::episode-user (s/keys :opt [:t_user/full_name :t_user/initials]))
(s/def :t_episode/referral_user ::episode-user)
(s/def :t_episode/registration_user ::episode-user)
(s/def :t_episode/discharge_user ::episode-user)
(s/def ::action-button (s/keys :req-un [::label]))
(s/def ::actions (s/coll-of ::action-button))
(s/def ::label string?)

(s/def ::episode-for-table
  (s/keys :req [:t_episode/status]
          :opt [:t_episode/project
                :t_episode/date_referral
                :t_episode/date_registration
                :t_episode/date_discharge
                :t_episode/referral_user
                :t_episode/registration_user
                :t_episode/discharge_user
                ::actions]))

(s/def ::column-id #{:project :type :status :date-referral :date-registration
                     :date-discharge :referred-by :registered-by :discharged-by :actions})
(s/def ::columns (s/coll-of ::column-id))
(s/def ::row-action (s/fspec :args (s/cat :episode ::episode-for-table) :ret map?))
(s/def ::empty-message string?)

(s/def ::episodes-table-opts
  (s/keys :opt-un [::columns ::row-action ::empty-message]))

(defn- episode-status-badge [status]
  (case status
    :registered [:span {:class "inline-flex items-center rounded-md bg-green-50 px-2 py-1 text-xs font-medium text-green-700 ring-1 ring-inset ring-green-600/20"}
                 "Registered"]
    :referred [:span {:class "inline-flex items-center rounded-md bg-blue-50 px-2 py-1 text-xs font-medium text-blue-700 ring-1 ring-inset ring-blue-600/20"}
               "Referred"]
    :discharged [:span {:class "inline-flex items-center rounded-md bg-gray-50 px-2 py-1 text-xs font-medium text-gray-600 ring-1 ring-inset ring-gray-500/20"}
                 "Discharged"]
    nil))

(defn- episode-type [{:t_episode/keys [project]}]
  (let [{:t_project/keys [type is_admission]} project]
    (cond
      is_admission "Admission"
      (= type :RESEARCH) "Research"
      (= type :NHS) "Clinical"
      (= type :ALL_PATIENTS) "All patients"
      :else (when type (name type)))))

(defn- format-user [{:t_user/keys [full_name initials]}]
  (or full_name initials ""))

(def ^:private default-columns
  [:project :type :status :date-referral :date-registration :date-discharge])

(def ^:private column-definitions
  {:project           {:title "Project"
                       :f     (fn [episode] (get-in episode [:t_episode/project :t_project/title] ""))}
   :type              {:title "Type"
                       :f     episode-type}
   :status            {:title "Status"
                       :f     (fn [episode] (episode-status-badge (:t_episode/status episode)))}
   :date-referral     {:title "Date referred"
                       :f     (fn [episode] (some-> (:t_episode/date_referral episode) ui/format-date))}
   :date-registration {:title "Date registered"
                       :f     (fn [episode] (some-> (:t_episode/date_registration episode) ui/format-date))}
   :date-discharge    {:title "Date discharged"
                       :f     (fn [episode] (some-> (:t_episode/date_discharge episode) ui/format-date))}
   :referred-by       {:title "Referred by"
                       :f     (fn [episode] (format-user (:t_episode/referral_user episode)))}
   :registered-by     {:title "Registered by"
                       :f     (fn [episode] (format-user (:t_episode/registration_user episode)))}
   :discharged-by     {:title "Discharged by"
                       :f     (fn [episode] (format-user (:t_episode/discharge_user episode)))}
   :actions           {:title "Actions"
                       :f     (fn [episode]
                                (when-let [actions (:actions episode)]
                                  [:div.flex.gap-2
                                   (for [{:keys [label] :as action} actions]
                                     [:button.inline-flex.items-center.rounded.bg-white.px-2.py-1.text-xs.font-semibold.text-gray-900.shadow-sm.ring-1.ring-inset.ring-gray-300.hover:bg-gray-50
                                      (dissoc action :label)
                                      label])]))}})

(s/fdef ui-episodes-table
  :args (s/cat :episodes (s/coll-of ::episode-for-table)
               :opts ::episodes-table-opts))

(defn ui-episodes-table
  "Render a table of episodes with configurable columns and actions.

  See specs for data requirements: ::episode-for-table, ::episodes-table-opts

  Available columns: :project, :type, :status, :date-referral, :date-registration,
                     :date-discharge, :referred-by, :registered-by, :discharged-by, :actions

  When :row-action is provided, cursor and hover effects are automatically added."
  [episodes {:keys [columns row-action empty-message]
             :or   {columns       default-columns
                    empty-message "No episodes found"}}]
  (let [columns' (cond-> columns
                   (and (some :actions episodes)
                        (not (some #{:actions} columns))) (conj :actions))
        column-defs (map #(assoc (column-definitions %) :id %) columns')]
    (if (empty? episodes)
      [:div.text-center.py-8.text-gray-500.italic empty-message]
      (ui/ui-table
        (ui/ui-table-head
          (ui/ui-table-row {}
                           (for [{:keys [title]} column-defs]
                             (ui/ui-table-heading {} title))))
        (ui/ui-table-body
          (for [episode episodes]
            (ui/ui-table-row
              (cond-> {}
                row-action (merge {:class "cursor-pointer hover:bg-gray-50"}
                                  (row-action episode)))
              (for [{:keys [f]} column-defs]
                (ui/ui-table-cell {} (or (f episode) ""))))))))))

(defn episode-modal-content
  "Render the content for an episode modal based on episode type.
  Returns hiccup for the modal body using reusable UI components."
  [{:t_episode/keys [date_referral date_registration date_discharge status]
    :as             episode}
   {:t_project/keys [id title long_description type is_admission]}]
  (ui/ui-simple-form

    (ui/ui-simple-form-item
      {:label "Project"}
      [:div
       [:a.text-blue-600.hover:text-blue-800.font-medium
        {:href (route/url-for :project/home :path-params {:project-id id})}
        title]
       (when long_description
         [:div.text-sm.text-gray-600.mt-1
          {:dangerouslySetInnerHTML {:__html long_description}}])])

    (ui/ui-simple-form-item
      {:label "Type"}
      (cond
        is_admission "Admission"
        (= type :RESEARCH) "Research"
        (= type :NHS) "Clinical Service"
        (= type :ALL_PATIENTS) "All Patients"
        :else (when type (name type))))

    (ui/ui-simple-form-item
      {:label "Status"}
      (episode-status-badge status))

    (when date_referral
      (ui/ui-simple-form-item
        {:label    "Referred"
         :sublabel (when-let [user (:t_episode/referral_user episode)]
                     (str "by " (format-user user)))}
        (ui/format-date date_referral)))

    (when date_registration
      (ui/ui-simple-form-item
        {:label    "Registered"
         :sublabel (when-let [user (:t_episode/registration_user episode)]
                     (str "by " (format-user user)))}
        (ui/format-date date_registration)))

    (when date_discharge
      (ui/ui-simple-form-item
        {:label    "Discharged"
         :sublabel (when-let [user (:t_episode/discharge_user episode)]
                     (str "by " (format-user user)))}
        (ui/format-date date_discharge)))))

(def episode-modal-handler
  "Handler for displaying an episode in a modal dialog."
  (pw/handler
    [:ui/csrf-token
     :ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/permissions
       {:t_patient/episodes
        [:t_episode/id
         :t_episode/status
         :t_episode/date_referral
         :t_episode/date_registration
         :t_episode/date_discharge
         {:t_episode/project [:t_project/id :t_project/title :t_project/long_description :t_project/type :t_project/is_admission]}
         {:t_episode/referral_user [:t_user/id :t_user/full_name :t_user/initials]}
         {:t_episode/registration_user [:t_user/id :t_user/full_name :t_user/initials]}
         {:t_episode/discharge_user [:t_user/id :t_user/full_name :t_user/initials]}]}]}]
    (fn [request {:ui/keys [csrf-token current-patient]}]
      (let [episode-id (parse-long (get-in request [:path-params :episode-id]))
            patient-identifier (:t_patient/patient_identifier current-patient)
            permissions (:t_patient/permissions current-patient)
            episodes (:t_patient/episodes current-patient)
            episode (first (filter #(= episode-id (:t_episode/id %)) episodes))]
        (if-not episode
          (web/not-found (ui/render [:div "Episode not found"]))
          (let [can-discharge? (and (contains? permissions :PATIENT_REGISTER)
                                    (not= :discharged (:t_episode/status episode)))
                modal-actions (cond-> [{:id          :close
                                        :title       "Close"
                                        :role        :secondary
                                        :hx-on:click "htmx.find('#episode-modal').setAttribute('hidden', '')"}]
                                can-discharge?
                                (conj {:id        :discharge
                                       :title     "Discharge"
                                       :role      :danger
                                       :hx-post   (route/url-for :patient/episode-discharge
                                                                 :path-params {:patient-identifier patient-identifier
                                                                               :episode-id         episode-id})
                                       :hx-vals   (str "{\"__anti-forgery-token\": \"" csrf-token "\"}")
                                       :hx-target "#episode-modal"
                                       :hx-swap   "outerHTML"}))]
            (web/ok
              (ui/render
                (ui/ui-modal
                  {:id      "episode-modal"
                   :hidden? false
                   :title   "Episode Details"
                   :size    :large
                   :cancel  :close
                   :actions modal-actions}
                  (episode-modal-content episode (:t_episode/project episode)))))))))))

(def episode-discharge-handler
  "Handler for discharging an episode."
  (pw/handler
    [{:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/permissions
       {:t_patient/episodes
        [:t_episode/id
         :t_episode/status
         {:t_episode/project [:t_project/id]}]}]}]
    (fn [request {:ui/keys [current-patient]}]
      (let [episode-id (parse-long (get-in request [:path-params :episode-id]))
            patient-identifier (:t_patient/patient_identifier current-patient)
            permissions (:t_patient/permissions current-patient)
            episodes (:t_patient/episodes current-patient)
            episode (first (filter #(= episode-id (:t_episode/id %)) episodes))
            user-id (get-in request [:session :authenticated-user :t_user/id])
            rsdb-svc (get-in request [:env :rsdb])]
        (cond
          ;; Episode not found
          (not episode)
          (web/ok
            (ui/render
              [:div#episode-modal
               (ui/alert-error {:title   "Episode not found"
                                :message "The requested episode could not be found."})]))

          ;; User doesn't have permission
          (not (contains? permissions :PATIENT_REGISTER))
          (web/ok
            (ui/render
              [:div#episode-modal
               (ui/alert-error {:title   "Permission denied"
                                :message "You do not have permission to discharge this episode."})]))

          ;; Episode already discharged
          (= :discharged (:t_episode/status episode))
          (web/ok
            (ui/render
              [:div#episode-modal
               (ui/alert-warning {:title   "Already discharged"
                                  :message "This episode is already discharged."})]))

          ;; Perform discharge
          :else
          (try
            (rsdb/discharge-episode! rsdb-svc user-id episode)
            (log/info "Episode discharged" {:episode-id episode-id :user-id user-id})
            (web/hx-redirect (route/url-for :patient/research :path-params {:patient-identifier patient-identifier}))
            (catch Exception e
              (log/error "Error discharging episode" {:episode-id episode-id :user-id user-id :error (.getMessage e)})
              (web/ok
                (ui/render
                  [:div#episode-modal
                   (ui/alert-error {:title   "Error discharging episode"
                                    :message (str "An error occurred: " (.getMessage e))})])))))))))
