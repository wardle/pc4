(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.eldrix.pc4/rsdb)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-lib-%s.jar" (name lib) version))


(defn clean [_]
      (b/delete {:path "target"}))

(defn jar [_]
      (clean nil)
      (println "Building" jar-file)
      (b/write-pom {:class-dir class-dir
                    :lib       lib
                    :version   version
                    :basis     jar-basis
                    :src-dirs  ["src"]
                    :scm       {:url                 "https://github.com/wardle/hermes"
                                :tag                 (str "v" version)
                                :connection          "scm:git:git://github.com/wardle/hermes.git"
                                :developerConnection "scm:git:ssh://git@github.com/wardle/hermes.git"}})
      (b/copy-dir {:src-dirs   ["src" "resources"]
                   :target-dir class-dir})
      (b/jar {:class-dir class-dir
              :jar-file  jar-file}))

(defn install
      "Installs pom and library jar in local maven repository"
      [_]
      (jar nil)
      (println "Installing" jar-file)
      (b/install {:basis     jar-basis
                  :lib       lib
                  :class-dir class-dir
                  :version   version
                  :jar-file  jar-file}))