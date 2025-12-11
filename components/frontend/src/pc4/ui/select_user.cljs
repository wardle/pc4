(ns pc4.ui.select-user
  "Fulcro-based user selection components.
   Provides single and multiple user selection with modal dialog interface.

   Usage:
   ```clojure
   ;; Single user selection
   (ui-select-user
     {:db/id :responsible-clinician}
     {:selected    current-user
      :label       \"Responsible Clinician\"
      :placeholder \"Choose clinician...\"
      :onSelect    (fn [user] ...)})

   ;; Multiple user selection
   (ui-select-users
     {:db/id :team-members}
     {:selected    [user1 user2]
      :label       \"Team Members\"
      :onSelect    (fn [users] ...)})
   ```"
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :as dom :refer [div span]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [goog.functions :as gf]
    [pc4.ui.core :as ui]))

;;
;; ============================================================================
;; Shared Utility Functions
;; ============================================================================
;;

(defn select-user-ident
  "Returns the ident for a SelectUser component. Can be passed a map of props or a raw ID."
  [id-or-props]
  (if (map? id-or-props)
    [:component/id (:db/id id-or-props)]
    [:component/id id-or-props]))

(defn sort-users
  "Sort users by last_name then first_names (case-insensitive, trimmed)."
  [users]
  (sort-by (fn [{:t_user/keys [last_name first_names]}]
             [(str/lower-case (str/trim (or last_name "")))
              (str/lower-case (str/trim (or first_names "")))])
           users))

(defn dedupe-users
  "Deduplicate users by :t_user/id, preserving first occurrence."
  [users]
  (->> users
       (reduce (fn [acc user]
                 (if (contains? acc (:t_user/id user))
                   acc
                   (assoc acc (:t_user/id user) user)))
               {})
       vals))

(defn prepare-users
  "Deduplicate users by :t_user/id and sort by last_name then first_names."
  [users]
  (-> users dedupe-users sort-users))

(defn filter-users
  "Filter users by search text. Matches against full_name and job_title (case-insensitive).
   Also deduplicates and sorts the results."
  [users search-text]
  (let [prepared (prepare-users users)]
    (if (str/blank? search-text)
      prepared
      (let [search-lower (str/lower-case (str/trim search-text))]
        (filter (fn [{:t_user/keys [full_name job_title]}]
                  (or (and full_name (str/includes? (str/lower-case full_name) search-lower))
                      (and job_title (str/includes? (str/lower-case job_title) search-lower))))
                prepared)))))

(defn filter-responsible-clinicians
  "Filter users to only those who can be responsible clinicians.
   When only-responsible? is false, returns users unchanged."
  [users only-responsible?]
  (if only-responsible?
    (filter :t_job_title/can_be_responsible_clinician users)
    users))

;;
;; ============================================================================
;; User Item Component (for query and display in search results)
;; ============================================================================
;;

(defsc UserItem
  "Component representing a user item for query and display purposes.
   Used for colleagues, search results, and selected users."
  [_ _]
  {:ident :t_user/id
   :query [:t_user/id
           :t_user/username
           :t_user/title
           :t_user/first_names
           :t_user/last_name
           :t_user/full_name
           :t_user/job_title
           :t_job_title/can_be_responsible_clinician]})

(defsc UserWithColleagues
  "Component for loading a user with their colleagues.
   Used for lazy-loading colleagues into the database."
  [_ _]
  {:ident :t_user/id
   :query [:t_user/id
           {:t_user/colleagues (comp/get-query UserItem)}]})

;;
;; ============================================================================
;; Shared Display Components
;; ============================================================================
;;

(defn ui-display-user
  "Renders a user's name and job title inline.
   Used by both single and multiple selection displays."
  [{:t_user/keys [full_name job_title] :as user}]
  (comp/fragment
    (span :.text-black full_name)
    (when-not (str/blank? job_title)
      (span :.text-gray-600.italic.text-sm.ml-2 job_title))))

(defn ui-display-user-block
  "Renders a user's name and job title as a block element.
   Used for displaying multiple selected users."
  [{:t_user/keys [id full_name job_title] :as user}]
  (div {:key id}
    (span :.text-black full_name)
    (when-not (str/blank? job_title)
      (span :.text-gray-600.italic.text-sm.ml-2 job_title))))

;;
;; ============================================================================
;; Single User Selection - Display Component
;; ============================================================================
;;

