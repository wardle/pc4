(ns com.eldrix.pc4.ui.core
     (:require [dommy.core :as dommy :refer-macros [sel sel1]]
               [com.eldrix.pc4.ui.user :as ui.user]))

#?(:cljs
   (defn ^:export init []
     (js/console.log "Hello, World")))


(defn click-handler [e]
     (-> (sel1 :#my-button)
         (dommy/remove-attr! :disabled)
         (dommy/add-class! :active)
         (dommy/set-text! "Click me!"))
     (.log js/console "You clicked my button! Congratulations"))

(dommy/listen! (sel1 :#my-button) :click click-handler)

(rum.core/hydrate "login-panel" (ui.user/login-panel {}))
