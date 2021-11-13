(ns eldrix.pc4-ward.lookups.events
  (:require [re-frame.core :as rf]))

(def all-lookups
  [{:com.eldrix.rsdb/all-ms-diagnoses [:t_ms_diagnosis/name :t_ms_diagnosis/id]}
   {:com.eldrix.rsdb/all-ms-event-types [:t_ms_event_type/id
                                         :t_ms_event_type/abbreviation
                                         :t_ms_event_type/name]}])


;; need to trigger this on login eventually, as there are not that many
;; lookups like this, and they don't change
(rf/reg-event-fx
  ::fetch
  (fn [{db :db} _]
    (js/console.log "fetching lookups")
    {:fx [[:pathom {:params     all-lookups
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-fetch-lookups]
                    :on-failure [::handle-fetch-lookups-failure]}]]}))

(rf/reg-event-fx   ;; e.g. stuff MS diagnoses into db at [:lookups :com.eldrix.rsdb/all-ms-diagnoses]
  ::handle-fetch-lookups
  []
  (fn [{db :db} [_ data]]
    (tap> {:lookup-data data})
    {:db (update-in db [:lookups] #(merge % data))}))

