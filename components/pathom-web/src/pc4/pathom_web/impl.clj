(ns pc4.pathom-web.impl
  (:require [edn-query-language.core :as eql]
            [pc4.log.interface :as log]
            [pc4.pathom-web.error :as error]
            [pc4.web.interface :as web]))

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
      (log/trace "process:" result)
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
        (let [result (process env request (query-fn request))]
          (if (error/pathom-error? result)
            (web/ok (error/render-pathom-error result))
            (handler* request result))))
       ([request output]
        (f request output))))))





