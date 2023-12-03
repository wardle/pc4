(ns pc4.project.downloads
  (:require [pc4.project.home :as project]
            [pc4.ui :as ui]))

(def download-page
  {:tx
   (fn [params] [{[:t_project/id (get-in params [:path :project-id])]
                  [:t_project/id :t_project/title :t_project/pseudonymous :t_project/type]}])
   :view
   (fn [_ [project]]
     (project/layout
       project
       {:selected-id :reports}
       [ui/ui-panel
        [ui/ui-title {:title    "Downloads / reports"
                      :subtitle "There are currently no downloads for this service / project"}]]))})