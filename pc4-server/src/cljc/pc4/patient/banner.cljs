(ns pc4.patient.banner
  (:require [clojure.string :as str]
            [com.eldrix.nhsnumber :as nhs-number]
            [pc4.dates :as dates]
            [pc4.ui :as ui])
  (:import (goog.date Date)))

(defn banner
  "A patient banner.
  Parameters
  - patient-name : patient name
  - nhs-number   : NHS number
  - gender       : Gender
  - born         : Date of birth, a goog.date.Date, or String
  - age          : Textual representation of age
  - deceased     : Date of death, a goog.date.Date or String
  - crn          : Hospital case record number
  - approximate  : boolean - if true, date of birth and age will be shown as approximate
  - close        : properties for close button (e.g. use on-click or hx-get)
  - content      : nested content"
  [{:keys [patient-name nhs-number gender born age deceased crn approximate address close content]}]
  (let [born' (if (instance? Date born) (if approximate (dates/format-month-year born)
                                                        (dates/format-date born)) born)]
    [:div.grid.grid-cols-1.border-2.shadow-lg.p-1.sm:p-4.sm:m-2.border-gray-200.bg-slate-50.relative
     (when close
       [:div.absolute.top-0.5.sm:-top-2.5.right-0.sm:-right-2.5
        [:button.rounded.bg-white.border.hover:bg-gray-300.bg-gray-50.px-1.py-1
         close
         [:svg {:xmlns "http://www.w3.org/2000/svg" :width "20" :height "20" :viewBox "0 0 18 18"} [:path {:d "M14.53 4.53l-1.06-1.06L9 7.94 4.53 3.47 3.47 4.53 7.94 9l-4.47 4.47 1.06 1.06L9 10.06l4.47 4.47 1.06-1.06L10.06 9z"}]]]])
     (when deceased
       [:div.grid.grid-cols-1.pb-2
        [ui/badge {:s (cond
                        (instance? Date deceased) (str "Died " (dates/format-date deceased))
                        (boolean? deceased) "Deceased"
                        :else deceased)}]])
     [:div.grid.grid-cols-2.lg:grid-cols-5.pt-1
      (when patient-name (if (> (count patient-name) 20)
                           [:div.font-bold.text-sm.min-w-min patient-name]
                           [:div.font-bold.text-lg.min-w-min patient-name]))
      [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
       (when gender [:span [:span.text-sm.font-thin.sm:inline "Gender "] [:span.font-bold gender]])]
      [:div.hidden.lg:block.text-right.lg:text-center.lg:mr-2.min-w-min
       [:span.text-sm.font-thin "Born "]
       (when born'
         [:span.font-bold born'])
       (when (and (not deceased) age) [:span.font-thin " (" (when approximate "~") age ")"])]
      [:div.lg:hidden.text-right.mr-8.md:mr-0 gender " " [:span.font-bold born']]
      (when (and nhs-number (not approximate))
        [:div.lg:text-center.lg:ml-2.min-w-min [:span.text-sm.font-thin "NHS No "] [:span.font-bold (nhs-number/format-nnn nhs-number)]])
      (when (and crn (not approximate))
        [:div.text-right.min-w-min [:span.text-sm.font-thin "CRN "] [:span.font-bold crn]])]
     [:div.grid.grid-cols-1 {:class (if-not deceased "bg-gray-100" "bg-red-100")}
      [:div.font-light.text-sm.tracking-tighter.text-gray-500.truncate address]]
     (when content
       [:div content])]))

(defn rsdb-banner
  [{:t_patient/keys [id patient_identifier nhs_number sex title first_names last_name
                     date_birth date_death status current_age address]
    :t_episode/keys [stored_pseudonym]}]
  (let [pseudonymous (= :PSEUDONYMOUS status)]
    [banner
     {:patient-name (str last_name ", " (str/join " " [title first_names]))
      :nhs-number   nhs_number
      :approximate  pseudonymous
      :address      (if pseudonymous
                      (str/join " / " [stored_pseudonym (:t_address/address1 address)])
                      (str/join ", " (remove nil? [(:t_address/address1 address)
                                                   (:t_address/address2 address)
                                                   (:t_address/address3 address)
                                                   (:t_address/address4 address)
                                                   (:t_address/postcode address)])))
      :gender       sex
      :born         date_birth
      :age          current_age
      :deceased     date_death}]))

(def banner-query
  [:t_patient/id :t_patient/patient_identifier :t_patient/nhs_number
   :t_patient/title :t_patient/first_names :t_patient/last_name
   {:t_patient/address [:t_address/id :t_address/address1 :t_address/address2
                        :t_address/address3 :t_address/address4 :t_address/postcode
                        :t_address/lsoa]}
   :t_patient/sex :t_patient/date_birth :t_patient/current_age :t_patient/date_death
   :t_patient/status :t_episode/project_fk :t_episode/stored_pseudonym])