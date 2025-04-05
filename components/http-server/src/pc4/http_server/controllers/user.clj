(ns pc4.http-server.controllers.user
  (:require
    [com.wsscode.pathom3.connect.operation :as pco]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.http-server.web :as web]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb])
  (:import (java.time LocalDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)))

(pco/defresolver authenticated-user
  [{:keys [request] :as env} _]
  {::pco/output [{:ui/authenticated-user [:t_user/id]}]}
  {:ui/authenticated-user (get-in request [:session :authenticated-user])})

(pco/defresolver authorization-manager
  [{:keys [request]} _]
  {:ui/authorization-manager (:authorization-manager request)})

(pco/defresolver csrf-token
  [{:keys [request] :as env} _]
  {:ui/csrf-token (csrf/existing-token request)})

(pco/defresolver user-photo-url
  [env {:t_user/keys [username]}]
  {:t_user/photo_url (route/url-for :user/photo
                                    {:params {:system "patientcare.app" :value username}})})

(pco/defresolver navbar
  [{:ui/keys [csrf-token authenticated-user current-project]}]
  {::pco/input  [:ui/csrf-token
                 {:ui/authenticated-user [:t_user/id :t_user/initials :t_user/full_name :t_user/photo_url]}
                 {:ui/current-project [:t_project/title]}]
   ::pco/output [:ui/navbar]}
  {:ui/navbar
   {:fullname   (:t_user/full_name authenticated-user)
    :initials   (:t_user/initials authenticated-user)
    :project    (:t_project/title current-project)
    :menu       [{:title "Change password"
                  :href  "/"}
                 {:title "My profile"
                  :href  "/"}
                 {:title "Sign out"
                  :post  (route/url-for :user/logout!)}]
    :photo-url  (:t_user/photo_url authenticated-user)
    :csrf-token csrf-token}})

(def resolvers [authenticated-user authorization-manager csrf-token user-photo-url navbar])

(defn format-rfc1123
  [^LocalDateTime dt]
  (when dt
    (.format DateTimeFormatter/RFC_1123_DATE_TIME (.atOffset dt ZoneOffset/UTC))))

(defn user-photo
  "Return a user photograph.
  This endpoint is designed to flexibly handle lookup of a user photograph.
  TODO: fallback to active directory photograph.
  TODO: better handling of caching - including responding to a check for whether can use cached value"
  [request]
  (let [rsdb (get-in request [:env :rsdb])
        user-id (some-> (get-in request [:path-params :user-id]) parse-long)]
    (when user-id
      (when-let [photo (rsdb/user-id->photo rsdb user-id)]
        {:status  200
         :headers {"Content-Type"  (:erattachment/mimetype photo)
                   "Cache-Control" "public, max-age=3600"
                   "Last-Modified" (format-rfc1123 (:erattachment/creationdate photo))}
         :body    (:erattachmentdata/data photo)}))))

(defn profile [request])

(defn messages [request])

(defn send-message [request])

(defn downloads
  [request])

(defn search [request])

