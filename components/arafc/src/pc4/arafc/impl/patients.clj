(ns pc4.arafc.impl.patients
  (:require [pc4.rsdb.interface :as rsdb]))




(comment
  (def conn (next.jdbc/get-connection "jdbc:postgresql:rsdb"))
  (require '[pc4.rsdb.nform.api :as nf])
  (def rsdb {:conn conn :form-store (nf/make-form-store conn)})
  (def patients (rsdb/araf-programme-outcome rsdb :valproate-f 15))
  patients
  (group-by :task (map #(rsdb/araf-status svc :valproate-f (:t_patient/id %))
                       (rsdb/patients {:conn conn} {:project-ids [5] :limit 20}))))