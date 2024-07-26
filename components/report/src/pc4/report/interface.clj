(ns pc4.report.interface
  (:require
   [clojure.spec.alpha :as s]
   [pc4.report.impl.stamp :as stamp]
   [pc4.report.impl.woc :as woc]))

(defn make-data-url
  [x mime-type]
  (woc/make-data-url x mime-type))

(s/def ::encounter-report ::woc/encounter-report)

(def gen-encounter-report
  "A test.check generator for synthetic data suitable for [[encounter-report]].
  Requires runtime access to clojure test check, so will fail if this library 
  is not on the classpath. Only for use in development and testing."
  woc/gen-encounter-report)

(defn encounter-report
  "Generate a PDF for an encounter report for the data specified.
  Returns bytes for the PDF file generated."
  [data]
  (woc/encounter-report data))

(s/def ::header string?)
(s/def ::centre string?)
(s/def ::footer string?)
(s/fdef stamp-pdf-text
  :args (s/cat :in some? :params (s/keys :opt-un [::header ::centre ::footer])))

(defn stamp-pdf-text
  "Stamp a PDF with text.
  Parameters:
  - in  : anything coercible via [[clojure.java.io/input-stream]] such as URL or byte array
  - header : text for a message shown at the very top of the page
  - centre : text for watermark - a message shown diagonally in centre across page
  - footer : text for a message shown at the very foot of the page"
  [in {:keys [header centre footer] :as params}]
  (stamp/stamp-pdf-text in params))

(s/fdef stamp-pdf-template
  :args (s/cat :in some? :template-in some?))
(defn stamp-pdf-template
  "Stamp a PDF document with content from another 'template' PDF. If the 
  template PDF has one page, its content is stamped on every page. If the 
  template PDF has two pages, then the content of the first page is stamped 
  on the first page, and the content of the second page stamped on all other 
  pages. It is expected that the page sizes are the same."
  [in template-in]
  (stamp/stamp-pdf-template in template-in))
