(ns pc4.log.interface
  (:require [clojure.tools.logging.readable :as log]))

(defmacro trace
  "Trace level logging using print-style args. See logp for details."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(log/logp :trace ~@args))

(defmacro debug
  "Debug level logging using print-style args. See logp for details."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(log/logp :debug ~@args))

(defmacro info
  "Info level logging using print-style args. See logp for details."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(log/logp :info ~@args))

(defmacro warn
  "Warn level logging using print-style args. See logp for details."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(log/logp :warn ~@args))

(defmacro error
  "Error level logging using print-style args. See logp for details."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(log/logp :error ~@args))

(defmacro fatal
  "Fatal level logging using print-style args. See logp for details."
  {:arglists '([message & more] [throwable message & more])}
  [& args]
  `(log/logp :fatal ~@args))

(comment)


