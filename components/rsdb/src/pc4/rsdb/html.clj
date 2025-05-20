(ns pc4.rsdb.html
  (:import (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)))

(defn html->text
  "Convert a string containing HTML to plain text."
  [^String html]
  (Jsoup/clean html (Safelist.)))