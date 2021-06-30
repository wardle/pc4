(ns eldrix.pc4-ward.user.subs
  "Subscriptions relating to users.
  * Login and logout
  * Session timeout
  * Token management, including refresh"
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::token
  (fn [db]
    (get-in db [:authenticated-user :io.jwt/token])))

(rf/reg-sub ::authenticated-user
  (fn [db]
    (get-in db [:authenticated-user :practitioner])))

(rf/reg-sub ::authenticated-user-full-name
  (fn []
    (rf/subscribe [::authenticated-user]))
  (fn [user]
    (:urn:oid:2.5.4/commonName user)))

(rf/reg-sub ::login-error
  (fn [db]
    (get-in db [:errors :user/login])))

(rf/reg-sub ::ping-error
  (fn [db]
    (get-in db [:errors :ping])))

;; we currently hardcode the common hospitals for a user,
;; but we could derive based on where they work or a user-configured list
;; after demo release.
(rf/reg-sub ::common-hospitals
  (fn [_db]
    [#:org.hl7.fhir.Organization{:name "UNIVERSITY HOSPITAL OF WALES"
                                 :identifier [#:org.hl7.fhir.Identifier{:system "2.16.840.1.113883.2.1.3.2.4.18.48", :value "7A4BV", :use :org.hl7.fhir.identifier-use/old} 
                                              #:org.hl7.fhir.Identifier{:system :urn:oid.2.16.840.1.113883.2.1.3.2.4.18.48, :value "7A4BV", :use :org.hl7.fhir.identifier-use/official} 
                                              #:org.hl7.fhir.Identifier{:system "https://fhir.nhs.uk/Id/ods-site", :value "7A4BV", :use :org.hl7.fhir.identifier-use/usual}]
                                 :address [#:org.hl7.fhir.Address{:use :org.hl7.fhir.address-use/work
                                                                  :type :org.hl7.fhir.address-type/both, 
                                                                  :text "HEATH PARK\nCARDIFF\nWALES\nCF14 4XW"
                                                                  :line ["HEATH PARK" nil]
                                                                  :city "CARDIFF"
                                                                  :postalCode "CF14 4XW"
                                                                  :country "WALES"}]
                                 :active true}
     {:org.hl7.fhir.Organization/name "ROOKWOOD HOSPITAL", :org.hl7.fhir.Organization/identifier [{:org.hl7.fhir.Identifier/system "2.16.840.1.113883.2.1.3.2.4.18.48", :org.hl7.fhir.Identifier/value "7A4BX", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/old} {:org.hl7.fhir.Identifier/system :urn:oid.2.16.840.1.113883.2.1.3.2.4.18.48, :org.hl7.fhir.Identifier/value "7A4BX", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/official} {:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/ods-site", :org.hl7.fhir.Identifier/value "7A4BX", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/usual}], :org.hl7.fhir.Organization/address [{:org.hl7.fhir.Address/use :org.hl7.fhir.address-use/work, :org.hl7.fhir.Address/type :org.hl7.fhir.address-type/both, :org.hl7.fhir.Address/text "18-20 FAIRWATER ROAD\nCARDIFF\nWALES\nCF5 2YN", :org.hl7.fhir.Address/line ["18-20 FAIRWATER ROAD" nil], :org.hl7.fhir.Address/city "CARDIFF", :org.hl7.fhir.Address/postalCode "CF5 2YN", :org.hl7.fhir.Address/country "WALES"}], :org.hl7.fhir.Organization/active true}
     {:org.hl7.fhir.Organization/name "THE ROYAL GLAMORGAN HOSPITAL", :org.hl7.fhir.Organization/identifier [{:org.hl7.fhir.Identifier/system "2.16.840.1.113883.2.1.3.2.4.18.48", :org.hl7.fhir.Identifier/value "7A5B1", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/old} {:org.hl7.fhir.Identifier/system :urn:oid.2.16.840.1.113883.2.1.3.2.4.18.48, :org.hl7.fhir.Identifier/value "7A5B1", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/official} {:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/ods-site", :org.hl7.fhir.Identifier/value "7A5B1", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/usual}], :org.hl7.fhir.Organization/address [{:org.hl7.fhir.Address/use :org.hl7.fhir.address-use/work, :org.hl7.fhir.Address/type :org.hl7.fhir.address-type/both, :org.hl7.fhir.Address/text "YNYSMAERDY\nPONTYCLUN\nWALES\nCF72 8XR", :org.hl7.fhir.Address/line ["YNYSMAERDY" nil], :org.hl7.fhir.Address/city "PONTYCLUN", :org.hl7.fhir.Address/postalCode "CF72 8XR", :org.hl7.fhir.Address/country "WALES"}], :org.hl7.fhir.Organization/active true}
     {:org.hl7.fhir.Organization/name "PRINCE CHARLES HOSPITAL SITE", :org.hl7.fhir.Organization/identifier [{:org.hl7.fhir.Identifier/system "2.16.840.1.113883.2.1.3.2.4.18.48", :org.hl7.fhir.Identifier/value "7A5B3", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/old} {:org.hl7.fhir.Identifier/system :urn:oid.2.16.840.1.113883.2.1.3.2.4.18.48, :org.hl7.fhir.Identifier/value "7A5B3", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/official} {:org.hl7.fhir.Identifier/system "https://fhir.nhs.uk/Id/ods-site", :org.hl7.fhir.Identifier/value "7A5B3", :org.hl7.fhir.Identifier/use :org.hl7.fhir.identifier-use/usual}], :org.hl7.fhir.Organization/address [{:org.hl7.fhir.Address/use :org.hl7.fhir.address-use/work, :org.hl7.fhir.Address/type :org.hl7.fhir.address-type/both, :org.hl7.fhir.Address/text "PRINCE CHARLES HOSPITAL\nMERTHYR TYDFIL\nWALES\nCF47 9DT", :org.hl7.fhir.Address/line ["PRINCE CHARLES HOSPITAL" nil], :org.hl7.fhir.Address/city "MERTHYR TYDFIL", :org.hl7.fhir.Address/postalCode "CF47 9DT", :org.hl7.fhir.Address/country "WALES"}], :org.hl7.fhir.Organization/active true}]))

(rf/reg-sub ::default-hospital
  (fn [_ db]
    #:org.hl7.fhir.Organization{:name "UNIVERSITY HOSPITAL OF WALES"
                                :identifier [#:org.hl7.fhir.Identifier{:system "2.16.840.1.113883.2.1.3.2.4.18.48", :value "7A4BV", :use :org.hl7.fhir.identifier-use/old}
                                             #:org.hl7.fhir.Identifier{:system :urn:oid.2.16.840.1.113883.2.1.3.2.4.18.48, :value "7A4BV", :use :org.hl7.fhir.identifier-use/official}
                                             #:org.hl7.fhir.Identifier{:system "https://fhir.nhs.uk/Id/ods-site", :value "7A4BV", :use :org.hl7.fhir.identifier-use/usual}]
                                :address [#:org.hl7.fhir.Address{:use :org.hl7.fhir.address-use/work
                                                                 :type :org.hl7.fhir.address-type/both,
                                                                 :text "HEATH PARK\nCARDIFF\nWALES\nCF14 4XW"
                                                                 :line ["HEATH PARK" nil]
                                                                 :city "CARDIFF"
                                                                 :postalCode "CF14 4XW"
                                                                 :country "WALES"}]
                                :active true}))