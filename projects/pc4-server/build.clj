(ns build
  (:require [clojure.edn :as edn]
            [clojure.tools.build.api :as b]))

(def lib 'com.eldrix/pc4)
(def version (format "1.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-lib-%s.jar" (name lib) version))
(def uber-basis (delay (b/create-basis {:project "deps.edn" :aliases [:run]})))
(def uber-file (format "target/%s-%s.jar" (name lib) version))

(defn error [s]
  (println "ERROR:" s)
  (System/exit 1))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (println "** Building" jar-file)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
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
  (b/install {:basis     @basis
              :lib       lib
              :class-dir class-dir
              :version   version
              :jar-file  jar-file}))

(defn uber [{:keys [out] :or {out uber-file}}]
  (clean nil)
  (b/compile-clj {:basis        @uber-basis
                  :src-dirs     ["src/clj"]
                  :ns-compile   ['com.eldrix.pc4.core]
                  :compile-opts {:elide-meta [:doc :added]}
                  :class-dir    class-dir})
  (b/copy-file {:src (str "deps.edn")
                :target (str class-dir "/deps.edn")})
  (b/copy-file {:src    (str "resources/logback.xml")
                :target (str class-dir "/logback.xml")})
  (b/copy-file {:src    (str "../bases/frontend/resources/public/css/output.css")
                :target (str class-dir "/public/css/output.css")})
  (b/copy-file {:src    (str "../bases/frontend/resources/public/js/compiled/manifest.edn")
                :target (str class-dir "/public/js/compiled/manifest.edn")})
  (let [manifest (edn/read-string (slurp "../bases/frontend/resources/public/js/compiled/manifest.edn"))
        output-names (map :output-name manifest)] ;; get a list of all shadow cljs outputs
    (if (pos-int? (count output-names))
      (doseq [output-name output-names]
        (println "Installing cljs frontend module:" output-name)
        (b/copy-file {:src (str "../bases/frontend/resources/public/js/compiled/" output-name)
                      :target (str class-dir "/public/js/compiled/" output-name)}))
      (error "no shadow cljs outputs found")))
  (b/copy-dir {:src-dirs   ["../components/rsdb/resources/rsdb/migrations"]
               :target-dir (str class-dir "/rsdb/migrations")})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file (str out)
           :basis     @uber-basis
           :main      'com.eldrix.pc4.core
           :exclude   [#"(?i)^META-INF/license/.*"
                       #"^license/.*"]}))



