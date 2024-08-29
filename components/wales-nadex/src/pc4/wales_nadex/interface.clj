(ns pc4.wales-nadex.interface
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [com.eldrix.concierge.wales.nadex :as nadex]
   [integrant.core :as ig]
   [pc4.log.interface :as log])
  (:import (java.io Closeable)))

(defprotocol LdapService
  "A LDAP service that provides user authentication and lookup."
  (can-authenticate?
    [this username password]
    "Authenticate against the directory using username and password.")
  (search-by-username
    [this username]
    [this opts username]
    "Perform a search against the directory for the given username.
  The default bind username and password will be used unless explicitly provided.")
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

(s/def ::user (s/keys :req-un [::username ::password]))   ;;TODO: include other fake user information for lookup as per LDAP search
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
  (log/info "Creating no-op LDAP service in absence of any configured live services")
  (reify
    LdapService
    (can-authenticate? [_ _ _] false)
    (search-by-username [_ _] [])
    (search-by-username [_ _ _] [])
    (search-by-name [_ _] [])
    (search-by-name [_ _ _] [])
    Closeable
    (close [_])))

(defn make-mock-service
  [{:keys [users]}]
  (log/info "creating 'mock' LDAP service with " (count users) "mock users.")
  (let [user-by-username (reduce (fn [acc {:keys [username] :as user}]
                                   (assoc acc username user))
                                 {} users)]
    (reify
      LdapService
      (can-authenticate? [_ username password]
        (let [{pw :password} (user-by-username username)]
          (and (not (str/blank? password)) (= password pw))))
      (search-by-username [_ _] [])   ;; TODO: implement
      (search-by-username [_ _ _] []) ;; TODO: implement
      (search-by-name [_ _] [])       ;; TODO: implement
      (search-by-name [_ _ _] [])     ;; TODO: implement
      Closeable
      (close [_]))))

(defn make-service
  "Create an LDAP service that can authenticate and lookup user information against
  a directory. This is highly configurable, and will create different types of 
  service depending on that configuration. The current three implementations
  comprise:
  :live - operates against a remote live directory
  :none - a no-op service that returns false or empty collections
  :mock - a mock service that returns a fixed set of test data
  
  In the future, additional options may be possible to provide synthetic data etc"
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

(comment
  (make-service {})
  (def svc (make-service {:users [{:username "ma090906"
                                   :password "password"}]}))
  (can-authenticate? svc "ma090906" "password"))

(defmethod ig/init-key ::svc
  [_ config]
  (make-service config))

(defmethod ig/halt-key! ::svc
  [_ {:keys [pool]}]
  (when pool (.close pool)))







