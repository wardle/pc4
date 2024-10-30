(ns pc4.lemtrada.dev
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.spec.test.alpha :as stest]
   [honey.sql :as sql]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.sql]
   [pc4.config.interface :as config]
   [pc4.lemtrada.core :as dmt]
   [pc4.rsdb.interface :as rsdb]))

(defn write-nhs-numbers
  "For a given centre (e.g. :cambridge) write out CSV file of patient identifiers and NHS number"
  [{:keys [rsdb] :as env} centre out]
  (let [data (rsdb/execute! rsdb
                            (sql/format
                             {:select [:patient_identifier :nhs_number] :from :t_patient
                              :where [:in :patient_identifier
                                      (dmt/fetch-study-patient-identifiers env centre)]}))]
    (with-open [writer (io/writer out)]
      (csv/write-csv writer
                     (into [["patient_identifier" "nhs_number"]]
                           (map (fn [{:t_patient/keys [patient_identifier nhs_number]}] (vector patient_identifier nhs_number)) data))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;   THIS COMMENT BLOCK IS FOR INTERACTIVE USE WITH THE REMOTE SERVER PC4   ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  ;; just connect to remote database
  (stest/unstrument) ;; optionally, uninstrument for production workflows
  (ig/load-namespaces (config/config :dev))
  (def system (ig/init (config/config :pc4-dev) [:pc4.fulcro-server.interface/server]))
  (def system (ig/init (config/config :pc4-dev) [:pc4.lemtrada.interface/env]))
  (ig/halt! system)
  (def env (:pc4.lemtrada.interface/env system))
  (def rsdb (:rsdb env))
  (keys env)
  (rsdb/user-by-id (:rsdb env) 1)
  (dmt/write-data env :plymouth)
  (dmt/write-data env :cambridge)
  (write-nhs-numbers env :cambridge "2024-10-22-cb-patient-ids-nnn.csv")
  (dmt/merge-matching-data "/Users/mark/lemtrada/centres" "/Users/mark/lemtrada/combined")

  (def users
    [{:t_user/username     ""
      :t_user/first_names  ""
      :t_user/last_name    ""
      :t_user/title        ""
      :t_user/email        ""
      :t_user/job_title_fk 8}])

  (def project-id 126) ;; Cambridge
  (def project-id 127) ;; Plymouth
  (map (fn [{:t_user/keys [username] :as user}]
         (let [user (rsdb/create-user! rsdb user)]
           (rsdb/register-user-to-project! rsdb {:username username :project-id project-id})
           user)) users)
  (:t_user/id (rsdb/user-by-username rsdb "xxx"))
  (:t_user/id (rsdb/user-by-username rsdb "xxx"))
  (rsdb/reset-password! rsdb {:t_user/id 968})
  (next.jdbc.sql/update! (:conn rsdb) :t_user {:credential "xxxx"} {:t_user/id 967})
  (next.jdbc/execute! (:conn rsdb) ["select * from t_user"])

  (rsdb/project-by-id rsdb 3)

  (rsdb/register-user-to-project! rsdb {:username   "xxx" :project-id 1})

  (rsdb/set-must-change-password! rsdb  "xxx")

  (rsdb/patient-by-project-pseudonym (:com.eldrix.rsdb/conn system) 3 "xxx")
  (rsdb/update-legacy-pseudonymous-patient!
   rsdb 128283
   {:nhs-number "xxx"
    :date-birth (java.time.LocalDate/of 1900 1 1)
    :sex        :FEMALE})

  (rsdb/project-by-id rsdb 126)

  (rsdb/discharge-episode! rsdb 1 {:t_episode/id 48256})

  (dmt/make-patient-identifiers-table env
                                      (dmt/fetch-study-patient-identifiers env :cambridge))
  (dmt/write-table system
                   dmt/patient-identifiers-table
                   :plymouth
                   (dmt/fetch-study-patient-identifiers system :plymouth))

  (dmt/all-patient-diagnoses system [124079])
  (dmt/write-data system :cambridge))
