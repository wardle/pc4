(ns eldrix.pc4-ward.project.views
  (:require [reitit.frontend.easy :as rfe]
            [re-frame.core :as rf]))


(defn project-home-page []
  (let [route @(rf/subscribe [:eldrix.pc4-ward.subs/current-route])
        project-id (get-in route [:path-params :id])
        project-slug (get-in route [:path-params :slug])]
    [:div.grid.grid-cols-1.md:grid-cols-4.md:gap-4.m-4
     [:div.col-span1.md:col-span-4 "Information about project: id:" project-id " slug: "project-slug]]))
