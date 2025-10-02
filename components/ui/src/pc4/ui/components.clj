(ns pc4.ui.components
  (:require [clojure.string :as str]
            [rum.core :as rum]))


(rum/defc badge
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
  [:div.bg-white.shadow-lg.sm:rounded-lg.border.border-gray-200
   (cond-> {}
     class (assoc :class class)
     id (assoc :id id))
   [:div.m-4.px-4.py-5:sm:p-6
    (when title [:h3.text-base.font-semibold.leading-6.text-gray-900 title])
    (when subtitle [:div.mt-2.max-w-xl.text-sm.text-gray-500
                    [:p subtitle]])
    [:div.mt-5
     content]]])

(rum/defc ui-modal
  [{:keys [id hidden? title actions left-content cancel size] :or {size :large, cancel :cancel}} & content]
  [:div.fixed.z-10.inset-0.overflow-y-auto
   (cond-> {:id id :role "dialog" :aria-modal "true" :aria-labelledby (str id "-title")}
     (or (nil? hidden?) hidden?) (assoc :class "hidden"))
   [:div.flex.items-end.justify-center.min-h-screen.pt-4.px-4.pb-20.text-center.sm:block.sm:p-0
    [:div.fixed.inset-0.bg-gray-500.bg-opacity-75.transition-opacity.cursor-pointer
     (merge {:aria-hidden "true"}
            (if (map? cancel)
              cancel
              (first (filter #(when (= (:id %) cancel) %) actions))))

     [:span.hidden.sm:inline-block.sm:align-middle.sm:h-screen
      {:aria-hidden             "true"
       :dangerouslySetInnerHTML {:__html "&#8203;"}}]]

    [:div.inline-block.align-bottom.bg-white.rounded-lg.px-4.pt-5.pb-4.text-left.overflow-hidden.shadow-xl.transform.transition-all.sm:my-8.sm:align-middle.sm:p-6
     {:class (into ["w-11/12" "sm:w-full"]
                   (case size
                     :small ["sm:max-w-sm"]
                     :medium ["sm:max-w-xl"]
                     :large ["sm:max-w-4xl"]
                     :xl ["sm:max-w-7xl"]
                     :full ["sm:max-w-[95%]"]
                     (if (string? size) [size] [])))}
     (when title
       [:div.mt-3.text-center.sm:mt-0.sm:text-left
        [:h3#modal-title.text-lg.font-medium.leading-6.text-gray-900
         {:id (str id "-title")}
         title]])

     [:div.mt-4 content]

     (when (or left-content (seq actions))
       [:div.mt-5.sm:mt-4.sm:flex.sm:justify-between.sm:items-center
        (when left-content
          [:div.flex.items-center.gap-3 left-content])
        (when (seq actions)
          [:div.mt-2.flex.flex-row-reverse.gap-3
           (for [{:keys [id title role disabled? hidden?] :as action} actions
                 :when (and action (not hidden?))]
             [:button
              (cond-> {:id    id
                       :type  "button"
                       :class (str "inline-flex justify-center rounded-md border shadow-sm px-4 py-2 text-base font-medium focus:outline-none focus:ring-2 focus:ring-offset-2 sm:text-sm "
                                   (case role
                                     :primary "border-transparent text-white bg-blue-600 hover:bg-blue-700 focus:ring-blue-500"
                                     :danger "border-transparent text-white bg-red-600 hover:bg-red-700 focus:ring-red-500"
                                     "bg-white border-gray-300 text-gray-700 hover:bg-gray-50 focus:ring-blue-500")
                                   (when disabled? " opacity-50 cursor-not-allowed"))}
                disabled? (assoc :disabled "disabled")
                :else (merge (select-keys action (filter #(str/starts-with? (name %) "hx-") (keys action)))))
              title])])])]]])

(rum/defc ui-label [{:keys [for label]}]
  [:label.block.text-sm.font-medium.text-gray-600 {:for for} label])

(rum/defc ui-textfield*
  [{:keys [id name placeholder type required auto-focus disabled] :as opts :or {type "text"}}]
  [:input.p-2.shadow.sm-focus.ring-indigo-500.border.focus:border-indigo-500.block.w-full.sm:text-sm.border-gray-300.rounded-md
   (merge opts
          {:name  (or name id)
           :id    (or id name)
           :class (if-not disabled ["text-gray-700" "bg-white" "shadow"] ["text-gray-500" "bg-gray-50" "italic"])})])

(rum/defc ui-textfield
  [{:keys [id name type label placeholder required auto-focus help-text disabled] :or {type "text"} :as params}]
  [:div
   (when label
     (ui-label {:for id :label label}))
   (ui-textfield* params)
   (when help-text
     [:p.text-sm.text-gray-500.italic help-text])])

(rum/defc ui-submit-button
  [{:keys [disabled] :as params} content]
  [:button.inline-flex.justify-center.py-2.px-4.border.border-transparent.shadow-sm.text-sm.font-medium.rounded-md.text-white.bg-blue-600
   (merge {:type  "submit"
           :class (if disabled ["opacity-50" "pointer-events-none"] ["hover:bg-blue-400" "focus:outline-none" "focus:ring-2 focus:ring-offset-2.focus:ring-blue-500"])}
          params)
   content])

(rum/defc ui-cancel-button
  [{:keys [disabled] :as params} content]
  [:a.px-3.py-2.text-sm.font-semibold.rounded-md.bg-white.text-gray-900.shadow-sm.ring-1.ring-inset.ring-gray-300
   (merge {:class (if disabled ["opacity-50" "pointer-events-none"] ["hover:bg-gray-50" "cursor-pointer"])}
          params)
   content])

(rum/defc ui-delete-button
  [{:keys [disabled] :as params} content]
  [:button.px-3.py-2.text-sm.font-semibold.rounded-md.bg-red-50.text-red-700.shadow-sm.ring-1.ring-inset.ring-red-300
   (merge {:type  "submit"
           :class (if disabled ["opacity-50" "pointer-events-none"] ["hover:bg-red-100" "focus:outline-none" "focus:ring-2 focus:ring-offset-2.focus:ring-red-500"])}
          params)
   content])

(rum/defc ui-select-button
  [{:keys [id name label disabled hx-post hx-get hx-target hx-swap options selected-id selected no-selection-string]}]
  (let [options# (if no-selection-string (into [{:id nil :text no-selection-string}] options) options)]
    [:div
     (when label [:label.block.font-medium.text-gray-900 {:for id :class "text-sm/6"} label])
     [:div.mt-2.grid.grid-cols-1
      [:select.col-start-1.row-start-1.w-full.appearance-none.bg-none.rounded-md.bg-white.py-2.pl-3.pr-8.text-base.outline.outline-1.-outline-offset-1.outline-gray-300.focus:outline.focus:outline-2.focus:-outline-offset-2.focus:outline-indigo-600
       (cond-> {:name  (or name id), :id (or id name)
                :class (if disabled ["bg-gray-100" "text-gray-600"] ["bg-white" "text-gray-800"])}
         disabled (assoc :disabled "disabled")
         hx-get (assoc :hx-get hx-get)
         hx-post (assoc :hx-post hx-post)
         hx-target (assoc :hx-target hx-target)
         hx-swap (assoc :hx-swap hx-swap))
       (for [{:keys [id text] :as option} options#]
         (if (or (and selected-id (= id selected-id)) (and selected (= option selected)))
           [:option {:value id :selected "selected"} text]
           [:option {:value id} text]))]
      [:svg.pointer-events-none.col-start-1.row-start-1.mr-2.size-5.self-center.justify-self-end.text-gray-500.sm:size-4 {:viewBox "0 0 16 16" :fill "currentColor" :aria-hidden "true" :data-slot "icon"}
       [:path {:fill-rule "evenodd" :d "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z" :clip-rule "evenodd"}]]]]))

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
     [:div.overflow-hidden.shadow.border.border-gray-200.md:rounded-lg
      [:table.min-w-full.divide-y.divide-gray-200 content]]]]])

(rum/defc ui-table-head [& content]
  [:thead.bg-gray-50 content])

(rum/defc ui-table-heading
  [opts content]
  [:th.px-2.py-3.text-left.text-xs.font-semibold.text-gray-900.uppercase.tracking-wider
   opts content])

(rum/defc ui-table-body [& content]
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
       [:h4.text-xs.italic.font-light.text-gray-600 subtitle])
     [:h3.text-lg.font-medium.leading-6.text-gray-900 title]]]])

(rum/defc ui-simple-form-item
  [{:keys [for label sublabel]} & content]
  [:div {:class "sm:border-t sm:border-gray-200 sm:pt-5"}
   [:div {:class "flex flex-col sm:flex-row"}
    (when label
      [:div {:class "w-full sm:w-1/3"}
       [:label {:class "block text-sm font-medium text-gray-700 sm:mt-px sm:pt-2" :for for}
        label
        (when sublabel [:span {:class "block text-xs font-medium text-gray-400"} sublabel])]])
    [:div {:class "w-full sm:w-2/3 pt-2 sm:pt-0"} content]]])

(rum/defc ui-simple-form
  [& content]
  [:div.space-y-8.divide-y.divide-gray-200
   [:div.mt-6.sm:mt-5.space-y-6.sm:space-y-5
    content]])

(rum/defc ui-local-date
  [{:keys [id name label disabled] :as opts} local-date]
  [:div.mt-2
   (when label
     (ui-label {:for (or id name) :label label}))
   [:input (merge opts
                  {:name  (or name id)
                   :id    (or id name)
                   :type  "date"
                   :value (str local-date)
                   :class (into ["p-2" "shadow" "sm-focus" "ring-indigo-500" "border" "focus:border-indigo-500" "block" "w-full" "sm:text-sm" "border-gray-300" "rounded-md"]
                                (if disabled
                                  ["text-gray-500" "bg-gray-50" "italic"]
                                  ["text-gray-700" "bg-white"]))})]])

(rum/defc ui-local-date-accuracy
  [{:keys [name disabled] :as opts} value]
  [:select.mt-2.appearance-none.italic.rounded-md.bg-white.text-gray-600.py-1.5.pl-3.pr-8.text-sm.outline.outline-1.-outline-offset-1.outline-gray-300.focus:outline.focus:outline-2.focus:-outline-offset-2.focus:outline-indigo-600
   (cond-> opts
     disabled (assoc :class ["bg-gray-100" "text-gray-400"]))
   (for [{:keys [id text]}
         [{:id :DAY :text "Accurate to the day"}
          {:id :WEEK :text "Accurate to the week"}
          {:id :MONTH :text "Accurate to the month"}
          {:id :QUARTER :text "Accurate only to the quarter"}
          {:id :YEAR :text "Accurate only to the year"}
          {:id :DECADE :text "Accurate only to the decade"}
          {:id :UNKNOWN :text "Unknown accuracy"}]]
     (if (= value id)
       [:option {:value id :selected "selected"} text]
       [:option {:value id} text]))])

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
  [:button.w-full.inline-flex.items-center.justify-center.rounded-md.border.shadow-sm.px-4.py-2.text-base.font-medium.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-red-500.sm:w-auto.sm:text-sm
   (merge {:type  "button"
           :class (if disabled "opacity-50" "cursor-pointer")}
          params)
   content])

(rum/defc ui-action-bar
  [& content]
  (when (seq content)
    [:div.mt-5.sm:mt-4.sm:flex.sm:flex-row-reverse.gap-2
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

(rum/defc ui-checkbox
  [{:keys [name label description checked disabled]}]
  [:div.relative.flex.items-start
   [:div.flex.items-center.h-5
    [:input.focus:ring-indigo-500.h-4.w-4.text-indigo-600.border-gray-300.rounded
     {:name     name
      :type     "checkbox"
      :disabled disabled
      :checked  checked}]]
   [:div.ml-3.text-sm
    [:label.font-medium.text-gray-700 {:for name} label]
    (when description [:p.text-gray-500 description])]])

(rum/defc ui-radio-button
  [{:keys [name value value-id options disabled id-key display-key] :or {display-key :text id-key :id}}]
  (for [{:keys [id] :as option} options
        :let [id' (or id (id-key option))]]
    [:div.flex.items-center.divide-y.divide-dotted {:key id'}
     [:input.text-blue-600.bg-gray-100.border-gray-300.focus:ring-blue-500
      {:type     "radio"
       :id       id'
       :disabled disabled
       :name     name
       :checked  (or (and value-id (= id' value-id)) (= id' (id-key value)))
       :value    id'}]
     [:label.ms-2.text-sm.font-medium.text-gray-900
      {:for id'}
      (display-key option)]]))

