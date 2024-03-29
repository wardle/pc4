(ns pc4.snomed.events
  "Events relating to SNOMED CT."
  (:require [re-frame.core :as rf]
            [pc4.server :as srv]
            [taoensso.timbre :refer [debug]]))

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

(defn make-fetch-concept
  "Fetch a SNOMED concept"
  [concept-id]
  [{[:info.snomed.Concept/id concept-id]
    [:info.snomed.Concept/id
     :info.snomed.Concept/descriptions
     {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}])

(rf/reg-event-fx
  ::search []
  (fn [{db :db} [_ id params]]
    (debug "search SNOMED CT" id " params: " params)
    (when-not (get-in db [:authenticated-user :io.jwt/token])
      (debug "SNOMED search event: error : no authenticated user"))
    {:db (-> db
             (update :snomed/search dissoc id)
             (update-in [:errors] dissoc :snomed/search))
     :fx [[:pathom {:params     (make-search params)
                    :token      (get-in db [:authenticated-user :io.jwt/token])
                    :on-success [::handle-search-response id (js/Date.)]
                    :on-failure [::handle-search-failure id]}]]}))

(rf/reg-event-db ::clear-search-results
  (fn [db [_ id]]
    (update-in db [:snomed/search-results] dissoc id)))

(rf/reg-event-fx ::handle-search-response
  []
  (fn [{db :db} [_ id date {results 'info.snomed.Search/search :as response}]]
    (debug "search snomed response: " results)
    ;; be careful to not overwrite results from later autocompletion for this id, which may be returned more quickly
    (let [existing (get-in db [:snomed/search-results id :date])]
      (when (or (not existing) (> date existing))
        {:db (assoc-in db [:snomed/search-results id] {:date date :results results})}))))

(rf/reg-event-fx ::handle-search-failure
  []
  (fn [{:keys [db]} [_ id response]]
    (debug "search snomed failure: response " response)
    {:db (-> db
             (update-in [:snomed/search-results] dissoc id)
             (assoc-in [:errors :snomed/search] "Failed to search for SNOMED: unable to connect to server. Please check your connection and retry."))}))

(rf/reg-event-fx
  ::fetch-concept []
  (fn [{db :db} [_ id concept-id]]
    (debug "fetch concept " id ": " concept-id)
    {:db (-> db
             (update :snomed/fetch-concept dissoc id)
             (update-in [:errors] dissoc :snomed/fetch-concept))
     :fx [[:http-xhrio (srv/make-xhrio-request {:params     (make-fetch-concept concept-id)
                                                :token      (get-in db [:authenticated-user :io.jwt/token])
                                                :on-success [::handle-fetch-concept-response id]
                                                :on-failure [::handle-fetch-concept-failure id]})]]}))


(rf/reg-event-fx ::handle-fetch-concept-response
  []
  (fn [{db :db} [_ id response]]
    (debug "fetch concept response: " response)
    {:db (assoc-in db [:snomed/fetch-concept-result id] (second (first response)))}))

(rf/reg-event-fx ::handle-fetch-concept-failure
  []
  (fn [{:keys [db]} [_ id response]]
    (debug "fetch concept failure: response " response)
    {:db (-> db
             (update-in [:snomed/fetch-concept-result] dissoc id)
             (assoc-in [:errors :snomed/fetch-concept] "Failed to fetch SNOMED concept: unable to connect to server. Please check your connection and retry."))}))



(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (rf/dispatch-sync [:pc4.events/do-login {:username "ma090906'", :password "password"}])
  @(rf/subscribe [:pc4.subs/authenticated-user])
  (make-search {:s "Multi Sclerosis"})
  (make-fetch-concept 24700007)
  (rf/dispatch-sync [::search :wibble {:s "kenya"}])
  @(rf/subscribe [:pc4.snomed.subs/search-results :wibble])
  (tap> @(rf/subscribe [:pc4.snomed.subs/search-results :wibble]))
  (rf/dispatch-sync [::fetch-concept :wibble 24700007])
  (tap> @(rf/subscribe [:pc4.snomed.subs/fetch-result :wibble])))


