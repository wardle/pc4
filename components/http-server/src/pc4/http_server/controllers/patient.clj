(ns pc4.http-server.controllers.patient
  (:require [clojure.string :as str]
            [com.eldrix.nhsnumber :as nnn]
            [pc4.http-server.controllers.user :as user]
            [pc4.http-server.html :as html]
            [pc4.log.interface :as log]
            [pc4.rsdb.interface :as rsdb]
            [selmer.parser :as selmer]))


(def authorized
  {:enter
   (fn [ctx]
     ctx)})

(defn banner-env
  [{:t_patient/keys [id patient-identifier nhs_number status sex title first_names last_name date_birth date_death]}
   {:t_address/keys [address1 address2 address3 address4 postcode] :as address}]
  (if (= :PSEUDONYMOUS status)
    {:name         (when sex (name sex))
     :date-birth   date_birth
     :date-death   date_death
     :gender       (some-> sex name str/upper-case)
     :address      address1
     :pseudonymous true}
    {:name         (str (str/join ", " (remove str/blank? [(when last_name (str/upper-case last_name)) first_names]))
                        (when title (str " (" title ")")))
     :date-birth   date_birth
     :nhs-number   (nnn/format-nnn nhs_number)
     :date-death   date_death
     :gender       (some-> sex name str/upper-case)
     :address      (if address (str/join ", " (remove str/blank? [address1 address2 address3 address4 postcode]))
                               (if date_death "No known address" "No known current address"))
     :pseudonymous false}))

(defn home
  [request]
  (let [patient-identifier (some-> (get-in request [:path-params :patient-identifier]) parse-long)
        patient# {:t_patient/patient_identifier patient-identifier}
        rsdb (get-in request [:env :rsdb])
        patient (rsdb/fetch-patient rsdb patient#)
        address (rsdb/address-for-date (rsdb/patient->addresses rsdb patient#))
        project (get-in request [:session :project])]
    (log/info "current project:" project)
    (html/ok
      (selmer/render-file
        "patient/home-page.html"
        (-> (user/session-env request)
            (assoc :patient (banner-env patient address)))))))

(defn break-glass
  [request]
  )

(defn nhs [request])

(defn projects [request])

(defn admissions [request])

(defn register [request])

(defn encounters [request])

(defn diagnoses [request])

(defn medication [request])

(defn documents [request])

(defn results [request])

(defn procedures [request])

(defn alerts [request])

(defn family [request])

(defn neuroinflammatory [request])

(defn motorneurone [request])

(defn epilepsy [request])