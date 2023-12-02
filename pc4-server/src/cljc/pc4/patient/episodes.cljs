(ns pc4.patient.episodes
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [pc4.dates :as dates]
            [pc4.events :as events]
            [pc4.patient.banner :as banner]
            [pc4.patient.home :as patient]
            [pc4.ui :as ui]
            [re-frame.core :as rf]))
(def episode-properties
  [:t_episode/id
   :t_episode/project_fk
   :t_episode/patient_fk
   {:t_episode/project
    [:t_project/id
     :t_project/name
     :t_project/title
     :t_project/active?
     :t_project/is_admission]}
   :t_episode/date_registration
   :t_episode/date_discharge
   :t_episode/stored_pseudonym
   :t_episode/status])

(defn save-episode [patient-identifier episode {:keys [on-success]}]
  (rf/dispatch
    [::events/remote                                        ;; take care to pull in refreshed list of medications for patient
     {:id         ::save-admission
      :query      [{(list 'pc4.rsdb/save-admission (select-keys episode [:t_episode/id
                                                                         :t_episode/patient_fk
                                                                         :t_episode/date_registration
                                                                         :t_episode/date_discharge]))
                    (conj episode-properties
                          {:t_episode/patient [:t_patient/id
                                               {:t_patient/episodes [:t_episode/id]}]})}]

      :failed?    (fn [response] (get-in response ['pc4.rsdb/save-episode :com.wsscode.pathom3.connect.runner/mutation-error]))
      :on-success on-success}]))

(defn delete-episode [episode {:keys [on-success]}]
  (rf/dispatch
    [::events/remote                                        ;; take care to pull in refreshed list of medications for patient
     {:id         ::delete-admission
      :query      [(list 'pc4.rsdb/delete-admission episode)]
      :failed?    (fn [response] (get-in response ['pc4.rsdb/delete-admission :com.wsscode.pathom3.connect.runner/mutation-error]))
      :on-success on-success}]))

(defn edit-admission-episode
  [{:t_episode/keys [id date_registration date_discharge] :as episode} {:keys [on-change]}]
  [ui/ui-simple-form
   [ui/ui-simple-form-title {:title (if id "Edit admission" "Add admission")}]
   [ui/ui-simple-form-item {:label "Date of admission"}
    [ui/ui-local-date {:value     date_registration
                       :on-change #(on-change (assoc episode :t_episode/date_registration %))}]]
   [ui/ui-simple-form-item {:label "Date of discharge"}
    [ui/ui-local-date {:value     date_discharge
                       :on-change #(on-change (assoc episode :t_episode/date_discharge %))}]]])


(s/def :t_episode/patient_fk int?)
(s/def :t_episode/date_registration some?)
(s/def :t_episode/date_discharge any?)
(s/def ::edit-episode (s/keys :req [:t_episode/patient_fk
                                    :t_episode/date_registration
                                    :t_episode/date_discharge]))

(defn valid-episode?
  [{:t_episode/keys [date_registration date_discharge], :as episode}]
  (let [now (.valueOf (goog.date.Date.))]
    (and (s/valid? ::edit-episode episode)
         (>= now (.valueOf date_registration))
         (or (nil? date_discharge)
             (and (>= (.valueOf date_discharge) (.valueOf date_registration))
                  (>= now (.valueOf date_discharge)))))))
(def admission-page
  {:query
   (fn [params]
     [{(patient/patient-ident params)
       (conj banner/banner-query
             {:t_patient/episodes episode-properties})}])

   :view
   (fn [_ [{project-id :t_episode/project_fk patient-pk :t_patient/id :t_patient/keys [patient_identifier episodes] :as patient}]]
     (let [admissions (filter #(get-in % [:t_episode/project :t_project/is_admission]) episodes)
           editing-episode @(rf/subscribe [:pc4.subs/modal :episodes])
           modal (fn [episode] (rf/dispatch [::events/modal :episodes episode]))]
       (when patient
         [patient/layout {:t_project/id project-id} patient
          {:selected-id :admissions
           :sub-menu    {:items [{:id      :add-admission
                                  :content [ui/menu-button
                                            {:on-click #(modal {:t_episode/patient_fk patient-pk})}
                                            "Add admission"]}]}}
          (when editing-episode
            [ui/ui-modal {:on-close #(modal nil)
                          :actions  [{:id        ::save-action
                                      :title     "Save" :role :primary
                                      :disabled? (not (valid-episode? editing-episode))
                                      :on-click  #(save-episode patient_identifier editing-episode {:on-success [::events/modal :episodes nil]})}
                                     (when (:t_episode/id editing-episode)
                                       {:id       ::delete-action
                                        :title    "Delete"
                                        :on-click #(delete-episode editing-episode
                                                                   {:on-success {:fx [[:dispatch [::events/local-delete [:t_episode/id (:t_episode/id editing-episode)]]]
                                                                                      [:dispatch [::events/modal :episodes nil]]]}})})
                                     {:id       ::cancel-action
                                      :title    "Cancel"
                                      :on-click #(modal nil)}]}
             (edit-admission-episode editing-episode
                                     {:on-change #(rf/dispatch-sync [::events/modal :episodes %])})])
          (if (seq admissions)
            [ui/ui-table
             [ui/ui-table-head
              [ui/ui-table-row
               (for [{:keys [id title]} [{:id :from :title "Date from"} {:id :to :title "Date to"} {:id :diagnoses :title "Problems / diagnoses"} {:id :actions :title ""}]]
                 ^{:key id} [ui/ui-table-heading {} title])]]
             [ui/ui-table-body
              (for [{:t_episode/keys [id date_registration date_discharge] :as episode}
                    (->> admissions
                         (sort-by #(.valueOf (:t_episode/date_registration %)))
                         reverse)]
                [ui/ui-table-row {:key id}
                 [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_registration)]
                 [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_discharge)]
                 [ui/ui-table-cell {} ""]
                 [ui/ui-table-cell {} (ui/ui-table-link {:on-click #(rf/dispatch [::events/modal :episodes episode])} "Edit")]])]]
            [ui/ui-panel
             [ui/ui-title {:title "Admissions"
                           :subtitle "There are no recorded admissions for this patient."}]])])))})