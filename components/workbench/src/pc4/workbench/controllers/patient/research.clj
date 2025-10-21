(ns pc4.workbench.controllers.patient.research
  (:require
    [io.pedestal.http.route :as route]
    [pc4.log.interface :as log]
    [pc4.pathom-web.interface :as pw]
    [pc4.ui.interface :as ui]
    [pc4.web.interface :as web]
    [pc4.workbench.controllers.patient.episodes :as episodes]))

(def research-handler
  (pw/handler
    {:menu :research}
    [:ui/patient-page
     :ui/csrf-token
     {:ui/current-patient
      [:t_patient/patient_identifier
       :t_patient/permissions
       {:t_patient/episodes
        [:t_episode/id
         :t_episode/status
         :t_episode/date_referral
         :t_episode/date_registration
         :t_episode/date_discharge
         {:t_episode/project [:t_project/id :t_project/title :t_project/type :t_project/is_admission]}
         {:t_episode/referral_user [:t_user/id :t_user/full_name :t_user/initials]}
         {:t_episode/registration_user [:t_user/id :t_user/full_name :t_user/initials]}
         {:t_episode/discharge_user [:t_user/id :t_user/full_name :t_user/initials]}]}
       {:t_patient/suggested_registrations [:t_project/id :t_project/title :t_project/type :t_project/is_admission]}]}]
    (fn [request {:ui/keys [patient-page csrf-token current-patient]}]
      (log/debug "research-handler called" {:patient-page patient-page :current-patient current-patient})
      (let [patient-identifier (:t_patient/patient_identifier current-patient)
            permissions (:t_patient/permissions current-patient)
            can-register? (contains? permissions :PATIENT_REGISTER)
            episodes (:t_patient/episodes current-patient)
            research-episodes (filter #(= :RESEARCH (get-in % [:t_episode/project :t_project/type])) episodes)
            sorted-episodes (sort-by #(get-in % [:t_episode/project :t_project/title]) research-episodes)
            active-episodes (filter #(not= :discharged (:t_episode/status %)) sorted-episodes)
            inactive-episodes (filter #(= :discharged (:t_episode/status %)) sorted-episodes)
            suggested-registrations (:t_patient/suggested_registrations current-patient)
            research-registrations (filter #(= :RESEARCH (:t_project/type %)) suggested-registrations)]
        (log/debug "research episodes" {:count (count research-episodes) :active (count active-episodes) :inactive (count inactive-episodes)})
        (web/ok
          (ui/render-file
            "templates/patient/base.html"
            (assoc patient-page
              :content (ui/render
                         [:div.space-y-6
                          [:div#episode-modal-container]
                          [:div#episode-content
                           (ui/ui-title {:title "Active projects"})
                           (episodes/ui-episodes-table
                             active-episodes
                             {:columns       [:project :status :date-referral :date-registration]
                              :row-action    (fn [episode]
                                               {:hx-get            (route/url-for :patient/episode-modal
                                                                                  :path-params {:patient-identifier patient-identifier
                                                                                                :episode-id         (:t_episode/id episode)})
                                                :hx-target         "#episode-modal-container"
                                                :hx-on--after-swap "htmx.removeClass(document.getElementById('episode-modal'), 'hidden')"})
                              :empty-message "No active research projects"})]
                          (when (and can-register? (seq research-registrations))
                            [:div
                             (ui/ui-title {:title "Register"})
                             (ui/ui-table
                               (ui/ui-table-head
                                 (ui/ui-table-row {}
                                                  (ui/ui-table-heading {} "Project")
                                                  (ui/ui-table-heading {} "")))
                               (ui/ui-table-body
                                 (for [{:t_project/keys [id title]} research-registrations]
                                   (ui/ui-table-row {}
                                                    (ui/ui-table-cell {} title)
                                                    (ui/ui-table-cell {}
                                                                      [:form {:method "post"
                                                                              :action (route/url-for :patient/do-register-to-project
                                                                                                     :path-params {:patient-identifier patient-identifier})}
                                                                       [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]
                                                                       [:input {:type "hidden" :name "project-id" :value id}]
                                                                       [:input {:type "hidden" :name "redirect-url" :value (route/url-for :patient/research
                                                                                                                                           :path-params {:patient-identifier patient-identifier})}]
                                                                       [:button.inline-flex.items-center.rounded.bg-blue-600.px-2.py-1.text-xs.font-semibold.text-white.shadow-sm.hover:bg-blue-700
                                                                        {:type "submit"}
                                                                        "Register"]])))))])
                          (when (seq inactive-episodes)
                            [:div
                             (ui/ui-title {:title "Inactive projects"})
                             (episodes/ui-episodes-table
                               inactive-episodes
                               {:columns    [:project :status :date-registration :date-discharge]
                                :row-action (fn [episode]
                                              {:hx-get            (route/url-for :patient/episode-modal
                                                                                 :path-params {:patient-identifier patient-identifier
                                                                                               :episode-id         (:t_episode/id episode)})
                                               :hx-target         "#episode-modal-container"
                                               :hx-on--after-swap "htmx.removeClass(document.getElementById('episode-modal'), 'hidden')"})})])]))))))))