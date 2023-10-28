(ns pc4.rsdb
  (:require [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]
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

(defmutation register-patient
  [params]
  (remote [env]
          (m/returning env 'pc4.ui.patients/PatientPage))
  (ok-action
    [{:keys [state ref] :as env}]
    (tap> {:mutation-env env})                              ;; ref = ident of the component
    (if-let [patient-id (get-in env [:result :body 'pc4.rsdb/register-patient :t_patient/patient_identifier])]
      (do (log/debug "register patient : patient id: " patient-id)
          (dr/change-route! @pc4.app/SPA ["patient" patient-id]))
      (do (log/debug "failed to register patient:" env)
          (swap! state update-in ref assoc :ui/error "Unable to register patient.")))))

(defmutation register-patient-by-pseudonym
  [params]
  (remote
    [env]
    (log/debug "Registering pseudonymous patient:" env)
    (m/returning env 'pc4.ui.patients/PatientPage))
  (ok-action
    [{:keys [state ref] :as env}]
    (tap> {:mutation-env env})                              ;; ref = ident of the component
    (if-let [patient-id (get-in env [:result :body 'pc4.rsdb/register-patient-by-pseudonym :t_patient/patient_identifier])]
      (do (log/debug "register patient : patient id: " patient-id)
          (dr/change-route! @pc4.app/SPA ["patient" patient-id]))
      (do (log/debug "failed to register patient:" env)
          (swap! state update-in ref assoc :ui/error "Incorrect patient demographics")))))

(defmutation save-medication
  [params]
  (remote [env] true)
  (ok-action
    [{:keys [ref state] :as env}]
    (tap> {:save-medication-ok env})))

(defmutation delete-medication
  [params]
  (remote [env] true)
  (ok-action
    [{:keys [component] :as env}]
    (df/load-field! component :t_patient/medications {})))

(defmutation create-admission
  [params]
  (action [{:keys [ref state]}]
          (swap! state update-in ref assoc :ui/editing-admission params)))

(defmutation save-admission
  [{:t_episode/keys [id] :as params}]
  (remote
    [{:keys [ref state] :as env}] true)
  (ok-action                                                ;; once admission is saved, close modal editing form
    [{:keys [ref state] :as env}]
    (swap! state assoc-in (conj ref :ui/editing-admission) nil)))

(defmutation delete-admission
  [{:t_episode/keys [id patient_fk]}]
  (remote [_]
          (println "Deleting admission" id)
          true)
  (ok-action                                                ;; once admission is deleted, close modal editing form
    [{:keys [ref state] :as env}]
    (swap! state assoc-in (conj ref :ui/editing-admission) nil)))

(defmutation save-ms-diagnosis
  [params]
  (action [{:keys [ref state]}]
          (let [path (conj ref :t_summary_multiple_sclerosis/ms_diagnosis)]
            (swap! state assoc-in path (select-keys params [:t_ms_diagnosis/id :t_ms_diagnosis/name]))))
  (remote [env] true))

(defmutation save-pseudonymous-patient-postal-code
  [params]
  (remote [env] true)
  (ok-action [{:keys [component]}]
             (df/refresh! component)))

(defmutation edit-death-certificate
  [params]
  (action [{:keys [ref state]}]
          (swap! state update-in ref assoc :ui/editing-death-certificate params)))

(defmutation notify-death
  [params]
  (remove [env] (m/returning env 'pc4.ui.patients/PatientDeathCertificate)))
