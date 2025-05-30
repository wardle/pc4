(ns pc4.rsdb.html
  (:require [clojure.string :as str])
  (:import (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)))

(defn html->text
  "Convert a string containing HTML to plain text."
  [^String html]
  (when-not (str/blank? html)
    (.text (Jsoup/parse html))))