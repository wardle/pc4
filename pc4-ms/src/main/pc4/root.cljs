(ns pc4.root
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.dom :as dom :refer [div ul li p h3 button b]]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro-css.css :as css]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [pc4.app :refer [SPA]]
    [pc4.ui :as ui]
    [pc4.users]
    [pc4.projects :as projects]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.data-fetch :as df]))

(defn field [{:keys [label valid? error-message] :as props}]
  (let [input-props (-> props (assoc :name label) (dissoc :label :valid? :error-message))]
    (div :.ui.field
         (dom/label {:htmlFor label} label)
         (dom/input input-props)
         (dom/div :.ui.error.message {:classes [(when valid? "hidden")]}
                  error-message))))


(defsc Main [this props]
  {:query         [:main/welcome-message]
   :initial-state {:main/welcome-message "Hi!"}
   :ident         (fn [] [:component/id :main])
   :route-segment ["home"]}
  (div :.ui.container.segment
       (h3 "Main")
       (p (str "Welcome to the Fulcro template. "
               "The Sign up and login functionalities are partially implemented, "
               "but mostly this is just a blank slate waiting "
               "for your project."))))

(defsc Settings [this {:keys [:account/time-zone :account/real-name] :as props}]
  {:query         [:account/time-zone :account/real-name :account/crap]
   :ident         (fn [] [:component/id :settings])
   :route-segment ["settings"]
   :initial-state {}}
  (div :.ui.container.segment
       (h3 "Settings")
       (div "TODO")))


(defsc SnomedDescription [this {:info.snomed.Description/keys [term] :as props}]
  {:query [:info.snomed.Description/id :info.snomed.Description/term]
   :ident (fn [] [:info.snomed.Description/id (:info.snomed.Description/id props)])}
  (dom/span term))

(def ui-snomed-description (comp/factory SnomedDescription))

(defsc SnomedConcept [this {:info.snomed.Concept/keys [id preferredDescription] :as props}]
  {:query [:info.snomed.Concept/id :info.snomed.Concept/preferredDescription]
   :ident (fn [] [:info.snomed.Concept/id (:info.snomed.Concept/id props)])}
  (ui-snomed-description preferredDescription))

(def ui-snomed-concept (comp/factory SnomedConcept))

(defsc Login
  "Login component that keeps user credentials in local state."
  [this {:keys [error]}]
  {:route-segment ["login"]}
  (let [username (or (comp/get-state this :username) "")
        password (or (comp/get-state this :password) "")
        disabled? (or (str/blank? username) (str/blank? password))
        do-login #(comp/transact! @SPA [(pc4.users/login {:system "cymru.nhs.uk" :value username :password password})])]
    (div :.flex.h-screen.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
         (div :.max-w-md.w-full.space-y-8.m-auto
              (dom/form
                {:method   "POST"
                 :onSubmit (fn [evt] (evt/prevent-default! evt) (do-login))}
                (dom/h1 :.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter.mb-8 "PatientCare " (dom/span :.font-bold "v4"))
                (div :.rounded-md.shadow-sm.-space-y-px
                     (div
                       (dom/label :.sr-only {:htmlFor "username"} "username")
                       (dom/input :.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
                                  {:name         "username" :type "text"
                                   :autoComplete "username" :required true :placeholder "Username"
                                   :autoFocus    true :disabled false
                                   :value        username
                                   :onChange     (fn [evt] (comp/set-state! this {:username (evt/target-value evt)}))
                                   :onKeyDown    (fn [evt] (when (evt/enter? evt) (.focus (.getElementById js/document "password"))))}))
                     (div
                       (dom/label :.sr-only {:htmlFor "password"} "Password")
                       (dom/input :.password.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
                                  {:id           "password"
                                   :type         "password"
                                   :autoComplete "current-password"
                                   :value        password
                                   :onChange     #(comp/set-state! this {:password (evt/target-value %)})
                                   :onKeyDown    (fn [evt] (when (evt/enter? evt) (do-login)))
                                   :required     true
                                   :placeholder  "Password"})))
                (when error                                 ;; error
                  (ui/box-error-message :message error))
                (div
                  (dom/button :.mt-4.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500.bg-indigo-600.hover:bg-indigo-700
                              {:type     "submit"
                               :classes  (when disabled? ["opacity-50"])
                               :disabled disabled?}
                              "Sign in")))))))

(def ui-login (comp/factory Login))


(defsc HomePage
  [this {:session/keys [authenticated-user]}]
  {:ident         (fn [] [:component/id :home])
   :query         [{[:session/authenticated-user '_] (comp/get-query pc4.users/UserHomePage)}]
   :route-segment ["home"]
   :initial-state {}}
  (js/console.log "HomePage: authenticated user = " authenticated-user)
  (when (seq authenticated-user) (pc4.users/ui-user-home-page authenticated-user)))

(def ui-home-page (comp/factory HomePage))

(defsc NavBar
  [this {:t_user/keys [id title first_names last_name initials] :as params}]
  {:ident         :t_user/id
   :query         [:t_user/id :t_user/title :t_user/first_names :t_user/last_name :t_user/initials]}
  (ui/ui-nav-bar {:title     "PatientCare v4" :show-user? true
                  :full-name (str (when-not (str/blank? title) (str title " ")) first_names " " last_name)
                  :initials  initials
                  :user-menu [{:id :logout :title "Sign out" :onClick #(comp/transact! @SPA [(list 'pc4.users/logout)])}]}))

(def ui-nav-bar (comp/factory NavBar))

(defrouter MainRouter [this props]
  {:router-targets [HomePage projects/ProjectPage pc4.patients/PatientPage]})

(def ui-main-router (comp/factory MainRouter))

(defsc Root [this {authenticated-user :session/authenticated-user
                   router             :root/router
                   login-error        :session/error}]
  {:query         [{:session/authenticated-user (comp/get-query NavBar)}
                   {:root/router (comp/get-query MainRouter)}
                   :session/error]
   :initial-state {:root/router {}}}

  (if-not (seq authenticated-user)
    (ui-login {:error login-error})
    (comp/fragment (ui-nav-bar authenticated-user)
                   (ui-main-router router))))

(comment
  (comp/get-query Root))

