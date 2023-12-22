(ns pc4.patient.home
  (:require [pc4.events :as events]
            [pc4.patient.banner :as banner]
            [pc4.snomed.views]
            [pc4.ui :as ui]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reitit.frontend.easy :as rfe]))

(defn patient-search-by-id []
  (let [s (r/atom "")]
    (fn []
      [:input.shadow-sm.focus:ring-indigo-500.border.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md.pl-5.py-2
       {:type      "text" :placeholder "Patient identifier"
        :autoFocus true
        :value     @s
        :on-key-up #(when (= (-> % .-keyCode) 13)
                      (rf/dispatch [::events/push-state :patient/home {:project-id 1 :patient-identifier @s}]))
        :on-change #(reset! s (-> % .-target .-value))}])))

(defn menu
  "Patient menu. At the moment, we have a different menu for pseudonymous
  patients but this will become increasingly unnecessary."
  [{project-id :t_project/id}
   {:t_patient/keys [patient_identifier first_names title last_name status]
    pseudonym       :t_episode/stored_pseudonym}
   {:keys [selected-id sub-menu]}]
  (let [content (fn [s] (vector :span.truncate s))
        pseudonymous (= status :PSEUDONYMOUS)]
    [ui/vertical-navigation
     {:selected-id selected-id
      :items
      (if pseudonymous
        [{:id      :home
          :content (content "Home")
          :attrs   {:href (rfe/href :pseudonymous-patient/home {:project-id project-id :pseudonym pseudonym})}}
         {:id      :diagnoses
          :content (content "Diagnoses")
          :attrs   {:href (rfe/href :pseudonymous-patient/diagnoses {:project-id project-id :pseudonym pseudonym})}}
         {:id      :treatment
          :content (content "Medication")
          :attrs   {:href (rfe/href :pseudonymous-patient/medication {:project-id project-id :pseudonym pseudonym})}}
         {:id      :relapses
          :content (content "Relapses")
          :attrs   {:href (rfe/href :pseudonymous-patient/relapses {:project-id project-id :pseudonym pseudonym})}}
         {:id      :encounters
          :content (content "Encounters")
          :attrs   {:href (rfe/href :pseudonymous-patient/encounters {:project-id project-id :pseudonym pseudonym})}}
         {:id      :investigations
          :content (content "Investigations")}
         ; :attrs   {:href (rfe/href :pseudonymous-patient/investigations patient-link-attrs)}}
         {:id      :admissions
          :content (content "Admissions")
          :attrs   {:href (rfe/href :pseudonymous-patient/admissions {:project-id project-id :pseudonym pseudonym})}}]

        [{:id      :home
          :content (content "Home")
          :attrs   {:href (rfe/href :patient/home {:patient-identifier patient_identifier})}}
         {:id      :diagnoses
          :content (content "Diagnoses")
          :attrs   {:href (rfe/href :patient/diagnoses {:patient-identifier patient_identifier})}}
         {:id      :treatment
          :content (content "Medication")
          :attrs   {:href (rfe/href :patient/medication {:patient-identifier patient_identifier})}}
         {:id      :relapses
          :content (content "Relapses")
          :attrs   {:href (rfe/href :patient/relapses {:patient-identifier patient_identifier})}}
         {:id      :encounters
          :content (content "Encounters")
          :attrs   {:href (rfe/href :patient/encounters {:patient-identifier patient_identifier})}}
         {:id      :investigations
          :content (content "Investigations")}
         ; :attrs   {:href (rfe/href :pseudonymous-patient/investigations patient-link-attrs)}}
         {:id      :admissions
          :content (content "Admissions")
          :attrs   {:href (rfe/href :patient/admissions {:patient-identifier patient_identifier})}}])

      :sub-menu    sub-menu}]))

(defn layout
  [project patient menu-options & content]
  (when patient
    [:<>
     [banner/rsdb-banner patient]
     [:div.grid.grid-cols-1.md:grid-cols-6.gap-x-4.relative.pr-2
      [:div.col-span-1.p-2
       [menu project patient menu-options]]
      (into [:div.col-span-1.md:col-span-5.pt-2] content)]]))

(defn patient-ident
  "Returns the 'ident' of the patient given route parameters. This works both
  for identifiable and pseudonymous patients. "
  [params]
  (let [patient-identifier (get-in params [:path :patient-identifier])
        project-id (get-in params [:path :project-id])
        pseudonym (get-in params [:path :pseudonym])]
    (if patient-identifier
      [:t_patient/patient_identifier patient-identifier]
      [:t_patient/project_pseudonym [project-id pseudonym]])))


