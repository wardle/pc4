(ns eldrix.pc4-ward.events
  (:require
    [re-frame.core :as rf]
    [day8.re-frame.http-fx]                                 ;; required for its side-effects in registering a re-frame "effect"
    [eldrix.pc4-ward.db :as db]
    [ajax.core :as ajax]
    ))

(rf/reg-event-db
  ::initialize-db
  (fn [_ _]
    db/default-db))

(defn make-snomed-search-op [{:keys [s constraint] :as params}]
  [{(list 'info.snomed.Search/search
          params)
    [:info.snomed.Concept/id
     :info.snomed.Description/id
     :info.snomed.Description/term
     {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}])

(defn make-login-op [{:keys [system value password] :as params}]
  [{(list 'pc4.users/login
          params)
    [:urn.oid.1.2.840.113556.1.4/sAMAccountName
     :urn.oid.2.5.4/givenName
     :urn.oid.2.5.4/surname
     :urn.oid.2.5.4/commonName
     {:org.hl7.fhir.Practitioner/name
      [:org.hl7.fhir.HumanName/use
       :org.hl7.fhir.HumanName/family
       :org.hl7.fhir.HumanName/given]}]}])


(defn make-xhrio-request [{:keys [service-token params on-success on-failure]}]
  {:method          :post
   :uri             "http://localhost:8080/api"
   :timeout         3000
   :format          (ajax/transit-request-format)
   :response-format (ajax/transit-response-format)
   :headers         (when service-token {:Authorization (str "Bearer " service-token)})
   :params          params
   :on-success      on-success
   :on-failure      on-failure})

(rf/reg-event-fx
  :user/user-login-do
  []
  (fn [{db :db} [_ namespace username password]]
    (js/console.log "performing login " username)
    {:http-xhrio (make-xhrio-request {:service-token (:service-token db)
                                      :params        (make-login-op {:system namespace :value username :password password})
                                      :on-success    [:user/user-login-success]
                                      :on-failure    [:user/user-login-failure]})}))

(rf/reg-event-fx
  :user/user-login-success
  []
  (fn [{db :db} [_ response]]
    (js/console.log "User login success: response: " response)))

(rf/reg-event-fx
  :user/user-login-failure
  []
  (fn [{:keys [db]} [_ response]]
    (js/console.log "User login failure: response " response)))

(comment
  (make-login-op {:system "cymru.nhs.uk" :value "ma090906" :password "password"})
  (rf/dispatch [:user/user-login-do "cymru.nhs.uk" "donduck" "password"])
  )