(ns pc4.http-server.controllers.user.select
  "User selection controller with mode-based switching. State is represented
  either as form values or as an encoded 'config' based on the parameters used
  initially, and this latter state is propagated unchanged through the lifecycle
  of the user interaction."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [io.pedestal.http.csrf :as csrf]
    [io.pedestal.http.route :as route]
    [pc4.http-server.pathom :as pathom]
    [pc4.http-server.ui :as ui]
    [pc4.http-server.web :as web]
    [pc4.log.interface :as log]
    [pc4.rsdb.interface :as rsdb]))

(s/def ::id string?)
(s/def ::name string?)
(s/def ::label (s/nilable string?))
(s/def ::required boolean?)
(s/def ::disabled boolean?)
(s/def ::multiple boolean?)
(s/def ::user-id int?)
(s/def ::full-name string?)
(s/def ::job-title string?)
(s/def ::user (s/keys :req-un [::user-id ::full-name ::job-title]))
(s/def ::selected (s/nilable (s/or :single ::user :multiple (s/coll-of ::user))))
(s/def ::params (s/keys :req-un [(or ::id ::name)]
                        :opt-un [::label ::required ::disabled ::multiple ::selected]))

(defn make-config
  [{component-name :name, :keys [id label placeholder multiple selected disabled required], :as params}]
  (when-not (s/valid? ::params params)
    (log/error "invalid parameters" (s/explain-data ::params params))
    (throw (ex-info "invalid parameters" (s/explain-data ::params params))))
  (let [id# (or id component-name (throw (ex-info "invalid parameters: must specify id or name" params)))]
    {:id            id#
     :label         label
     :name          (or component-name id)
     :disabled      (boolean disabled)
     :required      (boolean required)
     :selected      selected
     :multiple      (boolean multiple)
     :placeholder   (or placeholder (if multiple "Choose users..." "Choose user..."))
     :target        (str id# "-target")
     :search-target (str id# "-search-target")
     :action-key    (str id# "-action")
     :mode-key      (str id# "-mode")}))

(defn make-context
  [config request]
  (assoc config
    :url (route/url-for :user/select)
    :search-url (route/url-for :user/search)
    :csrf-token (csrf/existing-token request)               ;; returns nil if no request
    :hx-vals (web/write-hx-vals :data config)
    :config (pr-str config)))

(defn render*
  ([template config]
   (render* template config nil))
  ([template config request]
   [:div
    {:dangerouslySetInnerHTML
     {:__html
      (web/render-file template (make-context config request))}}]))

(defn ui-select-user
  "A 'user selection control'. Displays current selection and allows 'click to
  edit' functionality with a modal dialog.
  Parameters:
  - :id
  - :name
  - :disabled
  - :multiple
  - :selected
  - :placeholder
  - :label"
  [params]
  (log/debug "rendering display:" params)
  (let [config (make-config params)]
    (render* "templates/user/select/display.html" config)))

(defn ui-select-users
  [params]
  (ui-select-user (assoc params :multiple true)))

(defn user-query [user-id]
  {:pathom/entity {:t_user/id user-id}
   :pathom/eql    [:t_user/id :t_user/full_name :t_user/job_title]})

(def user-properties
  [:t_user/id :t_user/last_name :t_user/first_names :t_user/full_name :t_user/job_title :t_user/active?])

(defn ^:private make-user
  [{:t_user/keys [id full_name job_title] :as user}]
  (when user {:user-id id :full-name full_name :job-title job_title}))

(defn user-select-handler
  [{:keys [env form-params] :as request}]
  (let [{:keys [target label multiple required selected] :as config} (web/read-hx-vals :data form-params)
        trigger (web/hx-trigger request)
        user-id (some-> trigger parse-long)                 ;; trigger is sometimes a user-id of the item selected
        modal-action {:hx-post (route/url-for :user/select), :hx-target (str "#" target)
                      :hx-vals (web/write-hx-vals :data config)}]
    (log/debug "user-select-handler" {:trigger trigger :user-id user-id :config config})
    (clojure.pprint/pprint form-params)
    (web/ok
      (web/render
        (cond
          ;; user has cancelled, just show control, essentially closing modal dialog
          (= "cancel" trigger)
          (ui-select-user config)

          ;; user has chosen to clear the selection
          (= "clear" trigger)
          (ui-select-user (dissoc config :selected))

          ;; user has selected an item -> return to rendering selected items with no modal dialog
          (and user-id (not multiple))
          (ui-select-user (assoc config :selected (make-user (pathom/process env request (user-query user-id)))))

          ;; user opening modal dialog to select multiple users
          multiple
          (ui/ui-modal {:title label :hidden? false} (render* "templates/user/select/choose-multiple.html" config request))

          ;; user opening modal dialog to select a single user
          (not multiple)
          (ui/ui-modal {:title   label :hidden? false
                        :actions [(assoc modal-action :id :clear :title "Clear" :hidden? (or required (empty? selected)))
                                  (assoc modal-action :id :cancel :title "Cancel")]}
                       (render* "templates/user/select/choose-single.html" config request)))))))

(defn make-filter-fn
  "Return a function that filters users by a search string."
  [s]
  ;; TODO: tokenise and use tokens for filtering instead for more intuitive search
  (if (str/blank? s)
    (constantly true)
    (let [s' (str/lower-case s)]
      (fn [{:keys [id full-name job-title]}]
        (or (str/includes? (str/lower-case full-name) s')
            (str/includes? (str/lower-case job-title) s'))))))

(defn search-colleagues
  [env request user]
  (:t_user/colleagues
    (pathom/process env request
                    {:pathom/entity user
                     :pathom/eql    [{:t_user/colleagues user-properties}]})))

(defn search-all-users
  [env request s]
  (when-not (str/blank? s)
    (get (pathom/process env request
                         [{(list 'pc4.rsdb/search-users {:s s :limit 500})
                           user-properties}])
         'pc4.rsdb/search-users)))

(defn minimum-chars
  "Return 's' when and only when it meets length 'n'."
  [s n]
  (when (>= (count s) n) s))

(defn user-search-handler
  "Return a rendered list of users
  Parameters:
  - s: search string
  - mode: 'colleagues' or 'all-users'."                     ;;TODO: add 'register user' option
  [{:keys [env form-params session] :as request}]
  (let [user (:authenticated-user session)
        {:keys [mode-key] :as config} (web/read-hx-vals :data form-params)
        mode (keyword (get form-params (keyword mode-key)))
        s (some-> (:s form-params) str/trim (minimum-chars 3))
        users (->> (case mode
                     :colleagues (search-colleagues env request user)
                     :all-users (search-all-users env request s))
                   (sort-by (juxt (comp str/trim :t_user/last_name) :t_user/first_names))
                   (map make-user)
                   (filter (make-filter-fn s))
                   distinct)]
    (log/debug "user search" {:s s :mode mode})
    (web/ok
      (web/render-file "templates/user/select/list-single.html"
                       (assoc (make-context config request)
                         :users users, :s s, :mode mode)))))