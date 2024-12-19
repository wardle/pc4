(ns pc4.http-server.controllers.home
  (:require
    [clojure.string :as str]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
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
                      :url (route/url-for :project/home :path-params {:project-id id})})
        news (rsdb/user->latest-news rsdb (:t_user/username authenticated-user))
        news-fn (fn [{:t_news/keys [title date_time body] :t_user/keys [first_names last_name]}]
                  {:title title, :author (str first_names " " last_name), :date-time date_time, :body body})]
    (html/ok
      (selmer/render-file
        "home-page.html"
        {:csrf-token (csrf/existing-token request)
         :user       {:fullname (:t_user/full_name authenticated-user)
                      :initials (:t_user/initials authenticated-user)
                      :menu      [{:title "Change password"
                                   :href  "/"}
                                  {:title "My profile"
                                   :href  "/"}
                                  {:title "Sign out"
                                   :post  (route/url-for :logout)}]
                      :photo-url (route/url-for :user/photo
                                                {:params {:system "patientcare.app" :value (:t_user/username authenticated-user)}})}
         :projects
         {:clinical (map project-fn (sort-by :t_project/title (:NHS projects)))
          :research (map project-fn (sort-by :t_project/title (:RESEARCH projects)))}
         :news (map news-fn news)}))))


