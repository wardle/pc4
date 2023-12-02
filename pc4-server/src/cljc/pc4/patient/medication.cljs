(ns pc4.patient.medication
  (:require [clojure.string :as str]
            [pc4.dates :as dates]
            [pc4.events :as events]
            [pc4.patient.home :as patient]
            [pc4.patient.banner :as banner]
            [pc4.server :as server]
            [pc4.snomed.views :as snomed]
            [pc4.ui :as ui]
            [re-frame.core :as rf]))


(defn ^:private remove-medication-event-by-idx
  [medication event-idx]
  (update medication :t_medication/events
          (fn [evts]
            (->> evts
                 (map-indexed vector)
                 (filterv (fn [[i _]]
                            (not= event-idx i)))
                 (mapv second)))))

(defn edit-medication
  "Edit medication form."
  [{:t_medication/keys [id date_from date_to reason_for_stopping more_information events] :as medication} {:keys [on-change]}]
  (tap> {:edit-medication medication})
  [ui/ui-simple-form
   [ui/ui-simple-form-title {:title (if (= :new id) "Add medication" "Edit medication")}]
   [ui/ui-simple-form-item {:label "Medication"}
    (if id                                                  ;; if we already have a saved diagnosis, don't allow user to change
      [:h3.text-lg.font-medium.leading-6.text-gray-900 (get-in medication [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
      [snomed/select-snomed
       :id ::choose-medication
       :common-choices []
       :value (:t_medication/medication medication)
       :constraint "(<10363601000001109 MINUS <<10363901000001102)"
       :select-fn #(on-change (assoc medication :t_medication/medication %))])]

   [ui/ui-simple-form-item {:label "Date from"}
    [ui/ui-local-date {:name      "date-from" :value date_from
                       :on-change #(on-change (assoc medication :t_medication/date_from %))}]]
   [ui/ui-simple-form-item {:label "Date to"}
    [ui/ui-local-date {:name      "date-to" :value date_to
                       :on-change #(on-change (cond-> (assoc medication :t_medication/date_to %)
                                                      (nil? %)
                                                      (assoc :t_medication/reason_for_stopping :NOT_APPLICABLE)))}]]
   [ui/ui-simple-form-item {:label "Reason for stopping"}
    [ui/ui-select
     {:name          "reason-for-stopping" :value reason_for_stopping
      :choices       #{:CHANGE_OF_DOSE :ADVERSE_EVENT :NOT_APPLICABLE :PREGNANCY :LACK_OF_EFFICACY :PLANNING_PREGNANCY :RECORDED_IN_ERROR
                       :ALLERGIC_REACTION :ANTI_JCV_POSITIVE__PML_RISK :LACK_OF_TOLERANCE
                       :NON_ADHERENCE :OTHER
                       :PATIENT_CHOICE_CONVENIENCE :PERSISTENCE_OF_RELAPSES
                       :PERSISTING_MRI_ACTIVITY :DISEASE_PROGRESSION :SCHEDULED_STOP}
      :display-key   name
      :default-value :NOT_APPLICABLE
      :disabled?     (nil? date_to)
      :on-select     #(on-change (assoc medication :t_medication/reason_for_stopping %))}]]
   [ui/ui-simple-form-item {:label "More information"}
    [ui/ui-textarea
     {:name      "more_information", :value more_information
      :on-change #(on-change (assoc medication :t_medication/more_information %))}]]
   (when (seq events)
     (for [[idx {:t_medication_event/keys [id type event_concept] :as event}] (map-indexed vector events)]
       [ui/ui-simple-form-item {:key id :label [:span (name type) [ui/ui-button {:on-click #(on-change (remove-medication-event-by-idx medication idx))} "Delete"]]}
        [snomed/select-snomed
         :id (keyword (str "choose-med-event-id" id))
         :common-choices []
         :value event_concept
         :constraint "<404684003"
         :select-fn #(on-change (assoc-in medication [:t_medication/events idx :t_medication_event/event_concept] %))]]))])


(def medication-query
  [:t_medication/id :t_medication/patient_fk
   :t_medication/date_from :t_medication/date_to
   {:t_medication/medication [:info.snomed.Concept/id {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
   :t_medication/reason_for_stopping
   :t_medication/more_information
   {:t_medication/events [:t_medication_event/id
                          :t_medication_event/type
                          :t_medication_event/reaction_date_time
                          :t_medication_event/event_concept_fk
                          {:t_medication_event/event_concept [:info.snomed.Concept/id
                                                              {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]}])

(defn save-medication [patient-identifier medication {:keys [on-success]}]
  (rf/dispatch
    [::events/remote                                          ;; take care to pull in refreshed list of medications for patient
     {:id         ::save-medication
      :query      [{(list 'pc4.rsdb/save-medication (assoc medication :t_patient/patient_identifier patient-identifier))
                    (conj medication-query
                          {:t_medication/patient [:t_patient/id {:t_patient/medications [:t_medication/id]}]})}]
      :failed?    (fn [response] (get-in response ['pc4.rsdb/save-medication :com.wsscode.pathom3.connect.runner/mutation-error]))
      :on-success on-success}]))

(defn delete-medication [medication {:keys [on-success]}]
  (rf/dispatch
    [::events/remote                                          ;; take care to pull in refreshed list of medications for patient
     {:id         ::delete-medication
      :query      [(list 'pc4.rsdb/delete-medication medication)]
      :failed?    (fn [response] (get-in response ['pc4.rsdb/delete-medication :com.wsscode.pathom3.connect.runner/mutation-error]))
      :on-success on-success}]))

(defn ^:private medication-by-date-from [med]
  (- 0 (if-let [date-from (:t_medication/date_from med)] (.valueOf date-from) 0)))

(def medication-page
  {:query
   (fn [{:keys [query] :as params}]
     [{(patient/patient-ident params)
       (conj banner/banner-query
             {(if (str/blank? (:filter query))
                :t_patient/medications
                (list :t_patient/medications {:ecl (str "(* {{ D term = \"" (:filter query) "\"}})
                                                         OR
                                                         (<10363601000001109 AND (>> (<< (* {{ D term = \"" (:filter query) "\"}}))))")}))
              medication-query})}])

   :view
   (fn [_ [{project-id :t_episode/project_fk patient-pk :t_patient/id :t_patient/keys [patient_identifier medications] :as patient}]]
     (let [editing-medication @(rf/subscribe [:pc4.subs/modal :medication])
           modal (fn [medication] (rf/dispatch [::events/modal :medication medication]))]
       (println "editing medication " editing-medication)
       [patient/layout {:t_project/id project-id} patient
        {:selected-id :treatment
         :sub-menu
         {:items [{:id      :filter
                   :content [:input.border.p-2.w-full
                             {:type     "search" :name "search" :placeholder "Search..." :autocomplete "off"
                              :onChange #(let [s (-> % .-target .-value)]
                                           (server/dispatch-debounced [::events/push-query-params (if (str/blank? s) {} {:filter (-> % .-target .-value)})]))}]}
                  {:id      :add-medication
                   :content [ui/menu-button
                             {:on-click #(modal {:t_patient/patient_identifier patient_identifier
                                                 :t_medication/patient_fk      patient-pk})} "Add medication"]}]}}
        (when editing-medication
          [ui/ui-modal {:on-close #(modal nil)
                        :actions  [{:id       ::save-action
                                    :title    "Save" :role :primary
                                    :on-click #(save-medication patient_identifier editing-medication {:on-success [::events/modal :medication nil]})}
                                   {:id       ::delete-action
                                    :title    "Delete"
                                    :on-click #(delete-medication editing-medication
                                                                  {:on-success {:fx [[:dispatch [::events/local-delete [:t_medication/id (:t_medication/id editing-medication)]]]
                                                                                     [:dispatch [::events/modal :medication nil]]]}})}
                                   {:id       ::add-event-action
                                    :title    "Add event"
                                    :on-click #(modal (update editing-medication :t_medication/events (fnil conj []) {:t_medication_event/type :ADVERSE_EVENT}))}
                                   {:id       ::cancel-action
                                    :title    "Cancel"
                                    :on-click #(modal nil)}]}
           (edit-medication editing-medication
                            {:on-change #(do (println "Updating medication" %)
                                             (rf/dispatch-sync [::events/modal :medication %]))})])
        (when medications
          [ui/ui-table
           [ui/ui-table-head
            [ui/ui-table-row
             (for [{:keys [id title]} [{:id :medication :title "Medication"} {:id :from :title "From"} {:id :to :title "To"} {:id :stop :title "Why stopped"} {:id :actions :title ""}]]
               ^{:key id} [ui/ui-table-heading {} title])]]
           [ui/ui-table-body
            (for [{:t_medication/keys [id date_from date_to reason_for_stopping] :as medication}
                  (->> medications
                       (remove #(= :RECORDED_IN_ERROR (:t_medication/reason_for_stopping %)))
                       (sort-by (juxt medication-by-date-from #(get-in % [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term]))))]
              [ui/ui-table-row {:key id}
               [ui/ui-table-cell {} (get-in medication [:t_medication/medication :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
               [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_from)]
               [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_to)]
               [ui/ui-table-cell {} (if (= :NOT_APPLICABLE reason_for_stopping) "" (str/replace (name reason_for_stopping) #"_" " "))]
               [ui/ui-table-cell {} (ui/ui-table-link {:on-click #(rf/dispatch [::events/modal :medication medication])} "Edit")]])]])]))})
