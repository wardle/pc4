(ns pc4.common-ui.render
  (:require
    [clojure.string :as str]
    [rum.core :as rum]
    [selmer.parser :as selmer]
    [pc4.nhs-number.interface :as nnn])
  (:import (org.jsoup Jsoup)))

;; Register custom Selmer filters
(selmer/add-filter! :format-nhs-number (fn [nhs-number] (nnn/format-nnn nhs-number)))

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