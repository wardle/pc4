(ns pc4.wales-nadex.core
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.string :as str]
   [com.eldrix.concierge.wales.nadex :as nadex]
   [pc4.log.interface :as log])
  (:import (java.io Closeable)))

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

(defn gen-wales-email
  "Generate NHS Wales style email addresses."
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
(s/def ::title string?)
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

(defprotocol LdapService
  "A LDAP service that provides user authentication and lookup."
  (can-authenticate?
    [this username password]
    "Can the user authenticate against the directory using username and password?
    Returns a boolean.")
  (search-by-username
    [this username]
    [this opts username]
    "Perform a search against the directory for the given username.
    The default bind username and password will be used unless explicitly provided.
    Returns a sequence of maps containing LDAP data. For example:
    ```
    ({:department     \"Neurosciences\"
      :wwwHomePage    \"www.wardle.org\"
      :sAMAccountName \"ma090906\"
      :mail           \"mark.wardle@wales.nhs.uk\"
      :streetAddress  \"University Hospital Wales,\r\nHeath Park,\r\nCardiff,\"
      :l              \"Cardiff\"
      :title          \"Consultant Neurologist\"
      :telephoneNumber \"74 5274\"
      :postOfficeBox  \"GMC:4624000\"
      :postalCode     \"CF14 4XW\"
      :givenName      \"Mark\"
      :sn             \"Wardle\"
      :professionalRegistration {:regulator \"GMC\" :code \"4624000\"}
      :company        \"Cardiff and Vale UHB\"
      :physicalDeliveryOfficeName \"Ward C4 C4 corridor\"})
    ```")
  (search-by-name
    [this s]
    [this opts s]
    "Perform a search against the directory for the given name. Searches both first names and surname.
  The default bind username and password will be used unless explicitly provided."))

(s/def ::host string?)
(s/def ::hosts (s/coll-of ::host))
(s/def ::port pos-int?)
(s/def ::trust-all-certificates? boolean?)
(s/def ::pool-size pos-int?)
(s/def ::timeout-milliseconds pos-int?)
(s/def ::follow-referrals? boolean?)
(s/def ::default-bind-username string?)
(s/def ::default-bind-password string?)

(s/def ::live-config
  (s/keys :req-un [(or ::host ::hosts)]
          :opt-un [::port ::trust-all-certificates? ::pool-size ::timeout-milliseconds ::follow-referrals?
                   ::default-bind-username ::default-bind-password]))

(s/def ::data map?)
(s/def ::user (s/keys :req-un [::username ::password] :opt-un [::data]))
(s/def ::users (s/coll-of ::user))
(s/def ::mock-config
  (s/keys :req-un [::users]))

(s/def ::config
  (s/or :live ::live-config, :none empty?, :mock ::mock-config))

(defn make-live-service
  "Creates a 'live' NADEX service that expects a working LDAP environment."
  [{:keys [default-bind-username default-bind-password] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid nadex configuration" (s/explain-data ::config config))))
  (log/debug "creating 'live' LDAP service" (select-keys config [:host :hosts]))
  (let [pool (nadex/make-connection-pool config)]
    (reify
      LdapService
      (can-authenticate? [_ username password]
        (nadex/can-authenticate? pool username password))
      (search-by-username [_ username]
        (nadex/search pool default-bind-username default-bind-password (nadex/by-username username)))
      (search-by-username [_ {:keys [bind-username bind-password]} username]
        (nadex/search pool (or bind-username default-bind-username) (or bind-password default-bind-password) (nadex/by-username username)))
      (search-by-name [_ s]
        (nadex/search pool default-bind-username default-bind-password (nadex/by-name s)))
      (search-by-name [_ {:keys [bind-username bind-password]} s]
        (nadex/search pool (or bind-username default-bind-username) (or bind-password default-bind-password) (nadex/by-name s)))
      Closeable
      (close [_]
        (.close pool)))))

(defn make-nop-service [_]
  (log/debug "Creating no-op LDAP service in absence of any configured live services")
  (reify
    LdapService
    (can-authenticate? [_ _ _] false)
    (search-by-username [_ _] [])
    (search-by-username [_ _ _] [])
    (search-by-name [_ _] [])
    (search-by-name [_ _ _] [])
    Closeable
    (close [_])))

(defn match-name?
  "Create a predicate to match on name for mock service."
  [s]
  (let [s' (str/lower-case s)]
    (fn [{:keys [sn givenName]}]
      (or (and sn (str/starts-with? (str/lower-case sn) s'))
          (and givenName (str/starts-with? (str/lower-case givenName) s'))))))

(defn make-mock-service
  "The mock service takes an explicit list of users and generates synthetic data. 
  Parameters:
  - :users - a sequence of mock users"
  [{:keys [users]}]
  (log/debug "creating 'mock' LDAP service with " (count users) "mock users")
  (let [users# ;; generate any missing properties with synthetic data, and ensure sAMAccountName is explicitly defined username 
        (map (fn [{:keys [username data] :as user}]
               (assoc user :data (gen/generate (gen-ldap-user (assoc data :sAMAccountName username))))) users)
        user-by-username ;; generate a user directory with synthetic data
        (reduce (fn [acc {:keys [username] :as user}]
                  (assoc acc username user)) {} users#)]
    (reify
      LdapService
      (can-authenticate? [_ username password]
        (let [{pw :password} (user-by-username username)]
          (and (not (str/blank? password)) (= password pw))))
      (search-by-username [_ username]
        (list (:data (user-by-username username))))
      (search-by-username [_ _ username]
        (list (:data (user-by-username username))))
      (search-by-name [_ s] (filter (match-name? s) (map :data users#)))
      (search-by-name [_ _ s] (filter (match-name? s) (map :data users#)))
      Closeable
      (close [_]))))

(defn make-service
  "Create an LDAP service that can authenticate and lookup user information against
  a directory. This is highly configurable, and will create different types of 
  service depending on that configuration. The current three implementations
  comprise:
  :live - operates against a remote live directory
  :none - a no-op service that returns false or empty collections
  :mock - a mock service that returns test data including synthetic generation."
  [config]
  (let [config' (s/conform ::config config)]
    (if (= config' ::s/invalid)
      (throw (ex-info "invalid NADEX configuration" (s/explain-data ::config config)))
      (let [[mode data] config']
        (case mode
          :none (make-nop-service data)
          :live (make-live-service data)
          :mock (make-mock-service data)
          (throw (ex-info (str "unsupported NADEX service mode" mode) config')))))))

(defn valid-service? [svc]
  (satisfies? LdapService svc))

(defn close
  [^Closeable svc]
  (.close svc))

(comment
  (make-service {})
  (def svc (make-service {:users [{:username "ma090906", :password "password"
                                   :data {:mail "mark.wardle@wales.nhs.uk" :sn "Wardle"}}
                                  {:username "ja004216", :password "password"
                                   :data {:sn "Smith"}}]}))
  (can-authenticate? svc "ma090906" "password")
  (search-by-username svc "ja004216")
  (search-by-username svc "ma090906")
  (search-by-name svc "ch"))

