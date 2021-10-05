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


(rf/reg-event-db ::clear-search-results
  (fn [db [_ id]]
    (update-in db [:snomed/search-results] dissoc id)))

(rf/reg-event-fx ::handle-search-response
  []
  (fn [{db :db} [_ id date {results 'info.snomed.Search/search :as response}]]
    (js/console.log "search snomed response: " results)
    ;; be careful to not overwrite results from later autocompletion, which may be returned more quickly
    (let [existing (get-in db [:snomed/search-results id :date])]
      (when (or (not existing) (> date existing))
        {:db (assoc-in db [:snomed/search-results id] {:date date :results results})}))))

(rf/reg-event-fx ::handle-search-failure
  []
  (fn [{:keys [db]} [_ id response]]
    (js/console.log "search snomed failure: response " response)
    {:db (-> db
             (update-in [:snomed/search-results] dissoc id)
             (assoc-in [:errors :snomed/search] "Failed to search for SNOMED: unable to connect to server. Please check your connection and retry."))}))



(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (rf/dispatch-sync [:eldrix.pc4-ward.user.events/do-login "wales.nhs.uk" "ma090906'" "password"])
  @(rf/subscribe [:eldrix.pc4-ward.user.subs/authenticated-user])
  (make-search {:s "Multi Sclerosis"})
  (rf/dispatch-sync [::search :wibble {:s "kenya"}])
  (tap> @(rf/subscribe [:eldrix.pc4-ward.snomed.subs/search-results :wibble]))



  )

