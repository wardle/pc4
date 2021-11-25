(ns eldrix.pc4-ward.patient.views
  (:require [fork.re-frame :as fork]
            [eldrix.pc4-ward.ui :as ui]))








(defn form-ms-status'
  [{:keys [values errors handle-change handle-blur]}]
  [:<>
   [:form.space-y-8.divide-y.divide-gray-200 {:on-submit #(.preventDefault %)}
    [:div.space-y-8.divide-y.divide-gray-200
     [:div
      [:div.mt-6.grid.grid-cols-1.gap-y-6.gap-x-4.sm:grid-cols-6
       [:div.sm:col-span-4
        [:label.block.text-sm.font-medium.text-gray-700 {:for "username"} "Username"]
        [:div.mt-1.flex.rounded-md.shadow-sm
         [:span.inline-flex.items-center.px-3.rounded-l-md.border.border-r-0.border-gray-300.bg-gray-50.text-gray-500.sm:text-sm "workcation.com/"]
         [:input#username.flex-1.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.min-w-0.rounded-none.rounded-r-md.sm:text-sm.border-gray-300 {:type "text" :name "username" :autocomplete "username"}]]]
       [:div.sm:col-span-6
        [:label.block.text-sm.font-medium.text-gray-700 {:for "about"} "About"]
        [:div.mt-1
         [:textarea#about.shadow-sm.focus:ring-indigo-500.focus:border-indigo-500.block.w-full.sm:text-sm.border.border-gray-300.rounded-md {:name "about" :rows "3"}]]
        [:p.mt-2.text-sm.text-gray-500 "Write a few sentences about yourself."]]

       ]]]]
   [:p "Read back: " values]

   [:input
    {:name      "input"
     :value     (values :input)
     :on-change handle-change
     :on-blur   handle-blur}]])

(defn form-ms-status []
  [fork/form {:initial-values  {:t_form_edss/edss_score "SCORE1_0"}
              :keywordize-keys true}
   form-ms-status'])
