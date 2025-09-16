(ns pc4.pathom-web.impl
  (:require [edn-query-language.core :as eql]))

(def ^:dynamic *boundary-interface* (comp :pathom :env))

(defn set-boundary-interface!
  "Set a function to use to get to the boundary interface in the pathom
  environment. This uses [[alter-var-root]] to change the default binding for
  all threads, and [[set!]] iff the var has been dynamically rebound using
  [[binding]]."
  [f]
  (alter-var-root #'*boundary-interface* (constantly f))
  (when (thread-bound? #'*boundary-interface*)
    (set! *boundary-interface* f)))

(defn process
  "Execute the `query` in the context of the http `request`.
  It is expected that a pathom boundary interface function be available in the
  'request' and can be resolved by calling the function *boundary-interface* and
  the created pathom environment has the request included under key :request."
  [env request query]
  (let [pathom (or (*boundary-interface* request)
                   (throw (ex-info "missing pathom boundary interface in request" {:f       *boundary-interface*
                                                                                   :request request})))]
    (let [result (pathom (assoc env :request request) query)]
      (when (:com.wsscode.pathom3.connect.runner/attribute-errors result)
        (clojure.pprint/pprint result))
      result)))

(defn handler
  ([query f]
   (handler {} query f))
  ([env query f]
   (let [query-fn (if (fn? query) query (constantly query))]
     (fn handler*
       ([request]
        (handler* request (process env request (query-fn request))))
       ([request output]
        (f request output))))))





