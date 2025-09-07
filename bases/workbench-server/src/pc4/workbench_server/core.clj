(ns pc4.workbench-server.core
  (:require
   [clojure.core.server]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [integrant.core :as ig]
   [pc4.config.interface :as config]
   [pc4.graph.interface]                                    ;; declare dependent component
   [pc4.http-server.interface]                              ;; declare dependent component
   [pc4.log.interface :as log])
  (:gen-class))

(def supported-profiles #{"dev" "cvx" "pc4" "pc4-dev"})

(defn exit [status msg]
  (println msg)
  (System/exit status))

(def cli-options
  [["-h" "--help"]
   [nil "--profile PROFILE" "Configuration profile to use (dev/pc4/cvx/pc4-dev)"
    :default "dev"
    :validate [supported-profiles (str "Profile must be one of: " (str/join ", " supported-profiles))]]])

(defn usage [options-summary]
  (->> ["pc4: PatientCare v4"
        ""
        "Usage: "
        "  java -jar pc4.jar [options] command"
        "  OR clojure -M:run [options] command"
        ""
        "Options:"
        options-summary
        ""
        "Commands:"
        "  nrepl   : run nrepl server"
        "  serve   : run pc4 server"
        "  migrate : run any pending database migrations"
        "  status  : report pc4 configuration status"]
       (str/join \newline)))

(defmethod ig/init-key :repl/server [_ {server-name :name, :as opts}]
  (if server-name
    (do (log/info "starting repl server" (select-keys opts [:name :port]))
        {:server-name server-name
         :server      (clojure.core.server/start-server opts)})
    (log/info "skipping repl server: not configured")))

(defmethod ig/halt-key! :repl/server [_ {:keys [server-name]}]
  (clojure.core.server/stop-server server-name))

(defn nrepl [{:keys [profile]}]
  (when-not profile (exit 1 "Missing profile"))
  (log/info "starting pc4 nrepl server" {:profile profile})
  (ig/init profile [:repl/server]))

(defn ^:deprecated serve                                    ;; TODO: remove
  [{:keys [profile]}]
  (when-not profile (exit 1 "Missing profile"))
  (log/info "starting pc4 fulcro-server with profile" {:profile profile})
  (let [conf (config/config profile)]
    (ig/load-namespaces conf [:pc4.fulcro-server.interface/server])
    (ig/init conf [:repl/server :pc4.fulcro-server.interface/server])))

(defn serve2 [{:keys [profile]}]
  (when-not profile (exit 1 "Missing profile"))
  (log/info "starting pc4 http-server with profile" {:profile profile})
  (let [conf (config/config profile)]
    (ig/load-namespaces conf [:pc4.http-server.interface/server])
    (ig/init conf [:repl/server :pc4.http-server.interface/server])))

(defn migrate [{:keys [profile]}]
  (when-not profile (exit 1 "Missing profile"))
  (log/info "Running database migrations with profile" {:profile profile})
  (let [conf (config/config profile)]
    (ig/init conf [:pc4.rsdb.interface/migrate])))

(def status-checks
  [{:title         "SNOMED CT (using Hermes)"
    :pathom/entity {:info.snomed.Concept/id 24700007}
    :pathom/eql    [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]
    :expected      {:info.snomed.Concept/preferredDescription {:info.snomed.Description/term "Multiple sclerosis"}}}
   {:title         "UK dictionary of medicines and devices (dm+d) using `dmd`"
    :pathom/entity {:info.snomed.Concept/id 774557006}
    :pathom/eql    [:uk.nhs.dmd/NM]
    :expected      {:uk.nhs.dmd/NM "Amlodipine"}}
   {:title         "Deprivation indices (deprivare)"
    :pathom/entity {:uk.gov.ons/lsoa "W01001552"}
    :pathom/eql    [:wales-imd-2019-ranks/lsoa_name :wales-imd-2019-ranks/authority_name :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_quartile]
    :expected      {:wales-imd-2019-ranks/authority_name               "Monmouthshire"
                    :wales-imd-2019-ranks/lsoa_name                    "Dixton with Osbaston"
                    :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_quartile 4}}
   {:title         "NHS postcode directory (via nhspd)"
    :pathom/entity {:uk.gov.ons.nhspd/PCDS "CF14 4XW"}
    :pathom/eql    [:uk.gov.ons.nhspd/LSOA11 :uk.gov.ons.nhspd/PCT]
    :expected      {:uk.gov.ons.nhspd/LSOA11 "W01001770" :uk.gov.ons.nhspd/PCT "7A4"}}
   {:title         "rsdb (database connection)"
    :pathom/entity {:t_user/username "system"}
    :pathom/eql    [:t_user/full_name]
    :expected      {:t_user/full_name "Mr System Administrator"}}
   {:title         "NHS organisation data (via clods)"
    :pathom/entity {:uk.nhs.fhir.Id/ods-organization "7A4"}
    :pathom/eql    [:uk.nhs.ord/name]
    :expected      {:uk.nhs.ord/name "CARDIFF & VALE UNIVERSITY LHB"}}])

(def title-fmt
  (str "%-" (apply max (map count (map :title status-checks))) "s"))

(defn service-status [system {:keys [title test expected] :pathom/keys [entity eql] :as data}]
  (let [pathom (:pc4.graph.interface/boundary-interface system)
        result (cond
                 test
                 (test system)
                 (and entity eql)
                 (pathom {:pathom/entity entity :pathom/eql eql}))]
    (assoc data
           :result result
           :success (= result expected)
           :message (if (= result expected)
                      (str "- " (format title-fmt title) " : success ")
                      (str "- " (format title-fmt title) " : failure " \newline "   |- expected: " expected ")\n   |- actual  : " result ")")))))

(defn status [{:keys [profile]}]
  (when-not profile
    (exit 1 "Missing profile"))
  (let [conf (config/config profile)]
    (ig/load-namespaces conf [:pc4.graph.interface/boundary-interface])
    (let [system (ig/init conf [:pc4.graph.interface/boundary-interface])]
      (run! println
            (->> status-checks
                 (sort-by :title)
                 (map #(service-status system %))
                 (map :message)))
      (ig/halt! system))))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn validate-args [args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options)
        command (first arguments)]
    (cond
      (:help options)
      {:exit-message (usage summary)}
      errors
      {:error true, :exit-message (error-msg errors)}
      (#{"nrepl" "serve" "serve2" "migrate" "status"} command)
      {:command command :options options}
      :else
      {:error true, :exit-message (usage summary)})))

(defn -main [& args]
  (let [{:keys [exit-message error command options]} (validate-args args)
        profile (keyword (:profile options))]
    (if exit-message
      (exit (if error 1 0) exit-message)
      (case command
        "nrepl" (nrepl {:profile profile})
        "serve" (serve2 {:profile profile})
        "migrate" (migrate {:profile profile})
        "status" (status {:profile profile})))))

(comment
  (cli/parse-opts ["--profile" "cvx"] cli-options))
