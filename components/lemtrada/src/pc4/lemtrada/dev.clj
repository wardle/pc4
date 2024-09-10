(ns pc4.lemtrada.dev
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.spec.test.alpha :as stest]
   [integrant.core :as ig]
   [next.jdbc]
   [next.jdbc.sql]
   [pc4.config.interface :as config]
   [pc4.lemtrada.core :as dmt]
   [pc4.rsdb.interface :as rsdb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;   THIS COMMENT BLOCK IS FOR INTERACTIVE USE WITH THE REMOTE SERVER PC4   ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  ;; just connect to remote database
  (stest/unstrument) ;; optionally, uninstrument for production workflows
  (def system (ig/init (config/config :pc4-dev) [:pc4.rsdb.interface/svc]))
  (ig/load-namespaces (config/config :dev))
  (def system (ig/init (config/config :pc4-dev) [:pc4.graph.interface/boundary-interface]))
  (def system (ig/init (config/config :pc4-dev) [:pc4.fulcro-server.interface/server]))
  (def system (ig/init (config/config :pc4-dev) [:pc4.lemtrada.interface/env]))
  (ig/halt! system)
  (def env (:pc4.lemtrada.interface/env system))
  (keys env)
  (rsdb/user-by-id (:rsdb env) 1)
  (dmt/write-data env :plymouth)
  (dmt/write-data env :cambridge)
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
  (def conn (:pc4.rsdb.interface/conn system))
  (map (fn [{:t_user/keys [username] :as user}]
         (let [user (rsdb/create-user! conn user)]
           (rsdb/register-user-to-project! conn {:username username :project-id project-id})
           user)) users)
  (def conn (:com.eldrix.rsdb/conn system))
  (:t_user/id (rsdb/user-by-username conn "xxx"))
  (:t_user/id (rsdb/user-by-username conn "xxx"))
  (rsdb/reset-password! conn {:t_user/id 968})
  (next.jdbc.sql/update! conn :t_user {:credential "xxxx"} {:t_user/id 967})
  (next.jdbc/execute! (:com.eldrix.rsdb/conn system) ["select * from t_user"])

  (rsdb/project-by-id (:com.eldrix.rsdb/conn system) 3)

  (rsdb/register-user-to-project! (:com.eldrix.rsdb/conn system) {:username   "xxx" :project-id 1})

  (rsdb/set-must-change-password! (:com.eldrix.rsdb/conn system) "xxx")

  (rsdb/patient-by-project-pseudonym (:com.eldrix.rsdb/conn system) 3 "xxx")

  (def global-salt (get-in system [:pc4.rsdb.interface/config :legacy-global-pseudonym-salt]))
  global-salt
  (next.jdbc/with-transaction [txn (:com.eldrix.rsdb/conn system) {:isolation :serializable}]
    (rsdb/update-legacy-pseudonymous-patient!
     txn global-salt 128283
     {:nhs-number "xxx"
      :date-birth (java.time.LocalDate/of 1900 1 1)
      :sex        :FEMALE}))
  (rsdb/find-legacy-pseudonymous-patient (:com.eldrix.rsdb/conn system)
                                         {:salt       global-salt :project-id 126 :nhs-number "xxx"
                                          :date-birth (java.time.LocalDate/of 1900 1 1)})
  (rsdb/project-by-id (:com.eldrix.rsdb/conn system) 126)

  (rsdb/discharge-episode! (:com.eldrix.rsdb/conn system) 1 {:t_episode/id 48256})

  (dmt/make-patient-identifiers-table system
                                      (com.eldrix.pc4.modules.dmt/fetch-study-patient-identifiers system :cambridge))
  (dmt/write-table system
                   com.eldrix.pc4.modules.dmt/patient-identifiers-table
                   :plymouth
                   (com.eldrix.pc4.modules.dmt/fetch-study-patient-identifiers system :plymouth))

  (dmt/all-patient-diagnoses system [124079])
  (dmt/write-data system :cambridge))
