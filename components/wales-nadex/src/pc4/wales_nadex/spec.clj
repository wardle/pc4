(ns pc4.wales-nadex.spec
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.string :as str]))

(s/def ::non-blank-string (s/and string? (complement str/blank?)))

(defn gen-char-numeric
  "Return a generator for a numeric character."
  []
  (gen/fmap char (gen/choose 48 57)))

(defn gen-char-lowercase-alphabetical
  "Return a generator for a lowercase alphabetical character"
  []
  (gen/fmap char (gen/choose 97 122)))

(defn gen-string-with-length
  ([gen length]
   (gen/fmap str/join (gen/vector gen length)))
  ([gen min-chars max-chars]
   (gen/fmap str/join (gen/vector gen min-chars max-chars))))

(defn gen-non-blank-lowercase-string
  ([] (gen/such-that (complement str/blank?) (gen/fmap str/join (gen/vector (gen-char-lowercase-alphabetical)))))
  ([length] (gen/fmap str/join (gen/vector (gen-char-lowercase-alphabetical) length)))
  ([min-chars max-chars] (gen/fmap str/join (gen/vector (gen-char-lowercase-alphabetical) min-chars max-chars))))

(defn gen-nadex-username
  "Generate NADEX type usernames with a two character lowercase prefix and
  six numeric digits e.g. \"ul564587\"."
  []
  (gen/fmap
   (fn [[s1 s2]] (str s1 s2))
   (gen/tuple
    (gen-string-with-length (gen-char-lowercase-alphabetical) 2)
    (gen-string-with-length (gen-char-numeric) 6))))

(defn gen-job-title
  "Generate random job titles"
  []
  (gen/frequency [[2 (gen/elements ["Administrator"
                                    "Consultant" "Physician" "Consultant Neurologist"
                                    "Consultant renal physician" "Consultant nephrologist"
                                    "Nurse" "Specialist nurse" "ANP"
                                    "Physiotherapist" "Occupational therapist"
                                    "Specialist registrar" "Speciality registrar"
                                    "Clinical fellow" "Research fellow"])]

                  [2 (gen/return nil)]
                  [1 (gen/string)]]))

(comment
  (gen/sample (gen-job-title) 100))

(defn gen-wales-email
  "Generate NHS Wales style synthetic email addresses."
  []
  (gen/fmap (fn [[s1 s2]]
              (str s1 "." s2 "@wales.nhs.uk"))
            (gen/tuple (gen-non-blank-lowercase-string 1 12) (gen-non-blank-lowercase-string 1 18))))

(comment
  (gen/sample (gen-non-blank-lowercase-string 5 15))
  (gen/generate (gen/vector (gen-char-lowercase-alphabetical) 6))
  (gen/generate (gen-nadex-username))
  (gen/generate (gen-wales-email)))

(s/def ::sAMAccountName (s/with-gen ::non-blank-string gen-nadex-username))
(s/def ::mail (s/with-gen #(re-matches #".+@.+\..+" %) gen-wales-email))
(s/def ::streetAddress string?)
(s/def ::department string?)
(s/def ::wwwHomePage string?)
(s/def ::l string?)
(s/def ::title (s/with-gen string? gen-job-title))
(s/def ::personalTitle string?)
(s/def ::mobile string?)
(s/def ::telephoneNumber string?)
(s/def ::postOfficeBox string?)
(s/def ::postalCode string?)
(s/def ::givenName string?)
(s/def ::sn string?)
(s/def ::company string?)
(s/def ::physicalDeliveryOfficeName string?)
(s/def ::thumbnailPhoto bytes?)
(s/def ::regulator #{"GMC" "HCPC" "NMC"})
(s/def ::code string?)
(s/def ::professionalRegistration (s/keys :req-un [::regulator ::code]))
(s/def ::LdapUser
  (s/keys :req-un [::sAMAccountName ::mail
                   ::streetAddress  ::l
                   ::postalCode
                   ::department
                   ::wwwHomePage
                   ::title
                   ::telephoneNumber
                   ::postOfficeBox
                   ::givenName
                   ::sn
                   ::company
                   ::physicalDeliveryOfficeName]
          :opt-un [::personalTitle
                   ::mobile
                   ::thumbnailPhoto
                   ::professionalRegistration]))

(defn gen-ldap-user
  "Returns a generator for synthetic LDAP user data. Requires clojure test check
  on the classpath at runtime."
  ([]
   (s/gen ::LdapUser))
  ([m]
   (gen/fmap
    (fn [user] (merge user m))
    (s/gen ::LdapUser))))

(comment
  (gen/sample (gen-ldap-user))
  (gen/sample (gen-ldap-user {:company "SBUHB"}))
  (gen/sample (s/gen ::non-blank-string))
  (gen/sample (s/gen ::LdapUser))
  (gen/generate (s/gen ::LdapUser)))

(comment
  (def example-result
    {:department     "Neurosciences"
     :wwwHomePage    "www.wardle.org"
     :sAMAccountName "ma090906"
     :mail           "mark.wardle@wales.nhs.uk"
     :streetAddress  "University Hospital Wales,\r\nHeath Park, \r\nCardiff,"
     :l              "Cardiff"
     :title          "Consultant Neurologist"
     :telephoneNumber "74 5274"
     :postOfficeBox  "GMC:4624000"
     :postalCode     "CF14 4XW"
     :givenName      "Mark"
     :sn             "Wardle"
     :professionalRegistration {:regulator "GMC" :code "4624000"}
     :company        "Cardiff and Vale UHB"
     :physicalDeliveryOfficeName "Ward C4 C4 corridor"})
  (s/valid? ::LdapUser example-result))


