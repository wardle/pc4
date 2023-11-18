(ns pc4.ui.user
  (:require [clojure.string :as str]))

(def role->badge-class
  {:INACTIVE              "bg-black text-white"
   :NORMAL_USER           "bg-green-100 text-green-800"
   :POWER_USER            "bg-red-200 text-red-800"
   :PID_DATA              "bg-yellow-200 text-black"
   :LIMITED_USER          "bg-teal-600 text-teal-600"
   :BIOBANK_ADMINISTRATOR "bg-blue-600 text-blue-600"})

(defn role-badge [role]
  [:span.inline-block.flex-shrink-0.rounded-full.px-2.py-0.5.text-xs.font-medium
   {:class (role->badge-class role)}
   (str/replace (name role) #"_" " ")])
