(ns pc4.notify.interface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [integrant.core :as ig]
            [pc4.config.interface :as config]
            [pc4.log.interface :as log]
            [pc4.notify.interface :as notify]))

(def ^:dynamic *system* nil)

(defn with-system [f]
  (let [conf (config/config :dev)
        _ (ig/load-namespaces conf [::notify/svc])
        system (ig/init (ig/deprofile conf :dev) [::notify/svc])]
    (binding [*system* system]
      (try
        (f)
        (finally
          (ig/halt! system))))))

(use-fixtures :once with-system)

(deftest test-send-sms
  (let [url (str "https://araf.patientcare.app/araf/form/" (rand-int 1000))
        template-id "64bc8d82-2161-4585-ac08-21feee5a7922"
        {:keys [status body]} (notify/send-sms! (::notify/svc *system*) "07786000000" template-id
                                                {:patient "test" :drug "test" :url url})]
    (is (= 201 status))
    (is (str/includes? (get-in body [:content :body]) url))
    (is (= template-id (get-in body [:template :id])))
    (log/debug "sent sms via gov.uk notify with id" (:id body))))
