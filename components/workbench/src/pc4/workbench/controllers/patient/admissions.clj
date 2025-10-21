(ns pc4.workbench.controllers.patient.admissions
  (:require
    [pc4.log.interface :as log]
    [pc4.pathom-web.interface :as pw]
    [pc4.ui.interface :as ui]
    [pc4.web.interface :as web]
    [pc4.workbench.controllers.patient.episodes :as episodes]))

(def admissions-handler
  (pw/handler
    {:menu :admissions}
    [:ui/patient-page
     {:ui/current-patient
      [:t_patient/patient_identifier
       {:t_patient/episodes
        [:t_episode/id
         :t_episode/status
         :t_episode/date_registration
         :t_episode/date_discharge
         {:t_episode/project [:t_project/id :t_project/title :t_project/type :t_project/is_admission]}]}]}]
    (fn [request {:ui/keys [patient-page current-patient]}]
      (log/debug "admissions-handler called" {:patient-page patient-page :current-patient current-patient})
      (let [episodes (:t_patient/episodes current-patient)
            admission-episodes (filter #(get-in % [:t_episode/project :t_project/is_admission]) episodes)
            sorted-episodes (reverse (sort-by :t_episode/date_registration admission-episodes))]
        (log/debug "admission episodes" {:count (count admission-episodes)})
        (web/ok
          (ui/render-file
            "templates/patient/base.html"
            (assoc patient-page
              :content (ui/render
                         (episodes/ui-episodes-table
                           sorted-episodes
                           {:columns       [:project :status :date-registration :date-discharge]
                            :empty-message "No admissions"})))))))))
