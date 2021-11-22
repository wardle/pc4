(ns pc4.ui.ui
  (:require [com.fulcrologic.fulcro.dom :as dom]))

(defn box-error-message [& {:keys [title message]}]
  (when message
    (dom/div :.bg-red-100.border.border-red-400.text-red-700.px-4.py-3.rounded.relative.shadow-md {:role "alert"}
             (when title (dom/span :.strong.font-bold.mr-4 title))
             (dom/span :.block.sm:inline message))))