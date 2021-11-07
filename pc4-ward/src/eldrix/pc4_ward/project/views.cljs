(ns eldrix.pc4-ward.project.views
  (:require
    [clojure.spec.alpha :as s]
    [reitit.frontend.easy :as rfe]
    [re-frame.core :as rf]
    [eldrix.pc4-ward.patient.events :as patient-events]
    [eldrix.pc4-ward.patient.subs :as patient-subs]
    [eldrix.pc4-ward.project.subs :as project-subs]
    [eldrix.pc4-ward.user.subs :as user-subs]
    [eldrix.pc4-ward.user.events :as user-events]
    [eldrix.pc4-ward.snomed.views :as snomed]
    [eldrix.pc4-ward.ui :as ui]
    [clojure.string :as str]
    [com.eldrix.pc4.commons.dates :as dates]
    [malli.core :as m]
    [clojure.string :as str]
    [re-frame.db :as db]))

(defn valid-nhs-number?
  "Very crude validation of NHS number. We could implement in cljs, but the
  server will flag if we send it an invalid number, so this is just for the
  purposes of the UI enabling the 'register' button."
  [s]
  (= 10 (count (str/replace s #"\s" ""))))

(defn inspect-project [project]
  [:div.bg-white.shadow.overflow-hidden.sm:rounded-lg
   [:div.px-4.py-5.sm:px-6
    [:h3.text-lg.leading-6.font-medium.text-gray-900 (:t_project/title project)]
    [:p.mt-1.max-w-2xl.text-sm.text-gray-500 {:dangerouslySetInnerHTML {:__html (:t_project/long_description project)}}]]
   [:div.border-t.border-gray-200.px-4.py-5.sm:px-6
    [:dl.grid.grid-cols-1.gap-x-4.gap-y-8.sm:grid-cols-2
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Status"]
      [:dd.mt-1.text-sm.text-gray-900 (if (:t_project/active? project) "Active" "Inactive")]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Type"]
      [:dd.mt-1.text-sm.text-gray-900 (str/upper-case (name (:t_project/type project))) " " (when (:t_project/virtual project) "VIRTUAL")]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Date from"]
      [:dd.mt-1.text-sm.text-gray-900 (dates/format-date (:t_project/date_from project))]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Date to"]
      [:dd.mt-1.text-sm.text-gray-900 (dates/format-date (:t_project/date_to project))]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Registered patients"]
      [:dd.mt-1.text-sm.text-gray-900 (:t_project/count_registered_patients project)]]
     [:div.sm:col-span-1
      [:dt.text-sm.font-medium.text-gray-500 "Discharged episodes"]
      [:dd.mt-1.text-sm.text-gray-900 (:t_project/count_discharged_episodes project)]]

     (when (:t_project/inclusion_criteria project)
       [:div.sm:col-span-2
        [:dt.text-sm.font-medium.text-gray-500 "Inclusion criteria"]
        [:dd.mt-1.text-sm.text-gray-900 {:dangerouslySetInnerHTML {:__html (:t_project/inclusion_criteria project)}}]])
     (when (:t_project/exclusion_criteria project)
       [:div.sm:col-span-2
        [:dt.text-sm.font-medium.text-gray-500 "Exclusion criteria"]
        [:dd.mt-1.text-sm.text-gray-900 {:dangerouslySetInnerHTML {:__html (:t_project/exclusion_criteria project)}}]])]]])

(defn search-by-pseudonym-panel
  [project-id]
  (let [patient @(rf/subscribe [::patient-subs/search-by-legacy-pseudonym-result])]
    [:div.bg-white.overflow-hidden.shadow.sm:rounded-lg
     [:div.px-4.py-6.sm:p-6
      [:form.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
       [:div.divide-y.divide-gray-200.sm:space-y-5
        [:div
         [:div
          [:h3.text-lg.leading-6.font-medium.text-gray-900 "Search by pseudonymous identifier"]
          [:p.max-w-2xl.text-sm.text-gray-500 "Enter a project-specific pseudonym, or choose register to search by patient identifiable information."]]
         [:div.mt-4
          [:label.sr-only {:for "pseudonym"} "Pseudonym"]
          [:input.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md.pl-5.py-2
           {:type        "text" :name "pseudonym" :placeholder "Start typing pseudonym" :auto-focus true
            :on-key-down #(when (and patient (= 13 (.-which %)))
                            (rfe/push-state :patient-by-project-pseudonym {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)}))
            :on-change   #(let [s (-> % .-target .-value)]
                            (rf/dispatch [::patient-events/search-legacy-pseudonym project-id s]))}]]
         (when patient
           [:div.bg-white.shadow.sm:rounded-lg.mt-4
            [:div.px-4.py-5.sm:p-6
             [:h3.text-lg.leading-6.font-medium.text-gray-900
              (str (name (:t_patient/sex patient))
                   " "
                   "born: " (.getYear (:t_patient/date_birth patient)))]
             [:div.mt-2.sm:flex.sm:items-start.sm:justify-between
              [:div.max-w-xl.text-sm.text-gray-500
               [:p (:t_episode/stored_pseudonym patient)]]
              [:div.mt-5.sm:mt-0.sm:ml-6.sm:flex-shrink-0.sm:flex.sm:items-center
               [:button.inline-flex.items-center.px-4.py-2.border.border-transparent.shadow-sm.font-medium.rounded-md.text-white.bg-indigo-600.hover:bg-indigo-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500.sm:text-sm
                {:type     "button"
                 :on-click #(rfe/push-state :patient-by-project-pseudonym {:project-id project-id :pseudonym (:t_episode/stored_pseudonym patient)})}
                "View patient record"]]]]])]]]]]))


(def patient-registration-schema
  (m/schema [:map
             [:project-id int?]
             [:nhs-number [:fn valid-nhs-number?]]
             [:date-birth some?]
             [:sex [:enum :MALE :FEMALE :UNKNOWN]]]))

(defn register-pseudonymous-patient                         ;; TODO: create re-usable components from this example form
  [project-id]
  (let [data (reagent.core/atom {:project-id project-id})
        visited (reagent.core/atom #{})]
    (fn []
      (let [error @(rf/subscribe [::patient-subs/open-patient-error])
            valid? (m/validate patient-registration-schema @data)
            submit-fn #(when valid?
                         (rf/dispatch [::patient-events/register-pseudonymous-patient @data]))
            _ (tap> {:values @data
                     :error  error
                     :valid? valid? :explain (m/explain patient-registration-schema @data) :visited @visited})]
        [:div.space-y-6
         [:div.bg-white.shadow.px-4.py-5.sm:rounded-lg.sm:p-6
          [:div.md:grid.md:grid-cols-3.md:gap-6
           [:div.md:col-span-1
            [:h3.text-lg.font-medium.leading-6.text-gray-900 "Register a patient"]
            [:p.mt-1.mr-12.text-sm.text-gray-500 "Enter your patient details."
             [:p "This is safe even if patient already registered"]
             [:p.mt-4 "Patient identifiable information is not stored but simply used to generate a pseudonym."]]]
           [:div.mt-5.md:mt-0.md:col-span-2
            [:form {:on-submit #(do (.preventDefault %) (submit-fn))}
             [:div.grid.grid-cols-6.gap-6
              [:div.col-span-6.sm:col-span-3.space-y-6
               [:div [ui/textfield-control (:nhs-number @data) :label "NHS number" :auto-focus true
                      :on-change #(swap! data assoc :nhs-number %)
                      :on-blur #(swap! visited conj :nhs-number)]]
               [:div
                [ui/ui-label :for "date-birth" :label "Date of birth"]
                [:input.pb-4 {:name      "date-birth" :value (:date-birth @data) ;; TODO: spin out into own component
                              :type      "date"
                              :on-blur   #(swap! visited conj :date-birth)
                              :on-change #(swap! data assoc :date-birth (-> % .-target .-value))}]]
               [ui/select :name "gender"
                :value (:sex @data)
                :label "Gender"
                :choices [:MALE :FEMALE :UNKNOWN]
                :no-selection-string ""
                :on-key-down #(when (and (= 13 %) valid? (submit-fn)))
                :select-fn #(swap! data assoc :sex %)]
               ;[ui/textfield-control "" :label "Postal code" :disabled true :help-text "You will only need to enter this if a patient isn't already registered"]

               (when error [ui/box-error-message :message error])]]]]]]
         [:div.flex.justify-end.mr-8
          [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
           {:type     "submit"
            :class    (if-not valid? "opacity-50 pointer-events-none" "hover:bg-blue-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-blue-500")
            :on-click #(when valid? (submit-fn))
            } "Search or register patient »"]]]))))

(defn preferred-synonym [diagnosis]
  (get-in diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term]))

(defn list-diagnoses []
  (let [current-patient @(rf/subscribe [::patient-subs/current])
        current-diagnosis @(rf/subscribe [::patient-subs/current-diagnosis])
        sorted-diagnoses (sort-by preferred-synonym @(rf/subscribe [::patient-subs/diagnoses]))
        active-diagnoses (filter #(= "ACTIVE" (:t_diagnosis/status %)) sorted-diagnoses)
        resolved-diagnoses (filter #(= "INACTIVE_RESOLVED" (:t_diagnosis/status %)) sorted-diagnoses)
        incorrect-diagnoses (filter #(#{"INACTIVE_REVISED" "INACTIVE_IN_ERROR"} (:t_diagnosis/status %)) sorted-diagnoses)
        _ (tap> @db/app-db)]
    [:<>
     (when current-diagnosis
       (tap> current-diagnosis)
       [ui/modal :disabled? false
        :content
        [:form.space-y-8.divide-y.divide-gray-200
         [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
          [:div
           [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
            [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
             [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for ::choose-diagnosis} "Diagnosis"]
             [:div.mt-1.sm:mt-0.sm:col-span-2
              [:div.w-full.rounded-md.shadow-sm.space-y-2
               (if (:t_diagnosis/id current-diagnosis)      ;; if we already have a saved diagnosis, don't allow user to change
                 [:h3.text-lg.font-medium.leading-6.text-gray-900 (get-in current-diagnosis [:t_diagnosis/diagnosis :info.snomed.Concept/preferredDescription :info.snomed.Description/term])]
                 [eldrix.pc4-ward.snomed.views/select-snomed
                  :id ::choose-diagnosis
                  :common-choices []
                  :value (:t_diagnosis/diagnosis current-diagnosis)
                  :constraint "<404684003"
                  :select-fn #(rf/dispatch [::patient-events/set-current-diagnosis (assoc current-diagnosis :t_diagnosis/diagnosis %)])])]]]
            [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
             [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-onset"} "Date onset"]
             [:div.mt-1.sm:mt-0.sm:col-span-2
               [ui/html-date-picker :name "date-onset" :value (:t_diagnosis/date_onset current-diagnosis)
                :on-change #(rf/dispatch-sync [::patient-events/set-current-diagnosis (assoc current-diagnosis :t_diagnosis/date_onset %)])]]]
            [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
             [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-diagnosis"} "Date diagnosis"]
             [:div.mt-1.sm:mt-0.sm:col-span-2
              [ui/html-date-picker :name "date-diagnosis" :value (:t_diagnosis/date_diagnosis current-diagnosis)
               :on-change #(rf/dispatch-sync [::patient-events/set-current-diagnosis (assoc current-diagnosis :t_diagnosis/date_diagnosis %)])]]]
            [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
             [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "date-onset"} "Date to"]
             [:div.mt-1.sm:mt-0.sm:col-span-2
              [ui/html-date-picker :name "date-to" :value (:t_diagnosis/date_to current-diagnosis)
               :on-change #(rf/dispatch-sync [::patient-events/set-current-diagnosis (assoc current-diagnosis :t_diagnosis/date_to %)])]]]
            [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
             [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 {:for "status"} "Status"]
             [:div.mt-1.sm:mt-0.sm:col-span-2
              [ui/select
               :name "status"
               :value (:t_diagnosis/status current-diagnosis)
               :default-value "ACTIVE"
               :choices ["INACTIVE_REVISED" "ACTIVE" "INACTIVE_RESOLVED" "INACTIVE_IN_ERROR"]
               :select-fn #(rf/dispatch [::patient-events/set-current-diagnosis (assoc current-diagnosis :t_diagnosis/status %)])]]]]]]]
        :actions [{:id       ::save-action :title "Save" :is-primary true
                   :on-click #(rf/dispatch [::patient-events/save-diagnosis
                                            (assoc current-diagnosis
                                              :t_patient/patient_identifier (:t_patient/patient_identifier current-patient))])}
                  {:id ::cancel-action :title "Cancel" :on-click #(rf/dispatch [::patient-events/clear-diagnosis])}]])
     [ui/section-heading "Active diagnoses"
      :buttons [:button.ml-3.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.
                {:on-click #(rf/dispatch [::patient-events/set-current-diagnosis {}])} "Add diagnosis"]]
     [ui/list-entities-fixed
      :items active-diagnoses
      :headings ["Diagnosis" "Date onset" "Date diagnosis" "Date to" "Status"]
      :width-classes {"Diagnosis" "w-2/6" "Date onset" "w-1/6" "Date diagnosis" "w-1/6" "Date to" "w-1/6" "Status" "w-1/6"}
      :id-key :t_diagnosis/id
      :value-keys [preferred-synonym
                   #(dates/format-date (:t_diagnosis/date_onset %))
                   #(dates/format-date (:t_diagnosis/date_diagnosis %))
                   #(dates/format-date (:t_diagnosis/date_to %))
                   :t_diagnosis/status]
      :on-edit (fn [diagnosis] (js/console.log "edt diag")(rf/dispatch [::patient-events/set-current-diagnosis diagnosis]))]
     (when (seq resolved-diagnoses)
       [:div.mt-8
        [ui/section-heading "Inactive diagnoses"]
        [ui/list-entities-fixed
         :items resolved-diagnoses
         :headings ["Diagnosis" "Date onset" "Date diagnosis" "Date to" "Status"]
         :width-classes {"Diagnosis" "w-2/6" "Date onset" "w-1/6" "Date diagnosis" "w-1/6" "Date to" "w-1/6" "Status" "w-1/6"}
         :id-key :t_diagnosis/id
         :value-keys [preferred-synonym
                      #(dates/format-date (:t_diagnosis/date_onset %))
                      #(dates/format-date (:t_diagnosis/date_diagnosis %))
                      #(dates/format-date (:t_diagnosis/date_to %))
                      :t_diagnosis/status]
         :on-edit (fn [diagnosis] (js/console.log "edt diag")(rf/dispatch [::patient-events/set-current-diagnosis diagnosis]))]])]))

(def neuro-inflammatory-menus
  [{:id    :main
    :title "Main"}
   {:id        :diagnoses
    :title     "Diagnoses"
    :component list-diagnoses}
   {:id    :treatment
    :title "Treatment"}
   {:id    :relapses
    :title "Relapses"}
   {:id    :disability
    :title "Disability"}
   {:id    :admissions
    :title "Admissions"}
   {:id    :registration
    :title "Registration"}])

(def menu-by-id (reduce (fn [acc v] (assoc acc (:id v) v)) {} neuro-inflammatory-menus))

(defn view-pseudonymous-patient
  "This is a neuro-inflammatory 'view' of the patient record.
  TODO: split out common functionality and components into libraries"
  []
  (let [menu (reagent.core/atom :registration)]
    (fn []
      (let [patient @(rf/subscribe [::patient-subs/current])
            authenticated-user @(rf/subscribe [::user-subs/authenticated-user])
            _ (tap> {:patient patient :user authenticated-user})]
        [:div
         [ui/patient-banner
          :name (:t_patient/sex patient)
          :born (when-let [dob (:t_patient/date_birth patient)] (.getYear dob))
          :address (:t_episode/stored_pseudonym patient)
          :on-close #(when-let [project-id (:t_episode/project_fk patient)]
                       (println "opening project page for project" project-id)
                       (rfe/push-state :projects {:project-id project-id :slug "home"}))
          :content [ui/tabbed-menu
                    :name "patient-menu"
                    :value @menu
                    :on-change #(do (println "chosen" %) (reset! menu %))
                    :choices neuro-inflammatory-menus
                    :value-key :id
                    :display-key :title]]
         [:div.pt-3.border.bg-white.overflow-hidden.shadow-lg.sm:rounded-lg
          [:div.px-4.py-5.sm:p-6
           (when-let [component (:component (menu-by-id @menu))]
             [component])]]]))))

(defn list-users [users]
  [:div.flex.flex-col
   [:div.-my-2.overflow-x-auto.sm:-mx-6.lg:-mx-8
    [:div.py-2.align-middle.inline-block.min-w-full.sm:px-6.lg:px-8
     [:div.shadow.overflow-hidden.border-b.border-gray-200.sm:rounded-lg
      [:table.min-w-full.divide-y.divide-gray-200
       [:thead.bg-gray-50
        [:tr
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Name"]
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Title"]
         [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider {:scope "col"} "Email"]]]
       [:tbody.bg-white.divide-y.divide-gray-200
        (for [user (sort-by (juxt :t_user/last_name :t_user/first_names) (reduce-kv (fn [acc k v] (conj acc (first v))) [] (group-by :t_user/id users)))
              :let [id (:t_user/id user)]]
          [:tr {:key id}
           [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium.text-gray-900 (str/join " " [(:t_user/title user) (:t_user/first_names user) (:t_user/last_name user)])]
           [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (or (:t_user/custom_job_title user) (:t_job_title/name user))]
           [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-500 (:t_user/email user)]])]]]]]])

(defn project-home-page []
  (let [selected-page (reagent.core/atom :home)]
    (rf/dispatch [::patient-events/search-legacy-pseudonym nil ""])
    (rf/dispatch [::patient-events/clear-open-patient-error])
    (fn []
      (let [route @(rf/subscribe [:eldrix.pc4-ward.subs/current-route])
            authenticated-user @(rf/subscribe [::user-subs/authenticated-user])
            current-project @(rf/subscribe [::project-subs/current])]
        (when current-project
          [:<>
           [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200


            [:ul.flex
             [:div.font-bold.text-lg.min-w-min.mr-6.py-1 (:t_project/title current-project)]
             [ui/flat-menu [{:title "Home" :id :home}
                            {:title "Search" :id :search}
                            {:title "Register" :id :register}
                            {:title "Users" :id :users}]
              :selected-id @selected-page
              :select-fn #(do (reset! selected-page %)
                              (rf/dispatch [::patient-events/search-legacy-pseudonym (:t_project/id current-project) ""]))]]]
           (case @selected-page
             :home [inspect-project current-project]
             :search [search-by-pseudonym-panel (:t_project/id current-project)]
             :register [register-pseudonymous-patient (:t_project/id current-project)]
             :users [list-users (:t_project/users current-project)])])))))