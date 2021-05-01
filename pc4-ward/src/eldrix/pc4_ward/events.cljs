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




(defn make-snomed-search-op[params]
  [{(list 'info.snomed.Search/search
      params)
    [:info.snomed.Concept/id
     :info.snomed.Description/id
     :info.snomed.Description/term
     {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}])


(rf/reg-event-fx
  :user/user-login-do
  []
  (fn [{db :db} [_ namespace username password]]
    (js/console.log "performing login " username)
    {:http-xhrio {:method          :post
                  :uri             "http://localhost:8080/api"
                  :timeout         3000
                  :format          (ajax/transit-request-format)
                  :response-format (ajax/transit-response-format)
                  :headers         {:Authorization (str "Bearer " (:service-token db))}
                  :params          (make-snomed-search-op {:s "mult scler"
                                                           :constraint "<404684003"
                                                           :max-hits 100})
                  
                  
                  :on-success      [:user/user-login-success]
                  :on-failure      [:user/user-login-failure]}}))


;; (comment [{[:uk.gov.ons.nhspd/PCDS "CF14 4XW"]
;;              [:uk.gov.ons.nhspd/LSOA11
 ;;              :uk.gov.ons.nhspd/OSNRTH1M
 ;              :uk.gov.ons.nhspd/OSEAST1M
 ;              :uk.gov.ons.nhspd/PCT
 ;              :uk.nhs.ord/name
 ;              :uk.nhs.ord.primaryRole/displayName
 ;              {:uk.nhs.ord/predecessors [:uk.nhs.ord/name]}]}])

(rf/reg-event-fx
  :user/user-login-success
  []
  (fn [{db :db} [_ response]]
    (js/console.log "User login success: response: " + response)))

