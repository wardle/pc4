(ns pc4.http-server.controllers.home
  (:require
    [clojure.string :as str]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.http-server.controllers.user :as user]
    [pc4.http-server.html :as html]
    [pc4.rsdb.interface :as rsdb]
    [selmer.parser :as selmer]))

(defn home-page
  [request]
  (let [authenticated-user (get-in request [:session :authenticated-user])
        rsdb (get-in request [:env :rsdb])
        projects (group-by :t_project/type (rsdb/user->projects rsdb (:t_user/username authenticated-user)))
        project-fn (fn [{:t_project/keys [id title]}]
                     {:title title
                      :url   (route/url-for :project/home :path-params {:project-id id})})
        news (rsdb/user->latest-news rsdb (:t_user/username authenticated-user))
        news-fn (fn [{:t_news/keys [title date_time body] :t_user/keys [first_names last_name]}]
                  {:title title, :author (str first_names " " last_name), :date-time date_time, :body body})]
    (html/ok
      (selmer/render-file
        "home-page.html"
        (assoc (user/session-env request)
          :projects
          {:clinical (map project-fn (sort-by :t_project/title (:NHS projects)))
           :research (map project-fn (sort-by :t_project/title (:RESEARCH projects)))}
          :news (map news-fn news))))))


