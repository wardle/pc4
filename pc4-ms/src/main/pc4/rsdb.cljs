(ns pc4.rsdb
  (:require [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom :refer [div p dt dd table thead tbody tr th td]]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation returning]]))


(defsc PseudonymousPatient
  [this {:t_patient/keys [id sex date_birth]}]
  {:query [:t_patient/id :t_patient/sex :t_patient/date_birth]}
  (div "Pseudonymous patient:" id)
  (div :.bg-white.shadow.sm:rounded-lg.mt-4
       (div :.px-4.py-5.sm:p-6
            (dom/h3 :.text-lg.leading-6.font-medium.text-gray-900
                    (str (name sex))
                    " "
                    "born: " (.getYear date_birth)))))

(def ui-pseudonymous-patient (comp/factory PseudonymousPatient))

(defmutation search-patient-by-pseudonym
  [params]
  (remote
    [env]
    (-> env
        (m/returning PseudonymousPatient))))

(defmutation register-patient-by-pseudonym
  [params]
  (remote
    [env]
    (js/console.log "Registering pseudonymous patient:" env)
    (-> env
        (m/returning PseudonymousPatient)))
  (ok-action [env]
             (js/console.log "Success register patient" env))
  (error-action [env]
                (js/console.error "Failed to register patient" env)))