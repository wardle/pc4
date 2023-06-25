(ns com.eldrix.pc4.ui.core
     (:require [com.eldrix.pc4.ui.user :as ui.user]))

#?(:cljs
   (defn ^:export init []
     (js/console.log "Hello, World")))
