(ns pc4.report.interface
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [pc4.report.impl.form :as form]
   [pc4.report.impl.stamp :as stamp]
   [pc4.report.impl.woc :as woc]))

(defn make-data-url
  "Create a data URL for the file specified by 'in'. 'in' should be anything
  coercible using [[clojure.java.io/input-source]] such as a java.io.File, a
  string representing a URL or filename, or a URL.
  - in : anything coercible to an input-stream using [[clojure.java.io/input-stream]] 
  - mime-type : mime-type of the file e.g. \"image/jpeg\""
  [in mime-type]
  (woc/make-data-url in mime-type))

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


(defn fill-pdf-form
  "Fills PDF form fields from data map and returns byte array of completed PDF.

  Parameters:
  - in : PDF template (anything coercible via clojure.java.io/input-stream)
  - data : map with keys (strings or keywords) matching form field names
  - protect-fields : boolean, default true - mark filled fields as read-only

  Returns: byte array of completed PDF with form fields completed

  Example:
    (fill-pdf-form \"template.pdf\"
                   {:name \"John Doe\" :dob \"1980-01-01\"}
                   :protect-fields true)"
  [in data & {:keys [protect-fields] :or {protect-fields true}}]
  (form/fill-form-fields in data :protect-fields protect-fields))

;;;
;;;

(s/def ::drug string?)
(s/def ::department string?)
(s/def ::condition string?)
(s/def ::return-name-and-address string?)
(s/def ::cv-number (s/or :str string? :int int?))
(s/def ::consultant string?)
(s/def ::hospital string?)
(s/def ::signature (s/or :str string? :bytes bytes?))
(s/def ::telephone string?)
(s/def ::date string?)
(s/def ::patient string?)
(s/def ::to string?)
(s/def ::scp-request
  (s/keys :req-un [::drug ::department ::condition ::return-name-and-address ::cv-number ::consultant ::hospital ::signature ::telephone ::date ::patient ::to]))

(s/fdef scp-request-form
  :args (s/cat :request ::scp-request))
(defn scp-request-form
  "Generate a Welsh Medicines Shared Care Protocol request form, returning the PDF as bytes."
  [request]
  (if (s/valid? ::scp-request request)
    (let [pdf-file (io/resource "report/scp-request-v2.4.pdf")]
      (fill-pdf-form pdf-file request))
    (throw (ex-info (str "invalid SCP request data" (s/explain-str ::scp-request request))
                    (s/explain-data ::scp-request request)))))

(comment 
  (form/get-form-fields (io/resource "report/scp-request-v2.4.pdf")))