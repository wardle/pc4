(ns pc4.pathom-web.error
  (:require [clojure.pprint]
            [clojure.string :as str]
            [selmer.parser :as selmer]))

(defn pathom-error?
  "Check if the result contains a Pathom error."
  [result]
  (contains? result :com.wsscode.pathom3.error/error-message))

(defn- format-unreachable-attributes
  "Format unreachable attributes into a readable list."
  [unreachable-paths unreachable-details]
  (for [[attr _] unreachable-paths]
    (let [details (get unreachable-details attr)
          cause (:com.wsscode.pathom3.connect.planner/unreachable-cause details)]
      {:attribute (str attr)
       :cause (case cause
                :com.wsscode.pathom3.connect.planner/unreachable-cause-unknown-attribute
                "Unknown attribute - no resolver outputs this attribute"
                :com.wsscode.pathom3.connect.planner/unreachable-cause-missing-input
                "Missing input - resolver needs inputs that aren't available"
                (str cause))})))

(defn render-pathom-error
  "Render a developer-friendly error page for Pathom resolution errors."
  [result]
  (let [{error-msg :com.wsscode.pathom3.error/error-message
         error-data :com.wsscode.pathom3.error/error-data
         error-stack :com.wsscode.pathom3.error/error-stack} result
        {unreachable-paths :com.wsscode.pathom3.connect.planner/unreachable-paths
         unreachable-details :com.wsscode.pathom3.connect.planner/unreachable-details
         path :com.wsscode.pathom3.path/path} error-data
        tx (:tx error-data)
        unreachable-attrs (format-unreachable-attributes unreachable-paths unreachable-details)]
    (selmer/render-file
      "templates/pathom-error.html"
      {:error-message error-msg
       :unreachable-attrs (seq unreachable-attrs)
       :error-path (when path (str/join " → " (map str path)))
       :query (when tx (with-out-str (clojure.pprint/pprint tx)))
       :stack-trace error-stack})))
