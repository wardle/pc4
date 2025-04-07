(ns pc4.http-server.controllers.user
  (:require
    [clojure.string :as str]
    [com.wsscode.pathom3.connect.operation :as pco]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.ui :as ui]
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
                  :href  (route/url-for :user/change-password :path-params {:user-id (:t_user/id authenticated-user)})}
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

;; Removed UI component functions in favor of Selmer templates


(def change-password
  "Handler for the change password page."
  (pathom/handler
    [:ui/csrf-token
     {:ui/authenticated-user [:t_user/id :t_user/authentication_method]}]
    (fn [request {:ui/keys [csrf-token authenticated-user]}]
      (let [user-id (:t_user/id authenticated-user)
            user-auth-method (:t_user/authentication_method authenticated-user)
            ;; Create a closure to render the password page with common parameters
            action (route/url-for :user/process-change-password! :path-params {:user-id user-id})
            render-page (fn [params]
                          (web/ok
                            (web/render-file "templates/change-password.html"
                                             (merge {:title "Change Password"
                                                    :csrf-token csrf-token
                                                    :authentication-method user-auth-method
                                                    :action action}
                                                   params))))]
        (log/info "change password page" {:user-id user-id :auth-method user-auth-method})
        (render-page {})))))

(def process-change-password
  "Process the change password form submission."
  (pathom/handler
    [:ui/csrf-token
     {:ui/authenticated-user [:t_user/id :t_user/authentication_method]}]
    (fn [{:keys [env form-params path-params] :as request} {:ui/keys [csrf-token authenticated-user]}]
      (let [rsdb (:rsdb env)
            user-id (some-> (get path-params :user-id) parse-long)
            authenticated-user-id (:t_user/id authenticated-user)
            user-auth-method (:t_user/authentication_method authenticated-user)
            current-password (:current-password form-params)
            new-password (:new-password form-params)
            confirm-password (:confirm-password form-params)
            ;; Create a closure to render the password page with common parameters
            action (route/url-for :user/process-change-password! :path-params {:user-id user-id})
            render-page (fn [params]
                          (web/ok
                            (web/render-file "templates/change-password.html"
                                             (merge {:title "Change Password"
                                                    :csrf-token csrf-token
                                                    :action action
                                                    :authentication-method user-auth-method}
                                                   params))))]
        ;; Safety check - users can only change their own password unless they're admins
        (when-not (= user-id authenticated-user-id)
          (log/warn "Attempt to change password for another user"
                    {:authenticated-user-id authenticated-user-id :target-user-id user-id})
          (web/forbidden "You are not authorized to change this user's password"))

        (cond
          ;; check valid new password
          (< (count new-password) 8)
          (render-page {:error "Invalid new password; must be a minimum 8 characters - longer passwords are better"})

          ;; same as old password?
          (= current-password new-password)
          (render-page {:error "Invalid new password; it is the same as your current password"})

          ;; Check if passwords match
          (not= new-password confirm-password)
          (render-page {:error "New passwords do not match"})

          ;; Try to update the password
          :else
          (let [user (rsdb/user-by-id rsdb user-id {:with-credentials true})
                authenticated? (rsdb/authenticate rsdb user current-password)]
            (if authenticated?
              (do
                ;; Update the password
                (rsdb/save-password! rsdb user new-password)
                (log/info "Password changed successfully for user" {:user-id user-id})

                ;; Show success message
                (render-page {:success "Your password has been changed successfully."}))

              ;; Current password is incorrect
              (render-page {:error "Current password is incorrect"}))))))))

(defn downloads
  [request])

(defn search [request])

