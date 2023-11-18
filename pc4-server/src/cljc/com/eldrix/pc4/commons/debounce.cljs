(ns com.eldrix.pc4.commons.debounce
  (:require [re-frame.core :as rf]
            [goog.async.Debouncer]))


;; Thank you to Martin Klepsch for this code
;;  https://martinklepsch.org/posts/simple-debouncing-in-clojurescript.html
(defn debounce
  "Returns a function that will debounce with the interval specified."
  [f interval]
  (let [dbnc (goog.async.Debouncer. f interval)]
    (fn [& args] (.apply (.-fire dbnc) dbnc (to-array args)))))

(def dispatch-debounced
  "A convenience debouncer with a predetermined interval."
  (debounce rf/dispatch 200))
