(ns pc4.msbase.cli
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [pc4.config.interface :as config]
   [pc4.lemtrada.interface :as lemtrada]
   [pc4.msbase.core :as msbase]
   [pc4.rsdb.interface :as rsdb]
   [pc4.rsdb.users]))

(s/def ::profile #{:cvx :dev :pc4-dev})
(s/def ::out some?)
(s/def ::centre #{:cambridge :cardiff :plymouth})
(s/def ::patient-identifier int?)
(s/def ::project-id int?)
(s/def ::n int?)
(s/def ::export-options
  (s/or
   :by-patient (s/keys :req-un [::profile ::out ::patient-identifier])
   :by-centre (s/keys :req-un [::profile ::out ::centre] :opt-un [::n])
   :by-project (s/keys :req-un [::profile ::out ::project-id])))

(defn patient-ids
  "Return patient identifiers based on the configuration specified."
  [{:keys [rsdb] :as lemtrada-env} conf]
  (let [opts (s/conform ::export-options conf)]
    (if (= opts ::s/invalid)
      (throw (ex-info "invalid options" (s/explain-data ::export-options conf)))
      (let [[mode opts'] opts]
        (case mode
          :by-patient
          (let [patient-identifier (:patient-identifier opts')]
            #{patient-identifier})
          :by-centre
          (let [centre (:centre opts')
                n (:n opts')]
            (if n
              (take n (sort (lemtrada/patient-identifiers lemtrada-env centre)))
              (lemtrada/patient-identifiers lemtrada-env centre)))
          :by-project
          (let [project-id (:project-id opts')]
            (rsdb/project-ids->patient-ids rsdb #{project-id})))))))

(defn export
  "Export MSBase data. 
  e.g.,
  ```
  clj -X:dev pc4.msbase.cli/export :profile :pc4-dev :centre :cambridge :out msbase :n 100
  ```"
  [{:keys [profile out] :as opts}]
  (let [conf         (config/config profile)
        _            (ig/load-namespaces conf)
        system       (ig/init conf [:pc4.lemtrada.interface/env :pc4.graph.interface/boundary-interface])
        lemtrada-env (:pc4.lemtrada.interface/env system)
        pids         (patient-ids lemtrada-env opts)
        rsdb         (:pc4.rsdb.interface/svc system)
        ;; create a fake pathom env with required authentication... this needs to be cleaned up
        ;; TODO: clean up creation of pathom environment so it is standardised, and can be built
        ;; on the fly from data in a real session, or created ad-hoc for programmatic / REPL usage 
        ;; like this
        pathom-env   {:session/authenticated-user
                      (assoc (rsdb/user-by-username rsdb "system")
                             :t_user/active_roles (pc4.rsdb.users/active-roles-by-project-id (:conn rsdb) "system"))
                      :session/authorization-manager (rsdb/username->authorization-manager rsdb "system")}
        pathom       (partial (:pc4.graph.interface/boundary-interface system) pathom-env)]
    (println "exporting" (count pids) "patient records")
    (doseq [patient-id pids]
      (spit (io/file (str out) (str "cambridge-" patient-id ".json")) (json/write-str (msbase/fetch-patient pathom patient-id))))
    (ig/halt! system)))

(comment
  (export {:profile :pc4-dev :centre :cambridge :out "msbase" :n 100}))


