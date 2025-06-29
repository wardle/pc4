(ns pc4.http-server.controllers.user.select
  "User selection controller with mode-based switching. State is represented
  either as form values or as an encoded 'config' based on the parameters used
  initially, and this latter state is propagated unchanged through the lifecycle
  of the user interaction."
  (:require
    [clojure.edn :as edn]
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
     :selected      selected                                ;; original selection before modal opened
     :selected-key  (str id# "-selected")                   ;; form keeps track of 'new' selection using this id
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
  [{:t_user/keys [id first_names last_name full_name job_title] :as user}]
  (when user {:user-id id :full-name full_name :last-name last_name :first-names first_names :job-title job_title}))

(defn user-select-handler
  [{:keys [env form-params] :as request}]
  (let [{:keys [target label multiple required selected selected-key] :as config} (web/read-hx-vals :data form-params)
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

          (= "save" trigger)
          (ui-select-user (assoc config :selected (some-> (get form-params (keyword selected-key)) edn/read-string)))

          ;; open modal dialog to select multiple users
          multiple
          (ui/ui-modal {:title   label :hidden? false :size :full
                        :actions [(assoc modal-action :id :save :title "Save" :role :primary)
                                  (assoc modal-action :id :cancel :title "Cancel")]}
                       (render* "templates/user/select/choose-multiple.html" config request))

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

(def by-name
  (juxt (comp (fnil str/trim "") :last-name) :first-names :full-name))

(defn add-user-action
  [user action]
  (assoc user :action (pr-str [action user])))

(defn user-search-handler
  "Return a rendered list of users
  Parameters:
  - s: search string
  - mode: 'colleagues' or 'all-users'."                     ;;TODO: add 'register user' option
  [{:keys [env form-params session] :as request}]
  (let [authenticated-user (:authenticated-user session)
        {:keys [mode-key multiple selected selected-key] :as config} (web/read-hx-vals :data form-params) ;; selected represents 'original' list of selected users when modal first opened
        mode (keyword (get form-params (keyword mode-key))) ;; :colleagues or :all-users
        s (some-> (:s form-params) str/trim (minimum-chars 3))]
    (web/ok
      (if multiple
        (let [trigger (some-> (web/hx-trigger-name request) edn/read-string) ;; when we trigger adding or removing user and are refreshing, we will have an action and a user clicked
              [action user] (when (vector? trigger) trigger)      ;; if trigger encodes clojure data, destructure action and user
              ;; generate a list of 'currently selected' users - either from 'original list', or from form during editing
              selected (or (some-> (get form-params (keyword selected-key)) edn/read-string) selected)
              selected-users (->> (case action
                                    :add (conj selected user)
                                    :remove (remove #(= (:user-id %) (:user-id user)) selected)
                                    selected)
                                  (sort-by by-name))
              ;; generate a set of 'currently selected' user ids, to make it easy to remove from the 'available' list.
              selected-ids (into #{} (map :user-id) selected-users)
              ;; generate a list of 'available users'... we remove all 'selected' users.
              users (->> (case mode
                           :colleagues (search-colleagues env request authenticated-user)
                           :all-users (search-all-users env request s))
                         (map make-user)
                         (sort-by by-name)
                         (remove #(selected-ids (:user-id %)))
                         (filter (make-filter-fn s))
                         distinct)]
          (web/render-file "templates/user/select/list-multiple.html"
                           (assoc (make-context config request)
                             :selected (map #(add-user-action % :remove) selected-users)
                             :selected-data (pr-str selected-users)
                             :users (map #(add-user-action % :add) users)
                             :s s, :mode mode)))
        (let [;; generate a list of 'available users' - no need to filter out selected users
              users (->> (case mode
                           :colleagues (search-colleagues env request authenticated-user)
                           :all-users (search-all-users env request s))
                         (map make-user)
                         (sort-by by-name)
                         (filter (make-filter-fn s))
                         distinct)]
          (web/render-file "templates/user/select/list-single.html"
                           (assoc (make-context config request)
                             :selected selected
                             :users users
                             :s s, :mode mode)))))))