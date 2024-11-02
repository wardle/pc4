(ns pc4.wales-nadex.interface
  (:require
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [pc4.wales-nadex.core :as core]
   [pc4.wales-nadex.fhir :as fhir]
   [pc4.wales-nadex.spec :as nspec]
   [com.eldrix.hermes.core :as hermes]))

(s/def ::conn ::core/config)
(s/def ::hermes any?)
(s/def ::config (s/keys :req-un [::hermes ::conn]))

(defn open
  [{:keys [conn] :as config}]
  (when-not (s/valid? ::config config)
    (throw (ex-info "invalid nadex configuration" (s/explain-data ::config config))))
  (assoc config :conn (core/make-service conn)))

(defn close
  [{:keys [conn]}]
  (core/close conn))

(defmethod ig/init-key ::svc
  [_ config]
  (open config))

(defmethod ig/halt-key! ::svc
  [_ svc]
  (close svc))

(defn valid-service?
  "Is 'svc' a valid NADEX service?"
  [{:keys [hermes conn]}]
  (and hermes (core/valid-service? conn)))

(s/def ::svc valid-service?)

(s/fdef can-authenticate?
  :args (s/cat :svc ::svc :username string? :password string?))
(defn can-authenticate?
  "Can the user authenticate with these credentials. Returns a boolean."
  [{:keys [conn]} username password]
  (core/can-authenticate? conn username password))

(s/fdef search-by-username
  :args (s/cat :svc ::svc :username string?))
(defn search-by-username
  "Search by username - searching against an exact match on 'sAMAccountName' LDAP field."
  [{:keys [conn]} username]
  (core/search-by-username conn username))

(s/fdef search-by-name
  :args (s/cat :svc ::svc :s string?))
(defn search-by-name
  "Search by name; LDAP fields 'sn' and 'givenName' will be searched by prefix."
  [{:keys [conn]} s]
  (core/search-by-name conn s))

(defn gen-user
  "Return a generator for synthetic LDAP user data. Any specified data will
  be used in preference to generated data.
  Requires the [test.check](https://github.com/clojure/test.check) library to be on the classpath,
  but this dependency is dynamically loaded only if used at runtime. 

  For example,
  ```
  (require '[clojure.spec.gen.alpha :as gen])
  (gen/generate (gen-ldap-user))
  =>
  {:department      \"06xb1Z2W5xp8QN0Abx9g45\",
   :wwwHomePage     \"Y5Fu5TXJHpr8L8q9V286\",
   :sAMAccountName  \"cx562069\",
   :mail            \"amwsvghw.yezwelhzo@wales.nhs.uk\",
   :streetAddress   \"23A4Bbl07B0OGREZXC3x1\",
   :l               \"OV7HmyiD51\",
   :title           \"NV\",
   :telephoneNumber \"nNlxkwki4K36676T54PRbl052T6uk1\",
   :postOfficeBox   \"yFJHFajg72t7c6Mq4pG15HVajSl\",
   :postalCode      \"C4auzOCrO0ii2lK\",
   :givenName       \"cH85BxY7eJvMI61ohNx\",
   :sn              \"F7u17dUpj4qzMnK\",
   :mobile          \"EN\",
   :company         \"t1zj5e3y554u53S0vuekU753IQ1\",
   :physicalDeliveryOfficeName \"N5m9y4nG10aV18O4Ou9Hn0zZ83nI\"}
  ```"
  ([]
   (nspec/gen-ldap-user))
  ([m]
   (nspec/gen-ldap-user m)))

(s/fdef user->fhir-r4
  :args (s/cat :svc ::svc :user ::nspec/LdapUser)
  :ret :org.hl7.fhir/Practitioner)
(defn user->fhir-r4
  [_ user]
  (fhir/user->fhir-r4 user))

(s/fdef user->fhir-r4-practitioner-role
  :args (s/cat :svc ::svc :user ::nspec/LdapUser)
  :ret  :org.hl7.fhir/PractitionerRole)
(defn  user->fhir-r4-practitioner-role
  [{:keys [hermes]} user]
  (fhir/user->fhir-r4-practitioner-role hermes user))

(comment
  (def conn (core/make-service {:users [{:username "ma090906" :password "password" :data {:sn "Wardle"}}]}))
  (def hermes (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  (def svc {:hermes hermes :conn conn})
  (can-authenticate? svc "ma090906" "password")
  (search-by-username svc "ma090906")
  (search-by-name svc "ward")
  (require '[clojure.spec.gen.alpha :as gen])
  (gen/generate (gen-user))
  (def user (gen/generate (gen-user)))
  (def user' (fhir/user->fhir-r4 user))
  (s/valid? (:ret (s/get-spec `fhir/user->fhir-r4)) user')
  (s/explain :org.hl7.fhir/Practitioner user')
  (s/explain-data :org.hl7.fhir/PractitionerRole (user->fhir-r4-practitioner-role svc user)))


