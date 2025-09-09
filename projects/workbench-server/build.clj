(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :refer [sh]]
            [clojure.edn :as edn]))

(def uber-name 'workbench-server)
(def version (let [{:keys [major minor]} (edn/read-string (slurp "../../version.edn"))]
               (format "%d.%d.%s" major minor (b/git-count-revs nil))))
(def class-dir "target/classes")
(def uber-basis (delay (b/create-basis {:project "deps.edn" :aliases [:run]})))
(def uber-file (format "target/%s-%s.jar" (name uber-name) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn css [_]
  (println "** Building CSS with Tailwind")
  (let [result (sh "npx" "tailwindcss"
                   "-c" "projects/workbench-server/tailwind.config.js"
                   "-i" "projects/workbench-server/tailwind.css"
                   "-o" "components/workbench/resources/public/css/workbench.css"
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
                  :ns-compile   ['pc4.workbench-server.core]
                  :compile-opts {:elide-meta [:doc :added]}
                  :class-dir    class-dir})
  (println "** Copying files")
  (b/copy-dir {:src-dirs ["../../bases/workbench-server/resources"
                          "../../components/araf/resources"
                          "../../components/config/resources"
                          "../../components/rsdb/resources"
                          "../../components/workbench/resources"
                          "../../components/report/resources"]
               :target-dir class-dir})
  (println "** Generating uberfile")
  (b/uber {:class-dir class-dir
           :uber-file (str out)
           :basis     @uber-basis
           :main      'pc4.workbench-server.core
           :exclude   [#"(?i)^META-INF/license/.*"
                       #"^license/.*"
                       #"logback-test.xml"]}))