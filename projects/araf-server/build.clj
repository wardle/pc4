(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :refer [sh]]
            [clojure.edn :as edn]))

(def uber-name 'araf-server)
(def version (let [{:keys [major minor]} (edn/read-string (slurp "../../version.edn"))]
               (format "%d.%d.%s" major minor (b/git-count-revs nil))))
(def class-dir "target/classes")
(def uber-basis (delay (b/create-basis {:project "deps.edn" :aliases [:run]})))
(def uber-file (format "target/%s-%s.jar" (name uber-name) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn css [_]
  (println "** Building CSS with Tailwind")
  (let [result (sh "tailwindcss"
                   "-o" "bases/araf-server/resources/public/css/araf.css"
                   "--content" "components/araf/resources/**/*.html,components/araf/**/*.clj,bases/araf-server/src/**/*.clj"
                   "--minify"
                   :dir "../..")]
    (when (not= 0 (:exit result))
      (throw (ex-info "CSS build failed" result)))))

(defn uber [{:keys [out] :or {out uber-file}}]
  (println "**** Building uber" uber-file)
  (clean nil)
  (css nil)
  (println "** Compiling")
  (b/compile-clj {:basis        @uber-basis
                  :ns-compile   ['pc4.araf-server.main]
                  :compile-opts {:elide-meta [:doc :added]}
                  :class-dir    class-dir})
  (println "** Copying files")
  (b/copy-dir {:src-dirs ["../../bases/araf-server/resources"
                          "../../components/config/resources"
                          "../../components/araf/resources"]
               :target-dir class-dir
               :ignores [#"logback-test\.xml"]})
  (println "** Generating uberfile")
  (b/uber {:class-dir class-dir
           :uber-file (str out)
           :basis     @uber-basis
           :main      'pc4.araf-server.main
           :exclude   [#"(?i)^META-INF/license/.*"
                       #"^license/.*"
                       #"logback-test.xml"]}))