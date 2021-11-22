(ns pc4.ui.root
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.dom :as dom :refer [div ul li p h3 button b]]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro-css.css :as css]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [pc4.app :refer [SPA]]
    [pc4.ui.components]
    [pc4.ui.ui :as ui]
    [pc4.users]
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

(dr/defrouter TopRouter [this props]
  {:router-targets [Main Settings]})

(def ui-top-router (comp/factory TopRouter))


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
  [this {login-error :session/error}]
  {:query [:session/error]}
  (let [username (or (comp/get-state this :username) "")
        password (or (comp/get-state this :password) "")
        disabled? (or (str/blank? username) (str/blank? password))
        do-login #(comp/transact! @SPA [(pc4.users/login {:system "cymru.nhs.uk" :value username :password password})])]
    (div :.flex.h-screen.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
         (div :.max-w-md.w-full.space-y-8.m-auto
              (dom/form {:method   "POST"
                         :onSubmit (fn [evt] (evt/prevent-default! evt) (do-login))}
                        (dom/h1 :.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter.mb-8 "PatientCare " (dom/span :.font-bold "v4"))
                        (div :.rounded-md.shadow-sm.-space-y-px
                             (div
                               (dom/label :.sr-only {:htmlFor "username"} "username")
                               (dom/input :.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
                                          {:name         "username"
                                           :type         "text"
                                           :autoComplete "username" :required true :placeholder "Username"
                                           :autoFocus    true
                                           :disabled     false
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
                        (when login-error                         ;; error
                          (pc4.ui.ui/box-error-message :message login-error))
                        (div
                          (dom/button :.mt-4.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500.bg-indigo-600.hover:bg-indigo-700
                                      {:type     "submit"
                                       :classes  (when disabled? ["opacity-50"])
                                       :disabled disabled?}
                                      "Sign in")))))))

(def ui-login (comp/factory Login))

(defsc Root [this {authenticated-user :session/authenticated-user
                   login-error        :session/error
                   :root/keys         [selected-concept]}]
  {:query         [{:session/authenticated-user (comp/get-query pc4.users/User)}
                   :session/error
                   {:root/selected-concept (comp/get-query SnomedConcept)}]
   :initial-state {}}
  (if-not authenticated-user
    (ui-login {:session/error login-error})
    (div (dom/h1 "Hi there")
         (when selected-concept (ui-snomed-concept selected-concept))
         (when authenticated-user (pc4.users/ui-user authenticated-user))
         (pc4.ui.components/ui-placeholder {:w 200 :h 200 :label "avatar"}))))

(comment
  (comp/get-query Root)

  )