(ns pc4.project.register
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.nhsnumber :as nhsnumber]
            [eldrix.pc4-ward.project.views]                 ;; TODO: remove any use of legacy components
            [pc4.project.home :as project]
            [pc4.project.events]
            [pc4.project.subs]
            [pc4.ui :as ui]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [reitit.frontend.easy :as rfe]))


(defn search-by-pseudonym-panel
  [project-id]
  (let [patient @(rf/subscribe [:pc4.project.subs/search-by-legacy-pseudonym-result])]
    [:div.space-y-4
     [ui/ui-panel
      [ui/ui-simple-form
       [ui/ui-simple-form-title
        {:title    "Search by pseudonymous identifier"
         :subtitle "Enter a project-specific pseudonym, or choose register to search by patient identifiable information."}]
       [ui/ui-textfield
        {:name        "pseudonym" :placeholder "Start typing pseudonym"
         :auto-focus  true
         :on-key-down #(when (and patient (= 13 %))
                         (rfe/push-state :pseudonymous-patient/home {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)}))
         :on-change   #(rf/dispatch [:pc4.project.events/search-legacy-pseudonym project-id % [:t_patient/sex :t_patient/date_birth
                                                                                               :t_episode/project_fk :t_episode/stored_pseudonym]])}]]]
     (when patient
       [ui/ui-panel
        [ui/ui-title
         {:title    (str (name (:t_patient/sex patient))
                         " "
                         "born: " (.getYear (:t_patient/date_birth patient)))
          :subtitle (:t_episode/stored_pseudonym patient)}]
        [ui/ui-button
         {:role     :primary
          :on-click #(rfe/push-state :pseudonymous-patient/home {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)})}
         "View patient record"]])]))

(def find-pseudonymous-patient
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])]
              [:t_project/id :t_project/title :t_project/pseudonymous :t_project/type]}])
   :view  (fn [_ [project]]
            (project/layout project {:selected-id :find-pseudonymous-patient}
                            [search-by-pseudonym-panel (:t_project/id project)]))})


(s/def ::project-id int?)
(s/def ::nhs-number com.eldrix.nhsnumber/valid*?)
(s/def ::date-birth some?)
(s/def ::sex #{:MALE :FEMALE :UNKNOWN})
(s/def ::register-pseudonymous-patient (s/keys :req-un [::project-id ::nhs-number ::date-birth ::sex]))

(defn register-pseudonymous-patient-panel
  [_]
  (let [data (r/atom {})
        visited (r/atom #{})]
    (fn [project-id]
      (let [data* (assoc @data :project-id project-id)
            error @(rf/subscribe [:pc4.project.subs/register-patient-error])
            valid? (s/valid? ::register-pseudonymous-patient data*)
            submit-fn #(when valid?
                         (swap! data (fn [m] (assoc m :nhs-number (nhsnumber/format-nnn (:nhs-number m)))))
                         (rf/dispatch [:pc4.project.events/register-pseudonymous-patient data*]))]
        [ui/ui-panel
         [ui/ui-simple-form
          [ui/ui-simple-form-title
           {:title    "Register a patient"
            :subtitle "Enter patient details. This is safe even if patient already registered."}]
          [ui/ui-simple-form-item {:label "NHS Number"}
           [ui/ui-textfield {:value      (:nhs-number data*)
                             :auto-focus true :required true
                             :on-blur    #(do
                                            (swap! visited conj :nhs-number)
                                            (rf/dispatch [:pc4.project.events/clear-register-patient-error]))
                             :on-change  #(swap! data assoc :nhs-number %)}]
           (when (and (contains? @visited :nhs-number) (not (s/valid? ::nhs-number (:nhs-number data*))))
             (ui/box-error-message {:message "Invalid NHS number"}))]
          [ui/ui-simple-form-item {:label "Date of birth"}
           [ui/ui-local-date {:value     (:date-birth data*)
                              :on-blur   #(rf/dispatch [:pc4.project.events/clear-register-patient-error])
                              :on-change #(swap! data assoc :date-birth %)}]]
          [ui/ui-simple-form-item {:label "Gender"}
           [ui/ui-select {:name                "gender"
                          :value               (:sex data*)
                          :choices             [:MALE :FEMALE :UNKNOWN]
                          :no-selection-string ""
                          :on-key-down         #(when (and (= 13 %) valid?) (submit-fn))
                          :on-select           #(swap! data assoc :sex %)}]]
          (when error
            [ui/box-error-message {:message error}])
          [ui/ui-button {:role      :primary
                         :disabled? (not valid?)
                         :on-click  #(when valid? (submit-fn))}
           "Search or register patient »"]]]))))

(def register-pseudonymous-patient
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])]
              [:t_project/id :t_project/title :t_project/pseudonymous :t_project/type]}])
   :view  (fn [_ [project]]
            (project/layout project {:selected-id :register-pseudonymous-patient}
                            [register-pseudonymous-patient-panel (:t_project/id project)]))})



(s/def ::register-patient (s/keys :req-un [::project-id ::nhs-number]))

(defn register-patient-panel
  [_]
  (let [data (r/atom {})
        visited (r/atom #{})]
    (fn [project-id]
      (let [data* (assoc @data :project-id project-id)
            error @(rf/subscribe [:pc4.project.subs/register-patient-error])
            valid? (s/valid? ::register-patient data*)
            submit-fn #(when valid?
                         (swap! data (fn [m] (assoc m :nhs-number (nhsnumber/format-nnn (:nhs-number m)))))
                         (rf/dispatch [:pc4.project.events/register-patient-by-nhs-number data*]))]
        [ui/ui-panel
         [ui/ui-simple-form
          [ui/ui-simple-form-title
           {:title    "Register a patient"
            :subtitle "Enter patient details. This is safe even if patient already registered."}]
          [ui/ui-simple-form-item {:label "NHS Number"}
           [ui/ui-textfield {:value      (:nhs-number data*)
                             :auto-focus true :required true
                             :on-blur    #(do
                                            (swap! visited conj :nhs-number)
                                            (rf/dispatch [:pc4.project.events/clear-register-patient-error]))
                             :on-change  #(swap! data assoc :nhs-number %)}]
           (when (and (contains? @visited :nhs-number) (not (s/valid? ::nhs-number (:nhs-number data*))))
             (ui/box-error-message {:message "Invalid NHS number"}))]
          (when error
            [ui/box-error-message {:message error}])
          [ui/ui-button {:role      :primary
                         :disabled? (not valid?)
                         :on-click  #(when valid? (submit-fn))}
           "Search or register patient »"]]]))))

(def register-patient
  {:query (fn [params]
            [{[:t_project/id (get-in params [:path :project-id])]
              [:t_project/id :t_project/title :t_project/pseudonymous :t_project/type]}])
   :view  (fn [_ [project]]
            (project/layout project {:selected-id :register-patient}
                            [register-patient-panel (:t_project/id project)]))})