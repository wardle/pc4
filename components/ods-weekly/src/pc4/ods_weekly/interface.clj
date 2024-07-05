(ns pc4.ods-weekly.interface
  (:require [clojure.java.io :as io]
            [com.eldrix.odsweekly.core :as ow]
            [pc4.log.interface :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key ::svc [_ {:keys [root f path]}]
  (if-let [path' (or path (when (and root f) (.getCanonicalPath (io/file root f))))]
    (do (log/info "opening ods-weekly from " path')
        (ow/open-index path'))
    (log/info "skipping ods-weekly; no path specified")))

(defmethod ig/halt-key! ::svc
  [_ svc]
  (ow/close-index svc))
