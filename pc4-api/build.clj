(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.eldrix/pc4-api)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))


(defn uber [_]
  (clean nil)
  (b/compile-clj {:basis basis
                  :src-dirs ["src/main/clj"]
                  :ns-compile ['com.eldrix.hermes.snomed]
                  :class-dir class-dir})
  (b/javac {:src-dirs ["src"]
            :class-dir class-dir
            :basis basis
            :javac-opts ["-source" "8" "-target" "8"]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     basis
           :main      'com.eldrix.pc4.api.Factory}))