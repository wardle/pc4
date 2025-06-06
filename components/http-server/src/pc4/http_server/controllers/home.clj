(ns pc4.http-server.controllers.home
  (:require
    [com.wsscode.pathom3.connect.operation :as pco]
    [io.pedestal.http.route :as route]
    [pc4.http-server.pathom :as p]
    [pc4.http-server.web :as web]))

(defn ui-project
  [{:t_project/keys [id title]}]
  {:title title
   :url   (route/url-for :project/home :path-params {:project-id id})})

(pco/defresolver ui-projects
  [{:ui/keys [authenticated-user]}]
  {::pco/input  [{:ui/authenticated-user [{:t_user/active_projects [:t_project/id :t_project/type :t_project/title]}]}]
   ::pco/output [:ui/projects]}
  (let [projects (group-by :t_project/type (:t_user/active_projects authenticated-user))]
    {:ui/projects
     {:clinical (map ui-project (sort-by :t_project/title (:NHS projects)))
      :research (map ui-project (sort-by :t_project/title (:RESEARCH projects)))}}))

(pco/defresolver ui-latest-news
  [{:ui/keys [authenticated-user]}]
  {::pco/input [{:ui/authenticated-user [:t_user/latest_news]}]}
  {:ui/latest-news
   (map (fn [{:t_news/keys [title date_time body author]}]
          {:title title, :author (:t_user/full_name author), :date-time date_time, :body body})
        (:t_user/latest_news authenticated-user))})

(def resolvers [ui-projects ui-latest-news])

(def home-page
  (p/handler
    [:ui/projects :ui/latest-news :ui/navbar :ui/csrf-token]
    (fn [_ {:ui/keys [projects latest-news navbar csrf-token]}]
      (web/ok
        (web/render-file
          "templates/home-page.html"
          {:navbar      navbar
           :csrf-token  csrf-token
           :search-url  (route/url-for :patient/search)
           :projects    projects
           :latest-news latest-news})))))


