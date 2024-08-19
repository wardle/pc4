(ns pc4.rsdb.reports
  "Support for legacy style reports. 
 
  The legacy rsdb supported a number of approaches to templates, including a 
  PDF with fields that could be completed by key value coding. However, the
  only model ever used was one based on WebObjects own component-based HTML 
  templating engine, and so was called WOC. As such, all reports used in any
  live implementations use WOC as the template_behaviour_name. 
  
  It would be appropriate to continue to have flexibility in generation, 
  because there are some reports which probably should have fixed page 
  structures.

  The legacy provided report components were:
  - VisitReport - the main encounter report
  - ProcedureReport - unnecessarily duplicated VisitReport - should be removed
  - MultipleSclerosisDmtReport - a MS specific MDT report for encounter
  
  All legacy reports are built in the context of a single encounter. This
  assumption is not necessarily needed, although as reports are currently
  generated in that context, and stored against an encounter, and all data can
  be reached from an encounter in some way, perhaps that assumption continues
  to be reasonable.
  

  The underlying database has the following columns:
       Column       |            Type             | Collation | Nullable |              Default
--------------------+-----------------------------+-----------+----------+-----------------------------------
 created_by_user_fk | integer                     |           | not null |
 date_time_created  | timestamp without time zone |           | not null |
 encounter_fk       | integer                     |           | not null |
 filename           | character varying(255)      |           |          |
 id                 | integer                     |           | not null | nextval('t_report_seq'::regclass)
 interim_html       | character varying(10000000) |           | not null |
 report_data_fk     | integer                     |           | not null |
 signed_by_user_fk  | integer                     |           | not null |
 status             | character varying(50)       |           | not null |
 template_fk        | integer                     |           | not null |
 title              | character varying(255)      |           | not null |
 printed_by_user_fk | integer                     |           |          |
 date_time_signed   | timestamp without time zone |           |          | 
  
  The lifecycle of a report is handled in the 'status' column of 't_report'.
  - DRAFT             - report is in draft and not finalised
  - PENDING_SIGNATURE - report is not editable and awaiting signature
  - PENDING_PRINTING  
  - FINAL
  - DELETED

  The legacy application permitted a report to be generated but then the interim
  HTML to be edited. The new approach is that reports are generated atomically
  and cannot be edited, so DRAFT and PENDING_SIGNATURE are essentially synonymous.

  Each report is linked to t_user through three foreign keys:
  - created_by_user_fk - user who created the report
  - signed_by_user_fk  - will be the user who will sign, or who has signed
  - printed_by_user_fk - user who will or did 'print' the report.
 
  The main API consists of:
  - report-by-id           - fetch a report
  - report-data-for-report - fetch data for report
  - report-and-data-by-id  - fetch report and associated data
  - reports-for-encounter  - return reports for an encounter
  - reports-for-encounters - return reports for encounters
  - create-report!         - create a report
  - sign-report!           - sign a report off, setting STATUS to 'FINAL'
  - delete-report!         - mark a report as deleted
  - mark-report-printed!   - mark a report as 'printed'
  - statistics-by-user-id - get report statistics for a given user
  
  The 'printed' issue reflects a lack of appropriate technical architecture
  within NHS Wales. Ideally, the 'domain' of documents would mean nationally
  they'd be a single endpoint that would accept a document and publish it
  to document stores and ensure that document was made available to the
  patient and to others involved in their care, such as the general
  practitioner. Part of pc4 development is to create that end-point and
  so these functions will likely in future be called as part of an automated
  workflow rather than manually by end-users. It is therefore likely a log
  will need to be linked to a report to be able to track progress/status."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.plan :as plan]
   [next.jdbc.sql]
   [pc4.report.interface :as report]
   [pc4.rsdb.db :as db]
   [pc4.rsdb.patients :as patients]
   [pc4.rsdb.forms :as forms]
   [pc4.rsdb.users :as users]))

(s/def ::conn some?)

(defn ^:private -fetch-report-template-sql
  [report-template-id]
  (sql/format
   {:select [:rt.id :rt.name :rt.base_filename :rt.component_name
             :rt.template_behaviour_name :rt.css :a.mimetype :d.data]
    :from [[:t_report_template :rt]]
    :where [:= :rt.id report-template-id]
    :left-join [[:erattachment :a] [:= :a.id :rt.pdf_attachment_fk]
                [:erattachmentdata :d] [:= :d.id :a.attachmentdataid]]}))

(defn ^:private -fetch-report-template [conn report-template-id]
  (jdbc/execute-one!
   conn
   (-fetch-report-template-sql report-template-id)))

