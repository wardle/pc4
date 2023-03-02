(ns com.eldrix.pc4.ui.user
  (:require [com.eldrix.pc4.ui.misc :as misc]
            [rum.core :as rum]))

(rum/defc login-panel
  "Login panel with hero title, and parameters:
  - form      : properties for HTML form
  - username  :
  - password  :
  - disabled  : if login form should be disabled
  - error     : an error message to be shown"
  [{:keys [form username password hidden disabled error]}]
  [:div.flex.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
   [:div.max-w-md.w-full.space-y-8
    [:form form
     (for [[k v] hidden
           :when v]
       [:input {:type "hidden" :name (name k) :value (str v)}])
     [:div
      [:h1.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter "PatientCare " [:span.font-bold "v4"]]]
     [:div.rounded-md.shadow-sm
      [:div.mt-8
       [:label.sr-only {:for "username"} "username"]
       [:input#email-address.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
        (merge {:name "username" :type "text" :autoComplete "username" :required true :placeholder "Username" :autofocus true :disabled disabled}
               username)]]
      [:div.mt-2.mb-4
       [:label.sr-only {:for "password"} "Password"]
       [:input#password.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
        (merge {:name "password" :type "password" :autoComplete "current-password" :required true :placeholder "Password" :disabled disabled}
               password)]]]
     (when error
       (misc/box-error-message {:message error}))
     [:div.mt-2
      [:button.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500
       {:type "submit" :disabled disabled :class (if disabled "bg-gray-400 animate-pulse" "bg-indigo-600 hover:bg-indigo-700")}
       [:span.absolute.left-0.inset-y-0.flex.items-center.pl-3
        [:svg.h-5.w-5.text-indigo-500.group-hover:text-indigo-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true" :class (when disabled "animate-bounce")}
         [:path {:fill-rule "evenodd" :d "M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" :clip-rule "evenodd"}]]] (if disabled "Signing in" "Sign in")]]]]])