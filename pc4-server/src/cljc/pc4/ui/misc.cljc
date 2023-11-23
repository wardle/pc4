(ns pc4.ui.misc
  (:require [clojure.string :as str]
            [com.eldrix.pc4.commons.dates :as dates])
  (:import #?@(:clj  [(java.time LocalDate)
                      (java.time.format DateTimeFormatter)]
               :cljs [(goog.date Date)])))

(defn icon-home []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 12l8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25"}]])

(defn icon-team []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M15 19.128a9.38 9.38 0 002.625.372 9.337 9.337 0 004.121-.952 4.125 4.125 0 00-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 018.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0111.964-3.07M12 6.375a3.375 3.375 0 11-6.75 0 3.375 3.375 0 016.75 0zm8.25 2.25a2.625 2.625 0 11-5.25 0 2.625 2.625 0 015.25 0z"}]])

(defn icon-user []                                          ;; from https://heroicons.com/
  [:svg.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z"}]])

(defn icon-building-office []                               ;; from https://heroicons.com/
  [:svg.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 21h19.5m-18-18v18m10.5-18v18m6-13.5V21M6.75 6.75h.75m-.75 3h.75m-.75 3h.75m3-6h.75m-.75 3h.75m-.75 3h.75M6.75 21v-3.375c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21M3 3h12m-.75 4.5H21m-3.75 3.75h.008v.008h-.008v-.008zm0 3h.008v.008h-.008v-.008zm0 3h.008v.008h-.008v-.008z"}]])

(defn icon-building-library []                              ;; from https://heroicons.com/
  [:svg.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M12 21v-8.25M15.75 21v-8.25M8.25 21v-8.25M3 9l9-6 9 6m-1.5 12V10.332A48.36 48.36 0 0012 9.75c-2.551 0-5.056.2-7.5.582V21M3 21h18M12 6.75h.008v.008H12V6.75z"}]])

(defn icon-envelope-open []
  [:svg.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M21.75 9v.906a2.25 2.25 0 01-1.183 1.981l-6.478 3.488M2.25 9v.906a2.25 2.25 0 001.183 1.981l6.478 3.488m8.839 2.51l-4.66-2.51m0 0l-1.023-.55a2.25 2.25 0 00-2.134 0l-1.022.55m0 0l-4.661 2.51m16.5 1.615a2.25 2.25 0 01-2.25 2.25h-15a2.25 2.25 0 01-2.25-2.25V8.844a2.25 2.25 0 011.183-1.98l7.5-4.04a2.25 2.25 0 012.134 0l7.5 4.04a2.25 2.25 0 011.183 1.98V19.5z"}]])

(defn icon-plus-circle []                                   ;; from https://heroicons.com/
  [:svg.-ml-1.mr-3.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M12 9v6m3-3H9m12 0a9 9 0 11-18 0 9 9 0 0118 0z"}]])

(defn icon-magnifying-glass []
  [:svg.w-6.h-6.-ml-1.mr-3 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z"}]])

(defn icon-folder []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 12.75V12A2.25 2.25 0 014.5 9.75h15A2.25 2.25 0 0121.75 12v.75m-8.69-6.44l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z"}]])

(defn icon-calendar []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M6.75 3v2.25M17.25 3v2.25M3 18.75V7.5a2.25 2.25 0 012.25-2.25h13.5A2.25 2.25 0 0121 7.5v11.25m-18 0A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75m-18 0v-7.5A2.25 2.25 0 015.25 9h13.5A2.25 2.25 0 0121 11.25v7.5"}]])

(defn icon-inbox []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 13.5h3.86a2.25 2.25 0 012.012 1.244l.256.512a2.25 2.25 0 002.013 1.244h3.218a2.25 2.25 0 002.013-1.244l.256-.512a2.25 2.25 0 012.013-1.244h3.859m-19.5.338V18a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18v-4.162c0-.224-.034-.447-.1-.661L19.24 5.338a2.25 2.25 0 00-2.15-1.588H6.911a2.25 2.25 0 00-2.15 1.588L2.35 13.177a2.25 2.25 0 00-.1.661z"}]])

(defn icon-reports []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z"}]])

(defn icon-bell []
  [:svg.h-6.w-6 {:xmlns   "http://www.w3.org/2000/svg" :fill "none"
                 :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"}]])

(defn icon-chevron-down []
  [:svg.-mr-1.ml-2.h-5.w-5 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "white" :aria-hidden "true"}
   [:path {:fill-rule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clip-rule "evenodd"}]])

(defn avatar-8 []
  [:span.inline-block.h-8.w-8.overflow-hidden.rounded-full.bg-gray-100
   [:svg.h-full.w-full.text-gray-300 {:fill "currentColor" :viewBox "0 0 24 24"}
    [:path {:d "M24 20.993V24H0v-2.996A14.977 14.977 0 0112.004 15c4.904 0 9.26 2.354 11.996 5.993zM16.002 8.999a4 4 0 11-8 0 4 4 0 018 0z"}]]])

(defn avatar-14 []
  [:span.inline-block.h-14.w-14.overflow-hidden.rounded-full.bg-gray-100
   [:svg.h-full.w-full.text-gray-300 {:fill "currentColor" :viewBox "0 0 24 24"}
    [:path {:d "M24 20.993V24H0v-2.996A14.977 14.977 0 0112.004 15c4.904 0 9.26 2.354 11.996 5.993zM16.002 8.999a4 4 0 11-8 0 4 4 0 018 0z"}]]])

(defn badge
  "Display a small badge with the text specified."
  [{:keys [s text-color bg-color uppercase?] :or {text-color "text-red-200" bg-color "bg-red-500" uppercase? true}}]
  [:span.text-xs.text-center.font-semibold.inline-block.py-1.px-2.uppercase.rounded-full.ml-1.last:mr-0.mr-1
   {:class (str/join " " [text-color bg-color (when uppercase? "uppercase")])} s])

(defn box-error-message
  [{:keys [title message]}]
  (when message
    [:div.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.relative.shadow-md {:role "alert"}
     (when title [:span.strong.font-bold.mr-4 title])
     [:span.block.sm:inline message]]))


(defn two-column-card [{:keys [title subtitle items long-items]}]
  [:div.overflow-hidden.bg-white.shadow.sm:rounded-lg
   [:div.px-4.py-5.sm:px-6
    [:h3.text-base.font-semibold.leading-6.text-gray-900 title]
    [:p.mt-1.max-w-2xl.text-sm.text-gray-500 subtitle]]
   [:div.border-t.border-gray-200.px-4.py-5.sm:px-6
    [:dl.grid.grid-cols-1.gap-x-4.gap-y-8.sm:grid-cols-2
     (for [{:keys [title content]} items]
       ^{:key title}
       [:div.sm:col-span-1
        [:dt.text-sm.font-medium.text-gray-500 title]
        [:dd.mt-1.text-sm.text-gray-900 content]])
     (for [{:keys [title content]} long-items]
       ^{:key title}
       [:div.sm:col-span-2
        [:dt.text-sm.font-medium.text-gray-500 title]
        [:dd.mt-1.text-sm.text-gray-900 content]])]]])


(defn vertical-navigation
  "Vertical navigation bar with optional nested sub-menu."
  [{:keys [selected-id items sub-menu]}]
  [:nav {:aria-label "Sidebar"}
   [:div.space-y-1
    (for [{:keys [id icon content attrs]} items
          :when id]
      (if (= selected-id id)
        ^{:key id}
        [:a.bg-gray-300.text-gray-900.group.flex.items-center.rounded-md.px-2.py-2.text-sm.font-medium {:aria-current "page"}
         [:span.pr-2 icon] content]
        ^{:key id}
        [:a.text-gray-600.hover:bg-gray-50.hover:text-gray-900.font-bold.group.flex.items-center.rounded-md.px-2.py-2.text-sm.font-medium
         attrs
         [:span.pr-2 icon] content]))]
   (when sub-menu
     [:div.mt-4
      [:h3.px-3.text-sm.font-medium.text-gray-500 (:title sub-menu)]
      [:div.pt-1.space-y-1
       (for [{:keys [id attrs content]} (:items sub-menu)
             :when content]
         ^{:key id} [:a.group.flex.items-center.rounded-md.px-3.my-2.text-sm.font-medium.text-gray-600.hover:bg-gray-50.hover:text-gray-900 attrs
                     content])]])])

(defn grid-list [& items]
  (into [:ul.grid.grid-cols-1.gap-6.sm:grid-cols-2.lg:grid-cols-3 {:role "list"}] items))

(defn grid-list-item [{:keys [title subtitle image content]}]
  [:li.col-span-1.divide-y.divide-gray-200.rounded-lg.bg-white.shadow
   [:div.flex.w-full.items-center.justify-between.space-x-6.p-6
    [:div.flex-1.truncate
     [:div.flex.items-center.space-x-3
      [:h3.truncate.text-sm.font-medium.text-gray-900 title]]
     [:p.mt-1.truncate.text-sm.text-gray-500 subtitle]]
    (when image (cond
                  (:url image) [:img.max-h-16.max-w-16.flex-shrink-0.rounded-full.bg-gray-300 {:src (:url image) :alt title}]
                  (:content image) (:content image)))]
   [:div content]])

(defn description-list-item [{:keys [label content]}]
  [:div.py-4.sm:grid.sm:grid-cols-3.sm:gap-4.sm:py-5.sm:px-6
   [:dt.text-sm.font-medium.text-gray-500 label]
   [:dd.mt-1.text-sm.text-gray-900.sm:col-span-2.sm:mt-0 content]])

(defn description-list [{:keys [title subtitle]} & items]
  [:div.overflow-hidden.bg-white.shadow.sm:rounded-lg
   [:div.px-4.py-5.sm:px-6
    [:h3.text-base.font-semibold.leading-6.text-gray-900 title]
    [:p.mt-1.max-w-2xl.text-sm.text-gray-500 subtitle]]
   [:div.border-t.border-gray-200.px-4.py-5.sm:p-0
    (into [:dl.sm:divide-y.sm:divide-gray-200] items)]])

(defn action-button [props s]
  [:div.mt-5.sm:ml-6.sm:mt-0.sm:flex.sm:flex-shrink-0.sm:items-center
   [:button.inline-flex.items-center.rounded-md.bg-indigo-600.px-3.py-2.text-sm.font-semibold.text-white.shadow-sm.hover:bg-indigo-500.focus-visible:outline.focus-visible:outline-2.focus-visible:outline-offset-2.focus-visible:outline-indigo-600
    (merge {:type "button"} props) s]])

(defn action-panel [{:keys [title description button]}]
  [:div.bg-white.shadow.sm:rounded-lg
   [:div.px-4.py-5.sm:p-6
    [:div.sm:flex.sm:items-start.sm:justify-between
     [:div
      [:h3.text-base.font-semibold.leading-6.text-gray-900 title]
      [:div.mt-2.max-w-xl.text-sm.text-gray-500
       [:p description]]]
     [:div.mt-5.sm:ml-6.sm:mt-0.sm:flex.sm:flex-shrink-0.sm:items-center
      button]]]])


(defn breadcrumb-home
  "A breadcrumb home button. Designed to be used within a [[breadcrumbs]]
  component. Set props to configure the properties on the anchor tag.
  e.g.
  ```
    (breadcrumb-home {:href \"#\"})
  ```"
  [props]
  [:li.flex
   [:div.flex.items-center
    [:a.text-gray-400.hover:text-gray-500 props
     [:svg.h-5.w-5.flex-shrink-0 {:viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
      [:path {:fill-rule "evenodd" :d "M9.293 2.293a1 1 0 011.414 0l7 7A1 1 0 0117 11h-1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-3a1 1 0 00-1-1H9a1 1 0 00-1 1v3a1 1 0 01-1 1H5a1 1 0 01-1-1v-6H3a1 1 0 01-.707-1.707l7-7z" :clip-rule "evenodd"}]]
     [:span.sr-only "Home"]]]])

(defn breadcrumb-item
  [props title]
  [:li.flex
   [:div.flex.items-center
    [:svg.h-full.w-6.flex-shrink-0.text-gray-200 {:viewBox "0 0 24 44" :preserveAspectRatio "none" :fill "currentColor" :aria-hidden "true"}
     [:path {:d "M.293 0l22 22-22 22h1.414l22-22-22-22H.293z"}]]
    [:a.ml-4.text-sm.font-medium.text-gray-500.hover:text-gray-700 props title]]])

(defn breadcrumbs
  "A breadcrumb component. Use with [[breadcrumb-item]] and [[breadcrumb-home]].
  e.g.
  ```
  (breadcrumbs
    (breadcrumb-home {:href \"/app/home\"})
    (breadcrumb-item {:href \"#\"} \"Projects\"))
  ```"
  [& items]
  [:nav.flex.border-b.border-gray-200.bg-white {:aria-label "Breadcrumb"}
   [:ol.mx-auto.flex.w-full.max-w-screen-xl.space-x-4.px-4.sm:px-6.lg:px-8 {:role "list"}
    items]])

(defn ui-label [{:keys [for label]}]
  [:label.block.text-sm.font-medium.text-gray-600 (when for {:htmlFor for}) label])

(defn ui-textfield
  [{:keys [id value type label placeholder required disabled help-text on-change auto-focus] :as attrs}]
  [:div
   (when label (ui-label {:for id :label label}))
   [:div.mt-1
    [:input.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border.border-gray-200.rounded-md.p-2
     (merge
       (dissoc attrs :help-text :on-enter)
       (when on-change {:on-change #(on-change (-> % .-target .-value))})
       (when-not disabled {:classes ["text-gray-700" "bg-white" "shadow"]}))]
    (when help-text [:p.text-sm.text-gray-500.italic help-text])]])

(defn unparse-local-date [d]
  (when d
    #?(:clj  (.format ^LocalDate d (DateTimeFormatter/ISO_DATE))
       :cljs (.toIsoString ^Date d true))))

(defn ui-local-date
  [{:keys [id label value default-date min-date max-date on-blur on-enter-key on-change] :as params}]
  (println {:ui-local-date params})
  [:div
   (when label (ui-label {:for id :label label}))
   [:div.mt-1
    [:input
     (cond-> {:type "date", :value (unparse-local-date (or value default-date))}
       id (assoc :name id)
       min-date (assoc :min (unparse-local-date min-date))
       max-date (assoc :max (unparse-local-date max-date))
       on-blur (assoc :on-blur #(on-blur (Date/fromIsoString (-> % .-target .-value))))
       on-enter-key (assoc :on-key-down #(when (= 13 (.-keyCode %)) (on-enter-key)))
       on-change (assoc :on-change #(on-change (Date/fromIsoString (-> % .-target .-value)))))]]])


(defn ui-button [{:keys [disabled? role on-click]} content]
  (into [:button.inline-flex.justify-center.rounded-md.border.shadow-sm.px-4.py-2.text-base.font-medium.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-red-500.sm:ml-3.sm:text-sm
         {:type     "button"
          :class    [(case role :primary "border-transparent text-white bg-red-600" "bg-gray-100") (when disabled? "opacity-50")]
          :disabled disabled?
          :on-click #(when (not disabled?) (on-click))}] content))

(defn menu-button [{:keys [disabled? role on-click]} content]
  (into [:button.w-full.inline-flex.justify-center.rounded-md.border.shadow-sm.px-4.py-2.text-base.font-medium.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-red-500.sm:text-sm
         {:type     "button"
          :class    [(case role :primary "border-transparent text-white bg-red-600" "bg-gray-100") (when disabled? "opacity-50")]
          :disabled disabled?
          :on-click #(when (not disabled?) (on-click))}] content))

(defn ui-select
  "A select control that appears as a pop-up."
  [{:keys [name label value choices id-key display-key default-value select-fn
           no-selection-string on-key-down disabled? sort? sort-fn]
    :or   {id-key identity display-key identity sort? true}}]
  (let [all-choices (if (and value (id-key value) (not (some #(= (id-key value) (id-key %)) choices)))
                      (conj choices value) choices)
        sorted-values (if-not sort? all-choices (sort-by (or sort-fn display-key) all-choices))]
    #?(:cljs (when (and select-fn default-value (str/blank? value)) (select-fn default-value))) ;;in cljs, select the chosen value
    [:div
     (when label (ui-label {:for name :label label}))
     [:select.mt-1.block.pl-3.pr-10.py-2.text-base.border-gray-300.focus:outline-none.focus:ring-indigo-500.focus:border-indigo-500.sm:text-sm.rounded-md
      {:name        name
       :disabled    disabled?
       :value       (when value (str (id-key value)))
       :on-key-down #(when on-key-down #?(:cljs (on-key-down (.-which %))))
       :on-change   #(when select-fn #?(:cljs (let [idx (-> % .-target .-selectedIndex)]
                                                (if (and no-selection-string (= 0 idx))
                                                  (select-fn nil)
                                                  (select-fn (nth sorted-values (if no-selection-string (- idx 1) idx)))))))}
      (when no-selection-string [:option {:value nil :id nil} no-selection-string])
      (for [choice sorted-values
            :let [id (id-key choice)]]
        ^{:key id} [:option {:value (str id)} (display-key choice)])]]))


(defn ui-title [{:keys [key title subtitle]} & content]
  [:div.sm:flex.sm:items-center.p-4 (when key {:key key})
   [:div.sm:flex-auto
    [:h1.text-xl.font-semibold.text-gray-900 title]
    (when subtitle
      [:p.mt-2.text-sm.text-gray-700 subtitle])
    (when content
      (into [:div.mt-4.sm:mt-0.sm:ml-16.sm:flex-none] content))]])

(defn ui-simple-form-title [{:keys [title]}]
  [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
   [:div.mt-1.sm:mt-0.sm:col-span-2
    [:div.w-full.rounded-md.shadow-sm.space-y-2
     [:h3.text-lg.font-medium.leading-6.text-gray-900 title]]]])

(defn ui-simple-form-item [{:keys [html-for label]} & content]
  [:div.grid.grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
   [:label.col-span-1.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2 (when html-for {:html-for html-for})
    label]
   (into [:div.mt-1.sm:mt-0.col-span-2] content)])

(defn ui-simple-form [& content]
  [:form {:onSubmit #(.preventDefault %)}
   [:div.space-y-8.divide-y.divide-gray-200.sm:space-y-5
    (into [:div.mt-2.sm:mt-5.space-y-6.sm:space-y-5] content)]])

(defn ui-table [& content]
  [:div.flex.flex-col
   [:div.-my-2.-mx-4.overflow-x-auto.sm:-mx-6.lg:-mx-8
    [:div.inline-block.min-w-full.py-2.align-middle.md:px-6.lg:px-8
     [:div.overflow-hidden.shadow.ring-1.ring-black.ring-opacity-5.md:rounded-lg
      (into [:table.min-w-full.divide-y.divide-gray-200] content)]]]])

(defn ui-table-head [& content]
  (into [:thead.bg-gray-50] content))

(defn ui-table-heading [props content]
  (into [:th.px-2.py-3.text-left.text-xs.font-semibold.text-gray-900.uppercase.tracking-wider props] content))

(defn ui-table-body [& content]
  (into [:tbody.bg-white] content))

(defn ui-table-row [props & content]
  (into [:tr props] content))

(defn ui-table-cell [props & content]
  (into [:td.px-2.py-4.text-sm.text-gray-500 props] content))

(defn ui-table-link [props content]
  [:a.text-indigo-600.cursor-pointer.hover:text-indigo-900 props content])

(defn ui-modal
  "A modal dialog. Parameters:
  - disabled? - should modal dialog be disabled? (hidden)
  - title     - title of dialog
  - actions   - sequence of maps, each with:
                 - :id        -unique identifier
                 - :hidden?   - whether button hidden
                 - :disabled? - whether button disabled
                 - :role      - role, e.g. :primary
                 - :title     - title of button
                 - :on-click  - function to call when clicked"
  [{:keys [disabled? title actions on-close]} & content]
  [:div.fixed.z-10.inset-0.overflow-y-auto
   {:aria-labelledby title :role "dialog" :aria-modal "true"
    :class           (when disabled? "hidden")}
   [:div.flex.items-end.justify-center.min-h-screen.pt-4.px-4.pb-20.text-center.sm:block.sm:p-0
    [:div.fixed.inset-0.bg-gray-500.bg-opacity-75.transition-opacity
     {:aria-hidden "true"
      :on-click    #(when on-close (on-close))}]
    [:span.hidden.sm:inline-block.sm:align-middle.sm:h-screen
     {:aria-hidden "true", :dangerouslySetInnerHTML {:__html "&#8203;"}}]
    [:div.inline-block.align-bottom.bg-white.rounded-lg.px-4.pt-5.pb-4.text-left.overflow-hidden.shadow-xl.transform.transition-all.sm:my-8.sm:align-middle.sm:max-w-screen-sm.lg:max-w-screen-lg.sm:w-full.sm:p-6
     [:div
      [:div.mt-3.text-center.sm:mt-5
       (when title [:h3.modal-title.text-lg.leading-6.font-medium.text-gray-900 title])]
      (into [:div.mt-2] content)]
     (when (seq actions)
       [:div.mt-5.sm:mt-4.sm:flex.sm:flex-row-reverse
        (for [{:keys [id role disabled? on-click] :as action} actions, :when (and action (not (:hidden? action)))]
          (ui-button
            {:key       (or id (println "action missing :id field" action))
             :role      role
             :disabled? disabled?
             :on-click  #(when on-click (on-click))}
            (:title action)))])]]])