(defn ^:private -make-encounter-report-fn
  [conn report-template-id]
  (let [{:t_report_template/keys [component_name template_behaviour_name css]
         :erattachment/keys [mimetype] :erattachmentdata/keys [data] :as rt}
        (-fetch-report-template conn report-template-id)]
    (case template_behaviour_name
      "WOC"
      (case component_name
        ;; standard visit report -> this addresses almost all legacy report requirements
        "VisitReport"
        (fn [encounter-data]  ;; add in any custom supplied CSS in the template...
          (let [pdf (report/encounter-report (assoc-in encounter-data [:page :css] css))]
            (if (and data (= "application/pdf" mimetype))  ;; and then stamp pages with headers/footers from the report template PDF
              (report/stamp-pdf-template pdf data)
              pdf)))
        (throw (ex-info "Unsupported report template (component name)" rt)))
      (throw (ex-info "Unsupported report template (behaviour)" rt)))))

(defn extended-encounter-by-id [conn encounter-id]
  (db/execute-one! conn (sql/format {:select :*
                                     :from :t_encounter
                                     :where [:= :t_encounter/id encounter-id]
                                     :inner-join [:t_patient [:= :t_patient/id :t_encounter/patient_fk]]
                                     :left-join [:t_encounter_template [:= :t_encounter_template/id :encounter_template_fk]
                                                 :t_encounter_type [:= :t_encounter_type/id :encounter_type_fk]]})))
(defn ^:private -make-encounter-data
  "Given an encounter id, generate data that can be used to generate a report."
  [conn ods-svc encounter-id]
  (let [encounter (extended-encounter-by-id conn encounter-id)
        addresses (patients/fetch-patient-addresses conn encounter)
        users (patients/encounter->users# conn encounter-id)
        current-address (patients/address-for-date addresses)
        forms (forms/forms-and-form-types-in-encounter conn encounter-id)]
    {:title (:t_encounter_template/title encounter)
     :patient {:name (str/join " " [(:t_patient/title encounter)
                                    (:t_patient/first_names encounter)
                                    (:t_patient/last_name encounter)])
               :patient-identifier (:t_patient/patient_identifier encounter)
               :date-birth (:t_patient/date_birth encounter)
               :nhs-number (:t_patient/nhs_number encounter)
               :crn (when-let [org-code (:t_encounter/hospital_fk encounter)]
                      (patients/patient-pk->crn-for-org conn (:t_patient/id encounter) ods-svc org-code))
               :address (remove str/blank? [(:t_address/address1 current-address)
                                            (:t_address/address2 current-address)
                                            (:t_address/address3 current-address)
                                            (:t_address/address4 current-address)
                                            (:t_address/postcode_raw current-address)])}
     :encounter {:encounter-template {:title (:t_encounter_template/title encounter)}
                 :date-time (:t_encounter/date_time encounter)
                 :users (mapv #(hash-map :name (str/join " " (remove str/blank? [(:t_user/title %)
                                                                                 (:t_user/first_names %)
                                                                                 (:t_user/last_name %)]))
                                         :job-title (users/job-title %)) users)}}))

