(ns pc4.wales-nadex.core
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [clojure.string :as str]
   [com.eldrix.concierge.wales.nadex :as nadex]
   [pc4.log.interface :as log]
   [pc4.wales-nadex.spec :as nspec])
  (:import (java.io Closeable)))

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
  (log/debug "creating no-op LDAP service in absence of any configured live services")
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
  The data provided manually is supplemented with synthetic data, but this occurs
  at service set-up, rather than on each search/fetch, and so a fetch will be 
  referentially transparent.
  
  Parameters:
  - :users - a sequence of mock users"
  [{:keys [users]}]
  (log/debug "creating 'mock' LDAP service with " (count users) "mock users")
  (let [users# ;; generate any missing properties with synthetic data, and ensure sAMAccountName is explicitly defined username 
        (map (fn [{:keys [username data] :as user}]
               (assoc user :data (gen/generate (nspec/gen-ldap-user (assoc data :sAMAccountName username))))) users)
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
      (search-by-name [_ s]
        (filter (match-name? s) (map :data users#)))
      (search-by-name [_ _ s]
        (filter (match-name? s) (map :data users#)))
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
  (valid-service? svc)
  (can-authenticate? svc "ma090906" "password")
  (search-by-username svc "ja004216")
  (search-by-username svc "ma090906")
  (search-by-name svc "ch"))

