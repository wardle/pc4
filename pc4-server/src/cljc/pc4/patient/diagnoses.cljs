(ns pc4.patient.diagnoses
  (:require [clojure.string :as str]
            [pc4.snomed.views]
            [pc4.dates :as dates]
            [pc4.events :as events]
            [pc4.patient.home :as patient]
            [pc4.patient.banner :as banner]
            [pc4.server :as server]
            [pc4.subs :as subs]
            [pc4.ui :as ui]
            [re-frame.core :as rf]))

(defn edit-diagnosis
  [{:t_diagnosis/keys [id date_onset date_diagnosis date_to status] :as diagnosis} {:keys [on-change]}]
  [ui/ui-simple-form
   [ui/ui-simple-form-title {:title (if id "Edit diagnosis" "Add diagnosis")}]
   [ui/ui-simple-form-item {:label "Diagnosis"}
    [:div.pt-2
     (if id                                                 ;; if we already have a saved diagnosis, don't allow user to change
       [:h3.text-lg.font-medium.leading-6.text-gray-900 (get-in diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
       [pc4.snomed.views/select-snomed
        :id ::choose-diagnosis, :common-choices [] :value (:t_diagnosis/diagnosis diagnosis)
        :constraint "<404684003", :select-fn #(on-change (assoc diagnosis :t_diagnosis/diagnosis %))])]]
   [ui/ui-simple-form-item {:label "Date of onset"}
    [ui/ui-local-date {:name      "date-onset" :value date_onset
                       :on-change #(on-change (assoc diagnosis :t_diagnosis/date_onset %))}]]
   [ui/ui-simple-form-item {:label "Date of diagnosis"}
    [ui/ui-local-date {:name      "date-diagnosis" :value date_diagnosis
                       :on-change #(on-change (assoc diagnosis :t_diagnosis/date_diagnosis %))}]]
   [ui/ui-simple-form-item {:label "Date to"}
    [ui/ui-local-date {:name      "date-to" :value date_to
                       :on-change #(on-change (cond-> (assoc diagnosis :t_diagnosis/date_to %)
                                                      (nil? %)
                                                      (assoc :t_diagnosis/status "ACTIVE")
                                                      (some? %)
                                                      (assoc :t_diagnosis/status "INACTIVE_IN_ERROR")))}]]
   [ui/ui-simple-form-item {:label "Status"}
    [ui/ui-select
     {:name      "status", :value status, :default-value "ACTIVE"
      :choices   (if date_to ["INACTIVE_REVISED" "INACTIVE_RESOLVED" "INACTIVE_IN_ERROR"]
                             ["ACTIVE"])
      :on-select #(on-change (assoc diagnosis :t_diagnosis/status %))}]]
   (when (:t_diagnosis/id diagnosis)
     [:p.text-gray-500.pt-8 "To delete a diagnosis, record a 'to' date and update the status as appropriate."])])

(defn save-diagnosis [patient-identifier diagnosis]
  (rf/dispatch
    [::events/remote
     {:id         ::save-diagnosis
      :query      [{(list 'pc4.rsdb/save-diagnosis (assoc diagnosis :t_patient/patient_identifier patient-identifier))
                    [:t_diagnosis/id :t_diagnosis/date_onset
                     :t_diagnosis/date_diagnosis :t_diagnosis/date_to
                     :t_diagnosis/status {:t_diagnosis/diagnosis [:info.snomed.Concept/id
                                                                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                     {:t_diagnosis/patient [:t_patient/id :t_patient/diagnoses]}]}]
      :failed?    (fn [response] (get-in response ['pc4.rsdb/save-diagnosis :com.wsscode.pathom3.connect.runner/mutation-error]))
      :on-success (fn [_] [::events/modal :diagnoses nil])}]))

(defn diagnoses-table
  [title diagnoses]
  [:<>
   [ui/ui-title {:title title}]
   [ui/ui-table
    [ui/ui-table-head
     [ui/ui-table-row
      (for [{:keys [id title]} [{:id :diagnosis :title "Diagnosis"} {:id :date-onset :title "Date onset"} {:id :date-diagnosis :title "Date diagnosis"} {:id :date-to :title "Date to"} {:id :status :title "Status"} {:id :actions :title ""}]]
        ^{:key id} [ui/ui-table-heading {} title])]]
    [ui/ui-table-body
     (for [{:t_diagnosis/keys [id date_onset date_diagnosis date_to status] :as diagnosis}
           (sort-by #(get-in % [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term]) diagnoses)]
       [ui/ui-table-row
        {:key id}
        [ui/ui-table-cell {} (get-in diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
        [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_onset)]
        [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_diagnosis)]
        [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_to)]
        [ui/ui-table-cell {} (str status)]
        [ui/ui-table-cell {} (ui/ui-table-link {:on-click #(rf/dispatch [::events/modal :diagnoses diagnosis])} "Edit")]])]]])

(def diagnoses-page
  {:query
   (fn [{:keys [query] :as params}]
     [{(patient/patient-ident params)
       (conj banner/banner-query
             {(if (str/blank? (:filter query))
                :t_patient/diagnoses
                (list :t_patient/diagnoses {:ecl (str "<< (* {{ D term = \"" (:filter query) "\"}})")}))
              [:t_diagnosis/id :t_diagnosis/date_diagnosis :t_diagnosis/date_onset :t_diagnosis/date_to :t_diagnosis/status
               {:t_diagnosis/diagnosis
                [:info.snomed.Concept/id
                 {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}]})}])

   :view
   (fn [_ [{project-id :t_episode/project_fk :t_patient/keys [patient_identifier] :as patient}]]
     (let [editing-diagnosis @(rf/subscribe [::subs/modal :diagnoses])]
       [:<>
        (when editing-diagnosis
          [ui/ui-modal {:on-close #(rf/dispatch [::events/modal :diagnoses nil])
                        :actions  [{:id       :save, :title "Save", :role :primary
                                    :on-click #(save-diagnosis patient_identifier editing-diagnosis)}
                                   {:id       :cancel, :title "Cancel"
                                    :on-click #(rf/dispatch [::events/modal :diagnoses nil])}]}
           [edit-diagnosis editing-diagnosis {:on-change #(rf/dispatch [::events/modal :diagnoses %])}]])
        [patient/layout {:t_project/id project-id} patient
         {:selected-id :diagnoses
          :sub-menu    {:items [{:id      :filter
                                 :content [:input.border.p-2.w-full
                                           {:type     "search" :name "search" :placeholder "Search..." :autocomplete "off"
                                            :onChange #(let [s (-> % .-target .-value)]
                                                         (server/dispatch-debounced [::events/push-query-params (if (str/blank? s) {} {:filter (-> % .-target .-value)})]))}]}
                                {:id      :add-diagnosis
                                 :content [ui/menu-button {:on-click #(rf/dispatch [::events/modal :diagnoses {}])} "Add diagnosis"]}]}}
         (let [active-diagnoses (filter #(= "ACTIVE" (:t_diagnosis/status %)) (:t_patient/diagnoses patient))
               inactive-diagnoses (remove #(= "ACTIVE" (:t_diagnosis/status %)) (:t_patient/diagnoses patient))]
           [:<>
            (when (seq active-diagnoses)
              [diagnoses-table "Active diagnoses" active-diagnoses])
            (when (seq inactive-diagnoses)
              [diagnoses-table "Inactive diagnoses" inactive-diagnoses])])]]))})

