(ns com.eldrix.pc4.rsdb.core
  (:require [next.jdbc :as jdbc]
            [com.eldrix.pc4.rsdb.patients :as patients]))


(comment
  (def conn (jdbc/get-connection {:dbtype "postgresql" :dbname "rsdb"}))
  (com.eldrix.pc4.rsdb.patients/fetch-patient-addresses conn {:t_patient/id 8}))


