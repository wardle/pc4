(ns pc4.workbench.controllers.test.select-user
  "Test page for demonstrating user selection components."
  (:require
    [pc4.workbench.controllers.select-user :as select-user]
    [pc4.workbench.pathom :as pathom]
    [pc4.workbench.ui :as ui]
    [pc4.workbench.web :as web]))

(def test-user-select-handler
  "Test page demonstrating user selection components with various configurations."
  (pathom/handler
    [:ui/csrf-token
     {:ui/authenticated-user [:t_user/id :t_user/username :t_user/full_name :t_user/job_title]}]
    (fn [request {:ui/keys [csrf-token authenticated-user]}]
      (web/page
        {}
        [:div.p-6
         (ui/ui-title {:title    "User Selection Test Page"
                       :subtitle "Demonstration of user selection components"})

         [:form.space-y-6 {:method "POST" :action "#"}
          [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]

          ;; Basic encounter fields
          (ui/ui-simple-form
            (ui/ui-simple-form-title {:title "Basic Form Fields"})

            (ui/ui-simple-form-item {:label "Date"}
              (ui/ui-textfield {:id       "test-date"
                                :name     "test-date"
                                :type     "date"
                                :required true}))

            (ui/ui-simple-form-item {:label "Notes"}
              (ui/ui-textarea {:id          "notes"
                               :name        "notes"
                               :placeholder "Enter notes here..."
                               :rows        3} "")))

          ;; User selection examples
          [:div.space-y-6
           [:h3.text-lg.font-medium.text-gray-900.border-b.border-gray-200.pb-2 "User Selection Examples"]

           ;; Required user selection
           [:div.p-4.border.border-gray-200.rounded-lg
            [:h4.text-md.font-medium.text-gray-700.mb-3 "Required User Selection"]
            [:p.text-sm.text-gray-600.mb-3 "Select a responsible clinician (required field):"]
            (select-user/ui-select-user {:id               "responsible-clinician"
                                         :name             "responsible-clinician-id"
                                         :label            "Responsible Clinician"
                                         :csrf-token       csrf-token
                                         :only-responsible true
                                         :required         true})]

           ;; Multiple user selection
           [:div.p-4.border.border-gray-200.rounded-lg
            [:h4.text-md.font-medium.text-gray-700.mb-3 "Multiple User Selection"]
            [:p.text-sm.text-gray-600.mb-3 "Select team members involved:"]
            (select-user/ui-select-users {:id         "team-members"
                                          :name       "team-member-ids"
                                          :label      "Team Members"
                                          :csrf-token csrf-token
                                          :required   false})]

           ;; Optional user selection
           [:div.p-4.border.border-gray-200.rounded-lg
            [:h4.text-md.font-medium.text-gray-700.mb-3 "Optional User Selection"]
            [:p.text-sm.text-gray-600.mb-3 "Optionally assign a reviewer:"]
            (select-user/ui-select-user {:id         "reviewer"
                                         :csrf-token csrf-token
                                         :name       "reviewer-id"
                                         :label      "Reviewer (Optional)"
                                         :required   false})]

           ;; Pre-selected users example
           [:div.p-4.border.border-gray-200.rounded-lg
            [:h4.text-md.font-medium.text-gray-700.mb-3 "Pre-selected User"]
            [:p.text-sm.text-gray-600.mb-3 "Current user pre-selected as default:"]
            (select-user/ui-select-user {:id         "created-by"
                                         :name       "created-by-id"
                                         :label      "Created By"
                                         :csrf-token csrf-token
                                         :disabled   false
                                         :selected   {:user-id   (:t_user/id authenticated-user)
                                                      :full-name (:t_user/full_name authenticated-user)
                                                      :job-title (:t_user/job_title authenticated-user)}
                                         :required   true})]]

          ;; Action buttons
          (ui/ui-action-bar
            (ui/ui-submit-button {} "Test Form Submission")
            (ui/ui-cancel-button {:href "/"} "Back to Home"))

          ;; Help section
          [:div.mt-8.p-4.bg-blue-50.rounded-lg.border.border-blue-200
           [:h4.text-sm.font-medium.text-blue-800.mb-2 "Testing Instructions"]
           [:div.text-sm.text-blue-700.space-y-2
            [:p "• Click on any user control to open the selection modal"]
            [:p "• Try the 'My colleagues' vs 'Search all users' modes"]
            [:p "• Test multiple user selection with add/remove functionality"]
            [:p "• Notice how job titles are displayed in gray text"]
            [:p "• Form submission will show selected user IDs in the URL"]]]

          ;; Debug section
          [:div.mt-8.p-4.bg-gray-50.rounded-lg
           [:h4.text-sm.font-medium.text-gray-700.mb-2 "Current User Info (for testing)"]
           [:pre.text-xs.text-gray-600 (pr-str authenticated-user)]]]]))))