(ns pc4.patient.encounters
  (:require [pc4.dates :as dates]
            [pc4.patient.banner :as banner]
            [pc4.patient.home :as patient]
            [pc4.ui :as ui]
            [reitit.frontend.easy :as rfe]))

(def neuro-inflammatory
  [{:t_encounter/form_edss
    [:t_form_edss/score :t_form_edss_fs/score]}
   {:t_encounter/form_ms_relapse
    [:t_form_ms_relapse/in_relapse :t_ms_disease_course/name]}
   {:t_encounter/form_weight_height
    [:t_form_weight_height/weight_kilogram]}])

(def encounters-page
  {:tx (fn [{:keys [query] :as params}]
         (let [optional-headings neuro-inflammatory]
           [{(patient/patient-ident params)
             (conj banner/banner-query
                   {:t_patient/encounters
                    (into [:t_encounter/id :t_encounter/date_time
                           :t_encounter/active :t_encounter/is_deleted
                           {:t_encounter/encounter_template
                            [:t_encounter_template/id
                             :t_encounter_template/title]}
                           {:t_encounter/form_edss
                            [:t_form_edss/score :t_form_edss_fs/score]}
                           {:t_encounter/form_ms_relapse
                            [:t_form_ms_relapse/in_relapse
                             :t_ms_disease_course/name]}
                           :t_encounter/form_weight_height]
                          optional-headings)})}]))
   :view
   (fn [_ [{project-id :t_episode/project_fk, patient-pk :t_patient/id :t_patient/keys [patient_identifier encounters] :as patient}]]
     [patient/layout {:t_project/id project-id} patient
      {:selected-id :encounters}
      (when encounters
        [ui/ui-table
         [ui/ui-table-head
          [ui/ui-table-row
           (for [{:keys [id title]} [{:id :date :title "Date"} {:id :type :title "Type"}
                                     {:id :edss :title "EDSS"} {:id :disease-course :title "Disease course"}
                                     {:id :in-relapse :title "In relapse?"} {:id :weight :title "Weight"}
                                     {:id :actions :title ""}]]
             ^{:key id} [ui/ui-table-heading {} title])]]
         [ui/ui-table-body
          (for [{:t_encounter/keys [id date_time encounter_template form_edss form_ms_relapse form_weight_height] :as encounter}
                (->> encounters
                     (filter :t_encounter/active))]
            [ui/ui-table-row {:key id}
             [ui/ui-table-cell {:class ["whitespace-nowrap"]} (dates/format-date date_time)]
             [ui/ui-table-cell {} (:t_encounter_template/title encounter_template)]
             [ui/ui-table-cell {} (str (or (:t_form_edss_fs/score form_edss) (:t_form_edss/score form_edss)))]
             [ui/ui-table-cell {} (:t_ms_disease_course/name form_ms_relapse)]
             [ui/ui-table-cell {} (when (:t_form_ms_relapse/in_relapse form_ms_relapse) "Yes")]
             [ui/ui-table-cell {} (when-let [wt (:t_form_weight_height/weight_kilogram form_weight_height)]
                                    (str wt "kg"))]
             [ui/ui-table-cell {} (ui/ui-table-link
                                    {:href (rfe/href :patient/encounter {:patient-identifier patient_identifier :encounter-id id})}
                                    "Edit")]])]])])})

(defn menuitem [& content]
  (into [:span.truncate] content))

(defn menu
  [patient encounter {:keys [selected-id sub-menu]}]
  [ui/vertical-navigation
   {:selected-id selected-id
    :items [{:id      :home
             :content (menuitem "Home")}]
    :sub-menu    sub-menu}])

(defn layout [patient encounter & content]
  [:div.grid.grid-cols-1.md:grid-cols-6
   [:div.col-span-1.p-2
    [:div.pl-4.bg-gray-200.rounded-full.text-center.italic.text-gray-600
     (dates/format-date (:t_encounter/date_time encounter))]
    [:div.text-sm.p-2.pt-4.text-gray-600.italic {:style {:text-wrap "pretty"}}
     (get-in encounter [:t_encounter/encounter_template :t_encounter_template/project :t_project/title])]
    [:div.font-bold.text-lg.min-w-min.p-4.pt-0
     (get-in encounter [:t_encounter/encounter_template :t_encounter_template/title])]

    [:div.pt-4
     (menu patient encounter {:selected-id :home})]]
   (into [:div.col-span-5.p-6] content)])


(def encounter-page
  {:tx
   (fn [params]
     [{(patient/patient-ident params)
       banner/banner-query}
      {[:t_encounter/id (get-in params [:path :encounter-id])]
       [:t_encounter/id :t_encounter/patient_fk :t_encounter/date_time
        {:t_encounter/encounter_template [:t_encounter_template/id :t_encounter_template/title
                                          {:t_encounter_template/project [:t_project/id :t_project/title]}]}]}])
   :view
   (fn [_ [{patient-pk :t_patient/id :as patient} {:t_encounter/keys [id patient_fk date_time encounter_template] :as encounter}]]
     (if (not= patient-pk patient_fk) ;; check encounter is for given patient
       [ui/box-error-message "Invalid access" "Access not permitted"]
       [:<>
        [banner/rsdb-banner patient]
        [layout patient encounter {}]]))})