(comment
  (require '[integrant.repl.state])
  integrant.repl.state/system
  (def conn (:pc4.rsdb.interface/conn integrant.repl.state/system))
  (def ods-svc (:pc4.ods.interface/svc integrant.repl.state/system))
  (patients/patient-pk->crn-for-org conn 14031 ods-svc "RWM")
  (patients/encounter->users# conn 154621)
  (s/valid? ::report/encounter-report
            (assoc (-make-encounter-data conn ods-svc 14032 #_154621)
                   :report {:id 1
                            :date-time (java.time.LocalDateTime/now)
                            :signed {:user {:name "Dr Mark Wardle"
                                            :signature-url (report/make-data-url "https://www.jsign.com/wp-content/uploads/2022/06/graphic-signature-style.png" "image/png")}}
                            :to {:name "Dr Smith" :address ["GP SURGERY"]}})))

(defn ^:private -create-encounter-report!
  "Performs a SQL insert operation for a new encounter report."
  [conn {:keys [encounter-id pdf-data filename created-by-user-id sign-by-user-id print-by-user-id]}])

(defn ^:private -sql-still-in-draft
  "Clause for reports still in draft by the user"
  [user-id]
  [:and
   [:= "DRAFT" :status]
   [:= user-id :created_by_user_fk]])

(defn ^:private -sql-pending-my-signature
  "Clause for reports pending this user's signature"
  [user-id]
  [:and
   [:= "PENDING_SIGNATURE" :status]
   [:= user-id :signed_by_user_fk]])

(defn ^:private -sql-pending-other-signature
  "Clause for reports created by the user pending signature by others."
  [user-id]
  [:and
   [:= "PENDING_SIGNATURE" :status]
   [:= user-id :created_by_user_fk]])

(defn ^:private -sql-pending-my-printing
  "Clause for reports that user needs to 'print'"
  [user-id]
  [:and
   [:= "PENDING_PRINTING" :status]
   [:= user-id :printed_by_user_fk]])

(defn ^:private -sql-pending-other-printing
  "Clause for reports created or signed by user, but still pending printing by others"
  [user-id]
  [:and
   [:= "PENDING_PRINTING" :status]
   [:!= user-id :printed_by_user_fk]
   [:or
    [:= user-id :signed_by_user_fk]
    [:= user-id :created_by_user_fk]]])

(def ^:private report-cols
  [:created_by_user_fk
   :date_time_created
   :date_time_signed
   :encounter_fk
   :filename
   :id
   :printed_by_user_fk
   :report_data_fk
   :signed_by_user_fk
   :status
   :template_fk
   :title])

;;
;;
;;
;; Public API
;; 
;;
;;

(s/fdef report-by-id
  :args (s/cat :conn ::conn :report-id pos-int?))
(defn report-by-id
  [conn report-id]
  (jdbc/execute-one! conn (sql/format {:select report-cols
                                       :from :t_report
                                       :where [:= :id report-id]})))

(s/fdef report-data-for-report
  :args (s/cat :conn ::conn :report (s/keys :req [(or :t_report/id :t_report/report_data_fk)])))
(defn report-data-for-report
  [conn {:t_report/keys [id report_data_fk]}]
  (if report_data_fk ;; if we already have a report data id, use that, otherwise look up data id from report
    (next.jdbc.sql/get-by-id conn :t_report_data report_data_fk)
    (jdbc/execute-one! conn (sql/format {:select :*
                                         :from :t_report_data
                                         :where [:= :id {:select :report_data_fk :from :t_report :where [:= :id id]}]}))))

(defn report-and-data-by-id
  [conn report-id]
  (jdbc/execute-one! conn (sql/format {:select :*
                                       :from :t_report
                                       :left-join [:t_report_data [:= :t_report/report_data_fk :t_report_data/id]]
                                       :where [:= report-id :t_report/id]})))

(s/fdef reports-for-encounter
  :args (s/cat :conn ::conn :encounter-id pos-int?))
(defn reports-for-encounter
  "Return a sequence of reports for a given encounter."
  [conn encounter-id]
  (jdbc/execute! conn (sql/format {:select report-cols
                                   :from   :t_report
                                   :where  [:= encounter-id :encounter_fk]})))

(defn create-report!
  "Create a report of the specified type `report-template-id` for the encounter."
  [conn encounter-id {:keys [report-template-id created-by sign-by print-by]}]
  (let [f (-make-encounter-report-fn conn report-template-id)
        encounter-data (-make-encounter-data conn encounter-id)
        pdf-data (f encounter-data)]
    (if pdf-data
      (-create-encounter-report! conn {:encounter-id       encounter-id
                                       :pdf-data           pdf-data
                                       :created-by-user-id (:t_user/id created-by)
                                       :sign-by-user-id    (:t_user/id sign-by)
                                       :print-by-user-id   (:t_user/id print-by)})
      (throw (ex-info "Could not generate report for encounter" {:encounter-id encounter-id :report-template-id report-template-id})))))

(defn sign-report!
  [conn report-id user-id]
  (jdbc/execute-one! conn (sql/format {:update :t_report
                                       :where  [:= report-id :id]
                                       :set    {:status            "PENDING_PRINTING"
                                                :signed_by_user_fk user-id
                                                :date_time_signed  (java.time.LocalDateTime/now)}})))

(defn delete-report!
  [conn report-id user-id]
  (jdbc/execute-one! conn (sql/format {:update :t_report
                                       :where  [:= report-id :id]
                                       :set    {:status "DELETED"}})))

(defn mark-report-printed!
  [conn report-id user-id]
  (jdbc/execute-one! conn (sql/format {:update :t_report
                                       :where  [:= report-id :id]
                                       :set    {:status             "FINAL"
                                                :printed_by_user_fk user-id}})))

(s/fdef statistics-by-user-id
  :args (s/cat :conn ::conn :user-id pos-int?))
(defn statistics-for-user-id
  "Return report statistics for a user. Statistics are returned as a map
  {:draft             xx
   :pending-signature {:by-me xx :by-others xx}
   :pending-printing  {:by-me xx :by-others xx}}."
  [conn user-id]
  (let [f (fn [clause]
            (plan/select-one! conn :count
                              (sql/format {:select :%count.id :from :t_report :where clause})))]
    {:draft (f (-sql-still-in-draft user-id))
     :pending-signature
     {:by-me (f (-sql-pending-my-signature user-id))
      :by-others (f (-sql-pending-other-signature user-id))}
     :pending-printing
     {:by-me (f (-sql-pending-my-printing user-id))
      :by-others (f (-sql-pending-other-printing user-id))}}))

;;
;;
;;
;;

(comment
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument)
  (def conn (jdbc/get-connection "jdbc:postgresql:rsdb"))
  (extended-encounter-by-id  conn 154621)
  (-make-encounter-data  conn 154621)
  (report-by-id conn 1)
  (report-and-data-by-id conn 1)
  (report-data-for-report conn {:t_report/report_data_fk 1})
  (reports-for-encounter conn 154621)
  (time (user-report-statistics conn 2))
  (let [[stmt params] (-fetch-report-template-sql 1)]
    (println stmt))
  (def et (-fetch-report-template conn 1))
  (report/encounter-report {}))




