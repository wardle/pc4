(ns pc4.araf.impl.qr
  (:require [clojure.spec.alpha :as s]
            [com.eldrix.nhsnumber :as nnn])
  (:import
    (java.io ByteArrayOutputStream)
    (com.google.zxing BarcodeFormat EncodeHintType)
    (com.google.zxing.client.j2se MatrixToImageWriter)
    (com.google.zxing.qrcode QRCodeWriter)))

(defn generate
  "Generates a QR code for the given base URL and long access key.
   Returns a byte array of the PNG image.
   Options:
   - :size - QR code size in pixels (default 250)"
  ([base-url long-access-key]
   (generate base-url long-access-key {}))
  ([base-url long-access-key {:keys [size] :or {size 250}}]
   (let [url (str base-url "/" long-access-key)
         writer (QRCodeWriter.)
         bit-matrix (.encode writer url BarcodeFormat/QR_CODE size size)
         output-stream (ByteArrayOutputStream.)]
     (MatrixToImageWriter/writeToStream bit-matrix "PNG" output-stream)
     (.toByteArray output-stream))))