(ns eldrix.pc4-ward.refer2
  (:require [cljs.spec.alpha :as s]
            [re-frame.core :as rf]
            [eldrix.pc4-ward.rf.users :as users]
            [eldrix.pc4-ward.rf.patients :as patients]
            [reagent.core :as reagent]
            [clojure.string :as str]))

(defn icon-bell []
  [:svg.h-6.w-6 {:xmlns   "http://www.w3.org/2000/svg" :fill "none"
                 :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"}]])

(defn icon-chevron-down []
  [:svg.-mr-1.ml-2.h-5.w-5 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "white" :aria-hidden "true"}
   [:path {:fill-rule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clip-rule "evenodd"}]])

(defn badge
  "Display a small badge with the text specified."
  [s & {:keys [text-color bg-color uppercase?] :or {text-color "text-red-200" bg-color "bg-red-500" uppercase? true}}]
  [:span.text-xs.text-center.font-semibold.inline-block.py-1.px-2.uppercase.rounded-full.ml-1.last:mr-0.mr-1
   {:class (str/join " " [text-color bg-color (when uppercase? "uppercase")])} s])

(defn box-error-message [& {:keys [title message]}]
  (when message
    [:div.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.relative.shadow-md {:role "alert"}
     (when title [:span.strong.font-bold.mr-4 title])
     [:span.block.sm:inline message]]))

(defn nav-bar
  "A navigation bar.
  Parameters:
  - title      : the title to show
  - menu       : the main menu, a sequence of menu items with
                |- :id       : unique identifier
                |- :title    : the menu item title
                |- :on-click : function for when clicked
  - selected   : identifier of the currently selected menu
  - show-user? : should the user and user menu be shown?
  - user-menu  : a sequence of menu items each with keys :id :title :on-click
  - full-name  : full name of user
  - initials   : initials of user (for display on small devices)
  - photo      : photo to show for user  [todo: URL or something else?]
  - on-notify  : function if notify button should appear (and call if clicked)"
  [& {:keys [title menu selected user-menu show-user? full-name initials photo on-notify] :or {show-user? false}}]
  (let [show-menu? (reagent/atom false)
        show-user-menu? (reagent/atom false)]
    (fn []
      [:nav.bg-gray-800
       [:div.max-w-7xl.mx-auto.px-2.sm:px-6.lg:px-8
        [:div.relative.flex.items-center.justify-between.h-16
         (when (seq menu)
           [:div.absolute.inset-y-0.left-0.flex.items-center.sm:hidden
            [:button.inline-flex.items-center.justify-center.p-2.rounded-md.text-gray-400.hover:text-white.hover:bg-gray-700.focus:ring-2.focus:ring-inset.focus:ring-white
             {:type     "button" :aria-controls "mobile-menu" :aria-expanded "false"
              :on-click #(swap! show-menu? not)}
             [:span.sr-only "Open main menu"]
             [:svg.block.h-6.w-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
              [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M4 6h16M4 12h16M4 18h16"}]]
             [:svg.hidden.h-6.w-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
              [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M6 18L18 6M6 6l12 12"}]]]])
         [:div.flex-1.flex.items-center.justify-center.sm:items-stretch.sm:justify-start
          [:div.flex-shrink-0.flex.items-center
           [:span.text-white.rounded-md.text-lg.font-large.font-bold title]]
          (when (seq menu)
            [:div.hidden.sm:block.sm:ml-6
             [:div.flex.space-x-4
              (for [item menu]
                (if (and selected (:id item) (= (:id item) selected))
                  [:a.text-white.px-3.py-2.rounded-md.text-sm.font-medium.font-bold {:key (:id item) :on-click (:on-click item) :aria-current "page"} (:title item)]
                  [:a.text-gray-300.hover:bg-gray-700.hover:text-white.px-3.py-2.rounded-md.text-sm.font-medium {:key (:id item) :on-click (:on-click item)} (:title item)]))]])]
         [:div.absolute.inset-y-0.right-0.flex.items-center.pr-2.sm:static.sm:inset-auto.sm:ml-6.sm:pr-0
          (when on-notify
            [:button.bg-gray-800.p-1.rounded-full.text-gray-400 {:on-click on-notify} [:span.sr-only "View notifications"] [icon-bell]])
          (when show-user?
            [:div.ml-3.relative
             [:div
              [:button#user-menu-button.bg-gray-800.flex.text-sm.rounded-full
               {:type     "button" :aria-expanded "false" :aria-haspopup "true"
                :on-click #(swap! show-user-menu? not)}
               [:span.sr-only "Open user menu"]
               [:span.hidden.sm:block.text-white [:div.flex (or full-name "User") [icon-chevron-down]]]
               [:span.sm:hidden.text-white [:div.flex (when initials initials) [icon-chevron-down]]]
               (when photo [:img.h-8.w-8.rounded-full {:src "https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=facearea&facepad=2&w=256&h=256&q=80" :alt ""}])]]
             (when @show-user-menu?
               [:div.origin-top-right.absolute.right-0.mt-2.w-48.rounded-md.shadow-lg.py-1.bg-white.ring-1.ring-black.ring-opacity-5.focus:outline-none {:role "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabIndex "-1"}
                (for [item user-menu]
                  (if (:on-click item)
                    [:a.block.px-4.py-2.text-sm.text-gray-700.hover:bg-gray-700.hover:text-white
                     {:key (:id item) :on-click #(do (reset! show-user-menu? false) ((:on-click item))) :role "menuitem" :tabIndex "-1"} (:title item)]
                    [:a.block.px-4.py-2.text-sm.text-gray-700.italic {:key (:id item) :role "menuitem" :tabIndex "-1"} (:title item)]))])])]]
        (when (and (seq menu) @show-menu?)
          [:div#mobile-menu.sm:hidden
           [:div.px-2.pt-2.pb-3.space-y-1
            (for [item menu]
              (if (and selected (:id item) (= (:id item) selected))
                [:a.bg-gray-900.text-white.block.px-3.py-2.rounded-md.text-base.font-medium {:key (:id item) :on-click (:on-click item) :aria-current "page"} (:title item)]
                [:a.text-gray-300.hover:bg-gray-700.hover:text-white.block.px-3.py-2.rounded-md.text-base.font-medium {:key (:id item) :on-click (:on-click item)} (:title item)]))]])]])))

(defn patient-banner
  [& {:keys [name nhs-number born hospital-identifier address deceased]}]
  [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200
   (when deceased
     [:div.grid.grid-cols-1.pb-2
      [badge (if (instance? goog.date.Date deceased)
               (str "Died " (com.eldrix.pc4.commons.dates/format-date deceased))
               "Deceased")]])
   [:div.grid.grid-cols-2.lg:grid-cols-5
    [:div.font-bold.text-lg.min-w-min name]
    [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min [:span.text-sm.font-thin.hidden.sm:inline "Gender "] "Male"]
    [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min [:span.text-sm.font-thin "Born "] born]
    [:div.lg:hidden.text-right "Male" " " born]
    [:div.lg:text-center.lg:ml-2.min-w-min [:span.text-sm.font-thin "NHS No "] nhs-number]
    [:div.text-right.min-w-min [:span.text-sm.font-thin "CRN "] hospital-identifier]]
   [:div.grid.grid-cols-1 {:class (if-not deceased "bg-gray-100" "bg-red-100")}
    [:div.font-light.text-sm.tracking-tighter.text-gray-500.truncate address]]])

(defn login-panel
  "A login panel with parameters:
  - on-login   - function to be called with username and password
  - disabled   - if login form should be disabled
  - error      - an error message to show."
  [& params]
  (let [username (reagent/atom "")
        password (reagent/atom "")]
    (fn [& {:keys [on-login disabled error] :or {disabled false}}]
      [:div.flex.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
       [:div.max-w-md.w-full.space-y-8
        [:div
         [:h1.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter "PatientCare " [:span.font-bold "v4"]]
         [:h2.mt-6.text-center.text-2xl.md:text-3xl.font-extrabold.text-gray-900 "Please sign in to your account"]]
        [:div.rounded-md.shadow-sm.-space-y-px
         [:div
          [:label.sr-only {:for "username"} "username"]
          [:input#email-address.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
           {:name        "username" :type "text" :autoComplete "username" :required true :placeholder "Username" :auto-focus true :disabled disabled
            :on-change   #(reset! username (-> % .-target .-value))
            :on-key-down #(if (= 13 (.-which %)) (do (.focus (.getElementById js/document "password"))))}]]
         [:div
          [:label.sr-only {:for "password"} "Password"]
          [:input#password.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
           {:name        "password" :type "password" :autoComplete "current-password" :required true :placeholder "Password" :disabled disabled
            :on-change   #(reset! password (-> % .-target .-value))
            :on-key-down #(if (= 13 (.-which %))
                            (do (reset! password (-> % .-target .-value))
                                (when on-login (on-login @username @password))))}]]]
        (when error
          [box-error-message :title "Login error:" :message error])
        [:div
         [:button.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500
          {:type "submit" :on-click #(when on-login (on-login @username @password)) :disabled disabled :class (if disabled "bg-gray-400 animate-pulse" "bg-indigo-600 hover:bg-indigo-700")}
          [:span.absolute.left-0.inset-y-0.flex.items-center.pl-3
           [:svg.h-5.w-5.text-indigo-500.group-hover:text-indigo-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true" :class (when disabled "animate-bounce")}
            [:path {:fill-rule "evenodd" :d "M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" :clip-rule "evenodd"}]]] (if disabled "Signing in" "Sign in")]]]])))


(defn ui-textfield [& {:keys [name autocomplete field-type error] :or {field-type "text"}}]
  [:<> [:input.mt-1.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.shadow-sm.sm:text-sm.border-2.rounded-md.p-1
        {:type  field-type :name name :autocomplete autocomplete
         :class (if-not error "border-gray-200" "border-red-600")}]
   (if (string? error) [:span.text-red-600.text-sm "Please enter your email"])])

(defn ui-label [& {:keys [for label]}]
  [:label.block.text-sm.font-medium.text-gray-700.align-middle {:for for} label])

(defn ui-control [& {:keys [name _autocomplete _label] :as opts}]
  [ui-label (assoc opts :for name)]
  [ui-textfield opts])

(defn example-form
  "This is simply an experiment in styling a form with some help desk
  and different controls, with save and cancel buttons. Labels are above the
  fields."
  []
  [:div.mt-8.shadow-lg.sm:m-8.max-w-5xl
   [:div.md:grid.md:grid-cols-3.md:gap-0
    [:div.md:col-span-1.sm:bg-gray-50
     [:div.px-4.sm:px-4.sm:pt-8.border-none
      [:h3.text-lg.font-medium.leading-6.text-gray-900 "Personal Information"]
      [:p.mt-1.text-sm.text-gray-600 "Use a permanent address where you can receive mail."]
      [:div.mt-8 [box-error-message :message "Please fix the errors and retry."]]]]
    [:div.mt-5.md:mt-0.md:col-span-2
     [:div.overflow-hidden.sm:rounded-md
      [:div.px-4.py-5.bg-white.sm:p-6.border-l-2.border-b-2
       [:div.grid.grid-cols-6.gap-6
        [:div.col-span-6.sm:col-span-2
         [ui-label :for "title" :label "Title"]
         [ui-textfield :name "title"]]
        [:div.col-span-6.sm:col-span-2
         [ui-label :for "first_name" :label "First names"]
         [ui-textfield :name "first-name" :autocomplete "given-name"]]
        [:div.col-span-6.sm:col-span-2
         [ui-label :for "last_name" :label "Last name"]
         [ui-textfield :name "last-name" :autocomplete "family-name"]]
        [:div.col-span-6.sm:col-span-4
         [ui-label :for "email_address" :label "Email"]
         [ui-textfield :name "email_address" :autocomplete "email" :field-type "email" :error "Please enter your email."]]
        [:div.col-span-6
         [ui-label :for "address1" :label "Address 1"]
         [ui-textfield :name "address1" :autocomplete "streetaddress"]]
        [:div.col-span-6
         [ui-label :for "address2" :label "Address 2"]
         [ui-textfield :name "address2"]]
        [:div.col-span-6.sm:col-span-2
         [ui-label :for "city" :label "Town / city"]
         [ui-textfield :name "city"]]
        [:div.col-span-6.sm:col-span-2
         [ui-label :for "state" :label "County"]
         [ui-textfield :name "state"]]
        [:div.col-span-6.sm:col-span-2
         [ui-label :for "postal-code" :label "Postal code"]
         [ui-textfield :name "postal-code" :autocomplete "postal-code"]]
        [:div.col-span-6.sm:col-span-4
         [:label.block.text-sm.font-medium.text-gray-700 {:for "country"} "Country / Region"]
         [:select#country.mt-1.block.w-full.py-2.px-3.border.border-gray-300.bg-white.rounded-md.shadow-sm.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm {:name "country" :autocomplete "country"}
          [:option "United Kingdom"]
          [:option "United States"]
          [:option "Canada"]
          [:option "Mexico"]]]]]
      [:div.flex.flex-col.px-4.py-3.bg-gray-50.text-right.sm:px-6.sm:flex-row.sm:flex-row-reverse
       [:button.w-full.sm:w-max.inline-flex.justify-center.py-2.px-8.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-indigo-600.hover:bg-indigo-700.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500 {:type "submit"} "Save"]
       [:button.w-full.sm:w-max.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-black.bg-white.hover:bg-gray-100.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500.mr-4 {:type "cancel"} "Cancel"]]]]]])


(defn svg-shield []
  [:svg.w-5.h-5 {:fill "none" :stroke "currentColor" :stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :viewBox "0 0 24 24"}
   [:path {:d "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"}]])

(defn svg-person []
  [:svg.w-5.h-5 {:fill "none" :stroke "currentColor" :stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :viewBox "0 0 24 24"}
   [:path {:d "M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"}]
   [:circle {:cx "12" :cy "7" :r "4"}]])

(defn svg-anchor []
  [:svg.w-5.h-5 {:fill "none" :stroke "currentColor" :stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :viewBox "0 0 24 24"}
   [:circle {:cx "12" :cy "5" :r "3"}]
   [:path {:d "M12 22V8M5 12H2a10 10 0 0020 0h-3"}]])

(defn svg-graph []
  [:svg.w-5.h-5 {:fill "none" :stroke "currentColor" :stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :viewBox "0 0 24 24"}
   [:path {:d "M22 12h-4l-3 9L9 3l-3 9H2"}]])

(defn svg-tick []
  [:svg.w-5.h-5 {:fill "none" :stroke "currentColor" :stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :viewBox "0 0 24 24"}
   [:path {:d "M22 11.08V12a10 10 0 11-5.93-9.14"}]
   [:path {:d "M22 4L12 14.01l-3-3"}]])

(defn progress-item [& {:keys [id done selected title text svg end? on-click] :or {end? false}}]
  (let [done? (contains? done id)
        selected? (= id selected)]
    [:a [:div.flex.relative.pb-12 {:key id :on-click #(on-click id)}
     (when-not end? [:div.h-full.w-10.absolute.inset-0.flex.items-center.justify-center
                     [:div.h-full.w-1.bg-gray-200.pointer-events-none]])
     [:div.flex-shrink-0.w-10.h-10.rounded-full.inline-flex.items-center.justify-center.text-white.relative.z-10.hover:bg-blue-200
      {:class (cond selected? "bg-red-500" done? "bg-green-500" :else "bg-indigo-500")} svg]
     [:div.flex-grow.pl-4.h-10
      [:h2.title-font.text-gray-900.mb-1.tracking-wider {:class (if selected? "font-bold" "text-sm font-medium")} title]
      [:p.leading-relaxed {:class (if selected? "font-bold underline" "font-medium")} text]]]]))

(defn progress
  "Display progress through a multi-step process.
  Parameters:
  - title : title
  - items : a sequence of your items (progress-item)"
  [title & items]
  [:div.container.px-5.py-24.mx-auto.flex.flex-wrap
   [:h1.font-bold.font-lg.uppercase title]
   [:div.flex.flex-wrap.w-full.pt-4
    [:div.md:pr-10.md:py-6 {:class "lg:w-2/5 md:w-1/2"}
     (map-indexed #(with-meta %2 {:key %1}) items)
     [:div.md:mt-0.mt-12 {:class "lg:w-3/5 md:w-1/2"}]]]])



(def prog (reagent/atom {:done     #{}
                             :selected :clinician}))

(defn select-item [item]
  (swap! prog assoc :selected item))

(defn refer-page []
  (let [referral (reagent/atom {})]
    (fn []
      (let [user @(rf/subscribe [::users/authenticated-user])
            patient @(rf/subscribe [::patients/current-patient])]
        [:<>
         [nav-bar
          :title "PatientCare v4"                           ;:menu [{:id :refer-patient :title "Refer patient"}]
          :selected :refer-patient
          :show-user? user
          :full-name (:urn.oid.2.5.4/commonName user)
          :initials (:urn.oid.2.5.4/initials user)
          :user-menu [{:id :logout :title "Sign out" :on-click #(rf/dispatch [::users/do-logout])}]]
         (when patient
           (let [deceased (:org.hl7.fhir.Patient/deceased patient)]
             [patient-banner
              :name (:uk.nhs.cfh.isb1506/patient-name patient)
              :nhs-number (:uk.nhs.cfh.isb1504/nhs-number patient)
              :deceased deceased
              :born (str (com.eldrix.pc4.commons.dates/format-date (:org.hl7.fhir.Patient/birthDate patient)) " " (when-not deceased (:uk.nhs.cfh.isb1505/display-age patient)))
              :hospital-identifier (:wales.nhs.cavuhb.Patient/HOSPITAL_ID patient) ;; TODO: switch to using whichever organisation makes sense in context
              :address (get-in patient [:org.hl7.fhir.Patient/currentAddress :org.hl7.fhir.Address/text])]))
         ;;[login-panel :disabled false :on-login #(println "login for user : " %1)]


         ;[example-form]

         [:section.text-gray-600.body-font
          [progress "Make referral"
           [progress-item :id :clinician
            :done (:done @prog)
            :selected (:selected @prog)
            :on-click select-item
            :svg [svg-shield] :title "STEP 1:" :text "Who are you?"]
           [progress-item :id :patient
            :done (:done @prog)
            :selected (:selected @prog)
            :on-click select-item
            :svg [svg-person] :title "STEP 2:" :text "Who is the patient?"]
           [progress-item :id :service
            :selected (:selected @prog)
            :on-click select-item
            :svg [svg-anchor] :title "STEP 3:" :text "To which service?"]
           [progress-item :id :question
            :selected (:selected @prog)
            :on-click select-item
            :svg [svg-graph] :title "STEP 4:" :text "What is the question?"]
           [progress-item :id :send
            :selected (:selected @prog)
            :on-click select-item
            :svg [svg-tick] :title "FINISH:" :text "Send referral" :end? true]]]]
        ))))

(swap! prog assoc :done #{:clinician })
(swap! prog assoc :selected :service)