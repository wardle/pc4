(ns pc4.report.impl.stamp
  "Utilities to stamp PDF documents."
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s])
  (:import
   (java.io ByteArrayOutputStream)
   (com.lowagie.text.pdf BaseFont PdfReader PdfStamper PdfGState)
   (com.lowagie.text Element)))

(set! *warn-on-reflection* true)

(s/def ::header (s/nilable string?))
(s/def ::centre (s/nilable string?))
(s/def ::footer (s/nilable string?))
(s/fdef stamp-pdf-text
  :args (s/cat :in any? :opts (s/keys :opt-un [::header ::centre ::footer])))

(defn stamp-pdf-text
  "Stamp a PDF with a watermark or attribution.
  Parameters:
  - in  : anything coercible via [[clojure.java.io/input-stream]] such as URL or byte array
  - header : text for a message shown at the very top of the page
  - centre : text for watermark - a message shown diagonally in centre across page
  - footer : text for a message shown at the very foot of the page"
  [in {:keys [header centre footer]}]
  (let [xout (ByteArrayOutputStream.)]
    (with-open [xin (io/input-stream in)
                reader (PdfReader. xin)
                stamper (PdfStamper. reader xout)]
      (let [number-pages (.getNumberOfPages reader)
            gstate (doto (PdfGState.) (.setFillOpacity 0.1) (.setStrokeOpacity 0.1))
            bf (BaseFont/createFont BaseFont/HELVETICA BaseFont/CP1252 BaseFont/NOT_EMBEDDED)]
        (doseq [^int i (range 1 (inc number-pages))]
          (let [content (.getOverContent stamper i)
                page-size (.getPageSize reader i)
                w (.getWidth page-size)
                h (.getHeight page-size)]
            (.saveState content)
            (.setGState content gstate)
            (when centre
              (doto content
                (.beginText)
                (.setFontAndSize bf 96)
                (.showTextAligned Element/ALIGN_CENTER centre (/ w 2) (/ h 2) 45)
                (.endText)))
            (when header
              (doto content
                (.beginText)
                (.setFontAndSize bf 8)
                (.showTextAligned Element/ALIGN_CENTER header (/ w 2) (- h 8) 0)
                (.endText)))
            (when footer
              (doto content
                (.beginText)
                (.setFontAndSize bf 8)
                (.showTextAligned Element/ALIGN_CENTER footer (/ w 2) 2 0)
                (.endText)))
            (.restoreState content)))))
    (.toByteArray xout)))

(defn stamp-pdf-template
  "Stamp a PDF document with content from another 'template' PDF. If the 
  template PDF has one page, its content is stamped on every page. If the 
  template PDF has two pages, then the content of the first page is stamped 
  on the first page, and the content of the second page stamped on all other 
  pages. It is expected that the page sizes are the same."
  [in template-in]
  (let [xout (ByteArrayOutputStream.)]
    (with-open [xin (io/input-stream in)
                reader (PdfReader. xin)
                template-xin (io/input-stream template-in)
                template-reader (PdfReader. template-xin)
                stamper (PdfStamper. reader xout)]
      (let [number-of-pages (.getNumberOfPages reader)
            template-pages (.getNumberOfPages template-reader)]
        (doseq [^int i (range 1 (inc number-of-pages))]
          (let [under (.getUnderContent stamper i)
                from-page-no (if (and (> i 1) (> template-pages 1)) 2 1) ;; get content from page 1 unless we are past first page and template has more than one page
                template-page (.getImportedPage stamper template-reader from-page-no)]
            (.addTemplate under template-page 0.0 0.0)))))
    (.toByteArray xout)))

(defn ^:private write-bytes [out data]
  (with-open [xout (io/output-stream out)]
    (io/copy data xout)))

(comment
  (write-bytes "wibble.pdf" (stamp-pdf-text "https://www.maths.ed.ac.uk/~dmarsh/files/printer-testcard-colour.pdf"
                                            {:centre "DRAFT"
                                             :header "HELLO THERE"
                                             :footer (str "Printed on " (java.time.LocalDateTime/now) "by MW")}))

  (write-bytes "wibble2.pdf" (stamp-pdf-template "wibble.pdf" "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf")))


