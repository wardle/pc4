(ns eldrix.pc4-ward.org.events
  (:require [re-frame.core :as rf]
            [eldrix.pc4-ward.server :as srv]))

(rf/reg-event-fx
  ::fetch []
  (fn [{db :db} [_ system identifier]]
    (js/console.log "fetch organization " system identifier)
    {:db (-> db
             (update :organization/fetch dissoc )
             (update-in [:errors] dissoc ::login))
     :fx [[:http-xhrio (srv/make-xhrio-request {:params     (srv/make {:pas-identifier identifier})
                                                :token      (get-in db [:authenticated-user :io.jwt/token])
                                                :on-success [::handle-fetch-response]
                                                :on-failure [::handle-fetch-failure]})]]}))