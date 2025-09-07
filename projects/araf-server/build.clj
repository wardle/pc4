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
                   "-o" "bases/araf-server/resources/public/css/araf-patient.css"
                   "--content" "components/araf/resources/**/*.html,bases/araf-server/src/**/*.clj"
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
  (b/copy-file {:src    "../../components/config/resources/config/config.edn"
                :target (str class-dir "/config.edn")})
  (b/copy-file {:src    "../../bases/araf-server/resources/logback.xml"
                :target (str class-dir "/logback.xml")})
  (b/copy-dir {:src-dirs   ["../../bases/araf-server/resources/public"]
               :target-dir (str class-dir "/public")})
  (b/copy-dir {:src-dirs   ["../../components/araf/resources/araf/migrations"]
               :target-dir (str class-dir "/araf/migrations")})
  (b/copy-dir {:src-dirs   ["../../components/araf/resources/araf/templates"]
               :target-dir (str class-dir "/araf/templates")})
  (b/copy-dir {:src-dirs   ["../../components/araf/resources/araf/forms"]
               :target-dir (str class-dir "/araf/forms")})
  (println "** Generating uberfile")
  (b/uber {:class-dir class-dir
           :uber-file (str out)
           :basis     @uber-basis
           :main      'pc4.araf-server.main
           :exclude   [#"(?i)^META-INF/license/.*"
                       #"^license/.*"
                       #"logback-test.xml"]}))