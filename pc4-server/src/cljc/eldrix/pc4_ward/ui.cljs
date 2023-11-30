(ns ^:deprecated eldrix.pc4-ward.ui
  (:require [clojure.string :as str]
            [com.eldrix.nhsnumber :as nhs-number]
            [pc4.dates :as dates]
            [reagent.core :as reagent])
  (:import [goog.date Date]))


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

(defn show-boolean [x]
  (when x "✔️"))

(defn patient-banner
  [& {:keys [name nhs-number gender born hospital-identifier address deceased on-close content]}]
  [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200.relative
   (when on-close
     [:div.absolute.top-0.5.sm:-top-2.5.right-0.sm:-right-2.5
      [:button.rounded.bg-white.border.hover:bg-gray-300.bg-gray-50.px-1.py-1 {:on-click on-close :title "Close patient record"}
       [:svg {:xmlns "http://www.w3.org/2000/svg" :width "20" :height "20" :viewBox "0 0 18 18"} [:path {:d "M14.53 4.53l-1.06-1.06L9 7.94 4.53 3.47 3.47 4.53 7.94 9l-4.47 4.47 1.06 1.06L9 10.06l4.47 4.47 1.06-1.06L10.06 9z"}]]]])
   (when deceased
     [:div.grid.grid-cols-1.pb-2
      [badge (if (instance? goog.date.Date deceased)
               (str "Died " (dates/format-date deceased))
               "Deceased")]])
   [:div.grid.grid-cols-2.lg:grid-cols-5.pt-1
    (when name [:div.font-bold.text-lg.min-w-min name])
    [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min (when gender [:span.text-sm.font-thin.hidden.sm:inline "Gender "] [:span.font-bold gender])]
    [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min [:span.text-sm.font-thin "Born "] [:span.font-bold born]]
    [:div.lg:hidden.text-right.mr-8.md:mr-0 gender " " [:span.font-bold born]]
    (when nhs-number [:div.lg:text-center.lg:ml-2.min-w-min [:span.text-sm.font-thin "NHS No "] [:span.font-bold (nhs-number/format-nnn nhs-number)]])
    (when hospital-identifier [:div.text-right.min-w-min [:span.text-sm.font-thin "CRN "] [:span.font-bold hospital-identifier]])]
   [:div.grid.grid-cols-1 {:class (if-not deceased "bg-gray-100" "bg-red-100")}
    [:div.font-light.text-sm.tracking-tighter.text-gray-500.truncate address]]
   (when content
     [:div content])])

