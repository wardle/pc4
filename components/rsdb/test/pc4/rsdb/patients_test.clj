(ns pc4.rsdb.patients-test
  (:require [clojure.pprint]
            [clojure.spec.test.alpha :as stest]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest use-fixtures is]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [pc4.rsdb.forms :as forms]
            [pc4.rsdb.helper :as helper]
            [pc4.rsdb.interface :as rsdb]
            [pc4.rsdb.nform.api :as nf]
            [pc4.rsdb.patients :as patients]
            [pc4.rsdb.projects :as projects])
  (:import (java.time LocalDate)))

(stest/instrument)
(def ^:dynamic *conn* nil)
(def ^:dynamic *patient* nil)

(def patient
  {:salt       "1" :user-id 1 :project-id 1
   :nhs-number "9999999999" :sex :MALE :date-birth (LocalDate/of 1980 1 1)})

(defn with-patient
  [f]
  (with-open [conn (jdbc/get-connection (helper/get-dev-datasource))]
    (jdbc/with-transaction [txn conn {:rollback-only true :isolation :serializable}]
      (let [patient (projects/register-legacy-pseudonymous-patient txn patient)]
        (binding [*conn* txn
                  *patient* patient]
          (f))))))

(use-fixtures :each with-patient)

(deftest ^:live test-patient
  (is (= (:t_patient/nhs_number *patient*) (:nhs-number patient))))

