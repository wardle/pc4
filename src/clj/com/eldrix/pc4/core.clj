(ns com.eldrix.pc4.core
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [com.eldrix.pc4.system :as pc4])
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
        "  serve    : run pc4 server"
        "  migrate  : run any pending database migrations"
        "  validate : validate pc4 configuration"]
       (str/join \newline)))

(defn serve [{:keys [profile]}]
  (when-not profile (exit 1 "Missing profile"))
  (log/info "starting pc4 with profile" {:profile profile})
  (pc4/load-namespaces profile [:com.eldrix.pc4.pedestal/server])
  (pc4/init profile [:com.eldrix.pc4.pedestal/server :repl/server]))

(defn migrate [{:keys [profile]}]
  (when-not profile (exit 1 "Missing profile"))
  (log/info "Running database migrations with profile" {:profile profile})
  (pc4/init profile [:com.eldrix.rsdb/run-migrations]))


(def validation-checks
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
  (str "%-"
       (apply max (map count (map :title validation-checks)))
       "s"))

(defn validate-service [system {:keys [title test expected] :pathom/keys [entity eql] :as data}]
  (let [pathom (:pathom/boundary-interface system)
        result (cond
                 test
                 (test system)
                 (and entity eql)
                 (pathom {:pathom/entity entity :pathom/eql eql}))]
    (assoc data
      :result result
      :success (= result expected)
      :message (if (= result expected)
                 (str "- " (format title-fmt title) " : ✅ ")
                 (str "- " (format title-fmt title) " : ❌ " \newline "   |- expected: " expected ")\n   |- actual  : " result ")")))))

(defn validate [{:keys [profile]}]
  (when-not profile
    (exit 1 "Missing profile"))
  (let [system (pc4/init profile [:pathom/boundary-interface])]
    (run! println
          (->> validation-checks
               (sort-by :title)
               (map #(validate-service system %))
               (map :message)))
    (pc4/halt! system)))

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
      (#{"serve" "migrate" "validate"} command)
      {:command command :options options}
      :else
      {:error true, :exit-message (usage summary)})))

(defn -main [& args]
  (let [{:keys [exit-message error command options]} (validate-args args)
        profile (keyword (:profile options))]
    (if exit-message
      (exit (if error 1 0) exit-message)
      (case command
        "serve" (serve {:profile profile})
        "migrate" (migrate {:profile profile})
        "validate" (validate {:profile profile})))))


(comment
  (cli/parse-opts ["--profile" "cvx"] cli-options))