(defn main-login-panel
  "PatientCare main login panel with hero title, and parameters:
  - on-login   - function to be called with username and password
  - disabled   - if login form should be disabled
  - error      - an error message to show."
  [& params]
  (let [username (reagent/atom "")
        password (reagent/atom "")]
    (fn [& {:keys [on-login disabled error] :or {disabled false}}]
      [:div.flex.items-center.justify-center.bg-gray-50.py-12.px-4.sm:px-6.lg:px-8
       [:div.max-w-md.w-full.space-y-8
        [:form {:on-submit #(.preventDefault %)}
         [:div
          [:h1.mx-auto.w-auto.text-center.text-4xl.text-indigo-700.tracking-tighter "PatientCare " [:span.font-bold "v4"]]]
         [:div.rounded-md.shadow-sm
          [:div.mt-8
           [:label.sr-only {:for "username"} "username"]
           [:input#email-address.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-t-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
            {:name        "username" :type "text" :autoComplete "username" :required true :placeholder "Username" :auto-focus true :disabled disabled
             :on-change   #(reset! username (-> % .-target .-value))
             :on-key-down #(if (= 13 (.-which %)) (do (.focus (.getElementById js/document "password"))))}]]
          [:div.mt-2.mb-4
           [:label.sr-only {:for "password"} "Password"]
           [:input#password.appearance-none.rounded-none.relative.block.w-full.px-3.py-2.border.border-gray-300.placeholder-gray-500.text-gray-900.rounded-b-md.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.focus:z-10.sm:text-sm
            {:name        "password" :type "password" :autoComplete "current-password" :required true :placeholder "Password" :disabled disabled
             :on-change   #(reset! password (-> % .-target .-value))
             :on-key-down #(if (= 13 (.-which %))
                             (do (reset! password (-> % .-target .-value))
                                 (when on-login (on-login @username @password))))}]]]
         (when error
           [box-error-message :message error])
         [:div.mt-2
          [:button.group.relative.w-full.flex.justify-center.py-2.px-4.border.border-transparent.text-sm.font-medium.rounded-md.text-white.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500
           {:type "submit" :on-click #(when on-login (on-login (str/trim @username) @password)) :disabled disabled :class (if disabled "bg-gray-400 animate-pulse" "bg-indigo-600 hover:bg-indigo-700")}
           [:span.absolute.left-0.inset-y-0.flex.items-center.pl-3
            [:svg.h-5.w-5.text-indigo-500.group-hover:text-indigo-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true" :class (when disabled "animate-bounce")}
             [:path {:fill-rule "evenodd" :d "M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" :clip-rule "evenodd"}]]] (if disabled "Signing in" "Sign in")]]]]])))


(defn ui-textfield [& {:keys [name autocomplete field-type error] :or {field-type "text"}}]
  [:<> [:input.mt-1.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.shadow-sm.sm:text-sm.border-2.rounded-md.p-1
        {:type  field-type :name name :autocomplete autocomplete
         :class (if-not error "border-gray-200" "border-red-600")}]
   (if (string? error) [:span.text-red-600.text-sm error])])


(defn ui-label [& {:keys [for label]}]
  [:label.block.text-sm.font-medium.text-gray-700 {:for for} label])

(defn example-form
  "This is simply an experiment in styling a form with some help text
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

(defn progress-item [& {:keys [id done selected active title text svg end? on-click] :or {end? false}}]
  (let [done? (contains? done id)
        selected? (= id selected)
        active? (contains? active id)]
    [:a [:div.flex.relative.pb-12 {:key id :on-click #(when active? (on-click id))}
         (when-not end? [:div.h-full.w-10.absolute.inset-0.flex.items-center.justify-center
                         [:div.h-full.w-1.bg-gray-200.pointer-events-none]])
         [:div.flex-shrink-0.w-10.h-10.rounded-full.inline-flex.items-center.justify-center.text-white.relative.z-10
          {:class (cond
                    (and selected? done?) "bg-green-400 shadow-x1 border border-gray-800"
                    (and selected? (not done?)) "bg-indigo-500 shadow-xl border border-gray-800"
                    done? "bg-green-500 hover:opacity-70"
                    active? "bg-indigo-500 hover:opacity-70"
                    :else "bg-red-500 opacity-50")} svg]
         [:div.flex-grow.pl-4.h-10
          [:h2.title-font.text-gray-900.mb-1.tracking-wider
           {:class (if selected? "font-bold" "text-sm font-medium")} title]
          [:p.leading-relaxed {:class (if selected? "font-bold underline" "font-medium")} text]]]]))

(defn progress
  "Display progress through a multi-step process.
  Parameters:
  - title : title
  - items : a sequence of your items (progress-item)"
  [title & items]
  [:div.container.px-4.py-4.mx-auto.flex.flex-wrap
   [:h1.font-bold.font-lg.uppercase title]
   [:div.flex.flex-wrap.w-full.pt-4
    [:div.md:py-6
     (map-indexed #(with-meta %2 {:key %1}) items)
     [:div.md:mt-0.mt-12 {:class "lg:w-3/5 md:w-1/2"}]]]])


(defn panel
  [{:keys [title cols save-label save-disabled cancel-label on-save on-cancel] :or {cols 1}} & children]
  [:section.p-2.mx-auto.bg-white.rounded-md.shadow-md.dark:bg-gray-800
   (when title [:h2.text-lg.font-semibold.text-gray-700.dark:text-white title])
   [:div.grid.grid-cols-1.gap-6.mt-4
    (into [:div] children)]
   (when (or cancel-label save-label)
     [:div.flex.mt-6
      (when cancel-label
        [:button.px-6.py-2.leading-5.text-white.transition-colors.duration-200.transform.bg-gray-400.rounded-md.hover:bg-gray-600.focus:outline-none.focus:bg-gray-600
         {:on-click on-cancel} cancel-label])
      (when save-label
        [:button.px-6.py-2.leading-5.text-white.transition-colors.duration-200.transform.bg-gray-700.rounded-md
         {:on-click on-save :disabled save-disabled :class (if save-disabled "opacity-50 pointer-events-none" "hover:bg-gray-600.focus:outline-none.focus:bg-gray-600")} save-label])])])


(defn textfield-control
  [value & {:keys [name type label placeholder required auto-focus disabled on-change on-blur on-enter help-text] :or {type "text"}}]
  [:div
   (when label [:label.block.text-sm.font-medium.text-gray-600 {:for name} label])
   [:div.mt-1
    [:input.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md
     {:name          name
      :type          type
      :placeholder   placeholder :required required
      :class         (if-not disabled ["text-gray-700" "bg-white" "shadow"] ["text-gray-600" "bg-gray-50" "italic"])
      :disabled      disabled
      :default-value value
      :auto-focus    auto-focus
      :on-change     #(when on-change (let [v (-> % .-target .-value)]
                                        (on-change (if (str/blank? v) nil v))))
      :on-blur       #(when on-blur (on-blur))
      :on-key-down   #(when (and on-enter (= 13 (.-which %))) (on-enter))
      :on-wheel      #(when (= type "number") (-> % .-target .blur))}]
    (when help-text [:p.text-sm.text-gray-500.italic help-text])]])

(defn textarea [& {:keys [name value label on-change rows] :or {rows "5"}}]
  [:div
   (when label [:label.block.text-sm.font-medium.text-gray-700 {:for "comment"} label])
   [:div.mt-1
    [:textarea#comment.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md
     {:rows      rows
      :name      name
      :value     value
      :on-change #(when on-change (let [v (-> % .-target .-value)] (on-change (if (str/blank? v) nil v))))}]]])


(defn select
  "A select control that appears as a pop-up."
  [& {:keys [name label value choices id-key display-key default-value select-fn
             no-selection-string on-key-down disabled? sort? sort-fn]
      :or   {id-key identity display-key identity sort? true}}]
  (let [all-choices (if (and value (id-key value) (not (some #(= (id-key value) (id-key %)) choices)))
                      (conj choices value) choices)
        sorted-values (if-not sort? all-choices (sort-by (or sort-fn display-key) all-choices))]
    (when (and default-value (str/blank? value))
      (select-fn default-value))
    [:div
     (when label [ui-label :for name :label label])
     [:select#location.mt-1.block.w-full.pl-3.pr-10.py-2.text-base.border-gray-300.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm.rounded-md
      {:name        name
       :disabled    disabled?
       :value       (str (id-key value))
       :on-key-down #(when on-key-down (on-key-down (.-which %)))
       :on-change   #(when select-fn
                       (let [idx (-> % .-target .-selectedIndex)]
                         (if (and no-selection-string (= 0 idx))
                           (select-fn nil)
                           (select-fn (nth sorted-values (if no-selection-string (- idx 1) idx))))))}
      (when no-selection-string [:option.py-1 {:value nil :id nil} no-selection-string])
      (for [choice sorted-values
            :let [id (id-key choice)]]
        ^{:key id} [:option.py-1 {:value (str id)} (display-key choice)])]]))

(defn tabbed-menu
  "A simple tabbed menu that appears as a select control when on mobile and as a
  tabbed menu with underline for selected option on wider screens.
  Parameters:
  - name        : name of the control
  - value       : current value
  - choices     : a sequence of choices
  - display-key : key, or function to get what to display from each choice
  - value-key   : key, or function, to get value from each choice
  - on-change   : called with new value when changed."
  [& {:keys [name value choices display-key value-key on-change] :or {name "tabs" display-key identity value-key identity}}]
  [:div
   [:div.sm:hidden
    [:label.sr-only {:for name} "Select a tab"]
    [:select#tabs.block.w-full.pl-3.pr-10.py-2.text-base.border-gray-300.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm.rounded-md
     {:name      name
      :value     value
      :on-change #(when on-change
                    (let [idx (-> % .-target .-selectedIndex)]
                      (on-change (value-key (nth choices (- idx 1))))))}
     (for [choice choices]
       [:option {:key (value-key choice)} (display-key choice)])]]
   [:div.hidden.sm:block
    [:div.border-b.border-gray-200
     [:nav.-mb-px.flex.space-x-8 {:aria-label "Tabs"}
      (for [choice choices]
        (if (= value (value-key choice))
          [:a.border-indigo-500.text-indigo-600.whitespace-nowrap.py-4.px-1.border-b-2.font-medium.text-sm
           {:key (value-key choice)} (display-key choice)]
          [:a.border-transparent.cursor-pointer.text-gray-500.hover:text-gray-700.hover:border-gray-300.whitespace-nowrap.py-4.px-1.border-b-2.font-medium.text-sm
           {:key      (value-key choice)
            :on-click #(when on-change
                         (on-change (value-key choice)))}
           (display-key choice)]))]]]])


(defn select-or-autocomplete
  "A flexible select/autocompletion control.
  Parameters:
  - id             : identifier to use
  - label          : label to show
  - value          : currently selected value, if any
  - id-key         : function to get id from a value (e.g. could be a keyword)
  - display-key    : function to get display from value
  - select-display-key : function to get display from value in the select
  - common-choices : collection of common choices to show
  - autocomplete-fn: autocompletion function that takes one parameter
  - clear-fn       : function to run to clear autocompletion, if required
  - select-fn      : function to be called with a selected id
  - minimum-chars  : minimum number of characters needed to run autocompletion
  - autocomplete-results - results of autocompletion
  - placeholder    : placeholder text for autocompletion
  - no-selection-string : label for select when nothing selected
  - size           : size of the select box, default 5
  - disabled?      : if disabled"
  [& {:keys [id clear-fn]}]
  (when clear-fn (clear-fn))
  (let [mode (reagent/atom nil)]
    (fn [& {:keys [label value id-key display-key select-display-key common-choices autocomplete-fn
                   clear-fn autocomplete-results select-fn placeholder
                   minimum-chars no-selection-string default-value size disabled?]
            :or   {minimum-chars 3 id-key identity display-key identity size 5}}]
      [:<>
       (when label [ui-label :label label])
       (cond
         (and (= :select (or @mode :select)) (or value (seq common-choices)))
         (let [value-in-choices? (some #(= % value) common-choices)
               all-choices (if (and value (not value-in-choices?)) (conj common-choices value) common-choices)
               choices (zipmap (map id-key all-choices) all-choices)
               sorted-choices (sort-by display-key (vals choices))]
           (when (and default-value (str/blank? value))
             (select-fn default-value))
           [:<>
            [:select.block.border.p-3.bg-white.rounded.outline-none.w-full
             {:disabled  disabled?, :value     (str (id-key value))
              :on-change #(when select-fn
                            (let [idx (-> % .-target .-selectedIndex)]
                              (if (and no-selection-string (= 0 idx))
                                (select-fn nil)
                                (select-fn (nth sorted-choices (if no-selection-string (dec idx) idx))))))}
             (when no-selection-string [:option.py-1 {:value nil :id nil} no-selection-string])
             (for [choice sorted-choices]
               (let [id (id-key choice)]
                 [:option.py-1 {:value (str id) :key id} (if select-display-key (select-display-key choice) (display-key choice))]))]
            [:button.bg-blue-400.text-white.text-xs.mt-1.py-1.px-2.rounded-full
             {:disabled disabled? :class (if disabled? "opacity-50" "hover:bg-blue-500")
              :on-click #(reset! mode :autocomplete)} "..."]])
         :else
         [:<>
          [:input.w-full.p-1.block.w-full.px-4.py-1.border.border-gray-300.rounded-md.dark:bg-gray-800.dark:text-gray-300.dark:border-gray-600.focus:border-blue-500.dark:focus:border-blue-500.focus:outline-none.focus:ring
           {:id            id, :type "text"
            :placeholder   placeholder :required true
            :class         ["text-gray-700" "bg-white" "shadow"]
            :default-value nil, :disabled disabled?, :auto-focus true
            :on-change     #(let [s (-> % .-target .-value)]
                              (if (>= (count s) minimum-chars)
                                (autocomplete-fn s)
                                (when clear-fn (clear-fn))))}]
          [:button.bg-blue-400.hover:bg-blue-500.text-white.text-xs.my-1.py-1.px-2.rounded-full
           {:disabled disabled? :on-click #(reset! mode :select)} "Close"]

          [:div.w-full
           [:select.w-full.border.border-gray-300.rounded-md
            {:multiple        false
             :size            size
             :disabled        disabled?
             :on-change       #(when select-fn (tap> autocomplete-results) (select-fn (nth autocomplete-results (-> % .-target .-selectedIndex))))
             :on-double-click #(reset! mode :select)}
            (for [result autocomplete-results]
              (let [id (id-key result)]
                [:option {:value result :key id}
                 (display-key result)]))]]])])))

(defn flat-menu
  "A simple flat menu.
  Parameters:
  - items       : a collection of items, each with :id and :title
  - selected-id : identifier of the selected item
  - select-fn   : function to call on select with the identifier"
  [items & {:keys [selected-id select-fn]}]
  [:ul.flex
   (for [item items
         :let [id (:id item) title (:title item)]
         :when item]
     [:li.mr3 {:key id}
      (if (= selected-id id)
        [:a.inline-block.border.border-blue-500.rounded.py-1.px-3.bg-blue-500.text-white.cursor-not-allowed title]
        [:a.inline-block.border.border-white.rounded.hover:border-gray-200.text-blue-500.hover:bg-gray-200.py-1.px-3.cursor-pointer {:on-click #(when select-fn (select-fn id))} title])])])



(defn section-heading [title & {:keys [buttons]}]
  [:div.bg-white.px-4.py-5.border-b.border-gray-200.sm:px-6
   [:div.-ml-4.-mt-2.flex.items-center.justify-between.flex-wrap.sm:flex-nowrap
    [:div.ml-4.mt-2
     [:h3.text-lg.leading-6.font-medium.text-gray-900 title]]
    (when buttons
      [:div.ml-4.mt-2.flex-shrink-0
       buttons])]])

(defn list-entities-fixed
  "A fixed list of entities."
  [& {:keys [items headings width-classes id-key value-keys on-edit on-delete] :or {id-key identity} :as params}]
  (when-not (= (count headings) (count value-keys))
    (throw (ex-info "Number of headings must be the same as the number of value-keys" params)))
  [:div.flex.flex-col
   [:div.my-2.overflow-x-auto.sm:-mx-6.lg:-mx-8
    [:div.py-2.align-middle.inline-block.min-w-full.sm:px-2.lg:px-2
     [:div.shadow.overflow-hidden.border-b.border-gray-200.sm:rounded-lg
      [:table.min-w-full.divide-y.divide-gray-200
       (when (seq width-classes) {:class "table-fixed"})
       [:thead.bg-gray-50
        [:tr
         (for [heading headings]
           [:th.px-2.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider
            (cond-> {:scope "col" :key heading}
                    (get width-classes heading) (assoc :class (get width-classes heading))) heading])
         (when (or on-edit on-delete)
           [:th.relative.px-6.py-3 {:scope "col"}
            [:span.sr-only "Actions"]])]]
       [:tbody
        (for [item items]
          [:tr.bg-white
           {:key (id-key item)}
           (for [value-key value-keys]
             [:td.px-2.py-4.whitespace-nowrap.text-sm.text-gray-500 {:key value-key} (value-key item)])
           (when on-edit [:td.px-6.py-4.whitespace-nowrap.text-right.text-sm.font-medium
                          [:a.text-indigo-600.cursor-pointer.hover:text-indigo-900 {:on-click #(on-edit item)} "Edit"]])
           (when on-delete [:td.px-6.py-4.whitespace-nowrap.text-right.text-sm.font-medium
                            [:a.text-indigo-600.cursor-pointer.hover:text-indigo-900 {:on-click #(on-delete item)} "Delete"]])])]]]]]])


(defn html-date-picker [& {:keys [name value on-change min-date max-date]}]
  (let [d (cond (instance? goog.date.DateTime value)
                (Date. (.getYear value) (.getMonth value) (.getDate value))
                (instance? goog.date.Date value)
                (.toIsoString ^goog.date.Date value true)
                :else value)]
    [:input#max-w-lg.block.w-full.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.sm:max-w-xs.sm:text-sm.border-gray-300.rounded-md
     (cond-> {:type          "date"
              :name          name
              :default-value (if (instance? goog.date.Date d) (.toIsoString ^js d true) d)
              :on-change     #(let [d (Date/fromIsoString (-> % .-target .-value))]
                                (when on-change (on-change d)))}
             min-date
             (assoc :min (.toIsoString ^goog.date.Date min-date true))
             max-date
             (assoc :max (.toIsoString ^goog.date.Date max-date true)))]))


(defn button [& {:keys [disabled? role on-click label]}]
  [:button.w-full.inline-flex.justify-center.rounded-md.border.shadow-sm.px-4.py-2.text-base.font-medium.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-red-500.sm:ml-3.sm:w-auto.sm:text-sm
   {:type     "button"
    :class    [(case role :primary "border-transparent text-white bg-red-600" "bg-white") (when disabled? "opacity-50")]
    :disabled disabled?
    :on-click #(when (not disabled?) (on-click))} label])

(defn modal [& {:keys [disabled? title content actions on-close]}]
  [:div.fixed.z-10.inset-0.overflow-y-auto
   {:aria-labelledby title :role "dialog" :aria-modal "true"
    :class           (when disabled? "hidden")}
   [:div.flex.items-end.justify-center.min-h-screen.pt-4.px-4.pb-20.text-center.sm:block.sm:p-0
    [:div.fixed.inset-0.bg-gray-500.bg-opacity-75.transition-opacity
     {:aria-hidden "true"
      :on-click    #(when on-close (on-close))}]
    [:span.hidden.sm:inline-block.sm:align-middle.sm:h-screen {:aria-hidden "true"} "&#8203;"]
    [:div.inline-block.align-bottom.bg-white.rounded-lg.px-4.pt-5.pb-4.text-left.overflow-hidden.shadow-xl.transform.transition-all.sm:my-8.sm:align-middle.sm:max-w-screen-sm.lg:max-w-screen-lg.sm:w-full.sm:p-6
     [:div
      [:div.mt-3.text-center.sm:mt-5
       (when title [:h3#modal-title.text-lg.leading-6.font-medium.text-gray-900 title])]
      [:div.mt-2
       content]]
     (when (seq actions)
       [:div.mt-5.sm:mt-4.sm:flex.sm:flex-row-reverse
        (for [action actions
              :when (not (:hidden? action))]
          ^{:key (:id action)} [button
                                :role (:role action)
                                :label (:title action)
                                :disabled? (:disabled? action)
                                :on-click #(when-let [f (:on-click action)] (f))])])]]])


(defn checkbox [& {:keys [name label description checked on-change]}]
  [:div.relative.flex.items-start
   [:div.flex.items-center.h-5
    [:input#comments.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300.rounded
     {:name      name :type "checkbox"
      :checked   checked
      :on-change (when on-change #(on-change (-> % .-target .-checked)))}]]
   [:div.ml-3.text-sm
    [:label.font-medium.text-gray-700 {:for name} label]
    (when description [:p#comments-description.text-gray-500 description])]])

(defn multiple-checkboxes
  "A convenient way of presenting multiple checkboxes.
  Parameters:
  - legend      : a legend to be used for screenreaders
  - m           : a map containing all values
  - keys        : a sequence of keys to be set to true or false
  - display-key : a function such as a keyword, a map or function to derive display"
  [m & {:keys [legend keys display-key on-change] :or {display-key name}}]
  [:fieldset.space-y-5
   (when legend [:legend.sr-only legend])
   (for [item keys]
     ^{:key item} [checkbox
                   :name (name item)
                   :label (display-key item)
                   :checked (or (item m) false)
                   :on-change #(when on-change (on-change (assoc m item %)))])])

