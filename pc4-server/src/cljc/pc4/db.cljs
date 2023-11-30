(ns pc4.db)

(def default-db
  {:current-route nil
   :entity-db     {}})

(defn reset-database
  "Resets the database to an empty state, but preserves routing information."
  [db]
  (js/console.log "resetting database")

  (assoc default-db
    :current-route (:current-route db)))