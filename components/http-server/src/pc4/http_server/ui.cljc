(ns pc4.http-server.ui
  (:require [clojure.string :as str]
            [rum.core :as rum])
  (:import #?@(:clj  [(java.time LocalDate)
                      (java.time.format DateTimeFormatter)]
               :cljs [(goog.date Date)])))

(rum/defc icon-home []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 12l8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25"}]])

(rum/defc icon-team []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M15 19.128a9.38 9.38 0 002.625.372 9.337 9.337 0 004.121-.952 4.125 4.125 0 00-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 018.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0111.964-3.07M12 6.375a3.375 3.375 0 11-6.75 0 3.375 3.375 0 016.75 0zm8.25 2.25a2.625 2.625 0 11-5.25 0 2.625 2.625 0 015.25 0z"}]])

(rum/defc icon-user []                                      ;; from https://heroicons.com/
  [:svg.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z"}]])

(rum/defc icon-building-office []                           ;; from https://heroicons.com/
  [:svg.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 21h19.5m-18-18v18m10.5-18v18m6-13.5V21M6.75 6.75h.75m-.75 3h.75m-.75 3h.75m3-6h.75m-.75 3h.75m-.75 3h.75M6.75 21v-3.375c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21M3 3h12m-.75 4.5H21m-3.75 3.75h.008v.008h-.008v-.008zm0 3h.008v.008h-.008v-.008zm0 3h.008v.008h-.008v-.008z"}]])

(rum/defc icon-building-library []                          ;; from https://heroicons.com/
  [:svg.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M12 21v-8.25M15.75 21v-8.25M8.25 21v-8.25M3 9l9-6 9 6m-1.5 12V10.332A48.36 48.36 0 0012 9.75c-2.551 0-5.056.2-7.5.582V21M3 21h18M12 6.75h.008v.008H12V6.75z"}]])

(rum/defc icon-envelope-open []
  [:svg.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M21.75 9v.906a2.25 2.25 0 01-1.183 1.981l-6.478 3.488M2.25 9v.906a2.25 2.25 0 001.183 1.981l6.478 3.488m8.839 2.51l-4.66-2.51m0 0l-1.023-.55a2.25 2.25 0 00-2.134 0l-1.022.55m0 0l-4.661 2.51m16.5 1.615a2.25 2.25 0 01-2.25 2.25h-15a2.25 2.25 0 01-2.25-2.25V8.844a2.25 2.25 0 011.183-1.98l7.5-4.04a2.25 2.25 0 012.134 0l7.5 4.04a2.25 2.25 0 011.183 1.98V19.5z"}]])

(rum/defc icon-plus-circle []                               ;; from https://heroicons.com/
  [:svg.-ml-1.mr-3.w-6.h-6 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M12 9v6m3-3H9m12 0a9 9 0 11-18 0 9 9 0 0118 0z"}]])

(rum/defc icon-magnifying-glass []
  [:svg.w-6.h-6.-ml-1.mr-3 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z"}]])

(rum/defc icon-folder []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 12.75V12A2.25 2.25 0 014.5 9.75h15A2.25 2.25 0 0121.75 12v.75m-8.69-6.44l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z"}]])

(rum/defc icon-calendar []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M6.75 3v2.25M17.25 3v2.25M3 18.75V7.5a2.25 2.25 0 012.25-2.25h13.5A2.25 2.25 0 0121 7.5v11.25m-18 0A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75m-18 0v-7.5A2.25 2.25 0 015.25 9h13.5A2.25 2.25 0 0121 11.25v7.5"}]])

(rum/defc icon-inbox []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M2.25 13.5h3.86a2.25 2.25 0 012.012 1.244l.256.512a2.25 2.25 0 002.013 1.244h3.218a2.25 2.25 0 002.013-1.244l.256-.512a2.25 2.25 0 012.013-1.244h3.859m-19.5.338V18a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18v-4.162c0-.224-.034-.447-.1-.661L19.24 5.338a2.25 2.25 0 00-2.15-1.588H6.911a2.25 2.25 0 00-2.15 1.588L2.35 13.177a2.25 2.25 0 00-.1.661z"}]])

(rum/defc icon-reports []
  [:svg.-ml-1.mr-3.h-6.w-6.flex-shrink-0 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z"}]])

(rum/defc icon-bell []
  [:svg.h-6.w-6 {:xmlns   "http://www.w3.org/2000/svg" :fill "none"
                 :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"}]])

(rum/defc icon-chevron-down []
  [:svg.-mr-1.ml-2.h-5.w-5 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "white" :aria-hidden "true"}
   [:path {:fill-rule "evenodd" :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" :clip-rule "evenodd"}]])

(rum/defc avatar-8 []
  [:span.inline-block.h-8.w-8.overflow-hidden.rounded-full.bg-gray-100
   [:svg.h-full.w-full.text-gray-300 {:fill "currentColor" :viewBox "0 0 24 24"}
    [:path {:d "M24 20.993V24H0v-2.996A14.977 14.977 0 0112.004 15c4.904 0 9.26 2.354 11.996 5.993zM16.002 8.999a4 4 0 11-8 0 4 4 0 018 0z"}]]])

(rum/defc avatar-14 []
  [:span.inline-block.h-14.w-14.overflow-hidden.rounded-full.bg-gray-100
   [:svg.h-full.w-full.text-gray-300 {:fill "currentColor" :viewBox "0 0 24 24"}
    [:path {:d "M24 20.993V24H0v-2.996A14.977 14.977 0 0112.004 15c4.904 0 9.26 2.354 11.996 5.993zM16.002 8.999a4 4 0 11-8 0 4 4 0 018 0z"}]]])

(rum/defc badge
  "Display a small badge with the text specified."
  [{:keys [s text-color bg-color uppercase?] :or {text-color "text-red-200" bg-color "bg-red-500" uppercase? true}}]
  [:span.text-xs.text-center.font-semibold.inline-block.py-1.px-2.uppercase.rounded-full.ml-1.last:mr-0.mr-1
   {:class (str/join " " [text-color bg-color (when uppercase? "uppercase")])} s])

(rum/defc box-error-message
  [{:keys [title message]}]
  (when message
    [:div.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.relative.shadow-md {:role "alert"}
     (when title [:span.strong.font-bold.mr-4 title])
     [:span.block.sm:inline message]]))

(rum/defc active-panel
  [{:keys [id title subtitle class]} & content]
  [:div.bg-white.shadow-lg.sm:rounded-lg.border
   (cond-> {}
     class (assoc :class class)
     id (assoc :id id))
   [:div.m-4.px-4.py-5:sm:p-6
    (when title [:h3.text-base.font-semibold.leading-6.text-gray-900 title])
    (when subtitle [:div.mt-2.max-w-xl.text-sm.text-gray-500
                    [:p subtitle]])
    [:div.mt-5
     content]]])

(rum/defc ui-label [{:keys [for label]}]
  [:label.block.text-sm.font-medium.text-gray-600 {:for for} label])

(rum/defc ui-textfield*
  "HTML input control for a textfield."
  [{:keys [id name placeholder type required auto-focus disabled] :as opts :or {type "text"}}]
  [:input.p-2.shadow.sm-focus.ring-indigo-500.border.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md
   (merge opts
          {:name        (or name id)
           :id          (or id name)
           :class       (if-not disabled ["text-gray-700" "bg-white" "shadow"] ["text-gray-500" "bg-gray-50" "italic"]) })])

(rum/defc ui-textfield
  "Label and input for a textfield, with optional help-text."
  [{:keys [id name type label placeholder required auto-focus help-text disabled] :or {type "text"} :as params}]
  [:div
   (when label
     (ui-label {:for id :label label}))
   (ui-textfield* params)
   (when help-text
     [:p.text-sm.text-gray-500.italic help-text])])

(rum/defc ui-submit-button
  [{:keys [label disabled]}]
  [:button.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-blue-600
   {:type  "submit"
    :class (if disabled ["opacity-50 pointer-events-none"] ["hover:bg-blue-400" "focus:outline-none" "focus:ring-2 focus:ring-offset-2.focus:ring-blue-500"])}
   label])

(rum/defc ui-select-button
  [{:keys [id name label disabled hx-get hx-target hx-swap options selected-id selected]}]
  [:div
   (when label [:label.block.font-medium.text-gray-900 {:for id :class "text-sm/6"} label])
   [:div.mt-2.grid.grid-cols-1
    [:select.col-start-1.row-start-1.w-full.appearance-none.rounded-md.bg-white.py-1.5.pl-3.pr-8.text-base.outline.outline-1.-outline-offset-1.outline-gray-300.focus:outline.focus:outline-2.focus:-outline-offset-2.focus:outline-indigo-600
     (cond-> {:name  (or name id), :id (or id name)
              :class (if disabled ["bg-gray-100" "text-gray-600"] ["bg-white" "text-gray-800"])}
       disabled (assoc :disabled "disabled")
       hx-get (assoc :hx-get hx-get)
       hx-target (assoc :hx-target hx-target)
       hx-swap (assoc :hx-swap hx-swap))
     (for [{:keys [id text] :as option} options]
       (if (or (and selected-id (= id selected-id)) (and selected (= option selected)))
         [:option {:value id :selected "selected"} text]
         [:option {:value id} text]))]
    [:svg.pointer-events-none.col-start-1.row-start-1.mr-2.size-5.self-center.justify-self-end.text-gray-500.sm:size-4 {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true" :data-slot "icon"}
     [:path {:fill-rule "evenodd" :d "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z" :clip-rule "evenodd"}]]]])


(rum/defc ui-title [{:keys [id title subtitle]}]
  [:div.sm:flex.sm:items-center.pl-2.pb-4
   [:div.flex-auto
    [:h1.text-xl.font-semibold.text-gray-900 title]
    (when subtitle
      [:p.mt-2.text-sm.text-gray-700 subtitle])]])

(rum/defc ui-table
  [& content]
  [:div.flex.flex-col
   [:div.-my-2.-mx-4.overflow-x-auto.sm:-mx-6.lg:-mx-8
    [:div.inline-block.min-w-full.py-2.align-middle.md:px-6.lg:px-8
     [:div.overflow-hidden.shadow.ring-1.ring-black.ring-opacity-5.md:rounded-lg
      [:table.min-w-full.divide-y.divide-gray-200 content]]]]])

(rum/defc ui-table-head [content]
  [:thead.bg-gray-50 content])

(rum/defc ui-table-heading
  [opts content]
  [:th.px-2.py-3.text-left.text-xs.font-semibold.text-gray-900.uppercase.tracking-wider
   opts content])

(rum/defc ui-table-body [content]
  [:tbody.bg-white content])

(rum/defc ui-table-row [opts & content]
  [:tr opts content])

(rum/defc ui-table-cell
  [opts & content]
  [:td.px-2.py-3.whitespace-nowrap.text-sm.text-gray-500
   opts content])

(rum/defc ui-simple-form-title [{:keys [title subtitle]}]
  [:div.sm:grid.flex.flex-row.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-5
   [:div.mt-1.sm:mt-0.sm:col-span-2
    [:div.w-full.rounded-md.shadow-sm
     (when subtitle
       [:h4.text-xs.italic.font-light.text-gray-600 subtitle]
       [:h3.text-lg.font-medium.leading-6.text-gray-900 title])]]])

(rum/defc ui-simple-form-item
  [{:keys [for label sublabel]} & content]
  [:div.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start.sm:border-t.sm:border-gray-200.sm:pt-2
   (when label
     [:label.block.text-sm.font-medium.text-gray-700.sm:mt-px.sm:pt-2
      (when for {:for for}) label
      (when sublabel [:span.block.text-xs.font-medium.text-gray-400 sublabel])])
   [:div.pt-2.sm:pt-0.sm:col-span-2 content]])

(rum/defc ui-simple-form
  [& content]
  [:div.space-y-8.divide-y.divide-gray-200
   [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
    content]])

(rum/defc ui-local-date
  [{:keys [id name label disabled] :as opts} ^LocalDate local-date]
  [:div
   (when label
     (ui-label {:for (or id name) :label label}))
   (when (or (not disabled) local-date)
     [:input (merge opts
                    {:name  (or name id)
                     :id    (or id name)
                     :type  "date"
                     :value (when local-date (.toString local-date))})])])

(rum/defc ui-textarea
  [{:keys [id name label disabled rows]} value]
  [:div
   (when label
     (ui-label {:for (or id name) :label label}))
   [:textarea.p-1.comment.shadow-sm.focus:ring-indigo-500.border.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md
    {:id       (or id name)
     :name     (or name id)
     :class    (when disabled ["text-gray-600" "bg-gray-50" "italic"])
     :rows     (or rows 5)
     :disabled disabled} value]])

(rum/defc ui-button
  [{:keys [disabled] :as params} content]
  [:button.w-full.inline-flex.justify-center.rounded-md.border.shadow-sm.px-4.py-2.text-base.font-medium.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-red-500.sm:ml-3.sm:w-auto.sm:text-sm
   (merge {:type  "button"
           :class (if disabled "opacity-50" "cursor-pointer")}
          params)
   content])

(rum/defc ui-action-bar
  [& content]
  (when (seq content)
    [:div.mt-5.sm:mt-4.sm:flex.sm:flex-row-reverse
     content]))


(rum/defc ui-spinner
  [{:keys [id]}]
  [:div {:id id :class "inline-htmx-indicator"}
   [:div.grid.w-full.place-items-center.rounded-lg.p-6
    [:svg.text-gray-300.animate-spin
     {:viewBox "0 0 64 64" :fill "none" :xmlns "http://www.w3.org/2000/svg" :width "24" :height "24"}
     [:path {:d "M32 3C35.8083 3 39.5794 3.75011 43.0978 5.20749C46.6163 6.66488 49.8132 8.80101 52.5061 11.4939C55.199 14.1868 57.3351 17.3837 58.7925 20.9022C60.2499 24.4206 61 28.1917 61 32C61 35.8083 60.2499 39.5794 58.7925 43.0978C57.3351 46.6163 55.199 49.8132 52.5061 52.5061C49.8132 55.199 46.6163 57.3351 43.0978 58.7925C39.5794 60.2499 35.8083 61 32 61C28.1917 61 24.4206 60.2499 20.9022 58.7925C17.3837 57.3351 14.1868 55.199 11.4939 52.5061C8.801 49.8132 6.66487 46.6163 5.20749 43.0978C3.7501 39.5794 3 35.8083 3 32C3 28.1917 3.75011 24.4206 5.2075 20.9022C6.66489 17.3837 8.80101 14.1868 11.4939 11.4939C14.1868 8.80099 17.3838 6.66487 20.9022 5.20749C24.4206 3.7501 28.1917 3 32 3L32 3Z" :stroke "currentColor" :stroke-width "5" :stroke-linecap "round" :stroke-linejoin "round"}]
     [:path.text-gray-900 {:d "M32 3C36.5778 3 41.0906 4.08374 45.1692 6.16256C49.2477 8.24138 52.7762 11.2562 55.466 14.9605C58.1558 18.6647 59.9304 22.9531 60.6448 27.4748C61.3591 31.9965 60.9928 36.6232 59.5759 40.9762" :stroke "currentColor" :stroke-width "5" :stroke-linecap "round" :stroke-linejoin "round"}]]
    [:span.sr-only "Loading..."]]])