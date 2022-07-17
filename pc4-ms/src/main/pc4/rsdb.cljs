(ns pc4.rsdb
  (:require [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
            [pc4.route :as route]
            [taoensso.timbre :as log]))


(defmutation search-patient-by-pseudonym
  [params]
  (action [{:keys [state] :as env}]
          (if (empty? params)
            (swap! state dissoc :ui/search-patient-pseudonymous)))
  (remote [env]
          (when (seq params)
            (-> env
                (m/with-target [:ui/search-patient-pseudonymous])
                (m/returning 'pc4.ui.patients/PatientBanner)))))


(defmutation register-patient-by-pseudonym
  [params]
  (remote
    [env]
    (log/debug "Registering pseudonymous patient:" env)
    (m/returning env 'pc4.ui.patients/PatientPage))
  (ok-action
    [{:keys [state ref] :as env}]                           ;; ref = ident of the component
    (if-let [patient-id (get-in env [:result :body 'pc4.rsdb/register-patient-by-pseudonym :t_patient/patient_identifier])]
      (do (log/debug "register patient : patient id: " patient-id)
          (pc4.route/route-to! ["patients" patient-id]))
      (do (log/debug "failed to register patient:" env)
          (swap! state update-in ref assoc :ui/error "Incorrect patient demographics.")))))
