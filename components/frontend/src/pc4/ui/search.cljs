(ns pc4.ui.search
  "Patient search UI components.
   Provides a search box with filter options and results display."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div p]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m]
    [pc4.route :as route]
    [pc4.ui.core :as ui]))

;; SVG icon for search magnifying glass
(defn icon-search []
  (dom/svg {:className "pointer-events-none col-start-1 row-start-1 ml-3 size-5 self-center text-gray-400"
            :viewBox   "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
    (dom/path {:fillRule "evenodd"
               :d        "M9 3.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11ZM2 9a7 7 0 1 1 12.452 4.391l3.328 3.329a.75.75 0 1 1-1.06 1.06l-3.329-3.328A7 7 0 0 1 2 9Z"
               :clipRule "evenodd"})))

;; SVG icon for dropdown chevron
(defn icon-chevron-down-sm []
  (dom/svg {:className "pointer-events-none col-start-1 row-start-1 mr-2 size-5 self-center justify-self-end text-gray-500 sm:size-4"
            :viewBox   "0 0 16 16" :fill "currentColor" :aria-hidden "true"}
    (dom/path {:fillRule "evenodd"
               :d        "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z"
               :clipRule "evenodd"})))

(defsc PatientAddress
  "Renders a patient's address horizontally."
  [this {:uk.nhs.cfh.isb1500/keys [address-horizontal]}]
  {:query [:uk.nhs.cfh.isb1500/address-horizontal]}
  (when-not (str/blank? address-horizontal)
    (div :.mt-1.text-gray-500.text-xs.truncate {:style {:maxWidth "250px"}} address-horizontal)))

(def ui-patient-address (comp/factory PatientAddress))

(defsc PatientSearchResultItem
  "A single patient search result row - clickable to navigate to patient."
  [this {:t_patient/keys          [patient_identifier title first_names last_name date_birth date_death nhs_number address]
         :uk.nhs.cfh.isb1504/keys [nhs-number]}]
  {:ident :t_patient/patient_identifier
   :query [:t_patient/patient_identifier :t_patient/title :t_patient/first_names :t_patient/last_name
           :t_patient/date_birth :t_patient/date_death :t_patient/nhs_number
           :uk.nhs.cfh.isb1504/nhs-number
           {:t_patient/address (comp/get-query PatientAddress)}]}
  (dom/tr :.hover:bg-gray-50.cursor-pointer
          {:onClick #(route/route-to! ::route/patient-home {:patient-identifier patient_identifier})}
          ;; Name and address column
          (dom/td :.whitespace-nowrap.py-4.pl-4.pr-3.text-sm.sm:pl-2
                  (div :.flex.items-center
                    (div
                      (div :.font-medium.text-gray-900
                        (str/upper-case (or last_name "")) ", " first_names
                        (when-not (str/blank? title) (str " (" title ")")))
                      (ui-patient-address address))))
          ;; Date of birth
          (dom/td :.whitespace-nowrap.px-3.py-4.text-sm.text-gray-500
                  (div :.text-gray-900 (ui/format-date date_birth)))
          ;; Date of death
          (dom/td :.whitespace-nowrap.px-3.py-4.text-sm.text-gray-500
                  (when date_death
                    (div :.text-gray-900 (ui/format-date date_death))))
          ;; NHS Number
          (dom/td :.whitespace-nowrap.px-3.py-4.text-sm.text-gray-500
                  (div :.text-gray-900 (or nhs-number nhs_number)))))

(def ui-patient-search-result-item (comp/factory PatientSearchResultItem {:keyfn :t_patient/patient_identifier}))

(defsc PatientSearchResponse
  "Data-only component for normalizing the search-patients mutation response.
   Matches server response shape: {:patients [...] :try-all bool}"
  [_ _]
  {:query [:try-all {:patients (comp/get-query PatientSearchResultItem)}]})

(defsc PatientSearchResults
  "Container for patient search results - shows a table of results or appropriate messages.
   Shares state with PatientSearchBox via [:component/id :patient-search]."
  [this {:ui/keys [s loading? error results search-performed? try-all]}]
  {:ident (fn [] [:component/id :patient-search])
   :query [:ui/s :ui/filter :ui/loading? :ui/error :ui/search-performed? :ui/try-all
           {:ui/results (comp/get-query PatientSearchResultItem)}]}
  (cond
    ;; Loading state
    loading?
    (div :.flex.justify-center.items-center.py-12
      (ui/ui-loading {}))

    ;; Error state
    error
    (div :.bg-red-50.border.border-red-200.rounded.p-4.text-red-800
      (p :.font-medium "Search error")
      (p :.text-sm error))

    ;; No search performed yet - show nothing
    (not search-performed?)
    nil

    ;; Search performed but no results
    (empty? results)
    (div :.bg-yellow-50.border.border-yellow-200.rounded.p-4.text-yellow-800
      (p :.font-medium "No patients found")
      (if try-all
        (dom/div
          (p :.text-sm "No results in your patients, but matches exist in all patients.")
          (dom/button :.mt-2.rounded-md.bg-yellow-600.px-3.py-2.text-sm.font-semibold.text-white.shadow-sm.hover:bg-yellow-500
            {:type    "button"
             :onClick #(do (m/set-value! this :ui/filter "all")
                           (comp/transact! this [(list 'pc4.rsdb/search-patients {:s s})]))}
            "Search all patients"))
        (p :.text-sm "Try adjusting your search terms.")))

    ;; Results found - show table
    :else
    (div :.border.border-gray-200.rounded.shadow-lg.bg-white.overflow-hidden
      (dom/table :.min-w-full.divide-y.divide-gray-200
                 (dom/thead :.bg-gray-50
                            (dom/tr
                              (dom/th :.py-3.pl-4.pr-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider.sm:pl-2 "Name")
                              (dom/th :.px-3.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Born")
                              (dom/th :.px-3.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Died")
                              (dom/th :.px-3.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "NHS No.")))
                 (dom/tbody :.bg-white.divide-y.divide-gray-200
                            (map ui-patient-search-result-item results))))))

(def ui-patient-search-results (comp/factory PatientSearchResults))

(defsc PatientSearchBox
  "Search box with filter dropdown for patient search.
   Shares state with PatientSearchResults via [:component/id :patient-search].
   Receives :project-ids via computed props for 'my patients' filtering."
  [this {:ui/keys [s filter]}]
  {:ident (fn [] [:component/id :patient-search])
   :query [:ui/s :ui/filter]}
  (let [{:keys [project-ids]} (comp/get-computed this)
        search-text (or s "")
        filter-value (or filter "my")
        can-search? (>= (count (str/trim search-text)) 2)
        do-search (fn []
                    (when can-search?
                      (comp/transact! this [(list 'pc4.rsdb/search-patients
                                                  {:s           search-text
                                                   :project-ids (when (= "my" filter-value) project-ids)})])))
        do-clear (fn []
                   (m/set-string! this :ui/s :value "")
                   (comp/transact! this [(list 'pc4.rsdb/clear-patient-search {})]))]
    (dom/form {:onSubmit (fn [evt] (evt/prevent-default! evt) (do-search))}
      ;; Large version (md+): horizontal layout with inline dropdown
      (div :.hidden.md:block
        (div :.flex.items-center.rounded-md.bg-white.outline.outline-1.-outline-offset-1.outline-gray-300.focus-within:outline.focus-within:outline-2.focus-within:-outline-offset-2.focus-within:outline-indigo-600
          (div :.shrink-0.select-none.text-base.text-gray-500.sm:text-sm
            (icon-search))
          (dom/input :.block.min-w-0.grow.py-1.5.pl-1.pr-3.text-base.text-gray-900.placeholder:text-gray-400.outline-none.border-0.bg-transparent.focus:ring-0.focus:border-0.focus:outline-none.sm:text-sm
            {:type         "text"
             :name         "search"
             :placeholder  "Search..."
             :autoComplete "off"
             :autoFocus    "on"
             :value        search-text
             :onChange     #(let [v (evt/target-value %)]
                              (m/set-string! this :ui/s :value v)
                              (when (str/blank? v) (do-clear)))
             :onKeyDown    #(when (evt/enter-key? %) (do-search))})
          (div :.grid.shrink-0.grid-cols-1.focus-within:relative
            (dom/select :.col-start-1.row-start-1.w-full.appearance-none.py-1.5.pl-3.pr-7.text-base.text-gray-500.placeholder:text-gray-400.outline-none.border-0.bg-transparent.focus:ring-0.focus:border-0.focus:outline-none.text-xs
                        {:value    filter-value
                         :onChange #(m/set-value! this :ui/filter (evt/target-value %))}
                        (dom/option {:value "my"} "My patients")
                        (dom/option {:value "all"} "All patients"))
            (icon-chevron-down-sm))))
      ;; Small version (below md): stacked layout with bordered dropdown
      (div :.md:hidden.space-y-2
        (div :.flex.items-center.rounded-md.bg-white.outline.outline-1.-outline-offset-1.outline-gray-300.focus-within:outline.focus-within:outline-2.focus-within:-outline-offset-2.focus-within:outline-indigo-600
          (div :.shrink-0.select-none.text-base.text-gray-500
            (icon-search))
          (dom/input :.block.min-w-0.grow.py-1.5.pl-1.pr-3.text-base.text-gray-900.placeholder:text-gray-400.outline-none.border-0.bg-transparent.focus:ring-0.focus:border-0.focus:outline-none
            {:type         "text"
             :name         "search-sm"
             :placeholder  "Search patients..."
             :autoComplete "off"
             :value        search-text
             :onChange     #(let [v (evt/target-value %)]
                              (m/set-string! this :ui/s :value v)
                              (when (str/blank? v) (do-clear)))
             :onKeyDown    #(when (evt/enter-key? %) (do-search))}))
        (div :.grid.grid-cols-1.rounded-md.bg-white.outline.outline-1.-outline-offset-1.outline-gray-300
          (dom/select :.col-start-1.row-start-1.w-full.appearance-none.py-1.5.pl-3.pr-7.text-base.text-gray-500.placeholder:text-gray-400.outline-none.border-0.bg-transparent.focus:ring-0.focus:border-0.focus:outline-none.text-sm
                      {:value    filter-value
                       :onChange #(m/set-value! this :ui/filter (evt/target-value %))}
                      (dom/option {:value "my"} "My patients")
                      (dom/option {:value "all"} "All patients"))
          (icon-chevron-down-sm)))
      ;; Search button
      (dom/button :.rounded-md.w-full.mt-2.center.bg-white.px-2.5.py-2.text-xs.font-semibold.text-gray-900.shadow-sm.ring-1.ring-inset.ring-gray-300.hover:bg-gray-50
        {:type     "submit"
         :disabled (not can-search?)
         :classes  (when-not can-search? ["opacity-50" "cursor-not-allowed"])}
        "Search..."))))

(def ui-patient-search-box (comp/factory PatientSearchBox))