(defsc SelectUserDisplay
  "The closed state display for single user selection.
   Shows the currently selected user or a placeholder.

   Computed props:
   - :selected    - selected user map or nil
   - :placeholder - text when nothing selected (default: 'Choose user...')
   - :disabled    - boolean to prevent interaction
   - :onClick     - callback when clicked (if not disabled)"
  [this props {:keys [selected placeholder disabled onClick]}]
  (let [placeholder (or placeholder "Choose user...")]
    (if disabled
      ;; Disabled state
      (div :.border.border-gray-300.px-4.py-2.rounded.bg-gray-100.cursor-not-allowed.opacity-70.flex.items-center.justify-between
        (div :.flex-1.min-w-0
          (if selected
            (ui-display-user selected)
            (span :.text-gray-600.italic placeholder)))
        (ui/icon-selector))
      ;; Enabled state
      (div :.border.border-gray-300.px-4.py-2.rounded.bg-gray-50.cursor-pointer.hover:bg-gray-100.flex.items-center.justify-between
        {:onClick (when onClick #(onClick))}
        (div :.flex-1.min-w-0
          (if selected
            (ui-display-user selected)
            (span :.text-gray-600.italic placeholder)))
        (ui/icon-selector)))))

(def ui-select-user-display (comp/computed-factory SelectUserDisplay))

;;
;; ============================================================================
;; Multiple User Selection - Display Component
;; ============================================================================
;;

(defsc SelectUsersDisplay
  "The closed state display for multiple user selection.
   Shows all currently selected users (sorted) or a placeholder.

   Computed props:
   - :selected    - collection of selected user maps
   - :placeholder - text when nothing selected (default: 'Choose users...')
   - :disabled    - boolean to prevent interaction
   - :onClick     - callback when clicked (if not disabled)"
  [this props {:keys [selected placeholder disabled onClick]}]
  (let [placeholder (or placeholder "Choose users...")
        sorted-selected (sort-users selected)
        has-selection? (seq sorted-selected)]
    (if disabled
      ;; Disabled state
      (div :.border.border-gray-300.px-4.py-2.rounded.bg-gray-100.cursor-not-allowed.opacity-70.flex.items-center.justify-between
        (div :.flex-1.min-w-0
          (if has-selection?
            (for [user sorted-selected]
              (ui-display-user-block user))
            (span :.text-gray-600.italic placeholder)))
        (ui/icon-selector))
      ;; Enabled state
      (div :.border.border-gray-300.px-4.py-2.rounded.bg-gray-50.cursor-pointer.hover:bg-gray-100.flex.items-center.justify-between
        {:onClick (when onClick #(onClick))}
        (div :.flex-1.min-w-0
          (if has-selection?
            (for [user sorted-selected]
              (ui-display-user-block user))
            (span :.text-gray-600.italic placeholder)))
        (ui/icon-selector)))))

(def ui-select-users-display (comp/computed-factory SelectUsersDisplay))

;;
;; ============================================================================
;; State Management - Mutations
;; ============================================================================
;;

(defn reset-modal-state*
  "Helper to reset modal state to defaults. Used within mutations."
  [state path]
  (-> state
      (assoc-in (conj path :ui/modal-open?) false)
      (assoc-in (conj path :ui/mode) :colleagues)
      (assoc-in (conj path :ui/search-text) "")
      (assoc-in (conj path :ui/results) [])
      (assoc-in (conj path :ui/loading?) false)
      (assoc-in (conj path :ui/error) nil)))

(defn set-loading*
  "Helper to set loading state. Used within mutations."
  [state path loading?]
  (-> state
      (assoc-in (conj path :ui/loading?) loading?)
      (assoc-in (conj path :ui/error) nil)))

(defmutation open-user-modal
  "Open the user selection modal."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (swap! state assoc-in (conj (select-user-ident id) :ui/modal-open?) true)))

(defmutation close-user-modal
  "Close the user selection modal and reset search state."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (swap! state reset-modal-state* (select-user-ident id))))

(defmutation set-mode
  "Set the search mode (:colleagues or :all-users)."
  [{:keys [id mode]}]
  (action [{:keys [state]}]
          (let [path (select-user-ident id)]
            (swap! state #(-> %
                              (assoc-in (conj path :ui/mode) mode)
                              (assoc-in (conj path :ui/search-text) "")
                              (assoc-in (conj path :ui/results) [])
                              (assoc-in (conj path :ui/error) nil))))))

(defn load-colleagues!
  "Lazily load colleagues for the given user into the client database.
   Loads into the user entity by ident using UserWithColleagues query."
  [app user-id]
  (when user-id
    (df/load! app [:t_user/id user-id] UserWithColleagues)))

;;
;; ============================================================================
;; All Users Search - Server-side
;; ============================================================================
;;

(defn set-results*
  "Helper to set search results. Used within mutations."
  [state path results]
  (-> state
      (assoc-in (conj path :ui/loading?) false)
      (assoc-in (conj path :ui/results) (or results []))))

(defn set-error*
  "Helper to set error state. Used within mutations."
  [state path error]
  (-> state
      (assoc-in (conj path :ui/loading?) false)
      (assoc-in (conj path :ui/error) error)))

(defmutation search-users
  "Search for users via rsdb service.
   Parameters:
   - :id - component id for storing results
   - :s  - search string"
  [{:keys [id s] :as params}]
  (action [{:keys [state]}]
          (swap! state set-loading* (select-user-ident id) true))
  (remote [env]
          (-> env
              (m/with-server-side-mutation 'pc4.rsdb/search-users)
              (m/returning UserItem)))
  (ok-action [{:keys [state result]}]
             (let [results (get-in result [:body 'pc4.rsdb/search-users])]
               (swap! state set-results* (select-user-ident id) results)))
  (error-action [{:keys [state]}]
                (swap! state set-error* (select-user-ident id) "Search failed")))

(def search-users-debounced
  "Debounced function to search for users.
   Waits 400ms after last keystroke before triggering search."
  (letfn [(do-search [comp id search-text]
            (comp/transact! comp [(search-users {:id id :s search-text})]))]
    (gf/debounce do-search 400)))

;;
;; ============================================================================
;; Single User Search Modal (renders inside SelectUser)
;; ============================================================================
;;

(defsc UserSearchModal
  "Modal content for single user selection.
   Receives parent component reference for direct mutation calls.

   Computed props:
   - :parent-component - The parent SelectUser component for mutations
   - :id               - Component ID for mutations
   - :colleagues       - List of colleague users
   - :selected         - Currently selected user
   - :only-responsible - Filter to responsible clinicians only
   - :onSelect         - Callback when user is selected"
  [this {:ui/keys [mode search-text results loading? error] :as props}
   {:keys [parent-component id colleagues selected only-responsible onSelect]}]
  (let [colleagues-mode? (= mode :colleagues)
        base-results (if colleagues-mode?
                       (filter-users colleagues search-text)
                       results)
        display-results (filter-responsible-clinicians base-results only-responsible)
        result-count (count display-results)]
    (div :.space-y-4
      ;; Mode selector
      (div :.flex.space-x-4.border-b.pb-4
        (dom/button
          {:type    "button"
           :onClick #(comp/transact! parent-component [(set-mode {:id id :mode :colleagues})])
           :classes ["px-4" "py-2" "text-sm" "font-medium" "rounded-t-lg" "border-b-2"
                     (if colleagues-mode?
                       "border-blue-500 text-blue-600"
                       "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300")]}
          "My colleagues")
        (dom/button
          {:type    "button"
           :onClick #(comp/transact! parent-component [(set-mode {:id id :mode :all-users})])
           :classes ["px-4" "py-2" "text-sm" "font-medium" "rounded-t-lg" "border-b-2"
                     (if-not colleagues-mode?
                       "border-blue-500 text-blue-600"
                       "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300")]}
          "Search all users"))

      ;; Search input
      (div
        (dom/input :.block.w-full.px-3.py-2.border.border-gray-300.rounded-md.shadow-sm.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm
          {:type        "search"
           :autoFocus   true
           :placeholder (if colleagues-mode?
                          "Filter colleagues..."
                          "Search by name or username...")
           :value       (or search-text "")
           :onChange    (fn [e]
                          (let [text (evt/target-value e)]
                            (m/set-value! parent-component :ui/search-text text)
                            (when (and (= mode :all-users) (>= (count text) 3))
                              (search-users-debounced parent-component id text))))}))

      ;; Results area
      (div :.overflow-y-auto.h-80.border.rounded-lg.bg-white
        (cond
          error
          (ui/box-error-message {:message error})

          loading?
          (div :.flex.justify-center.items-center.h-full
            (ui/ui-loading {}))

          (and (not colleagues-mode?) (< (count search-text) 3))
          (div :.px-6.py-8.text-center
            (div :.text-sm.text-gray-500 "Enter at least 3 characters to search..."))

          (pos? result-count)
          (let [selected-id (:t_user/id selected)]
            (div :.divide-y.divide-gray-200
              (div :.px-4.py-2.text-xs.text-gray-500
                (str result-count " user" (when (not= 1 result-count) "s") " found"))
              (for [{:t_user/keys [id] :as user} display-results
                    :let [is-selected? (= id selected-id)]]
                (div {:key     id
                      :onClick #(onSelect user)
                      :classes ["px-4" "py-3" "cursor-pointer" "flex" "items-center" "justify-between"
                                (if is-selected?
                                  "bg-blue-50 hover:bg-blue-100"
                                  "hover:bg-gray-50")]}
                  (ui-display-user user)
                  (when is-selected?
                    (dom/svg :.w-5.h-5.text-blue-600.flex-shrink-0
                      {:fill "currentColor" :viewBox "0 0 20 20"}
                      (dom/path {:fillRule "evenodd"
                                 :d        "M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                                 :clipRule "evenodd"})))))))

          :else
          (div :.px-6.py-8.text-center
            (div :.text-sm.text-gray-500
              (if colleagues-mode?
                (if (seq colleagues)
                  "No colleagues matching your filter."
                  "No colleagues found.")
                "No users found matching your search."))))))))

(def ui-user-search-modal (comp/computed-factory UserSearchModal))

;;
;; ============================================================================
;; Main SelectUser Component (Single Selection)
;; ============================================================================
;;

(defsc SelectUser
  "Main user selector component for single selection.
   Displays current selection and opens modal for search/selection.
   The selected value is passed as a computed prop (:selected) from the parent,
   ensuring single source of truth for form state.

   Colleagues are lazily loaded into the database when the modal opens.

   Computed props:
   - :selected         - Currently selected user - owned by parent
   - :label            - Modal title (default 'Select User')
   - :placeholder      - Text when nothing selected (default 'Choose user...')
   - :disabled         - Read-only mode
   - :required         - If true, hides the Clear button
   - :only-responsible - If true, filter to only responsible clinicians
   - :onSelect         - Callback fn receiving selected user (or nil to clear)"
  [this {id                              :db/id
         :ui/keys                        [modal-open?]
         {user-id    :t_user/id
          colleagues :t_user/colleagues} :session/authenticated-user
         :as                             props}
   {:keys [selected label placeholder disabled required only-responsible onSelect]}]
  {:ident         (fn [] (select-user-ident (:db/id props)))
   :query         [:db/id
                   :ui/modal-open?
                   :ui/mode
                   :ui/search-text
                   :ui/results
                   :ui/loading?
                   :ui/error
                   {[:session/authenticated-user '_]
                    [:t_user/id
                     {:t_user/colleagues (comp/get-query UserItem)}]}]
   :initial-state (fn [{:keys [id]}]
                    {:db/id          id
                     :ui/modal-open? false
                     :ui/mode        :colleagues
                     :ui/search-text ""
                     :ui/results     []
                     :ui/loading?    false
                     :ui/error       nil})}
  (let [open-modal (fn []
                     (when (and user-id (empty? colleagues))
                       (load-colleagues! this user-id))
                     (comp/transact! this [(open-user-modal {:id id})]))
        close-modal #(comp/transact! this [(close-user-modal {:id id})])
        do-select (fn [user]
                    (close-modal)
                    (when onSelect (onSelect user)))
        do-clear (fn []
                   (close-modal)
                   (when onSelect (onSelect nil)))]
    (div :.user-select-component {:id (str id)}
      ;; Display control (closed state)
      (if disabled
        (ui-select-user-display {} {:selected selected :placeholder placeholder :disabled true})
        (ui-select-user-display {} {:selected    selected
                                    :placeholder placeholder
                                    :disabled    false
                                    :onClick     open-modal}))

      ;; Modal dialog
      (when modal-open?
        (ui/ui-modal
          {:title   (or label "Select User")
           :onClose close-modal
           :actions (cond-> [{:id :cancel :title "Cancel" :onClick close-modal}]
                            (and (not required) selected)
                            (conj {:id :clear :title "Clear" :onClick do-clear}))}
          (ui-user-search-modal props
                                {:parent-component this
                                 :id               id
                                 :colleagues       colleagues
                                 :selected         selected
                                 :only-responsible only-responsible
                                 :onSelect         do-select}))))))

(def ui-select-user
  "Factory function for the SelectUser component (single selection).

   Colleagues are lazily loaded into the database when the modal opens,
   using the authenticated user's ID from the session.

   Props (passed as first arg):
   - :db/id - Unique component identifier (required)

   Computed props (passed as second arg):
   - :selected         - Currently selected user - owned by parent
   - :label            - Modal title (default 'Select User')
   - :placeholder      - Text when nothing selected (default 'Choose user...')
   - :disabled         - Read-only mode
   - :required         - If true, hides the Clear button
   - :only-responsible - If true, filter to only responsible clinicians
   - :onSelect         - Callback fn receiving selected user (or nil to clear)"
  (comp/computed-factory SelectUser {:keyfn :db/id}))

;;
;; ============================================================================
;; Multiple User Selection - Mutations
;; ============================================================================
;;

(defmutation open-users-modal
  "Open the multiple user selection modal and initialize pending selection."
  [{:keys [id selected]}]
  (action [{:keys [state]}]
          (let [path (select-user-ident id)]
            (swap! state #(-> %
                              (assoc-in (conj path :ui/modal-open?) true)
                              (assoc-in (conj path :ui/pending-selection) (vec selected)))))))

(defmutation close-users-modal
  "Close the multiple user selection modal and reset state."
  [{:keys [id]}]
  (action [{:keys [state]}]
          (let [path (select-user-ident id)]
            (swap! state #(-> %
                              (reset-modal-state* path)
                              (assoc-in (conj path :ui/pending-selection) []))))))

(defmutation add-pending-user
  "Add a user to the pending selection."
  [{:keys [id user]}]
  (action [{:keys [state]}]
          (let [path (select-user-ident id)
                pending-path (conj path :ui/pending-selection)]
            (swap! state update-in pending-path
                   (fn [current]
                     (if (some #(= (:t_user/id %) (:t_user/id user)) current)
                       current
                       (conj (vec current) user)))))))

(defmutation remove-pending-user
  "Remove a user from the pending selection."
  [{:keys [id user-id]}]
  (action [{:keys [state]}]
          (let [path (select-user-ident id)
                pending-path (conj path :ui/pending-selection)]
            (swap! state update-in pending-path
                   (fn [current]
                     (vec (remove #(= (:t_user/id %) user-id) current)))))))

;;
;; ============================================================================
;; Multiple User Search Modal (renders inside SelectUsers)
;; ============================================================================
;;

(defsc UsersSearchModal
  "Modal content for multiple user selection.
   Shows a split-pane UI with selected users on left/top and search results on right/bottom.
   Responsive: stacks vertically on small screens, side-by-side on medium+.
   Receives parent component reference for direct mutation calls.

   Computed props:
   - :parent-component - The parent SelectUsers component for mutations
   - :id               - Component ID for mutations
   - :colleagues       - List of colleague users
   - :only-responsible - Filter to responsible clinicians only"
  [this {:ui/keys [mode search-text results loading? error pending-selection] :as props}
   {:keys [parent-component id colleagues only-responsible]}]
  (let [colleagues-mode? (= mode :colleagues)
        pending-ids (set (map :t_user/id pending-selection))
        sorted-pending (sort-users pending-selection)
        base-results (if colleagues-mode?
                       (filter-users colleagues search-text)
                       results)
        filtered-results (filter-responsible-clinicians base-results only-responsible)
        filtered-available (remove #(pending-ids (:t_user/id %)) filtered-results)
        available-count (count filtered-available)]
    ;; Responsive flex: column on mobile, row on md+
    (div :.flex.flex-col.md:flex-row.flex-1.overflow-hidden
      ;; Left/Top pane: Selected users
      (div :.w-full.md:w-80.md:border-r.border-b.md:border-b-0.border-gray-200.flex.flex-col.bg-gray-50.flex-shrink-0
        (div :.px-6.py-4.border-b.border-gray-200.bg-gray-50.font-medium.text-sm.text-gray-700
          (str "Selected (" (count pending-selection) ")"))
        (div :.flex-1.overflow-y-auto.py-2
          (if (seq sorted-pending)
            (for [{:t_user/keys [id full_name job_title] :as user} sorted-pending]
              ;; Card-style selected user with always-visible remove button
              (div :.relative.px-6.py-4.cursor-pointer.flex.flex-col.bg-white.mx-2.my-1.rounded.border.border-gray-300.hover:bg-red-50
                {:key     id
                 :onClick #(comp/transact! parent-component [(remove-pending-user {:id (:db/id props) :user-id id})])}
                ;; Circular red remove button - always visible
                (dom/button
                  {:type    "button"
                   :onClick #(do (.stopPropagation %)
                                 (comp/transact! parent-component [(remove-pending-user {:id (:db/id props) :user-id id})]))
                   :classes ["absolute" "top-1/2" "right-4" "transform" "-translate-y-1/2"
                             "bg-red-500" "border-none" "text-white" "cursor-pointer"
                             "w-7" "h-7" "flex" "items-center" "justify-center" "rounded-full"
                             "transition-all" "duration-200" "hover:bg-red-600" "hover:scale-110"]}
                  ;; X icon (stroke-based)
                  (dom/svg :.w-4.h-4 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
                    (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2"
                               :d             "M6 18L18 6M6 6l12 12"})))
                (div :.font-medium.mb-1 full_name)
                (div :.text-gray-600.text-sm job_title)))
            (div :.px-4.py-8.text-center.text-sm.text-gray-400
              "No users selected"))))

      ;; Right/Bottom pane: Search and available users
      (div :.flex-1.flex.flex-col
        ;; Header with mode selector and search
        (div :.px-6.py-4.border-b.border-gray-200.bg-white
          ;; Mode selector dropdown
          (div :.mt-2.grid.grid-cols-1
            (dom/select :.col-start-1.row-start-1.w-full.appearance-none.rounded-md.bg-white.py-1.5.pl-3.pr-8.text-base.text-gray-900.outline.outline-1.-outline-offset-1.outline-gray-300.focus:outline.focus:outline-2.focus:-outline-offset-2.focus:outline-indigo-600.sm:text-sm
                        {:value    (name (or mode :colleagues))
                         :onChange #(comp/transact! parent-component [(set-mode {:id id :mode (keyword (.. % -target -value))})])}
                        (dom/option {:value "colleagues"} "My colleagues")
                        (dom/option {:value "all-users"} "Search all users"))
            (dom/svg :.pointer-events-none.col-start-1.row-start-1.mr-2.size-5.self-center.justify-self-end.text-gray-500
              {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true"}
              (dom/path {:fillRule "evenodd"
                         :d        "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z"
                         :clipRule "evenodd"})))
          ;; Search input
          (dom/input :.w-full.px-2.py-2.border.border-gray-300.rounded.mb-4.mt-4.text-base.box-border
            {:type        "search"
             :autoFocus   true
             :placeholder "Enter search term..."
             :value       (or search-text "")
             :onChange    (fn [e]
                            (let [text (evt/target-value e)]
                              (m/set-value! parent-component :ui/search-text text)
                              (when (and (= mode :all-users) (>= (count text) 3))
                                (search-users-debounced parent-component id text))))}))

        ;; Available users list
        (div :.flex-1.overflow-y-auto.py-2
          (cond
            error
            (ui/box-error-message {:message error})

            loading?
            (div :.flex.justify-center.items-center.h-full
              (ui/ui-loading {}))

            (and (not colleagues-mode?) (< (count search-text) 3))
            (div :.px-6.py-4.text-gray-500.text-center
              "Enter 3 or more characters to search all users")

            (pos? available-count)
            (comp/fragment
              (div :.px-6.py-4.border-b.border-gray-200.bg-gray-50.font-medium.text-sm.text-gray-700
                (str "Found (" available-count ")"))
              (for [{:t_user/keys [id full_name job_title] :as user} filtered-available]
                ;; Available user row with always-visible add button
                (div :.relative.px-6.py-4.cursor-pointer.border-b.border-gray-100.flex.flex-col.hover:bg-green-50.last:border-b-0
                  {:key     id
                   :onClick #(comp/transact! parent-component [(add-pending-user {:id (:db/id props) :user user})])}
                  ;; Circular green add button - always visible
                  (dom/button
                    {:type    "button"
                     :onClick #(do (.stopPropagation %)
                                   (comp/transact! parent-component [(add-pending-user {:id (:db/id props) :user user})]))
                     :classes ["absolute" "top-1/2" "right-4" "transform" "-translate-y-1/2"
                               "bg-green-500" "border-none" "text-white" "cursor-pointer"
                               "w-7" "h-7" "flex" "items-center" "justify-center" "rounded-full"
                               "transition-all" "duration-200" "hover:bg-green-600" "hover:scale-110"]}
                    ;; Plus icon (stroke-based)
                    (dom/svg :.w-4.h-4 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
                      (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2"
                                 :d             "M12 4v16m8-8H4"})))
                  (div :.font-medium.mb-1 full_name)
                  (div :.text-gray-600.text-sm job_title))))

            :else
            (div :.px-6.py-4.text-gray-500.text-center
              (if colleagues-mode?
                (if (seq colleagues)
                  (str "No colleagues found matching \"" search-text "\"")
                  "No colleagues found")
                (str "No users found matching \"" search-text "\"")))))))))

(def ui-users-search-modal (comp/computed-factory UsersSearchModal))

;;
;; ============================================================================
;; Main SelectUsers Component (Multiple Selection)
;; ============================================================================
;;

(defsc SelectUsers
  "Main user selector component for multiple selection.
   Displays current selections and opens modal for search/selection.
   Uses pending selection state that's only committed on Save.

   Colleagues are lazily loaded into the database when the modal opens.

   Computed props:
   - :selected         - Currently selected users (collection) - owned by parent
   - :label            - Modal title (default 'Select Users')
   - :placeholder      - Text when nothing selected (default 'Choose users...')
   - :disabled         - Read-only mode
   - :only-responsible - If true, filter to only responsible clinicians
   - :onSelect         - Callback fn receiving selected users collection"
  [this {id                              :db/id
         :ui/keys                        [modal-open? pending-selection]
         {user-id    :t_user/id
          colleagues :t_user/colleagues} :session/authenticated-user
         :as                             props}
   {:keys [selected label placeholder disabled only-responsible onSelect]}]
  {:ident         (fn [] (select-user-ident (:db/id props)))
   :query         [:db/id
                   :ui/modal-open?
                   :ui/mode
                   :ui/search-text
                   :ui/results
                   :ui/loading?
                   :ui/error
                   :ui/pending-selection
                   {[:session/authenticated-user '_]
                    [:t_user/id
                     {:t_user/colleagues (comp/get-query UserItem)}]}]
   :initial-state (fn [{:keys [id]}]
                    {:db/id                id
                     :ui/modal-open?       false
                     :ui/mode              :colleagues
                     :ui/search-text       ""
                     :ui/results           []
                     :ui/loading?          false
                     :ui/error             nil
                     :ui/pending-selection []})}
  (let [open-modal (fn []
                     (when (and user-id (empty? colleagues))
                       (load-colleagues! this user-id))
                     (comp/transact! this [(open-users-modal {:id id :selected (or selected [])})]))
        close-modal #(comp/transact! this [(close-users-modal {:id id})])
        do-save (fn []
                  (close-modal)
                  (when onSelect (onSelect pending-selection)))]
    (div :.user-select-component {:id (str id)}
      ;; Display control (closed state)
      (if disabled
        (ui-select-users-display {} {:selected selected :placeholder placeholder :disabled true})
        (ui-select-users-display {} {:selected    selected
                                     :placeholder placeholder
                                     :disabled    false
                                     :onClick     open-modal}))

      ;; Modal dialog
      (when modal-open?
        (ui/ui-modal
          {:title   (or label "Select Users")
           :size    :full
           :onClose close-modal
           :actions [{:id :cancel :title "Cancel" :onClick close-modal}
                     {:id :save :title "Save" :role :primary :onClick do-save}]}
          (ui-users-search-modal props
                                 {:parent-component this
                                  :id               id
                                  :colleagues       colleagues
                                  :only-responsible only-responsible}))))))

(def ui-select-users
  "Factory function for the SelectUsers component (multiple selection).

   Colleagues are lazily loaded into the database when the modal opens,
   using the authenticated user's ID from the session.

   Props (passed as first arg):
   - :db/id - Unique component identifier (required)

   Computed props (passed as second arg):
   - :selected         - Currently selected users (collection) - owned by parent
   - :label            - Modal title (default 'Select Users')
   - :placeholder      - Text when nothing selected (default 'Choose users...')
   - :disabled         - Read-only mode
   - :only-responsible - If true, filter to only responsible clinicians
   - :onSelect         - Callback fn receiving selected users collection"
  (comp/computed-factory SelectUsers {:keyfn :db/id}))
