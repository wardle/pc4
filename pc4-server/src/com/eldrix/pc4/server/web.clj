(ns com.eldrix.pc4.server.web
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [com.eldrix.pc4.server.config :as config]
    [ring.util.response :as response]
    [rum.core :as rum])
  (:import (java.net URLEncoder)))

(defn encode-uri-component [s]
  (-> (URLEncoder/encode ^String s "UTF-8")
      (str/replace #"\+" "%20")
      (str/replace #"\%21" "!")
      (str/replace #"\%27" "'")
      (str/replace #"\%28" "(")
      (str/replace #"\%29" ")")
      (str/replace #"\%7E" "~")))

(defn build-url [path query]
  (str
    path
    "?"
    (str/join "&"
              (map
                (fn [[k v]]
                  (str (name k) "=" (encode-uri-component v)))
                query))))
(def empty-success-response
  {:status  200
   :headers {"content-type" "text/plain"}})

(defn redirect
  ([{:keys [url path query session logout?]}]
   (cond-> {:status  302
            :headers {"Location"
                      (cond
                        url url
                        (and path query) (build-url path query)
                        path path
                        :else "/")}}
           logout? (assoc :session nil)
           session (assoc :session session))))


(defn resource [name]
  (slurp (io/resource (str "public/" name))))

(defn first-file [& paths]
  (reduce
    (fn [resp path]
      (let [file (io/file path)]
        (if (.exists file)
          (reduced (response/file-response path))
          resp)))
    {:status 404} paths))

(defn style [name]
  [:link {:rel "stylesheet" :type "text/css" :href name}]
  #_(let [content (slurp (io/resource (str "www/" name)))]
      [:style {:type "text/css" :dangerouslySetInnerHTML {:__html content}}]))

(rum/defc page
  [opts & children]
  (let [{:keys [title page subtitle styles scripts]
         :or   {title "pc4"
                page  :other}} opts]
    [:html
     [:head
      [:meta {:http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title title]
      (style "styles.css")
      (for [css styles]
        (style css))
      [:script {:dangerouslySetInnerHTML {:__html (resource "scripts.js")}}]
      (for [script scripts]
        [:script {:dangerouslySetInnerHTML {:__html (resource script)}}])]
     [:body children]]))

(defn html-response
  ([component]
   (html-response {} component))
  ([opts component]
   (merge {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (str "<!DOCTYPE html>\n" (rum/render-static-markup component))}
          opts)))
