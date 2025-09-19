(ns pc4.ui.render
  (:require
    [clojure.string :as str]
    [rum.core :as rum]
    [selmer.parser :as selmer])
  (:import (org.jsoup Jsoup)))

(defn render
  "Render the markup 'src' using rum. This is designed only for server-side
  rendering and omits all React affordances.
  - src - HTML as Clojure data (aka hiccup)."
  [src]
  (rum/render-static-markup src))

(defn render-file
  "Render the context-map using the template from the filename or URL specified.
  Uses selmer."
  [filename-or-url context-map]
  (selmer/render-file filename-or-url context-map))

(defn html->text
  "Convert a string containing HTML to plain text."
  [^String html]
  (when-not (str/blank? html)
    (.text (Jsoup/parse html))))