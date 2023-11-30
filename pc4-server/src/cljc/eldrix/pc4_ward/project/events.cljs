(ns eldrix.pc4-ward.project.events
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.server :as srv]))

(defn vmake-fetch-project-op
  [{:keys [id]}]
  [{[:t_project/id (int id)]
    [:t_project/id
     :t_project/name
     :t_project/title
     :t_project/long_description
     :t_project/type
     :t_project/active?
     :t_project/virtual
     :t_project/pseudonymous
     :t_project/slug
     :t_project/parent
     :t_project/specialty
     :t_project/users
     :t_project/date_from
     :t_project/date_to
     :t_project/exclusion_criteria :t_project/inclusion_criteria
     :t_project/address1 :t_project/address2 :t_project/address3
     :t_project/address4 :t_project/postcode
     :t_project/care_plan_information
     :t_project/is_private
     :t_project/count_registered_patients
     :t_project/count_discharged_episodes
     :t_project/count_pending_referrals
     :t_project/encounter_templates]}])

(rf/reg-event-fx ::set-current-project                      ;; TODO: rename to open-project
  []
  (fn [{db :db} [_ project-id]]
    (js/console.log "selecting project " project-id)
    {:db (dissoc db :project/current)
     :fx [[:pathom {:params     (make-fetch-project-op {:id project-id})
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-fetch-response]
                    :on-failure [::handle-fetch-failure]}]]}))

(rf/reg-event-fx ::handle-fetch-response
  []
  ;; a response is a map of request to response e.g. {[:t_project/id 117] {:t_project/name "NORTHCWMTAFNEUROLOGY"}}
  (fn [{db :db} [_ response]]
    (let [[_ v] (first response)]
      {:db (assoc db :project/current v)})))

(rf/reg-event-fx ::handle-fetch-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "fetch project failure: response " response)
    {:db (-> db
             (dissoc :project/current)
             (assoc-in [:errors :project/current] "Failed to fetch project: unable to connect to server. Please check your connection and retry."))}))

(rf/reg-event-db ::close-current-project
  []
  (fn [db [_]]
    (js/console.log "closing project")
    (dissoc db :project/current)))

(comment
  (rf/dispatch [::set-current-project 21]))