(deftest ^:live test-create-medication
  (let [med (patients/upsert-medication! *conn* {:t_medication/patient_fk            (:t_patient/id *patient*)
                                                 :t_medication/medication_concept_fk 774459007 ;; alemtuzumab
                                                 :t_medication/as_required           true
                                                 :t_medication/date_from             (LocalDate/of 2020 1 1)
                                                 :t_medication/date_to               nil
                                                 :t_medication/reason_for_stopping   :NOT_APPLICABLE})]
    (is (= 774459007 (:t_medication/medication_concept_fk med)))
    (is (= (LocalDate/of 2020 1 1) (:t_medication/date_from med)))
    (let [med' (patients/upsert-medication! *conn* (assoc med :t_medication/date_to (LocalDate/of 2020 1 3)))
          meds (patients/fetch-medications *conn* *patient*)]
      (is (= 774459007 (:t_medication/medication_concept_fk med')))
      (is (= (LocalDate/of 2020 1 1) (:t_medication/date_from med')))
      (is (= (LocalDate/of 2020 1 3) (:t_medication/date_to med')))
      (is (= (dissoc med' :t_medication/events) (first meds))) ;; upsert-medication! will return {:t_medication/events []}
      (patients/delete-medication! *conn* med')
      (is (empty? (patients/fetch-medications *conn* *patient*))))))

(deftest ^:live test-medication-with-events
  (let [events [{:t_medication_event/type             :ADVERSE_EVENT
                 :t_medication_event/event_concept_fk 19307009}
                {:t_medication_event/type     :INFUSION_REACTION
                 :t_medication_event/severity :LIFE_THREATENING}]
        med (patients/upsert-medication!
             *conn* {:t_medication/patient_fk            (:t_patient/id *patient*)
                     :t_medication/medication_concept_fk 774459007 ;; alemtuzumab
                     :t_medication/as_required           true
                     :t_medication/date_from             (LocalDate/of 2020 1 1)
                     :t_medication/date_to               (LocalDate/of 2020 1 1)
                     :t_medication/reason_for_stopping   :ADVERSE_EVENT
                     :t_medication/events                events})
        meds (patients/fetch-medications-and-events *conn* *patient*)]
    (is (= 2 (count (:t_medication/events (first meds)))))
    (is (= (set (map #(merge {:t_medication_event/severity nil :t_medication_event/event_concept_fk nil} %) events))
           (set (map #(select-keys % [:t_medication_event/type :t_medication_event/event_concept_fk :t_medication_event/severity])
                     (:t_medication/events (first meds))))))
    (is (= med (first meds)))
    ;; update, this time with a single adverse event
    (let [med2 (patients/upsert-medication! *conn* (assoc med :t_medication/events (take 1 events)))]
      (is (= 1 (count (:t_medication/events med2))))
      (is (= 19307009 (get-in med2 [:t_medication/events 0 :t_medication_event/event_concept_fk]))))
    ;; update to the original... the ids might change, but the content must be the same
    (let [med3 (patients/upsert-medication! *conn* (assoc med :t_medication/events events))]
      (is (= (dissoc med :t_medication/events) (dissoc med3 :t_medication/events)))
      (is (= 2 (count (:t_medication/events med3))))
      (is (= (set (map #(dissoc % :t_medication_event/id) (:t_medication/events med)))
             (set (map #(dissoc % :t_medication_event/id) (:t_medication/events med3))))))
    (let [med4 (patients/upsert-medication! *conn* (assoc med :t_medication/events []))
          meds (patients/fetch-medications-and-events *conn* *patient*)]
      (is (= 0 (count (:t_medication/events med4))))
      (is (= med4 (first meds)))
      (patients/delete-medication! *conn* med4)
      (is (= 0 (count (patients/fetch-medications-and-events *conn* *patient*)))))))

(deftest ^:live test-encounter-forms
  (let [patient
        *patient*
        ;; create a suitable encounter template for our test
        {encounter-template-id :t_encounter_template/id :as encounter-template}
        (sql/insert! *conn* :t_encounter_template
                     {:encounter_type_fk 1, :title "Test encounter", :register_to_project_for_weeks -1})
        ;; create a single encounter to which we'll add some forms
        {encounter-id :t_encounter/id :as encounter}
        (patients/save-encounter! *conn*
                                  {:t_encounter/patient_fk            (:t_patient/id patient)
                                   :t_encounter/encounter_template_fk (:t_encounter_template/id encounter-template)
                                   :t_encounter/date_time             (java.time.LocalDateTime/now)
                                   :t_encounter/notes                 "Notes"})
        ;; get available and completed form types for this newly created encounter -> should be none!
        {:keys [available-form-types optional-form-types mandatory-form-types existing-form-types completed-forms deleted-forms]}
        (forms/forms-and-form-types-in-encounter *conn* encounter-id)

        _                                                   ;; there should be no forms available or completed at this point
        (is (= [0 0 0 0 0 0]
               [(count available-form-types) (count optional-form-types) (count mandatory-form-types)
                (count existing-form-types) (count completed-forms) (count deleted-forms)]))

        _                                                   ;; add short form EDSS to the encounter template 'available' form lists
        (sql/insert! *conn* :t_encounter_template__form_type {:encountertemplateid encounter-template-id
                                                              :formtypeid          2
                                                              :status              "AVAILABLE"})

        _                                                   ;; check that short form EDSS is available within encounter
        (is (= 2 (-> (forms/forms-and-form-types-in-encounter *conn* encounter-id)
                     :available-form-types first :form_type/id)))

        ;; add a short-form EDSS result
        saved-edss
        (forms/save-form! *conn* {:form_edss/id           nil
                                  :form_edss/encounter_fk encounter-id
                                  :form_edss/user_fk      1
                                  :form_edss/edss_score   "SCORE1_0"
                                  :form_edss/is_deleted   false})

        ;; get available and completed form types now we have created a form
        {:keys [available-form-types optional-form-types mandatory-form-types existing-form-types completed-forms deleted-forms]}
        (forms/forms-and-form-types-in-encounter *conn* (:t_encounter/id encounter))

        ;; should now have one existing form type as we have one completed form
        _
        (is (= [0 0 0 1 1 0]
               [(count available-form-types) (count optional-form-types) (count mandatory-form-types)
                (count existing-form-types) (count completed-forms) (count deleted-forms)]))

        ;; the only completed form should be an EDSS form
        returned-edss
        (first completed-forms)

        ;; it should be exactly the same as what was returned from save-form!
        _
        (is (= saved-edss returned-edss) "Form result returned from encounter should be same as save form result")

        _
        (is (= "SCORE1_0" (:form_edss/edss_score returned-edss)))

        ;; update the EDSS form - we should get the *same* result when updating as inserting
        nop-updated-edss
        (forms/save-form! *conn* returned-edss)

        _
        (is (= returned-edss nop-updated-edss) "Should be able to roundtrip with result of save-form! without change")

        updated-edss
        (forms/save-form! *conn* (assoc returned-edss :form_edss/edss_score "SCORE2_0"))

        ;; check EDSS result now updated
        _
        (is (= "SCORE2_0" (:form_edss/edss_score updated-edss)))

        {:keys [available-form-types optional-form-types mandatory-form-types existing-form-types completed-forms deleted-forms]}
        (forms/forms-and-form-types-in-encounter *conn* (:t_encounter/id encounter))

        _
        (is (= [0 0 0 1 1 0]
               [(count available-form-types) (count optional-form-types) (count mandatory-form-types)
                (count existing-form-types) (count completed-forms) (count deleted-forms)]))
        _
        (is (= updated-edss (first completed-forms)))

        ;; delete the form and check it is a) deleted, and b) forms-and-form-types then returns correctly.
        deleted-edss
        (forms/delete-form! *conn* updated-edss)

        _
        (is (= true (:form_edss/is_deleted deleted-edss)))

        {:keys [available-form-types optional-form-types mandatory-form-types existing-form-types completed-forms deleted-forms]}
        (forms/forms-and-form-types-in-encounter *conn* encounter-id)

        _                                                   ;; there should now be an EDSS form available but nothing completed at this point
        (is (= [1 0 0 0 0 1]
               [(count available-form-types) (count optional-form-types) (count mandatory-form-types)
                (count existing-form-types) (count completed-forms) (count deleted-forms)]))

        undeleted-edss
        (forms/undelete-form! *conn* deleted-edss)

        _
        (is (= updated-edss undeleted-edss))

        duplicate-edss                                      ; ;; add a duplicate form
        (forms/save-form! *conn* (-> updated-edss (assoc :form_edss/id nil :form/id nil :form_edss/edss_score "SCORE7_0")) {:replace-singular false})

        {:keys [completed-forms duplicated-form-types]}
        (forms/forms-and-form-types-in-encounter *conn* encounter-id)

        _
        (is (= 1 (count duplicated-form-types)))

        _
        (is (= "form_edss" (-> duplicated-form-types first :form_type/nm)))

        _
        (is (= (set [duplicate-edss undeleted-edss]) (set completed-forms)))]

    #_(clojure.pprint/pprint
       {:duplicates duplicated-form-types
        :completed  completed-forms})))

(deftest ^:live test-all-forms
  (let [patient
        *patient*
        ;; create a suitable encounter template for our test
        {encounter-template-id :t_encounter_template/id :as encounter-template}
        (sql/insert! *conn* :t_encounter_template
                     {:encounter_type_fk 1, :title "Test encounter", :register_to_project_for_weeks -1})
        ;; create a single encounter to which we'll add some forms
        {encounter-id :t_encounter/id}
        (patients/save-encounter! *conn*
                                  {:t_encounter/patient_fk            (:t_patient/id patient)
                                   :t_encounter/encounter_template_fk (:t_encounter_template/id encounter-template)
                                   :t_encounter/date_time             (java.time.LocalDateTime/now)
                                   :t_encounter/notes                 "Notes"})

        ;; generate some random forms
        forms
        (gen/sample (forms/gen-form {:form/id nil, :form/is_deleted false, :form/encounter_fk encounter-id, :form/user_fk 1}))

        ;; and add them to the database
        _
        (doseq [form forms]
          (forms/save-form! *conn* form))

        ;; and let's check all is correct
        {:keys [available-form-types optional-form-types mandatory-form-types existing-form-types completed-forms deleted-forms] :as afs}
        (forms/forms-and-form-types-in-encounter *conn* encounter-id)

        ;; some forms are only allowed one per encounter, so older ones should  have been marked as deleted automatically
        _
        (is (= (count forms) (+ (count completed-forms) (count deleted-forms))))]

    #_(clojure.pprint/pprint afs)))

(deftest ^:live test-search
  (let [patient *patient*
        conn *conn*
        patient-pk (:t_patient/id patient)
        opts {:status #{"PSEUDONYMOUS"}}]
    (patients/save-patient! conn (assoc patient :t_patient/first_names "Donald" :t_patient/last_name "Duck"))
    ;; Ensure patient is NOT registered to project 2 (discharge any active episodes)
    ;; This makes the project filtering test meaningful - patient is in project 1 but not project 2
    (doseq [{:t_episode/keys [date_discharge] :as episode} (projects/episodes-for-patient-in-project conn patient 2)]
      (when-not date_discharge
        (projects/discharge-episode! conn 1 episode)))
    (is (= patient-pk (:t_patient/id (first (patients/search conn (assoc opts :s "donald Duck"))))))
    (is (= patient-pk (:t_patient/id (first (patients/search conn (assoc opts :s (str (:t_patient/patient_identifier patient))))))))
    (is (= patient-pk (:t_patient/id (first (patients/search conn (assoc opts :s "9999999999"))))))
    (is (empty? (patients/search conn (assoc opts :s "duck" :project-ids [2]))))
    (is (= patient-pk (:t_patient/id (first (patients/search conn (assoc opts :s "duck" :project-ids [1]))))))
    (is (= 1 (:count (first (patients/search conn (assoc opts :s "duck" :project-ids [1] :query {:select :%count.* :from :t_patient}))))))
    (is (= 0 (:count (first (patients/search conn (assoc opts :s "duck" :project-ids [5] :query {:select :%count.* :from :t_patient}))))))
    (is (= patient-pk (:t_patient/id (first (patients/search conn (assoc opts :s "duck" :address? true))))))))

(deftest test-authority->provider-lookup
  (is (= {:provider-id :wales-empi
          :system      "https://fhir.nhs.uk/Id/nhs-number"
          :value       "1111111111"}
         (patients/authority->provider-lookup
          {:t_patient/authoritative_demographics :EMPI
           :t_patient/nhs_number                 "1111111111"
           :t_patient/patient_hospitals          []}))
      "EMPI authority should use NHS number")

  (is (= {:provider-id :wales-cav-pms
          :system      "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
          :value       "A999998"}
         (patients/authority->provider-lookup
          {:t_patient/authoritative_demographics :CAVUHB
           :t_patient/nhs_number                 "1111111111"
           :t_patient/patient_hospitals          [{:t_patient_hospital/authoritative      true
                                                    :t_patient_hospital/patient_identifier "A999998"
                                                    :t_patient_hospital/hospital_fk        "RWMBV"}]}))
      "CAVUHB authority should use authoritative CRN when available")

  (is (= {:provider-id :wales-cav-pms
          :system      "https://fhir.nhs.uk/Id/nhs-number"
          :value       "1111111111"}
         (patients/authority->provider-lookup
          {:t_patient/authoritative_demographics :CAVUHB
           :t_patient/nhs_number                 "1111111111"
           :t_patient/patient_hospitals          [{:t_patient_hospital/authoritative      false
                                                    :t_patient_hospital/patient_identifier "A999998"
                                                    :t_patient_hospital/hospital_fk        "RWMBV"}]}))
      "CAVUHB authority should fall back to NHS number if no authoritative CRN")

  (is (nil? (patients/authority->provider-lookup
             {:t_patient/authoritative_demographics :CAVUHB
              :t_patient/nhs_number                 nil
              :t_patient/patient_hospitals          [{:t_patient_hospital/authoritative      false
                                                       :t_patient_hospital/patient_identifier "A999998"
                                                       :t_patient_hospital/hospital_fk        "RWMBV"}]}))
      "CAVUHB authority should return nil if no authoritative CRN and no NHS number")

  (is (nil? (patients/authority->provider-lookup
             {:t_patient/authoritative_demographics :LOCAL
              :t_patient/nhs_number                 "1111111111"
              :t_patient/patient_hospitals          []}))
      "LOCAL authority should return nil")

  (is (nil? (patients/authority->provider-lookup
             {:t_patient/authoritative_demographics :EMPI
              :t_patient/nhs_number                 nil
              :t_patient/patient_hospitals          []}))
      "EMPI authority should return nil if no NHS number")

  (is (nil? (patients/authority->provider-lookup
             {:t_patient/authoritative_demographics :ABUHB
              :t_patient/nhs_number                 "1111111111"
              :t_patient/patient_hospitals          [{:t_patient_hospital/authoritative      true
                                                       :t_patient_hospital/patient_identifier "X123456"
                                                       :t_patient_hospital/hospital_fk        "RVFAR"}]}))
      "Unsupported authority (ABUHB) should return nil"))

(deftest ^:live test-forms-and-form-types-in-encounter
  (let [patient *patient*
        form-store (nf/make-form-store *conn*)
        svc {:conn *conn* :form-store form-store}

        ;; create an encounter template for our test
        {encounter-template-id :t_encounter_template/id}
        (sql/insert! *conn* :t_encounter_template
                     {:encounter_type_fk 1, :title "Test encounter", :register_to_project_for_weeks -1})

        ;; create a single encounter to which we'll add some forms
        {encounter-id :t_encounter/id}
        (patients/save-encounter! *conn*
                                  {:t_encounter/patient_fk            (:t_patient/id patient)
                                   :t_encounter/encounter_template_fk encounter-template-id
                                   :t_encounter/date_time             (java.time.LocalDateTime/now)
                                   :t_encounter/notes                 "Notes"})

        ;; get available and completed form types for newly created encounter - should be none!
        result1
        (rsdb/forms-and-form-types-in-encounter svc encounter-id)]

    ;; there should be no forms available or completed at this point
    (is (= 0 (count (:available-form-types result1))))
    (is (= 0 (count (:optional-form-types result1))))
    (is (= 0 (count (:mandatory-form-types result1))))
    (is (= 0 (count (:existing-form-types result1))))
    (is (= 0 (count (:completed-forms result1))))
    (is (= 0 (count (:deleted-forms result1))))

    ;; add short form EDSS (form-type-id=2) to encounter template as 'available'
    (sql/insert! *conn* :t_encounter_template__form_type
                 {:encountertemplateid encounter-template-id
                  :formtypeid          2
                  :status              "AVAILABLE"})

    ;; check that short form EDSS is available within encounter
    (let [result2 (rsdb/forms-and-form-types-in-encounter svc encounter-id)]
      (clojure.pprint/pprint result2)
      (is (= 1 (count (:available-form-types result2))))
      (is (= :edss/v1 (some-> result2 :available-form-types first :id))))

    ;; add an EDSS result using the new forms API
    (let [saved-edss (nf/upsert! form-store
                                 {:form_type    :edss/v1
                                  :encounter_fk encounter-id
                                  :patient_fk   (:t_patient/id patient)
                                  :user_fk      1
                                  :is_deleted   false
                                  :edss         "1.0"})

          ;; get form types now we have created a form
          result3 (rsdb/forms-and-form-types-in-encounter svc encounter-id)]

      ;; should now have one existing form type as we have one completed form
      (is (= 0 (count (:available-form-types result3))) "EDSS should no longer be available (already completed)")
      (is (= 1 (count (:completed-forms result3))))
      (is (= 0 (count (:deleted-forms result3))))

      ;; the completed form should have correct data
      (let [returned-form (first (:completed-forms result3))]
        (is (= :edss/v1 (:form_type returned-form)))
        (is (= "1.0" (:edss returned-form))))

      ;; update the form
      (let [updated-edss (nf/upsert! form-store (assoc saved-edss :edss "2.0"))
            result4 (rsdb/forms-and-form-types-in-encounter svc encounter-id)]
        (is (= "2.0" (:edss updated-edss)))
        (is (= 1 (count (:completed-forms result4))))
        (is (= "2.0" (-> result4 :completed-forms first :edss))))

      ;; delete the form
      (let [deleted-edss (nf/upsert! form-store (assoc saved-edss :is_deleted true))
            result5 (rsdb/forms-and-form-types-in-encounter svc encounter-id)]
        (is (= true (:is_deleted deleted-edss)))
        (is (= 1 (count (:available-form-types result5))) "EDSS should be available again")
        (is (= 0 (count (:completed-forms result5))))
        (is (= 1 (count (:deleted-forms result5))))
        (is (= :edss/v1 (-> result5 :deleted-forms first :form_type))))

      ;; undelete the form
      (let [undeleted-edss (nf/upsert! form-store (assoc saved-edss :is_deleted false))
            result6 (rsdb/forms-and-form-types-in-encounter svc encounter-id)]
        (is (= false (:is_deleted undeleted-edss)))
        (is (= 0 (count (:available-form-types result6))))
        (is (= 1 (count (:completed-forms result6))))
        (is (= 0 (count (:deleted-forms result6))))))))

(comment
  (def encounter-id 1))
