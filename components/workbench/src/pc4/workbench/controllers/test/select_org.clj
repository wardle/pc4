(ns pc4.workbench.controllers.test.select-org
  "Test page for demonstrating organisation selection components."
  (:require
    [pc4.ods-ui.interface :as ods-ui]
    [pc4.pathom-web.interface :as pw]
    [pc4.ui.interface :as ui]
    [pc4.web.interface :as web]))

(def test-org-select-handler
  "Test page demonstrating organisation selection components with various configurations."
  (pw/handler
    [:ui/csrf-token]
    (fn [request {:ui/keys [csrf-token]}]
      (web/ok
        (ui/render
          [:div.p-6
           (ui/ui-title {:title    "Organisation Selection Test Page"
                         :subtitle "Demonstration of ui-select-org component configurations"})

           [:form.space-y-8 {:method "POST" :action "#"}
            [:input {:type "hidden" :name "__anti-forgery-token" :value csrf-token}]

            ;; Basic organisation selection
            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "Basic GP Surgery Selection"]
             [:p.text-sm.text-gray-600.mb-3 "Select a GP surgery (role RO177) with name and code display:"]
             (ods-ui/ui-select-org {:id          "basic-gp"
                                    :name        "gp-surgery-code"
                                    :fields      #{:name :code}
                                    :label       "GP Surgery"
                                    :roles       "RO177"
                                    :placeholder "Choose GP surgery..."
                                    :required    true})]

            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "Basic GP Surgery Selection"]
             [:p.text-sm.text-gray-600.mb-3 "Select a GP surgery (role RO177) with name and address display:"]
             (ods-ui/ui-select-org {:id          "basic-gp2"
                                    :name        "gp-surgery-code2"
                                    :label       "GP Surgery"
                                    :roles       "RO177"
                                    :placeholder "Choose GP surgery..."
                                    :required    true})]

            ;; Hospital selection with address
            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "Hospital Trust/LHB Selection with Address"]
             [:p.text-sm.text-gray-600.mb-3 "Select a hospital trust (role RO57/RO142/RO144/RO197) showing name, town and postcode:"]
             (ods-ui/ui-select-org {:id          "hospital"
                                    :name        "hospital-code"
                                    :label       "Hospital Trust"
                                    :roles       ["RO57" "RO142" "RO144" "RO197"]
                                    :fields      #{:name :address}
                                    :placeholder "= SELECT HOSPITAL ="
                                    :required    false})]

            ;; Pre-selected organisation
            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "Pre-selected Organisation"]
             [:p.text-sm.text-gray-600.mb-3 "Cardiff & Vale UHB pre-selected with full address display:"]
             (let [fake-uhb {:org.hl7.fhir.Organization/identifier
                             [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/ods-organization-code"
                               :org.hl7.fhir.Identifier/value  "7A4"}]
                             :org.hl7.fhir.Organization/name   "Cardiff and Vale University Health Board"
                             :org.hl7.fhir.Organization/active true
                             :org.hl7.fhir.Organization/address
                             [{:org.hl7.fhir.Address/use        "work"
                               :org.hl7.fhir.Address/line       ["University Hospital of Wales" "Heath Park"]
                               :org.hl7.fhir.Address/city       "Cardiff"
                               :org.hl7.fhir.Address/postalCode "CF14 4XW"
                               :org.hl7.fhir.Address/country    "Wales"}]}]
               (ods-ui/ui-select-org {:id               "preselected"
                                      :name             "uhb-code"
                                      :label            "University Health Board"
                                      :roles            "RO142"
                                      :fields           #{:name :address}
                                      :allow-unfiltered true
                                      :selected         fake-uhb
                                      :required         true}))]

            ;; Common organisations
            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "GP Surgery with Common Options"]
             [:p.text-sm.text-gray-600.mb-3 "GP surgery selection with common local surgeries pre-populated:"]
             (ods-ui/ui-select-org {:id          "gp-with-common"
                                    :name        "local-gp-code"
                                    :label       "Local GP Surgery"
                                    :roles       "RO76"
                                    :common-orgs []
                                    :placeholder "Choose from common or search..."
                                    :required    false})]

            ;; Location-based search example
            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "Location-Based Search"]
             [:p.text-sm.text-gray-600.mb-3 "GP surgery search with default location filter (Cardiff area):"]
             (ods-ui/ui-select-org {:id          "location-gp"
                                    :name        "nearby-gp-code"
                                    :label       "Nearby GP Surgery"
                                    :roles       "RO177"
                                    :postcode    "CF14 2HB"
                                    :range       5000
                                    :limit       100
                                    :placeholder "Find nearby GP surgery..."
                                    :required    false})]

            ;; Include inactive organisations
            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "Include Inactive Organisations"]
             [:p.text-sm.text-gray-600.mb-3 "Hospital search including historical/inactive organisations:"]
             (ods-ui/ui-select-org {:id          "all-hospitals"
                                    :name        "any-hospital-code"
                                    :label       "Any Hospital (Active or Inactive)"
                                    :roles       ["RO198" "RO197"]
                                    :active      false
                                    :fields      #{:name :address}
                                    :limit       50
                                    :placeholder "Search all hospitals..."
                                    :required    false})]

            ;; Multiple roles example
            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "Multiple Organisation Roles"]
             [:p.text-sm.text-gray-600.mb-3 "Search across multiple organisation types (GP surgeries and health centres):"]
             (ods-ui/ui-select-org {:id          "multi-role"
                                    :name        "healthcare-provider-code"
                                    :label       "Healthcare Provider"
                                    :roles       ["RO76" "RO177"]
                                    :fields      #{:name :address}
                                    :limit       100
                                    :placeholder "Search healthcare providers..."
                                    :required    false})]

            ;; Disabled example
            [:div.p-4.border.border-gray-200.rounded-lg
             [:h4.text-md.font-medium.text-gray-700.mb-3 "Disabled Selection"]
             [:p.text-sm.text-gray-600.mb-3 "Pre-selected and disabled organisation (read-only):"]
             (let [fake-uhb {:org.hl7.fhir.Organization/identifier
                             [{:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/ods-organization-code"
                               :org.hl7.fhir.Identifier/value  "7A4"}]
                             :org.hl7.fhir.Organization/name   "Cardiff and Vale University Health Board"
                             :org.hl7.fhir.Organization/active true}]
               (ods-ui/ui-select-org {:id       "disabled"
                                      :name     "readonly-org-code"
                                      :label    "Assigned Organisation"
                                      :roles    "RO142"
                                      :selected fake-uhb
                                      :disabled true
                                      :required true}))]

            ;; Action buttons
            (ui/ui-action-bar
              (ui/ui-submit-button {} "Test Form Submission")
              (ui/ui-cancel-button {:href "/"} "Back to Home"))

            ;; Help section
            [:div.mt-8.p-4.bg-blue-50.rounded-lg.border.border-blue-200
             [:h4.text-sm.font-medium.text-blue-800.mb-2 "Testing Instructions"]
             [:div.text-sm.text-blue-700.space-y-2
              [:p "• Click on any organisation control to open the search modal"]
              [:p "• Try searching for 'Cardiff', 'Vale', 'University' to see real NHS organisations"]
              [:p "• Use the 'Show Location Filters' to test postcode-based searching"]
              [:p "• Notice how inactive organisations show '(inactive)' suffix"]
              [:p "• Different controls show different address information based on display-fields"]
              [:p "• Form submission will show selected organisation codes in the URL"]]]

            ;; Debug section
            [:div.mt-8.p-4.bg-gray-50.rounded-lg
             [:h4.text-sm.font-medium.text-gray-700.mb-2 "Configuration Examples"]
             [:div.text-xs.text-gray-600.space-y-4
              [:div
               [:strong "Basic GP (RO76):"]
               [:pre.mt-1.whitespace-pre-wrap "{:roles \"RO76\" :display-fields #{:name}}"]]
              [:div
               [:strong "Hospital with address (RO142):"]
               [:pre.mt-1.whitespace-pre-wrap "{:roles \"RO142\" :display-fields #{:name :address}}"]]
              [:div
               [:strong "Location search:"]
               [:pre.mt-1.whitespace-pre-wrap "{:postal-code \"CF14 4XW\" :distance 5000 :limit 25}"]]
              [:div
               [:strong "Include inactive:"]
               [:pre.mt-1.whitespace-pre-wrap "{:only-active? false :roles [\"RO198\" \"RO197\"]}"]]]]]])))))