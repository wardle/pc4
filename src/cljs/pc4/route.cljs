(ns pc4.route
  (:require [bidi.bidi :as bidi]
            [pc4.app :refer [SPA]]
            [com.fulcrologic.fulcro.mutations :refer [defmutation]]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pushy.core :as pushy]
            [taoensso.timbre :as log]))

(def routes
  "The routing table maps a HTML5 URL and our application dynamic routing."
  ["/" {""         ::home
        "project/" {[:id "/home"]                   ::project-home
                    [:id "/team"]                   ::project-team
                    [:id "/find-by-pseudonym"]      ::project-find-by-pseudonym
                    [:id "/register-pseudonymous"]  ::project-register-pseudonymous
                    [:id "/register-by-nhs-number"] ::project-register-by-nnn
                    [:id "/downloads"]              ::project-downloads}
        "patient/" {[:id "/home"]        ::patient-home
                    [:id "/diagnoses"]   ::patient-diagnoses
                    [:id "/medications"] ::patient-medications
                    [:id "/relapses"]    ::patient-relapses
                    [:id "/encounters"]  ::patient-encounters
                    [:id "/results"]     ::patient-results
                    [:id "/admissions"]  ::patient-admissions}}])

(defn match-route
  [url]
  (bidi/match-route routes url))

(defn dispatch-route
  [{:keys [handler route-params] :as matched-route}]
  (log/info "matched route" matched-route)
  (let [id (:id route-params)]
    (case handler
      ::home (dr/change-route! @SPA ["home"])
      ::project-home (dr/change-route! @SPA ["projects" id "home"])
      ::patient-home (dr/change-route! @SPA ["pt" id "home"])
      ::patient-diagnoses (dr/change-route! @SPA ["pt" id "diagnoses"])
      ::patient-medications (dr/change-route! @SPA ["pt" id "medications"])
      ::patient-relapses (dr/change-route! @SPA ["pt" id "neuroinflammatory"])
      ::patient-encounters (dr/change-route! @SPA ["pt" id "encounters"])
      ::patient-results (dr/change-route! @SPA ["pt" id "results"])
      ::patient-admissions (dr/change-route! @SPA ["pt" id "admissions"])
      ;; otherwise, fallback to the home
      (do (log/info "No match for route" matched-route)
          (dr/change-route! @SPA ["home"])))))

(defonce history
  (pushy/pushy dispatch-route match-route))

(defn start! []
  (log/info "starting HTML5 routing")
  (pushy/start! history))

(defn route-to!
  ([handler]
   (route-to! handler {}))
  ([handler params]
   (if-let [token (bidi/path-for routes handler params)]
     (pushy/set-token! history token)
     (log/error "Unable to match route" {:handler handler :params params}))))

(defmutation route-to
  [{:keys [handler params]}]
  (action
   [_]
   (route-to! handler params)))

(comment
  (bidi/path-for routes :home)
  (route-to! :home)
  (route-to! ::project-home {:id 5})
  (route-to! :project/register-by-nnn {:id 5})
  (pushy/set-token! history (bidi/path-for routes :home))
  (pushy/set-token! history (bidi/path-for routes :project/home {:id 5}))
  (route-to! :home)
  (route-to! :project/home {:id 5}))
