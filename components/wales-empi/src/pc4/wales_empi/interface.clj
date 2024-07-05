(ns pc4.wales-empi.interface
  (:require [integrant.core :as ig]
            [com.eldrix.concierge.wales.empi :as empi]))

(defmethod ig/init-key ::svc [_ config]
  config)

(defn pdq!
  "Perform a patient demographic query"
  [svc system value]
  (empi/resolve! svc system value))

(defn ^:deprecated pdq-fake    ;; TODO: remove
  "Perform a fake patient demographic query"
  [system value]
  (empi/resolve-fake system value))
