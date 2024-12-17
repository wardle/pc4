(ns pc4.http-server.html
  (:require [rum.core :as rum]))

(defn render
  [src]
  (rum/render-static-markup src))

(defn html5
  [{:keys [title]} content]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body
   (str "<!DOCTYPE html>"
        (render
         [:html {:lang "en"}
          [:head
           [:title (or title "pc4")]
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
           [:script {:src "/js/htmx-2.0.0.min.js"}]
           [:script {:src "https://cdn.tailwindcss.com"}]]
          [:body content]]))})

(defn ok
  [content]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body content})
