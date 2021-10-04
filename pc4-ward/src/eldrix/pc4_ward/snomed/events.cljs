(ns eldrix.pc4-ward.snomed.events
  "Events relating to SNOMED CT."
  (:require [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.server :as srv]))

(defn make-search
  "Create a SNOMED search"
  [{:keys [s constraint max-hits] :as params}]
  [{(list 'info.snomed.Search/search
          params)
    [:info.snomed.Concept/id
     :info.snomed.Description/id
     :info.snomed.Description/term
     {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
     :info.snomed.Concept/active]}])

(rf/reg-event-fx
  ::search []
  (fn [{db :db} [_ id params]]
    (js/console.log "search SNOMED CT" id " params: " params)
    (when-not (get-in db [:authenticated-user :io.jwt/token])
      (js/console.log "SNOMED search event: error : no authenticated user"))
    {:db (-> db
             (update :snomed/search dissoc :id)
             (update-in [:errors] dissoc ::search))
     :fx [[:http-xhrio (srv/make-xhrio-request {:params     (make-search params)
                                                :token      (get-in db [:authenticated-user :io.jwt/token])
                                                :on-success [::handle-search-response id (js/Date.)]
                                                :on-failure [::handle-search-failure id]})]]}))




(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (rf/dispatch-sync [:eldrix.pc4-ward.user.events/do-login "wales.nhs.uk" "ma090906'" "password"])
  @(rf/subscribe [:eldrix.pc4-ward.user.subs/authenticated-user])
  (make-search {:s "Multi Sclerosis"})
  (rf/dispatch-sync [::search :wibble {:s "Mult sclero"}])


  (rf/dispatch-sync [::search-uk :fred {:n "penylan" :roles "RO72" :limit 10 :from-location {:postcode "CF14 4XW"}}])
  (rf/dispatch-sync [::search-uk :fred {:n "royal glam" :roles "RO148" :from-location {:postcode "CF14 4XW"}}])
  (def result @(rf/subscribe [:eldrix.pc4-ward.org.subs/search-results :fred]))
  (tap> result)
  (rf/dispatch-sync [:eldrix.pc4-ward.user.events/do-login "wales.nhs.uk" "ma090906" "password"])
  @(rf/subscribe [:eldrix.pc4-ward.user.subs/authenticated-user])
  )

