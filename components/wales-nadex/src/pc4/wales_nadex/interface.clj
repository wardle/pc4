(ns pc4.wales-nadex.interface
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.concierge.wales.nadex :as nadex]
            [integrant.core :as ig]))

(s/def ::host string?)
(s/def ::hosts (s/coll-of ::host))
(s/def ::port pos-int?)
(s/def ::trust-all-certificates? boolean?)
(s/def ::pool-size pos-int?)
(s/def ::timeout-milliseconds pos-int?)
(s/def ::follow-referrals? boolean?)
(s/def ::default-bind-username string?)
(s/def ::default-bind-password string?)

(s/def ::config (s/keys :req-un [(or ::host ::hosts)]
                        :opt-un [::port ::trust-all-certificates? ::pool-size ::timeout-milliseconds ::follow-referrals?
                                 ::default-bind-username ::default-bind-password]))

(defmethod ig/init-key ::svc
  [_ config]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid nadex configuration" (s/explain-data ::config config))))
  (assoc config :pool (nadex/make-connection-pool config)))

(defmethod ig/halt-key! ::svc
  [_ {:keys [pool]}]
  (when pool (.close pool)))

(defn can-authenticate?
  "Authenticate against the directory using username and password."
  [{:keys [pool]} username password]
  (nadex/can-authenticate? pool username password))

(defn search-by-username
  "Perform a search against the directory for the given username.
  The default bind username and password will be used unless explicitly provided."
  ([{:keys [default-bind-username default-bind-password pool]} username]
   (nadex/search pool default-bind-username default-bind-password (nadex/by-username username)))
  ([{:keys [default-bind-username default-bind-password pool]} {:keys [bind-username bind-password]} username]
   (nadex/search pool (or bind-username default-bind-username) (or bind-password default-bind-password) (nadex/by-username username))))

(defn search-by-name
  "Perform a search against the directory for the given name. Searches both first names and surname.
  The default bind username and password will be used unless explicitly provided."
  ([{:keys [default-bind-username default-bind-password pool]} s]
   (nadex/search pool default-bind-username default-bind-password (nadex/by-name s)))
  ([{:keys [default-bind-username default-bind-password pool]} {:keys [bind-username bind-password]} s]
   (nadex/search pool (or bind-username default-bind-username) (or bind-password default-bind-password) (nadex/by-name s))))

