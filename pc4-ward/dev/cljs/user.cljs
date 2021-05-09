(ns cljs.user
  "Commonly used symbols for easy access in the ClojureScript REPL during
  development."
  (:require
    [cljs.repl :refer (apropos dir doc error->str ex-str ex-triage
                       find-doc print-doc pst source)]
    [clojure.pprint :refer (pprint)]
    [clojure.string :as str]))



(comment
  (shadow.cljs.devtools.api/nrepl-select :app)
  (println "Hello World!")
  (js/console.log "Hi there from the REPL" )
  (js/alert "Hi there")

  )