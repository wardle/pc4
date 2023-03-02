(ns com.eldrix.pc4.ui.patient
  (:require [com.eldrix.pc4.ui.misc :as misc]
            [rum.core :as rum])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)))

(def dtf (DateTimeFormatter/ofPattern "dd MMM yyyy"))
(def dtf-pseudonymous (DateTimeFormatter/ofPattern "MMM yyyy"))

(rum/defc patient-banner                                    ;; TODO: use user-specified locale
  "A patient banner.
  Parameters
  - patient-name : patient name
  - nhs-number   : NHS number
  - gender       : Gender
  - born         : Date of birth, a LocalDate, or String
  - age          : Textual representation of age
  - deceased     : Date of death, a LocalDate or String
  - crn          : Hospital case record number
  - approximate  : boolean - if true, date of birth and age will be shown as approximate
  - close        : properties for close button (e.g. use on-click or hx-get)
  - content      : nested content"
  [{:keys [patient-name nhs-number gender born age deceased crn approximate address close content]}]
  (let [df (if approximate dtf-pseudonymous dtf)]
    [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200.bg-gray-50.relative
     (when close
       [:div.absolute.top-0.5.sm:-top-2.5.right-0.sm:-right-2.5
        [:button.rounded.bg-white.border.hover:bg-gray-300.bg-gray-50.px-1.py-1
         close
         [:svg {:xmlns "http://www.w3.org/2000/svg" :width "20" :height "20" :viewBox "0 0 18 18"} [:path {:d "M14.53 4.53l-1.06-1.06L9 7.94 4.53 3.47 3.47 4.53 7.94 9l-4.47 4.47 1.06 1.06L9 10.06l4.47 4.47 1.06-1.06L10.06 9z"}]]]])
     (when deceased
       [:div.grid.grid-cols-1.pb-2
        (misc/badge {:s (cond
                          (instance? LocalDate deceased) (str "Died " (.format df deceased))
                          (boolean? deceased) "Deceased"
                          :else deceased)})])
     [:div.grid.grid-cols-2.lg:grid-cols-5.pt-1
      (when name [:div.font-bold.text-lg.min-w-min patient-name])
      [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
       (when gender [:span [:span.text-sm.font-thin.sm:inline "Gender "] [:span.font-bold gender]])]
      [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
       [:span.text-sm.font-thin "Born "]
       [:span.font-bold (if (instance? LocalDate born) (.format df born) born)]
       (when (and (not deceased) age) [:span.font-thin " (" (when approximate "~") age ")"])]
      [:div.lg:hidden.text-right.mr-8.md:mr-0 gender " " [:span.font-bold born]]
      (when (and nhs-number (not approximate))
        [:div.lg:text-center.lg:ml-2.min-w-min [:span.text-sm.font-thin "NHS No "] [:span.font-bold nhs-number]])
      (when (and crn (not approximate))
        [:div.text-right.min-w-min [:span.text-sm.font-thin "CRN "] [:span.font-bold crn]])]
     [:div.grid.grid-cols-1 {:class (if-not deceased "bg-gray-100" "bg-red-100")}
      [:div.font-light.text-sm.tracking-tighter.text-gray-500.truncate address]]
     (when content
       [:div content])]))

(rum/defc dev-page
  [content]
  [:html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:script {:src "https://unpkg.com/htmx.org@1.8.5"}]
    [:script {:src "https://cdn.tailwindcss.com"}]]
   [:body
    content]])


(comment
  (spit "banner.html" (rum/render-html (dev-page (patient-banner {:nhs-number "111 111 1111"
                                                                  :crn "A123456"
                                                                  :patient-name "SMITH, John Mr" :age "24y"
                                                                  :born (LocalDate/of 1970 1 1)
                                                                  :gender "Male"
                                                                  :address "1 Station Road, Cardiff, CF14 4XW"
                                                                  :close {:hx-get "https://localhost:8080/"}})))))
