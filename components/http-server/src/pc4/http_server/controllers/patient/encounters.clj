(ns pc4.http-server.controllers.patient.encounters
  (:require
    [clojure.string :as str]
    [io.pedestal.http.route :as route]
    [pc4.http-server.controllers.patient :as patient]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.ui :as ui]
    [pc4.http-server.web :as web]))


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
       :t_patient/permissions
       {:t_patient/encounters
        [:t_encounter/id
         :t_encounter/date_time
         :t_encounter/status
         {:t_encounter/encounter_template
          [:t_encounter_template/id
           :t_encounter_template/name
           :t_encounter_template/description]}]}]}]
    (fn [_ {:ui/keys [csrf-token patient-page current-patient]}]
      (let [{:t_patient/keys [patient_identifier permissions encounters]} current-patient
            can-edit? (get-in permissions [:PATIENT_EDIT])]
        (web/ok
          (web/render-file
            "templates/patient/base.html"
            (assoc patient-page
              :content
              (web/render
                [:div
                 (ui/ui-title {:title "Encounters"})
                 (ui/ui-table
                   (ui/ui-table-head
                     (ui/ui-table-row
                       {}
                       (map #(ui/ui-table-heading {} %) ["Date" "Type" "Description"])))
                   (ui/ui-table-body
                     (if (seq encounters)
                       (->> encounters
                            (sort-by :t_encounter/date_time)
                            (reverse)
                            (map encounter->display)
                            (map #(ui/ui-table-row
                                    {:class "hover:bg-gray-50"}
                                    (ui/ui-table-cell {} (:date-time %))
                                    (ui/ui-table-cell {} (:title %))
                                    (ui/ui-table-cell {} (:description %)))))
                       [(ui/ui-table-row
                          {}
                          (ui/ui-table-cell {:colspan 3} "No encounters found"))])))]))))))))