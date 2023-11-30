(ns pc4.project.events
  (:require [re-frame.core :as rf]
            [pc4.events :as events]))

(rf/reg-event-fx ::search-legacy-pseudonym
  (fn [{db :db} [_ project-id pseudonym properties]]
    (js/console.log "search by pseudonym" project-id pseudonym)
    (tap> {:event ::search-legacy-pseudonym :db db})
    (cond-> {:db (-> db
                     (dissoc :patient/search-legacy-pseudonym)
                     (update-in [:errors] dissoc ::search-legacy-pseudonym))}
            (>= (count pseudonym) 3)
            (assoc :fx [[:pathom {:params     [{(list 'pc4.rsdb/search-patient-by-pseudonym
                                                      {:project-id project-id
                                                       :pseudonym  pseudonym}) properties}]
                                  :token      (get-in db [:authenticated-user :io.jwt/token])
                                  :on-success [::handle-search-pseudonym-response]
                                  :on-failure [::handle-search-pseudonym-failure]}]]))))

(rf/reg-event-db ::clear-search-legacy-pseudonym-results
  (fn [db _]
    (dissoc db :patient/search-legacy-pseudonym)))

(rf/reg-event-fx ::handle-search-pseudonym-response
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/search-patient-by-pseudonym}]]
    (js/console.log "search by pseudonym response: " result)
    {:db (assoc db :patient/search-legacy-pseudonym result)}))

(rf/reg-event-fx ::handle-search-pseudonym-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "search by pseudonym failure: response " response)
    {:db (-> db
             (dissoc :patient/search-legacy-pseudonym)
             (assoc-in [:errors ::search-legacy-pseudonym] "Failed to search for patient: unable to connect to server. Please check your connection and retry."))}))

(rf/reg-event-fx ::register-pseudonymous-patient
  (fn [{db :db} [_ {:keys [project-id nhs-number date-birth sex] :as params}]]
    (js/console.log "searching or registering pseudonymous patient record:" params)
    {:db (-> db
             (dissoc :patient/current)
             (update-in [:errors] dissoc :open-patient))
     :fx [[:pathom {:params     [{(list 'pc4.rsdb/register-patient-by-pseudonym
                                        params)
                                  [:t_episode/project_fk :t_episode/stored_pseudonym]}]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-register-pseudonymous-patient-response]
                    :on-failure [::handle-register-pseudonymous-patient-failure]}]]}))

(rf/reg-event-fx ::handle-register-pseudonymous-patient-response
  []
  (fn [{db :db} [_ {result 'pc4.rsdb/register-patient-by-pseudonym}]]
    (js/console.log "register pseudonymous patient response: " result)
    (if (:com.wsscode.pathom3.connect.runner/mutation-error result)
      {:db (-> db
               (dissoc :patient/current)
               (assoc-in [:errors :open-patient] "Mismatch in patient demographics. There is already a patient registered with this NHS number, but the other demographic details do not match."))}
      {:db (assoc-in db [:patient/current :patient] result)
       :fx [[:dispatch [::events/push-state :pseudonymous-patient/home
                        {:project-id (:t_episode/project_fk result)
                         :pseudonym  (:t_episode/stored_pseudonym result)}]]]})))

(rf/reg-event-db ::clear-register-patient-error
  (fn [db _]
    (update-in db [:errors] dissoc :open-patient)))

(rf/reg-event-fx ::handle-register-pseudonymous-patient-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "register pseudonymous patient failure: response " response)
    {:db (-> db
             (dissoc :patient/current)
             (assoc-in [:errors :open-patient] (or (get-in response [:response :message]) (:status-text response))))}))

(rf/reg-event-fx ::register-patient-by-nhs-number
  []
  (fn [{db :db} [_ {:keys [project-id nhs-number] :as params}]]
    (js/console.log "searching or registering patient record:" params)
    {:db (-> db
             (dissoc :patient/current)
             (update-in [:errors] dissoc :open-patient))
     :fx [[:pathom {:params     [{(list 'pc4.rsdb/register-patient
                                        params)
                                  [:t_patient/patient_identifier :t_episode/project_fk]}]
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::register-patient-by-nhs-number-response]
                    :on-failure [::register-patient-by-nhs-number-failure]}]]}))

(rf/reg-event-fx ::register-patient-by-nhs-number-response
  []
  (fn [{db :db} [_ response]]
    (js/console.log "fetch  patient response: " response)
    (let [patient (get response 'pc4.rsdb/register-patient)]
      (tap> {:register-by-nnn patient})
      {:db (assoc-in db [:patient/current :patient] patient)
       :fx [[:dispatch [::events/push-state :patient/home
                        {:project-id         (:t_episode/project_fk patient)
                         :patient-identifier (:t_patient/patient_identifier patient)}]]]})))

(rf/reg-event-fx ::register-patient-by-nhs-number-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "fetch patient failure: response " response)
    {:db (-> db
             (dissoc :patient/current :patient/loading?)
             (assoc-in [:errors :open-patient] "Failed to fetch patient: unable to connect to server. Please check your connection and retry."))}))
