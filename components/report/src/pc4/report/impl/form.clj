(ns pc4.report.impl.form
  "Utilities to work with PDF form fields - reading, filling, and protecting fields."
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s])
  (:import
    (com.lowagie.text Image)
    (java.io ByteArrayOutputStream)
    (com.lowagie.text.pdf PdfReader PdfStamper AcroFields PdfFormField PdfContentByte)))

(set! *warn-on-reflection* true)

(defn- protect-field
  "Makes a form field read-only by setting the FF_READ_ONLY flag.

  Parameters:
  - form : AcroFields instance from PdfStamper
  - field-name : string name of field to protect"
  [^AcroFields form ^String field-name]
  (.setFieldProperty form field-name "setfflags" (int PdfFormField/FF_READ_ONLY) nil))

(s/def ::protect-fields boolean?)
(s/fdef fill-form-fields
  :args (s/cat :in any?
               :data map?
               :opts (s/keys* :opt-un [::protect-fields])))

(defn- fill-text-form-fields
  "Fill text fields, clear image fields, and optionally flatten the form.

  This is the first pass of the two-pass approach: form fields must be filled
  and flattened before images are added, as flattening destroys content on overlay layers.
  Image fields (byte array values) are cleared but not filled - they're handled in the
  second pass by fill-image-form-fields.

  Parameters:
  - in: PDF template (coercible via clojure.java.io/input-stream)
  - data: map of field-name -> value (strings or keywords, values can be strings or byte arrays)
  - protect-fields: if true, marks filled fields as read-only and flattens the form

  Returns: byte array of PDF with text fields filled"
  [in data protect-fields]
  (let [pdf-output (ByteArrayOutputStream.)]
    (with-open [pdf-input (io/input-stream in)
                reader (PdfReader. pdf-input)
                stamper (PdfStamper. reader pdf-output)]
      (let [form (.getAcroFields stamper)]
        (.setGenerateAppearances form true)
        (let [fields (.getAllFields form)]
          (doseq [[field-name _] fields]
            (when-some [value (get data field-name (get data (keyword field-name)))]
              (if (bytes? value)
                ;; Clear image fields
                (.setField form field-name "")
                ;; Fill text fields
                (do
                  (.setField form field-name (str value))
                  (when protect-fields
                    (protect-field form field-name))))))
          (.setFormFlattening stamper protect-fields))))
    (.toByteArray pdf-output)))

(defn- get-image-field-positions
  "Extract positions for all image fields (byte array values) from a PDF template.

  Parameters:
  - pdf-template: PDF template input (coercible via clojure.java.io/input-stream)
  - data: map of field-name -> value, where byte array values indicate image fields

  Returns: map of field-name -> positions array for each image field"
  [pdf-template data]
  (with-open [pdf-input (io/input-stream pdf-template)
              reader (PdfReader. pdf-input)]
    (let [form (.getAcroFields reader)
          fields (.getAllFields form)]
      ;; Build map of image field names to their positions
      (reduce (fn [acc [field-name _]]
                (let [value (get data field-name (get data (keyword field-name)))]
                  (if (bytes? value)
                    (assoc acc field-name (.getFieldPositions form field-name))
                    acc)))
              {}
              fields))))

(defn- fill-image-form-fields
  "Add images at specified field positions to a flattened PDF.
  Original-in is the original PDF template (before flattening) to get field positions.
  Returns byte array of PDF with images added."
  [pdf-bytes original-in data]
  (let [pdf-output (ByteArrayOutputStream.)]
    ;; Get field positions from original PDF
    (let [field-positions (get-image-field-positions original-in data)]
      (with-open [pdf-input (io/input-stream pdf-bytes)
                  reader (PdfReader. pdf-input)
                  stamper (PdfStamper. reader pdf-output)]
        (doseq [[field-name positions] field-positions]
          (let [value (get data field-name (get data (keyword field-name)))
                image (Image/getInstance ^bytes value)]
            ;; PDF coordinates: llx=lower-left-x, lly=lower-left-y, urx=upper-right-x, ury=upper-right-y
            (loop [[page llx lly urx ury & more] (seq positions)]
              (when page
                (let [^PdfContentByte over (.getOverContent stamper (int page))
                      field-width (- urx llx)
                      field-height (- ury lly)
                      img-width (.getWidth image)
                      img-height (.getHeight image)
                      scale (min (/ field-width img-width) (/ field-height img-height))
                      img-instance (Image/getInstance image)
                      scaled-width (* img-width scale)
                      scaled-height (* img-height scale)]
                  (.scaleAbsolute img-instance (float scaled-width) (float scaled-height))
                  (.setAbsolutePosition img-instance (float llx) (float lly))
                  (.addImage over img-instance)
                  (recur more))))))))
    (.toByteArray pdf-output)))

(defn fill-form-fields
  "Fills PDF form fields from data map and returns byte array of completed PDF.

  Parameters:
  - in : PDF template (anything coercible via clojure.java.io/input-stream)
  - data : map with keys (strings or keywords) matching form field names
           Values can be strings or byte arrays (for images)
  - protect-fields : boolean, default true - mark filled fields as read-only

  Returns: byte array of completed PDF with form fields filled in

  Example:
    (fill-form-fields \"template.pdf\"
                      {:name \"John Doe\" :dob \"1980-01-01\"}
                      :protect-fields true)

    ;; String keys work too:
    (fill-form-fields \"template.pdf\"
                      {\"name\" \"John Doe\" \"dob\" \"1980-01-01\"})

    ;; Byte arrays are treated as images:
    (fill-form-fields \"template.pdf\"
                      {:signature image-byte-array})"
  [in data & {:keys [protect-fields] :or   {protect-fields true}}]
  (let [flattened-pdf (fill-text-form-fields in data protect-fields)
        has-images? (some bytes? (vals data))]
    (if has-images?
      (fill-image-form-fields flattened-pdf in data)
      flattened-pdf)))

(defn get-form-fields
  "Returns information about all form fields in a PDF template.

  Parameters:
  - in : PDF template (anything coercible via clojure.java.io/input-stream)

  Returns: map of field-name (string) -> field-info where field-info contains:
           - :type - field type (e.g., 4 for text field, 2 for button/checkbox)
           - :value - current value of the field (may be nil or empty string)

  Example:
    (get-form-fields \"template.pdf\")
    => {\"name\" {:type 4 :value \"\"}
        \"dob\" {:type 4 :value \"\"}
        \"consent\" {:type 2 :value \"Off\"}}"
  [in]
  (with-open [pdf-input (io/input-stream in)
              reader (PdfReader. pdf-input)]
    (let [form (.getAcroFields reader)
          fields (.getAllFields form)]
      (into {}
            (for [[field-name _] fields]
              [field-name {:type  (.getFieldType form field-name)
                           :value (.getField form field-name)}])))))