(ns build
  (:require [clojure.edn :as edn]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.eldrix/pc4)
(def version (format "1.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-lib-%s.jar" (name lib) version))
(def uber-basis (b/create-basis {:project "deps.edn" :aliases [:run]}))
(def uber-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (println "** Building" jar-file)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  ["src"]
                :scm       {:url                 "https://github.com/wardle/pc4"
                            :tag                 (str "v" version)
                            :connection          "scm:git:git://github.com/wardle/pc4.git"
                            :developerConnection "scm:git:ssh://git@github.com/wardle/pc4.git"}})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn install
  "Installs pom and library jar in local maven repository"
  [_]
  (jar nil)
  (println "** Installing :" lib version)
  (b/install {:basis     basis
              :lib       lib
              :class-dir class-dir
              :version   version
              :jar-file  jar-file}))

(defn cljs [{:keys [verbose] :or {verbose true}}]
  (when verbose (println "Compiling cljs for production"))
  (b/process {:command-args ["yarn" "shadow-cljs" "release" "main"]})
  (let [manifest (edn/read-string (slurp "resources/public/js/compiled/manifest.edn"))
        modules (map :output-name manifest)]
    (b/copy-file {:src    (str "resources/public/js/compiled/manifest.edn")
                  :target (str class-dir "/public/js/compiled/manifest.edn")})
    (doseq [module modules]
      (println "** Copying module" module)
      (b/copy-file {:src    (str "resources/public/js/compiled/" module)
                    :target (str class-dir "/public/js/compiled/" module)}))))

(defn css [{:keys [verbose] :or {verbose true}}]
  (when verbose (println "Generating CSS for production"))
  (b/process {:command-args ["yarn" "tailwindcss" "-o" (str class-dir "/public/css/output.css") "--minify"]}))

(defn uber [{:keys [out] :or {out uber-file}}]
  (println "****************************************\n** Building uberjar: " out)
  (clean nil)
  (println "****************************************\n** 1/4 Compiling clj")
  (b/compile-clj {:basis        uber-basis
                  :src-dirs     ["src/clj"]
                  :ns-compile   ['com.eldrix.pc4.core]
                  :compile-opts {:elide-meta [:doc :added]}
                  :class-dir    class-dir})
  (b/copy-file {:src (str "deps.edn")
                :target (str class-dir "/deps.edn")})
  (b/copy-file {:src    (str "resources/config.edn")
                :target (str class-dir "/config.edn")})
  (b/copy-file {:src    (str "resources/logback.xml")
                :target (str class-dir "/logback.xml")})
  (b/copy-dir {:src-dirs   ["resources/migrations"]
               :target-dir (str class-dir "/migrations")})
  (b/copy-dir {:src-dirs   ["src/clj"]
               :target-dir class-dir})
  (println "****************************************\n** 2/4 Building CSS for production")
  (css {:verbose false})
  (println "****************************************\n** 3/4 Compiling cljs for production")
  (cljs {:verbose false})
  (println "****************************************\n** 4/4: Building uberjar")
  (b/uber {:class-dir class-dir
           :uber-file (str out)
           :basis     uber-basis
           :main      'com.eldrix.pc4.core
           :exclude   [#"(?i)^META-INF/license/.*"
                       #"^license/.*"]}))
