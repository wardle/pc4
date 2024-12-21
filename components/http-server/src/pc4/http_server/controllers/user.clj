(ns pc4.http-server.controllers.user
  (:require
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb])
  (:import (java.time LocalDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)))

(defn format-rfc1123
  [^LocalDateTime dt]
  (when dt
    (.format DateTimeFormatter/RFC_1123_DATE_TIME (.atOffset dt ZoneOffset/UTC))))

(def get-user-photo
  "Return a user photograph.
  This endpoint is designed to flexibly handle lookup of a user photograph.
  TODO: fallback to active directory photograph.
  TODO: better handling of caching - including responding to a check for whether can use cached value"
  {:name :user/photo
   :enter
   (fn [ctx]
     (let [rsdb (get-in ctx [:request :env :rsdb])
           system (get-in ctx [:request :path-params :system])
           value (get-in ctx [:request :path-params :value])]
       (log/debug "user photo request" {:system system :value value})
       (if (or (= "patientcare.app" system) (= "cymru.nhs.uk" system))
         (if-let [photo (rsdb/fetch-user-photo rsdb value)]
           (assoc ctx :response {:status  200
                                 :headers {"Content-Type" (:erattachment/mimetype photo)
                                           "Cache-Control" "public, max-age=3600"
                                           "Last-Modified" (format-rfc1123 (:erattachment/creationdate photo))}
                                 :body    (:erattachmentdata/data photo)})
           ctx)
         ctx)))})


(defn session-env
  "Return template environment data for the current session."
  [request]
  (let [authenticated-user (get-in request [:session :authenticated-user])]
    {:csrf-token (csrf/existing-token request)
     :user       {:fullname  (:t_user/full_name authenticated-user)
                  :initials  (:t_user/initials authenticated-user)
                  :menu      [{:title "Change password"
                               :href  "/"}
                              {:title "My profile"
                               :href  "/"}
                              {:title "Sign out"
                               :post  (route/url-for :logout)}]
                  :photo-url (route/url-for :user/photo
                                            {:params {:system "patientcare.app" :value (:t_user/username authenticated-user)}})}}))