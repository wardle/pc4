(ns com.eldrix.pc4.commons.debounce-fx
  "A re-frame effect handler that provides debounce functionality.
  Usage:

  (reg-event-fx
    :my-handler
    (fn [fx [_ value]]
      {:dispatch-debounce {:key :search
                           :event [:search value]
                           :delay 250}})).

  In essence, an event is registered and after a delay, if no further event
  with that id has been received, the event will be sent. Useful for
  autocomplete functionality."
  (:require [re-frame.core :as rf]))

(def events (atom {}))

(rf/reg-fx :dispatch-debounce
  (fn [{:keys [key event delay] :or {delay 200 } :as debounce}]
    (let [now (.getTime (js/Date.))]
      (swap! events assoc (:key debounce) now)
      (js/setTimeout
        #(when (= now (get events key)) (rf/dispatch event))
        delay))))