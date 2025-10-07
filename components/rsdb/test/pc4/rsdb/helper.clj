(ns pc4.rsdb.helper
  "Test utilities for rsdb component.

  Provides helper functions for tests that need database connections."
  (:require [next.jdbc :as jdbc]
            [pc4.config.interface :as config]))

(defn get-dev-datasource
  "Return a 'dev' datasource. Any tests using a 'dev' datasource should be
  annotated with :live. In the future, we will have a 'test' datasource that
  will fully configure a test database on-demand."
  []
  (jdbc/get-datasource (get-in (config/config :dev) [:pc4.rsdb.interface/conn])))
