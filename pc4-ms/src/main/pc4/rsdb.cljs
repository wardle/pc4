(ns pc4.rsdb
  (:require [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [pc4.route :as route]))


(defmutation search-patient-by-pseudonym
  [params]
  (action [{:keys [state] :as env}]
    (if (empty? params)
      (swap! state dissoc :ui/search-patient-pseudonymous)))
  (remote [env]
          (when (seq params)
            (-> env
                      (m/with-target [:ui/search-patient-pseudonymous])
                      (m/returning 'pc4.patients/PatientBanner)))))


(defmutation register-patient-by-pseudonym
  [params]
  (remote [env]
          (js/console.log "Registering pseudonymous patient:" env)
          (-> env
              (m/returning 'pc4.patients/PatientPage)))
  (ok-action [env]
             (js/console.log "Response register patient" env)
             (if-let [patient-id (get-in env [:result :body 'pc4.rsdb/register-patient-by-pseudonym :t_patient/patient_identifier])]
               (do (js/console.log "register patient : patient id: " patient-id)
                   (pc4.route/route-to! ["patients" patient-id]))
               (do (js/console.log "failed to register patient:" env))))
  (error-action [env]
                (js/console.error "Failed to register patient" env)))