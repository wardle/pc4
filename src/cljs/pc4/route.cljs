(ns pc4.route
  (:require [bidi.bidi :as bidi]
            [com.fulcrologic.fulcro.data-fetch :as df]
            [com.fulcrologic.fulcro.mutations :refer [defmutation]]
            [com.fulcrologic.fulcro.raw.components :as raw]
            [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
            [pc4.app :refer [SPA]]
            [pushy.core :as pushy]
            [taoensso.timbre :as log]))

(def routes
  "The routing table maps between HTML5 URLs and our application dynamic routing."
  ["/" {""                ::home
        "project/"        {[:project-id]                                 ::project-home
                           [:project-id "/patient/" :patient-identifier] ::project-patient}
        "patient/"        {[:patient-identifier] ::patient-home}
        "user/"           {[:user-id] ::user-profile}
        "change-password" ::change-password}])

(defn match-route
  [url]
  (bidi/match-route routes url))

(defn dispatch-route
  [{:keys [handler route-params] :as matched-route}]
  (log/info "matched route" matched-route)
  (case handler
    ::home
    (dr/change-route! @SPA ["home"])
    ::project-home
    (dr/change-route! @SPA ["projects" (:project-id route-params) "home"])
    ::project-patient
    (do (js/console.log (str "loading patient in context of project" {:project (:project-id route-params)
                                                                      :patient (:patient-identifier route-params)}))
        (df/load! @SPA [:t_project/id (parse-long (:project-id route-params))]
                  (raw/nc [:t_project/id :t_project/title :t_project/type])
                  {:target [:ui/current-project]})
        (dr/change-route! @SPA ["pt" (:patient-identifier route-params) "home"]))
    ::patient-home
    (dr/change-route! @SPA ["pt" (:patient-identifier route-params) "home"])
    ::user-profile
    (dr/change-route! @SPA ["user" (:user-id route-params) "profile"])
    ::change-password
    (dr/change-route! @SPA ["change-password"])
    ;; otherwise, fallback to the home
    (do (log/info "No match for route" matched-route)
        (dr/change-route! @SPA ["home"]))))

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
  (route-to! ::project-home {:project-id 5})
  (pushy/set-token! history (bidi/path-for routes :home))
  (pushy/set-token! history (bidi/path-for routes :project/home {:id 5}))
  (route-to! :home)
  (route-to! :project/home {:id 5}))
