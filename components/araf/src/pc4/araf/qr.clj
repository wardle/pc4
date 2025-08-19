(ns pc4.araf.qr
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.nhsnumber :as nnn])
  (:import
    (java.io ByteArrayOutputStream)
    (com.google.zxing BarcodeFormat EncodeHintType)
    (com.google.zxing.client.j2se MatrixToImageWriter)
    (com.google.zxing.qrcode QRCodeWriter)))

(defn generate
  "Generates a QR code for the given base URL, access key, and NHS number.
   Returns a byte array of the PNG image.
   Options:
   - :size - QR code size in pixels (default 250)"
  ([base-url nhs-number access-key]
   (generate base-url nhs-number access-key {}))
  ([base-url nhs-number access-key {:keys [size] :or {size 250}}]
   (let [url (str base-url "/" nhs-number "/" access-key)
         writer (QRCodeWriter.)
         bit-matrix (.encode writer url BarcodeFormat/QR_CODE size size)
         output-stream (ByteArrayOutputStream.)]
     (MatrixToImageWriter/writeToStream bit-matrix "PNG" output-stream)
     (.toByteArray output-stream))))