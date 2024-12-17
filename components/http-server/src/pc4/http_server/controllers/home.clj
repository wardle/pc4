(ns pc4.http-server.controllers.home
  (:require
   [clojure.string :as str]
   [io.pedestal.http.csrf :as csrf]
   [io.pedestal.http.route :as route]
   [pc4.http-server.html :as html]
   [pc4.http-server.ui :as ui]
   [rum.core :as rum]
   [selmer.parser :as selmer]))

(defn home [request]
  (let [authenticated-user (get-in request [:session :authenticated-user])]
    (html/ok
      (selmer/render-file "home-page.html"
                          {:csrf-token (csrf/existing-token request)
                           :user {:fullname (str/join " " [(:t_user/title authenticated-user)
                                                           (:t_user/first_names authenticated-user)
                                                           (:t_user/last_name authenticated-user)])

                                  :initials (:t_user/initials authenticated-user)
                                  :menu [{:title "Change password"
                                          :href "/"}
                                         {:title "My profile"
                                          :href "/"}
                                         {:title "Sign out"
                                          :post (route/url-for :logout)}]
                                  :photo-url (route/url-for :user/photo
                                                            {:params {:system "patientcare.app" :value (:t_user/username authenticated-user)}})}}))))


